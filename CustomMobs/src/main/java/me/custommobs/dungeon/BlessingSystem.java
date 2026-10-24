package me.custommobs.dungeon;

import me.custommobs.CustomMobsPlugin;
import me.custommobs.Text;
import me.custommobs.dungeon.Blessing.Rarity;
import me.custommobs.dungeon.Blessing.School;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Toda la lógica de las bendiciones de una partida: oferta y menú, efectos al golpear, matar y recibir daño,
 * dash, auras visuales, sinergias por escuela y el pequeño HUD de la barra de acción.
 *
 * <p>Las bendiciones nunca pisan las reglas de Vorthak: el daño "extra" (cadena, explosiones, estela, tormenta...)
 * solo afecta a enemigos normales y mini jefes, no al jefe final ni a sus heraldos.</p>
 */
final class BlessingSystem {

    // ------------------------------------------------------------------ constantes

    private static final Particle.DustOptions RED = new Particle.DustOptions(Color.fromRGB(200, 20, 30), 1.2f);
    private static final Particle.DustOptions PURPLE = new Particle.DustOptions(Color.fromRGB(170, 80, 255), 1.3f);

    private static final int[][] CHOICE_SLOTS = {{22}, {21, 23}, {20, 22, 24}, {19, 21, 23, 25}};
    private static final int INFO_SLOT = 4;
    private static final int REROLL_SLOT = 8;

    /** Estado temporal de cada jugador (no se guarda: se pierde al terminar la partida). */
    private static final class Rt {
        long dashReady;
        boolean dashCue = true;
        long invulUntil;
        long echoUntil;
        int guard;
        long guardNext;
        int frenzy;
        long frenzyUntil;
        long windReady;
        long stormNext;
        long chainReady;
        long lastHeartFx;
        boolean offerBoss;
        /** Puntos de la estela: {x, y, z, tick en que caduca}. */
        final List<double[]> trail = new ArrayList<>();
        final Map<UUID, Long> trailHit = new HashMap<>();
    }

    // ------------------------------------------------------------------ campos

    private final CustomMobsPlugin plugin;
    private final DungeonInstance inst;
    private final DungeonConfig cfg;
    private final World world;
    private final NamespacedKey healthKey;
    private final NamespacedKey speedKey;
    private final NamespacedKey absKey;
    private final Random rnd = new Random();
    private final Map<UUID, Rt> rts = new HashMap<>();
    /** Enemigos marcados por Marca de Brasas / Ignición: id -> tick en que caduca la marca. */
    private final Map<UUID, Long> brands = new HashMap<>();
    /** > 0 mientras se aplica daño causado por una bendición (evita bucles de efectos). */
    private int procDepth;
    /** Profundidad de explosiones encadenadas (limita las reacciones en cadena). */
    private int chainGen;

    BlessingSystem(CustomMobsPlugin plugin, DungeonInstance inst, DungeonConfig cfg, World world, int id) {
        this.plugin = plugin;
        this.inst = inst;
        this.cfg = cfg;
        this.world = world;
        this.healthKey = new NamespacedKey(plugin, "bless_health_" + id);
        this.speedKey = new NamespacedKey(plugin, "bless_speed_" + id);
        this.absKey = new NamespacedKey(plugin, "bless_abs_" + id);
    }

    // ------------------------------------------------------------------ utilidades de estado

    private DungeonInstance.PState st(Player p) {
        return inst.pstate(p.getUniqueId());
    }

    private Rt rt(Player p) {
        return rts.computeIfAbsent(p.getUniqueId(), k -> new Rt());
    }

    private Rt rt(UUID u) {
        return rts.computeIfAbsent(u, k -> new Rt());
    }

    boolean inProc() {
        return procDepth > 0;
    }

    private int schoolStacks(DungeonInstance.PState s, School sc) {
        int n = 0;
        for (Blessing b : Blessing.values()) {
            if (b.school == sc) n += s.stacks(b);
        }
        return n;
    }

    private static int tierOf(int stacks) {
        return stacks >= 4 ? 2 : stacks >= 2 ? 1 : 0;
    }

    private boolean tier(DungeonInstance.PState s, School sc, int t) {
        return tierOf(schoolStacks(s, sc)) >= t;
    }

    private int dashLevel(DungeonInstance.PState s) {
        int l = s.stacks(Blessing.DASH);
        if (l == 0 && tier(s, School.VACIO, 1)) l = 1;
        return l;
    }

    private boolean lowHp(Player p) {
        AttributeInstance mh = p.getAttribute(Attribute.MAX_HEALTH);
        double max = mh == null ? 20.0 : mh.getValue();
        return p.getHealth() < max * 0.4;
    }

    private boolean branded(LivingEntity e) {
        Long until = brands.get(e.getUniqueId());
        return until != null && until > inst.tick();
    }

    private void brand(LivingEntity e, int ticks) {
        if (inst.isBossEntity(e)) return;
        brands.put(e.getUniqueId(), inst.tick() + ticks);
        e.setFireTicks(Math.max(e.getFireTicks(), ticks));
    }

    private List<LivingEntity> enemiesNear(Location c, double rad) {
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity le : world.getNearbyLivingEntities(c, rad)) {
            if (inst.isProcTarget(le)) out.add(le);
        }
        return out;
    }

    /** Daño causado por una bendición (sin disparar otras bendiciones). */
    private void deal(Player p, LivingEntity target, double dmg) {
        if (dmg <= 0 || !target.isValid() || target.isDead()) return;
        procDepth++;
        try {
            target.setNoDamageTicks(0);
            target.damage(dmg, p);
        } finally {
            procDepth--;
        }
    }

    // ------------------------------------------------------------------ efectos visuales

    private void ring(Location c, double r, int points, Particle p) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            world.spawnParticle(p, c.getX() + Math.cos(a) * r, c.getY(), c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    private void ringDust(Location c, double r, int points, Particle.DustOptions d) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            world.spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * r, c.getY(), c.getZ() + Math.sin(a) * r,
                    1, 0, 0, 0, 0, d);
        }
    }

    /** Rayo irregular entre dos puntos. */
    private void lightning(Location a, Location b) {
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.01) return;
        d.multiply(1.0 / len);
        for (double t = 0; t <= len; t += 0.4) {
            double jx = (rnd.nextDouble() - 0.5) * 0.35;
            double jy = (rnd.nextDouble() - 0.5) * 0.35;
            double jz = (rnd.nextDouble() - 0.5) * 0.35;
            world.spawnParticle(Particle.ELECTRIC_SPARK, a.getX() + d.getX() * t + jx, a.getY() + d.getY() * t + jy,
                    a.getZ() + d.getZ() * t + jz, 1, 0, 0, 0, 0);
        }
        world.spawnParticle(Particle.END_ROD, b, 3, 0.15, 0.15, 0.15, 0.02);
    }

    private void bloodLine(Location a, Location b) {
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.01) return;
        d.multiply(1.0 / len);
        for (double t = 0; t <= len; t += 0.5) {
            world.spawnParticle(Particle.DUST, a.getX() + d.getX() * t, a.getY() + d.getY() * t + Math.sin(t) * 0.15,
                    a.getZ() + d.getZ() * t, 1, 0.03, 0.03, 0.03, 0, RED);
        }
    }

    private void title(Player p, String title, String sub) {
        p.showTitle(Title.title(Text.mm(title), Text.mm(sub),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(1800), Duration.ofMillis(500))));
    }

    private void push(LivingEntity le, Location from, double h, double v) {
        Vector d = le.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (d.lengthSquared() < 1.0E-4) d = new Vector(1, 0, 0);
        d.normalize().multiply(h).setY(v);
        le.setVelocity(d);
    }

    // ------------------------------------------------------------------ atributos

    private static AttributeModifier find(AttributeInstance ai, NamespacedKey key) {
        for (AttributeModifier m : ai.getModifiers()) {
            if (key.equals(m.getKey())) return m;
        }
        return null;
    }

    /** Solo toca el atributo si el valor cambia (así no se pierde vida al reaplicar cada segundo). */
    private static void setModifier(Player p, Attribute a, NamespacedKey key, double amount,
                                    AttributeModifier.Operation op) {
        AttributeInstance ai = p.getAttribute(a);
        if (ai == null) return;
        AttributeModifier cur = find(ai, key);
        if (cur != null && cur.getAmount() == amount) return;
        if (cur == null && amount == 0) return;
        if (cur != null) ai.removeModifier(cur);
        if (amount != 0) ai.addTransientModifier(new AttributeModifier(key, amount, op));
    }

    private void setMaxHealthBonus(Player p, double amount) {
        AttributeInstance ai = p.getAttribute(Attribute.MAX_HEALTH);
        if (ai == null) return;
        AttributeModifier cur = find(ai, healthKey);
        double curAmount = cur == null ? 0 : cur.getAmount();
        if (curAmount == amount) return;
        double before = p.getHealth();
        if (cur != null) ai.removeModifier(cur);
        if (amount != 0) {
            ai.addTransientModifier(new AttributeModifier(healthKey, amount, AttributeModifier.Operation.ADD_NUMBER));
        }
        // al quitar el modificador Bukkit recorta la vida: la restauramos (limitada al nuevo máximo)
        p.setHealth(Math.max(0.1, Math.min(ai.getValue(), before)));
    }

    /** Vida máxima, velocidad y tope de absorción según las bendiciones. */
    private void applyAttributes(Player p, DungeonInstance.PState s) {
        double hp = 4.0 * s.stacks(Blessing.VITALITY) - 4.0 * s.stacks(Blessing.PACT);
        double speed = Blessing.swift(s.stacks(Blessing.SWIFT));
        if (tier(s, School.SANGRE, 2) && lowHp(p)) speed += 0.20;
        int hv = s.stacks(Blessing.SOUL_HARVEST);
        setMaxHealthBonus(p, hp);
        setModifier(p, Attribute.MOVEMENT_SPEED, speedKey, speed, AttributeModifier.Operation.ADD_SCALAR);
        setModifier(p, Attribute.MAX_ABSORPTION, absKey, hv > 0 ? Blessing.harvestCap(hv) : 0,
                AttributeModifier.Operation.ADD_NUMBER);
    }

    void clearAttributes(Player p) {
        setMaxHealthBonus(p, 0);
        setModifier(p, Attribute.MOVEMENT_SPEED, speedKey, 0, AttributeModifier.Operation.ADD_SCALAR);
        setModifier(p, Attribute.MAX_ABSORPTION, absKey, 0, AttributeModifier.Operation.ADD_NUMBER);
        p.setAbsorptionAmount(0);
    }

    void resetAll() {
        rts.clear();
        brands.clear();
        chainGen = 0;
        procDepth = 0;
    }

    void onDeath(Player p) {
        Rt r = rt(p);
        r.frenzy = 0;
        r.trail.clear();
        r.invulUntil = 0;
        r.echoUntil = 0;
        p.setAbsorptionAmount(0);
    }

    double takenMultiplier(Player p) {
        DungeonInstance.PState s = st(p);
        if (s == null) return 1.0;
        return Math.max(0.4, 1.0 - Blessing.iron(s.stacks(Blessing.IRON)));
    }

    // ------------------------------------------------------------------ bucle

    /** Se llama en cada vuelta del bucle de la dungeon (cada 2 ticks). */
    void tick(long t) {
        if (t % 4 != 0 || inst.state() != DungeonInstance.State.RUNNING) return;
        boolean sec = t % 20 == 0;
        boolean regen = t % 100 == 0;
        for (Player p : inst.aliveOnline()) {
            DungeonInstance.PState s = st(p);
            if (s == null) continue;
            Rt r = rt(p);
            if (sec) applyAttributes(p, s);
            if (regen) regenTick(p, s);
            auraTick(p, s, t);
            guardTick(p, s, r, t);
            trailTick(p, s, r, t);
            if (t % 8 == 0) stormTick(p, s, r, t);
            dashCueTick(p, s, r, t);
            frenzyTick(p, s, r, t);
            if (s.stacks(Blessing.SWIFT) > 0 && p.isSprinting()) {
                Location l = p.getLocation();
                world.spawnParticle(Particle.FLAME, l.getX(), l.getY() + 0.1, l.getZ(), 2, 0.15, 0.02, 0.15, 0.01);
            }
        }
        if (t % 8 == 0) brandTick(t);
    }

    private void regenTick(Player p, DungeonInstance.PState s) {
        int rg = s.stacks(Blessing.REGEN);
        if (rg == 0 && tier(s, School.SANGRE, 1)) rg = 1;
        if (rg <= 0) return;
        inst.healPlayer(p, rg);
        world.spawnParticle(Particle.HEART, p.getLocation().add(0, 2.1, 0), 1, 0.25, 0.1, 0.25, 0);
    }

    /** Aura de cada escuela: partículas suaves; con 4+ niveles aparece además un anillo giratorio. */
    private void auraTick(Player p, DungeonInstance.PState s, long t) {
        Location base = p.getLocation();
        double bx = base.getX();
        double by = base.getY();
        double bz = base.getZ();
        for (School sc : School.values()) {
            if (sc == School.NINGUNA) continue;
            int n = schoolStacks(s, sc);
            if (n <= 0) continue;
            int tr = tierOf(n);
            double a = t * 0.22 + sc.ordinal() * 1.57;
            double ox = Math.cos(a) * 0.55;
            double oz = Math.sin(a) * 0.55;
            switch (sc) {
                case CENIZA -> {
                    world.spawnParticle(Particle.SMALL_FLAME, bx + ox, by + 0.15, bz + oz, 1, 0.05, 0.05, 0.05, 0.005);
                    if (tr >= 1) world.spawnParticle(Particle.FLAME, bx - ox, by + 0.9, bz - oz, 1, 0.05, 0.1, 0.05, 0.005);
                }
                case ALMAS -> {
                    world.spawnParticle(Particle.SOUL, bx + ox, by + 0.4, bz + oz, 1, 0.05, 0.1, 0.05, 0.01);
                    if (tr >= 1) world.spawnParticle(Particle.SOUL_FIRE_FLAME, bx - ox, by + 1.5, bz - oz, 1, 0.03, 0.03, 0.03, 0.0);
                }
                case SANGRE -> {
                    world.spawnParticle(Particle.DUST, bx + ox, by + 0.2 + (t % 12) * 0.12, bz + oz, 1, 0, 0, 0, 0, RED);
                    if (tr >= 1) {
                        world.spawnParticle(Particle.DUST, bx - ox, by + 0.6 + (t % 8) * 0.1, bz - oz, 1, 0, 0, 0, 0, RED);
                    }
                }
                case VACIO -> {
                    world.spawnParticle(Particle.PORTAL, bx, by + 1.0, bz, 2, 0.35, 0.6, 0.35, 0.3);
                    if (tr >= 1) world.spawnParticle(Particle.REVERSE_PORTAL, bx + ox, by + 0.3, bz + oz, 1, 0.05, 0.05, 0.05, 0.02);
                }
                default -> { }
            }
            if (tr >= 2 && t % 8 == 0) {
                ringDust(base.clone().add(0, 1.0, 0), 0.8, 10, new Particle.DustOptions(sc.rgb(), 0.9f));
            }
        }
    }

    // --- Guardianes Espectrales

    private int guardRecharge(DungeonInstance.PState s, int gl) {
        int tk = Blessing.guardRechargeTicks(gl);
        if (tier(s, School.ALMAS, 2)) tk = (int) (tk / 1.5);
        return tk;
    }

    private void guardTick(Player p, DungeonInstance.PState s, Rt r, long t) {
        int gl = s.stacks(Blessing.GUARD);
        if (gl <= 0) {
            r.guard = 0;
            return;
        }
        if (r.guard > gl) r.guard = gl;
        if (r.guard < gl) {
            if (r.guardNext == 0) {
                r.guardNext = t + guardRecharge(s, gl);
            } else if (t >= r.guardNext) {
                r.guard++;
                r.guardNext = 0;
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, p.getLocation().add(0, 1, 0), 14, 0.5, 0.6, 0.5, 0.05);
            }
        }
        double base = t * 0.12;
        for (int i = 0; i < r.guard; i++) {
            double a = base + i * (Math.PI * 2 / Math.max(1, gl));
            Location c = p.getLocation().add(Math.cos(a) * 1.15, 1.0 + Math.sin(t * 0.07 + i) * 0.25, Math.sin(a) * 1.15);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, c, 2, 0.03, 0.03, 0.03, 0.0);
            world.spawnParticle(Particle.END_ROD, c, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    // --- Estela Ígnea

    private void trailTick(Player p, DungeonInstance.PState s, Rt r, long t) {
        int tl = s.stacks(Blessing.TRAIL);
        if (tl <= 0) {
            if (!r.trail.isEmpty()) r.trail.clear();
            return;
        }
        if (p.isSprinting()) {
            Location l = p.getLocation();
            r.trail.add(new double[]{l.getX(), l.getY(), l.getZ(), t + 20.0 * Blessing.trailSeconds(tl)});
            if (r.trail.size() > 40) r.trail.remove(0);
        }
        r.trail.removeIf(pt -> pt[3] < t);
        if (r.trail.isEmpty()) return;
        for (double[] pt : r.trail) {
            world.spawnParticle(Particle.FLAME, pt[0], pt[1] + 0.1, pt[2], 1, 0.25, 0.05, 0.25, 0.01);
            if (rnd.nextInt(4) == 0) world.spawnParticle(Particle.SMOKE, pt[0], pt[1] + 0.3, pt[2], 1, 0.2, 0.1, 0.2, 0.01);
        }
        double dmg = Blessing.trailDamage(tl);
        for (LivingEntity le : enemiesNear(p.getLocation(), 20)) {
            Location el = le.getLocation();
            for (double[] pt : r.trail) {
                double dx = el.getX() - pt[0];
                double dz = el.getZ() - pt[2];
                double dy = el.getY() - pt[1];
                if (dx * dx + dz * dz <= 2.25 && Math.abs(dy) < 2.0) {
                    Long last = r.trailHit.get(le.getUniqueId());
                    if (last == null || t - last >= 10) {
                        r.trailHit.put(le.getUniqueId(), t);
                        deal(p, le, dmg);
                        brand(le, 40);
                    }
                    break;
                }
            }
        }
        if (t % 40 == 0) r.trailHit.values().removeIf(v -> t - v > 100);
    }

    // --- Tormenta de Ceniza (legendaria)

    private void stormTick(Player p, DungeonInstance.PState s, Rt r, long t) {
        int sl = s.stacks(Blessing.STORM);
        if (sl <= 0 || t < r.stormNext) return;
        if (enemiesNear(p.getLocation(), Blessing.stormRadius(sl)).isEmpty()) return;
        r.stormNext = t + Blessing.stormCooldownTicks(sl);
        castStorm(p, sl);
    }

    private void castStorm(Player p, int sl) {
        double rad = Blessing.stormRadius(sl);
        double dmg = Blessing.stormDamage(sl);
        Set<UUID> hit = new HashSet<>();
        Location c0 = p.getLocation();
        world.playSound(c0, Sound.ENTITY_BLAZE_SHOOT, 1.3f, 0.6f);
        world.playSound(c0, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f);
        world.spawnParticle(Particle.FLAME, c0.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.15);
        final int steps = 9;
        inst.repeat(1, steps, i -> {
            if (!p.isOnline() || p.isDead()) return;
            Location c = p.getLocation();
            double cur = rad * (i + 1) / steps;
            ring(c.clone().add(0, 0.2, 0), cur, 14 + i * 5, Particle.FLAME);
            if (i % 2 == 0) ring(c.clone().add(0, 0.2, 0), cur, 8 + i * 2, Particle.LAVA);
            for (LivingEntity le : enemiesNear(c, cur + 0.8)) {
                if (hit.add(le.getUniqueId())) {
                    deal(p, le, dmg);
                    brand(le, 80);
                }
            }
        }, null);
    }

    // --- Dash, frenesí y marcas

    private void dashCueTick(Player p, DungeonInstance.PState s, Rt r, long t) {
        if (dashLevel(s) <= 0) return;
        if (!r.dashCue && t >= r.dashReady) {
            r.dashCue = true;
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.6f);
            ringDust(p.getLocation().add(0, 0.2, 0), 0.9, 16, new Particle.DustOptions(School.VACIO.rgb(), 1.0f));
        }
    }

    private void frenzyTick(Player p, DungeonInstance.PState s, Rt r, long t) {
        if (r.frenzy > 0) {
            if (t > r.frenzyUntil) {
                r.frenzy = 0;
            } else {
                Location l = p.getLocation();
                world.spawnParticle(Particle.DUST, l.getX(), l.getY() + 1.0, l.getZ(), 2 + r.frenzy, 0.35, 0.6, 0.35, 0, RED);
            }
        }
        if (tier(s, School.SANGRE, 2) && lowHp(p)) {
            Location l = p.getLocation();
            world.spawnParticle(Particle.DUST, l.getX(), l.getY() + 1.0, l.getZ(), 4, 0.4, 0.7, 0.4, 0, RED);
            if (t % 24 == 0) p.playSound(l, Sound.ENTITY_WARDEN_HEARTBEAT, 0.6f, 1.0f);
        }
    }

    private void brandTick(long t) {
        Iterator<Map.Entry<UUID, Long>> it = brands.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> en = it.next();
            if (en.getValue() <= t) {
                it.remove();
                continue;
            }
            Entity e = Bukkit.getEntity(en.getKey());
            if (e == null || !e.isValid()) {
                it.remove();
                continue;
            }
            Location l = e.getLocation().add(0, e.getHeight() + 0.2, 0);
            world.spawnParticle(Particle.FLAME, l, 2, 0.15, 0.05, 0.15, 0.01);
        }
    }

    // ------------------------------------------------------------------ al infligir daño

    /** Antes de aplicar el daño (prioridad alta): multiplicadores. */
    void modifyDealt(Player p, LivingEntity target, EntityDamageByEntityEvent e) {
        if (procDepth > 0) return;
        DungeonInstance.PState s = st(p);
        if (s == null || s.eliminated) return;
        Rt r = rt(p);
        long t = inst.tick();
        double bonus = Blessing.fury(s.stacks(Blessing.FURY)) + Blessing.pact(s.stacks(Blessing.PACT));
        int bl = s.stacks(Blessing.BLOODLUST);
        if (bl > 0 && r.frenzy > 0 && t <= r.frenzyUntil) bonus += Blessing.bloodlust(bl) * r.frenzy;
        int eb = s.stacks(Blessing.EMBER_BRAND);
        if (eb > 0 && branded(target)) bonus += Blessing.brandBonus(eb);
        if (tier(s, School.SANGRE, 2) && lowHp(p)) bonus += 0.30;
        if (t < r.echoUntil) {
            int ec = s.stacks(Blessing.ECHO);
            bonus += ec > 0 ? Blessing.echo(ec) : 0.40;
            r.echoUntil = 0;
            Location at = target.getLocation().add(0, target.getHeight() * 0.6, 0);
            world.spawnParticle(Particle.DUST, at.getX(), at.getY(), at.getZ(), 25, 0.4, 0.5, 0.4, 0, PURPLE);
            world.spawnParticle(Particle.ENCHANTED_HIT, at, 12, 0.4, 0.5, 0.4, 0.2);
            world.playSound(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 1.4f);
        }
        if (bonus != 0) e.setDamage(e.getDamage() * (1.0 + bonus));
    }

    /** Después de aplicar el daño: robo de vida, marcas, cadena y efectos de golpe. */
    void afterDealt(Player p, LivingEntity target, double dealt) {
        if (procDepth > 0 || dealt <= 0) return;
        DungeonInstance.PState s = st(p);
        if (s == null || s.eliminated) return;
        Rt r = rt(p);
        long t = inst.tick();
        Location at = target.getLocation().add(0, target.getHeight() * 0.6, 0);

        int fu = s.stacks(Blessing.FURY);
        if (fu > 0) {
            world.spawnParticle(Particle.FLAME, at, 3 + 2 * fu, 0.25, 0.3, 0.25, 0.05);
            world.spawnParticle(Particle.SMALL_FLAME, at, 4, 0.3, 0.3, 0.3, 0.02);
        }

        int eb = s.stacks(Blessing.EMBER_BRAND);
        if (eb > 0 || tier(s, School.CENIZA, 1)) {
            int secs = Math.max(3, Blessing.brandSeconds(eb));
            if (!branded(target)) world.playSound(at, Sound.ITEM_FIRECHARGE_USE, 0.5f, 1.4f);
            brand(target, secs * 20);
        }

        int vp = s.stacks(Blessing.VAMPIRE);
        if (vp > 0) {
            double heal = Math.min(4.0, dealt * Blessing.vampire(vp) * (tier(s, School.SANGRE, 1) ? 1.5 : 1.0));
            if (heal > 0.05) {
                bloodLine(at, p.getLocation().add(0, 1.1, 0));
                inst.healPlayer(p, heal);
                if (heal >= 0.8 && t - r.lastHeartFx > 10) {
                    r.lastHeartFx = t;
                    world.spawnParticle(Particle.HEART, p.getLocation().add(0, 2.0, 0), 2, 0.25, 0.1, 0.25, 0);
                }
            }
        }

        int ch = s.stacks(Blessing.CHAIN);
        if (ch > 0) chain(p, s, r, target, dealt, ch, t);
    }

    private void chain(Player p, DungeonInstance.PState s, Rt r, LivingEntity from, double dealt, int lvl, long t) {
        if (t < r.chainReady || rnd.nextDouble() > Blessing.chainChance(lvl)) return;
        Location origin = from.getLocation();
        List<LivingEntity> near = enemiesNear(origin, 7.0);
        near.removeIf(le -> le.getUniqueId().equals(from.getUniqueId()));
        if (near.isEmpty()) return;
        r.chainReady = t + 6;
        near.sort(Comparator.comparingDouble(le -> le.getLocation().distanceSquared(origin)));
        boolean legion = tier(s, School.ALMAS, 2);
        double dmg = dealt * 0.5 * (legion ? 1.5 : 1.0);
        Location prev = from.getLocation().add(0, from.getHeight() * 0.6, 0);
        int jumps = Math.min(near.size(), Blessing.chainJumps(lvl));
        for (int i = 0; i < jumps; i++) {
            LivingEntity le = near.get(i);
            Location to = le.getLocation().add(0, le.getHeight() * 0.6, 0);
            lightning(prev, to);
            deal(p, le, dmg);
            prev = to;
        }
        world.playSound(origin, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.35f, 2.0f);
    }

    // ------------------------------------------------------------------ al matar

    void onKill(LivingEntity mob, Player killer) {
        DungeonInstance.PState s = st(killer);
        if (s == null || s.eliminated) return;
        Rt r = rt(killer);
        long t = inst.tick();
        Location at = mob.getLocation().add(0, mob.getHeight() * 0.5, 0);
        boolean wasBranded = branded(mob) || mob.getFireTicks() > 0;
        brands.remove(mob.getUniqueId());

        int bl = s.stacks(Blessing.BLOODLUST);
        if (bl > 0) {
            r.frenzy = Math.min(5, r.frenzy + 1);
            r.frenzyUntil = t + 120;
            world.spawnParticle(Particle.DUST, at.getX(), at.getY(), at.getZ(), 18, 0.4, 0.5, 0.4, 0, RED);
            killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.6f, 0.7f + 0.1f * r.frenzy);
        }

        int hv = s.stacks(Blessing.SOUL_HARVEST);
        if (hv > 0) soulOrb(killer, at, hv);

        if (tier(s, School.ALMAS, 1)) {
            inst.healPlayer(killer, 2.0);
            world.spawnParticle(Particle.HEART, killer.getLocation().add(0, 2.0, 0), 2, 0.25, 0.1, 0.25, 0);
        }

        if (chainGen >= 3) return;
        int gen = chainGen + 1;
        int nv = s.stacks(Blessing.NOVA);
        if (nv > 0) {
            boolean legion = tier(s, School.ALMAS, 2);
            double rad = Blessing.novaRadius(nv) * (legion ? 1.3 : 1.0);
            double dmg = Blessing.novaDamage(nv) * (legion ? 1.5 : 1.0);
            Location c = at.clone();
            inst.later(3, () -> blast(killer, c, rad, dmg, gen, Particle.SOUL_FIRE_FLAME, false));
        }
        if (wasBranded && tier(s, School.CENIZA, 2)) {
            Location c = at.clone();
            inst.later(4, () -> blast(killer, c, 3.5, 8.0, gen, Particle.FLAME, true));
        }
    }

    /** Orbe de alma que vuela del enemigo muerto hasta el jugador y le da absorción. */
    private void soulOrb(Player p, Location from, int lvl) {
        Location start = from.clone();
        final int steps = 10;
        inst.repeat(1, steps, i -> {
            if (!p.isOnline()) return;
            Location end = p.getLocation().add(0, 1.0, 0);
            double f = (i + 1) / (double) steps;
            double e = f * f;
            double x = start.getX() + (end.getX() - start.getX()) * e;
            double y = start.getY() + (end.getY() - start.getY()) * e + Math.sin(f * Math.PI) * 0.8;
            double z = start.getZ() + (end.getZ() - start.getZ()) * e;
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, x, y, z, 2, 0.05, 0.05, 0.05, 0.0);
            world.spawnParticle(Particle.SOUL, x, y, z, 1, 0.1, 0.1, 0.1, 0.01);
        }, () -> {
            if (!p.isOnline() || p.isDead()) return;
            double cap = Blessing.harvestCap(lvl);
            double cur = p.getAbsorptionAmount();
            if (cur < cap) p.setAbsorptionAmount(Math.min(cap, cur + Blessing.harvestAbsorb(lvl)));
            Location l = p.getLocation().add(0, 1.0, 0);
            world.spawnParticle(Particle.SOUL, l, 8, 0.3, 0.5, 0.3, 0.03);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.9f);
        });
    }

    /** Explosión de área (Explosión de Almas y Pira). */
    private void blast(Player p, Location at, double rad, double dmg, int gen, Particle ringParticle, boolean fire) {
        if (!p.isOnline()) return;
        world.spawnParticle(Particle.EXPLOSION, at.clone().add(0, 0.3, 0), 1, 0, 0, 0, 0);
        world.playSound(at, fire ? Sound.ENTITY_GENERIC_EXPLODE : Sound.ENTITY_EVOKER_CAST_SPELL, 0.7f, fire ? 1.4f : 1.2f);
        if (!fire) world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.7f);
        final int steps = 5;
        inst.repeat(1, steps, i -> {
            ring(at.clone().add(0, 0.15, 0), rad * (i + 1) / steps, 10 + 4 * i, ringParticle);
            if (fire) world.spawnParticle(Particle.FLAME, at, 8, rad * 0.3, 0.3, rad * 0.3, 0.05);
            else world.spawnParticle(Particle.SCULK_SOUL, at.clone().add(0, 0.5, 0), 3, rad * 0.3, 0.3, rad * 0.3, 0.02);
        }, null);
        int prev = chainGen;
        chainGen = gen;
        try {
            for (LivingEntity le : enemiesNear(at, rad)) {
                deal(p, le, dmg);
                if (fire) brand(le, 60);
                push(le, at, 0.5, 0.25);
            }
        } finally {
            chainGen = prev;
        }
    }

    // ------------------------------------------------------------------ al recibir daño

    /** Después del multiplicador de daño recibido: dash, esquiva, guardianes, segundo aliento y espinas. */
    void onHurt(Player p, EntityDamageEvent e) {
        DungeonInstance.PState s = st(p);
        if (s == null || s.eliminated || inst.state() != DungeonInstance.State.RUNNING) return;
        DamageCause c = e.getCause();
        if (c == DamageCause.VOID || c == DamageCause.KILL || c == DamageCause.SUICIDE
                || c == DamageCause.WORLD_BORDER) {
            return;
        }
        Rt r = rt(p);
        long t = inst.tick();
        Location at = p.getLocation().add(0, 1.0, 0);

        // 1. invulnerabilidad del dash
        if (t < r.invulUntil) {
            e.setCancelled(true);
            world.spawnParticle(Particle.REVERSE_PORTAL, at, 10, 0.3, 0.5, 0.3, 0.05);
            return;
        }

        // 2. evasión sombría (solo ataques de enemigos)
        int ph = s.stacks(Blessing.PHASE);
        if (ph > 0 && e instanceof EntityDamageByEntityEvent && rnd.nextDouble() < Blessing.phase(ph)) {
            e.setCancelled(true);
            world.spawnParticle(Particle.PORTAL, at, 40, 0.4, 0.8, 0.4, 0.6);
            world.spawnParticle(Particle.DUST, at.getX(), at.getY(), at.getZ(), 20, 0.4, 0.7, 0.4, 0, PURPLE);
            world.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.8f);
            p.sendActionBar(Text.mm("<dark_purple>✧ ¡Esquivado!</dark_purple>"));
            return;
        }

        double fin = e.getFinalDamage();

        // 3. guardianes espectrales
        if (r.guard > 0 && fin > 0.5) {
            r.guard--;
            e.setCancelled(true);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 30, 0.6, 0.7, 0.6, 0.08);
            world.spawnParticle(Particle.END_ROD, at, 15, 0.6, 0.7, 0.6, 0.1);
            world.playSound(at, Sound.ITEM_SHIELD_BREAK, 1f, 1.4f);
            world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 0.6f);
            for (LivingEntity le : enemiesNear(p.getLocation(), 3.5)) push(le, p.getLocation(), 0.7, 0.3);
            p.sendActionBar(Text.mm("<aqua>◆ Un guardián te protege (" + r.guard + " restantes)</aqua>"));
            return;
        }

        // 4. segundo aliento
        if (s.stacks(Blessing.SECOND_WIND) > 0 && t >= r.windReady && fin >= p.getHealth()) {
            e.setCancelled(true);
            r.windReady = t + 2000;
            AttributeInstance mh = p.getAttribute(Attribute.MAX_HEALTH);
            double max = mh == null ? 20.0 : mh.getValue();
            p.setHealth(Math.max(2.0, Math.min(max, max * 0.4)));
            p.setFireTicks(0);
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 80, 2));
            p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 200, 0));
            Location pl = p.getLocation();
            for (LivingEntity le : enemiesNear(pl, 6.0)) push(le, pl, 1.1, 0.5);
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 70, 0.5, 1.0, 0.5, 0.4);
            inst.repeat(1, 6, i -> ring(pl.clone().add(0, 0.2, 0), 1.0 + i, 14 + 6 * i, Particle.SOUL_FIRE_FLAME), null);
            world.playSound(pl, Sound.ITEM_TOTEM_USE, 1.2f, 1.0f);
            title(p, "<green><bold>SEGUNDO ALIENTO</bold></green>", "<gray>Has burlado a la muerte</gray>");
            return;
        }

        // 5. espinas de sangre
        int th = s.stacks(Blessing.THORNS);
        if (th > 0 && e instanceof EntityDamageByEntityEvent be && be.getDamager() instanceof LivingEntity att
                && inst.isProcTarget(att)) {
            double refl = Math.min(30.0, fin * Blessing.thorns(th));
            if (refl > 0.5) {
                Location al = att.getLocation().add(0, att.getHeight() * 0.6, 0);
                bloodLine(at, al);
                world.spawnParticle(Particle.CRIT, al, 10, 0.3, 0.3, 0.3, 0.3);
                world.spawnParticle(Particle.DAMAGE_INDICATOR, al, 3, 0.2, 0.2, 0.2, 0.1);
                world.playSound(al, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.7f, 0.8f);
                deal(p, att, refl);
            }
        }
    }

    // ------------------------------------------------------------------ dash

    /** @return true si el evento de cambio de mano debe cancelarse (el jugador tiene dash). */
    boolean tryDash(Player p) {
        DungeonInstance.PState s = st(p);
        if (s == null || s.eliminated || p.isDead() || inst.state() != DungeonInstance.State.RUNNING) return false;
        int lvl = dashLevel(s);
        if (lvl <= 0) return false;
        Rt r = rt(p);
        long t = inst.tick();
        if (t < r.dashReady) {
            p.sendActionBar(Text.mm("<dark_purple>⇨ Dash recargando <white>"
                    + String.format(Locale.ROOT, "%.1f", (r.dashReady - t) / 20.0) + "s</white></dark_purple>"));
            return true;
        }
        int cd = Blessing.dashCooldownTicks(lvl);
        if (tier(s, School.VACIO, 1)) cd = (int) (cd * 0.8);
        r.dashReady = t + cd;
        r.dashCue = false;
        r.invulUntil = Math.max(r.invulUntil, t + Blessing.dashInvulTicks(lvl));

        boolean cuts = lvl >= 3 || tier(s, School.VACIO, 2);
        boolean echo = s.stacks(Blessing.ECHO) > 0 || tier(s, School.VACIO, 2);
        double dmg = 6 + 2 * lvl;
        Vector d = p.getLocation().getDirection().setY(0);
        if (d.lengthSquared() < 1.0E-4) d = new Vector(0, 0, 1);
        final Vector dir = d.normalize();

        Location from = p.getLocation();
        world.spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.4);
        world.playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 1.7f);
        world.playSound(from, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.6f);

        Set<UUID> hit = new HashSet<>();
        inst.repeat(2, 6, i -> {
            if (!p.isOnline() || p.isDead()) return;
            Vector v = dir.clone().multiply(0.85);
            v.setY(Math.max(-0.1, Math.min(0.12, p.getVelocity().getY())));
            p.setVelocity(v);
            Location l = p.getLocation().add(0, 0.9, 0);
            world.spawnParticle(Particle.REVERSE_PORTAL, l, 14, 0.25, 0.5, 0.25, 0.05);
            world.spawnParticle(Particle.DUST, l.getX(), l.getY(), l.getZ(), 8, 0.3, 0.5, 0.3, 0, PURPLE);
            if (cuts) {
                for (LivingEntity le : enemiesNear(l, 2.2)) {
                    if (hit.add(le.getUniqueId())) {
                        deal(p, le, dmg);
                        Location el = le.getLocation().add(0, le.getHeight() * 0.6, 0);
                        world.spawnParticle(Particle.SWEEP_ATTACK, el, 2, 0.3, 0.3, 0.3, 0);
                        world.playSound(el, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.7f);
                    }
                }
            }
        }, () -> {
            if (!p.isOnline()) return;
            Location l = p.getLocation().add(0, 0.9, 0);
            world.spawnParticle(Particle.CLOUD, l, 10, 0.3, 0.4, 0.3, 0.04);
            if (echo) {
                rt(p).echoUntil = inst.tick() + 70;
                ringDust(p.getLocation().add(0, 1.0, 0), 0.9, 16, PURPLE);
                p.sendActionBar(Text.mm("<dark_purple>✧ Eco del Vacío listo: tu próximo golpe será más fuerte</dark_purple>"));
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ HUD

    /** Estado de las habilidades activas para la barra de acción. */
    String hudLine(Player p) {
        DungeonInstance.PState s = st(p);
        if (s == null) return "";
        Rt r = rt(p);
        long t = inst.tick();
        List<String> parts = new ArrayList<>();
        if (dashLevel(s) > 0) {
            parts.add(t >= r.dashReady
                    ? "<light_purple>⇨ DASH</light_purple> <gray>[F]</gray>"
                    : "<dark_gray>⇨ " + String.format(Locale.ROOT, "%.1f", (r.dashReady - t) / 20.0) + "s</dark_gray>");
        }
        int gl = s.stacks(Blessing.GUARD);
        if (gl > 0) {
            parts.add("<aqua>" + "◆".repeat(Math.max(0, r.guard)) + "</aqua><dark_gray>"
                    + "◇".repeat(Math.max(0, gl - r.guard)) + "</dark_gray>");
        }
        if (s.stacks(Blessing.BLOODLUST) > 0 && r.frenzy > 0 && t <= r.frenzyUntil) {
            parts.add("<red>⚔ x" + r.frenzy + "</red>");
        }
        if (s.stacks(Blessing.SECOND_WIND) > 0) {
            parts.add(t >= r.windReady ? "<green>♥ Aliento</green>" : "<dark_gray>♥ " + ((r.windReady - t) / 20) + "s</dark_gray>");
        }
        if (t < r.echoUntil) parts.add("<dark_purple>✧ Eco</dark_purple>");
        return String.join(" <dark_gray>·</dark_gray> ", parts);
    }

    // ------------------------------------------------------------------ oferta y elección

    private int choices() {
        return Math.max(1, Math.min(4, cfg.i("blessings.choices")));
    }

    private List<Blessing> rollOffer(DungeonInstance.PState s, int n, int room, Rarity minRarity) {
        List<Blessing> pool = new ArrayList<>();
        for (Blessing b : Blessing.values()) {
            if (s.stacks(b) >= b.max || room < b.minRoom) continue;
            if (b.requires != null && s.stacks(b.requires) <= 0
                    && !(b.requires == Blessing.DASH && dashLevel(s) > 0)) {
                continue;
            }
            pool.add(b);
        }
        if (minRarity != null) {
            List<Blessing> good = new ArrayList<>();
            for (Blessing b : pool) {
                if (b.rarity.ordinal() >= minRarity.ordinal()) good.add(b);
            }
            if (good.size() >= n) pool = good;
        }
        List<Blessing> out = new ArrayList<>();
        while (out.size() < n && !pool.isEmpty()) {
            int[] w = new int[pool.size()];
            int total = 0;
            for (int i = 0; i < w.length; i++) {
                Blessing b = pool.get(i);
                int wt = b.weight * 2;
                // las escuelas en las que ya tienes bendiciones salen más a menudo (para poder completar sinergias)
                if (b.school != School.NINGUNA && schoolStacks(s, b.school) > 0) wt += b.weight;
                w[i] = wt;
                total += wt;
            }
            int roll = rnd.nextInt(total);
            for (int i = 0; i < w.length; i++) {
                roll -= w[i];
                if (roll < 0) {
                    out.add(pool.remove(i));
                    break;
                }
            }
        }
        return out;
    }

    /** Ofrece una bendición a cada jugador vivo. @param boss true = recompensa de jefe (mínimo rara). */
    void offer(boolean boss) {
        if (inst.state() != DungeonInstance.State.RUNNING) return;
        int n = choices();
        for (UUID u : new ArrayList<>(inst.memberStates().keySet())) {
            DungeonInstance.PState s = inst.pstate(u);
            if (s == null || s.eliminated) continue;
            if (s.offer != null) choose(u, rnd.nextInt(s.offer.size()), true);
            List<Blessing> offer = rollOffer(s, n, inst.roomOrdinal(), boss ? Rarity.RARE : null);
            if (offer.isEmpty()) continue;
            s.offer = offer;
            rt(u).offerBoss = boss;
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
            p.sendMessage(Text.mm(DungeonManager.PREFIX + (boss ? "<gold>¡Recompensa de jefe!</gold> " : "<yellow>¡Bendición disponible!</yellow> "))
                    .append(Text.mm("<green><bold>[Elegir]</bold></green>")
                            .clickEvent(ClickEvent.runCommand("/dungeon bless"))));
            if (!p.isDead()) openMenu(p);
        }
    }

    /** Quien no eligió a tiempo recibe una al azar al entrar en la siguiente sala. */
    void autoPickPending() {
        for (UUID u : new ArrayList<>(inst.memberStates().keySet())) {
            DungeonInstance.PState s = inst.pstate(u);
            if (s != null && s.offer != null) choose(u, rnd.nextInt(s.offer.size()), true);
        }
    }

    private void reroll(Player p, DungeonInstance.PState s) {
        if (s.offer == null) return;
        if (s.rerolls <= 0) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            return;
        }
        Rt r = rt(p);
        List<Blessing> prev = s.offer;
        List<Blessing> offer = prev;
        for (int attempt = 0; attempt < 4; attempt++) {
            List<Blessing> cand = rollOffer(s, choices(), inst.roomOrdinal(), r.offerBoss ? Rarity.RARE : null);
            if (cand.isEmpty()) break;
            offer = cand;
            if (!new HashSet<>(cand).equals(new HashSet<>(prev))) break;
        }
        s.offer = offer;
        s.rerolls--;
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.7f);
        inst.later(1, () -> {
            if (p.isOnline()) openMenu(p);
        });
    }

    void choose(UUID u, int idx, boolean auto) {
        DungeonInstance.PState s = inst.pstate(u);
        if (s == null || s.offer == null || idx < 0 || idx >= s.offer.size()) return;
        Blessing b = s.offer.get(idx);
        s.offer = null;
        int oldDash = dashLevel(s);
        int oldSchool = schoolStacks(s, b.school);
        int lvl = s.blessings.merge(b, 1, Integer::sum);
        if (b == Blessing.LIFE) {
            s.bonusLives++;
            s.lives++;
        }
        Rt r = rt(u);
        if (b == Blessing.GUARD) {
            r.guard = lvl;
            r.guardNext = 0;
        }
        if (b == Blessing.STORM) r.stormNext = 0;
        if (dashLevel(s) > 0 && oldDash == 0) {
            r.dashReady = 0;
            r.dashCue = true;
        }
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            applyAttributes(p, s);
            if (b == Blessing.VITALITY) inst.healPlayer(p, 4.0);
            p.sendMessage(Text.mm(DungeonManager.PREFIX + "<gray>" + (auto ? "Bendición aleatoria: " : "Bendición elegida: ")
                    + "</gray>" + b.rarity.color + "<bold>" + b.title + "</bold> <gray>(nivel " + lvl + "/" + b.max + ")</gray>"));
            title(p, b.rarity.color + "<bold>" + b.title.toUpperCase() + "</bold>",
                    b.rarity.color + b.rarity.label + " <dark_gray>·</dark_gray> " + b.school.color + b.school.label
                            + " <dark_gray>·</dark_gray> <gray>nivel " + lvl + "/" + b.max + "</gray>");
            pickFx(p, b);
            if (dashLevel(s) > 0 && oldDash == 0) {
                p.sendMessage(Text.mm(DungeonManager.PREFIX + "<light_purple>Pulsa <white><bold>F</bold></white> (cambiar de mano) para hacer un dash.</light_purple>"));
            }
            int now = schoolStacks(s, b.school);
            if (b.school != School.NINGUNA && tierOf(now) > tierOf(oldSchool)) {
                final int newTier = tierOf(now);
                inst.later(45, () -> {
                    if (p.isOnline()) synergyFx(p, b.school, newTier);
                });
            }
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof DungeonInstance.BlessingMenu) {
                Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
            }
        }
        inst.broadcast("<white>" + s.name + "</white> <gray>obtiene</gray> " + b.rarity.color + b.title
                + " <dark_gray>(" + b.school.color + b.school.label + "</dark_gray><dark_gray>)</dark_gray>");
    }

    /** Animación al elegir: hélice de partículas de la escuela y estallido según la rareza. */
    private void pickFx(Player p, Blessing b) {
        Particle.DustOptions dust = new Particle.DustOptions(b.school.rgb(), 1.4f);
        inst.repeat(1, 18, i -> {
            if (!p.isOnline()) return;
            Location c = p.getLocation();
            double y = i * 0.12;
            for (int k = 0; k < 2; k++) {
                double a = i * 0.55 + k * Math.PI;
                double x = c.getX() + Math.cos(a) * 0.9;
                double z = c.getZ() + Math.sin(a) * 0.9;
                world.spawnParticle(Particle.DUST, x, c.getY() + y, z, 2, 0.02, 0.02, 0.02, 0, dust);
                world.spawnParticle(Particle.END_ROD, x, c.getY() + y, z, 1, 0, 0, 0, 0.01);
            }
        }, () -> {
            if (p.isOnline()) {
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 25, 0.4, 0.8, 0.4, 0.25);
            }
        });
        Location l = p.getLocation();
        switch (b.rarity) {
            case COMMON -> p.playSound(l, Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.4f);
            case RARE -> {
                p.playSound(l, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.3f);
                world.spawnParticle(Particle.ENCHANT, l.clone().add(0, 1.5, 0), 40, 0.6, 0.8, 0.6, 0.6);
            }
            case EPIC -> {
                p.playSound(l, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.1f);
                p.playSound(l, Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 0.8f);
                world.spawnParticle(Particle.END_ROD, l.clone().add(0, 1, 0), 50, 0.5, 1.0, 0.5, 0.15);
                world.strikeLightningEffect(l);
            }
            case LEGENDARY -> {
                p.playSound(l, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.0f);
                p.playSound(l, Sound.ITEM_TOTEM_USE, 0.8f, 1.2f);
                world.spawnParticle(Particle.FLAME, l.clone().add(0, 1, 0), 80, 0.5, 1.2, 0.5, 0.2);
                world.spawnParticle(Particle.END_ROD, l.clone().add(0, 1, 0), 60, 0.6, 1.2, 0.6, 0.2);
                world.strikeLightningEffect(l);
            }
        }
    }

    /** Aviso y efecto al desbloquear una sinergia de escuela. */
    private void synergyFx(Player p, School sc, int tr) {
        title(p, sc.color + "<bold>SINERGIA: " + sc.tierName(tr).toUpperCase() + "</bold>",
                "<gray>" + sc.tierDesc(tr) + "</gray>");
        p.sendMessage(Text.mm(DungeonManager.PREFIX + sc.color + "<bold>✦ Sinergia de " + sc.label + " " + (tr == 1 ? "I" : "II")
                + ": " + sc.tierName(tr) + "</bold> <gray>— " + sc.tierDesc(tr) + "</gray>"));
        Location l = p.getLocation();
        p.playSound(l, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f);
        p.playSound(l, Sound.ENTITY_EVOKER_CAST_SPELL, 0.8f, 1.2f);
        Particle.DustOptions dust = new Particle.DustOptions(sc.rgb(), 1.5f);
        inst.repeat(1, 8, i -> ringDust(p.getLocation().add(0, 0.2 + i * 0.25, 0), 1.0 + 0.1 * i, 18, dust), null);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, l.clone().add(0, 1, 0), 40, 0.5, 1.0, 0.5, 0.3);
        inst.broadcast("<white>" + st(p).name + "</white> <gray>activa la sinergia</gray> " + sc.color + "<bold>" + sc.tierName(tr)
                + "</bold> <gray>(" + sc.label + ")</gray>");
    }

    // ------------------------------------------------------------------ menú

    boolean openMenu(Player p) {
        DungeonInstance.PState s = st(p);
        if (s == null || s.eliminated || s.offer == null || s.offer.isEmpty()) return false;
        DungeonInstance.BlessingMenu menu = new DungeonInstance.BlessingMenu(inst);
        Inventory inv = Bukkit.createInventory(menu, 36,
                Text.mm("<gradient:#ffb347:#ff4d4d><bold>✦ Elige tu bendición ✦</bold></gradient>"));
        menu.inv = inv;
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.space());
        filler.setItemMeta(fm);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        int[] slots = CHOICE_SLOTS[s.offer.size() - 1];
        for (int i = 0; i < s.offer.size(); i++) {
            Blessing b = s.offer.get(i);
            inv.setItem(slots[i], blessingItem(b, s));
            ItemStack pane = new ItemStack(b.rarity.pane);
            ItemMeta pm = pane.getItemMeta();
            pm.displayName(Text.item(b.rarity.color + b.rarity.label));
            pane.setItemMeta(pm);
            inv.setItem(slots[i] - 9, pane);
            inv.setItem(slots[i] + 9, pane);
        }
        inv.setItem(INFO_SLOT, infoItem(s));
        inv.setItem(REROLL_SLOT, rerollItem(s));
        p.openInventory(inv);
        return true;
    }

    void click(Player p, int slot) {
        DungeonInstance.PState s = st(p);
        if (s == null || s.offer == null || s.offer.isEmpty()) return;
        if (slot == REROLL_SLOT) {
            reroll(p, s);
            return;
        }
        int[] slots = CHOICE_SLOTS[s.offer.size() - 1];
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                choose(p.getUniqueId(), i, false);
                return;
            }
        }
    }

    private static String levelBar(int cur, int max) {
        return "<green>" + "■".repeat(Math.max(0, cur)) + "</green><yellow>■</yellow><dark_gray>"
                + "■".repeat(Math.max(0, max - cur - 1)) + "</dark_gray>";
    }

    private ItemStack blessingItem(Blessing b, DungeonInstance.PState s) {
        int cur = s.stacks(b);
        int next = cur + 1;
        ItemStack it = new ItemStack(b.icon);
        ItemMeta m = it.getItemMeta();
        m.displayName(Text.item(b.rarity.color + "<bold>" + b.title));
        List<Component> lore = new ArrayList<>();
        lore.add(Text.item(b.rarity.color + b.rarity.label + " <dark_gray>·</dark_gray> " + b.school.color + b.school.label));
        lore.add(Text.item(""));
        for (String line : b.desc(next).split("\n")) lore.add(Text.item("<gray>" + line));
        lore.add(Text.item(""));
        lore.add(Text.item("<dark_gray>Nivel:</dark_gray> " + levelBar(cur, b.max) + " <white>" + next + "</white><dark_gray>/" + b.max));
        if (cur > 0) lore.add(Text.item("<dark_gray>Mejora tu nivel actual (" + cur + ")"));
        if (b.school != School.NINGUNA) {
            int n = schoolStacks(s, b.school);
            int t0 = tierOf(n);
            int t1 = tierOf(n + 1);
            lore.add(Text.item(""));
            lore.add(Text.item(b.school.color + "✦ " + b.school.label + " <dark_gray>(" + n + " → " + (n + 1) + ")"));
            if (t1 > t0) {
                lore.add(Text.item("<green>¡Desbloquea la sinergia <bold>" + b.school.tierName(t1) + "</bold>!"));
                lore.add(Text.item("<gray>" + b.school.tierDesc(t1)));
            } else if (t0 >= 2) {
                lore.add(Text.item("<green>✔ Sinergia completa"));
            } else {
                lore.add(Text.item("<dark_gray>Próxima sinergia con " + (t0 == 0 ? 2 : 4) + " niveles de " + b.school.label));
            }
        }
        lore.add(Text.item(""));
        lore.add(Text.item("<yellow>▶ Clic para elegir"));
        m.lore(lore);
        if (b.rarity.ordinal() >= Rarity.EPIC.ordinal()) m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack infoItem(DungeonInstance.PState s) {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta m = it.getItemMeta();
        m.displayName(Text.item("<yellow><bold>Tu build"));
        List<Component> lore = new ArrayList<>();
        boolean any = false;
        for (School sc : School.values()) {
            int n = schoolStacks(s, sc);
            if (n <= 0) continue;
            any = true;
            lore.add(Text.item(sc.color + "<bold>" + sc.label + "</bold> <dark_gray>(" + n + ")"));
            for (Blessing b : Blessing.values()) {
                if (b.school == sc && s.stacks(b) > 0) {
                    lore.add(Text.item("<gray> • " + b.rarity.color + b.title + " <gray>x" + s.stacks(b)));
                }
            }
            int tr = tierOf(n);
            for (int k = 1; k <= 2 && sc != School.NINGUNA; k++) {
                if (tr >= k) lore.add(Text.item("<green>   ✔ " + sc.tierName(k)));
            }
        }
        if (!any) lore.add(Text.item("<gray>Aún no tienes bendiciones."));
        lore.add(Text.item(""));
        lore.add(Text.item("<dark_gray>Reúne 2 y 4 niveles de una escuela"));
        lore.add(Text.item("<dark_gray>para activar sus sinergias."));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack rerollItem(DungeonInstance.PState s) {
        boolean has = s.rerolls > 0;
        ItemStack it = new ItemStack(has ? Material.ENDER_EYE : Material.BARRIER);
        ItemMeta m = it.getItemMeta();
        m.displayName(Text.item(has ? "<yellow><bold>Relanzar opciones" : "<gray><bold>Sin relanzamientos"));
        List<Component> lore = new ArrayList<>();
        lore.add(Text.item("<gray>Relanzamientos restantes: <white>" + s.rerolls));
        if (has) {
            lore.add(Text.item(""));
            lore.add(Text.item("<yellow>▶ Clic para cambiar las opciones"));
        }
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }
}
