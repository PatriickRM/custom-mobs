package me.custommobs.boss;

import me.custommobs.CustomMobsPlugin;
import me.custommobs.Sfx;
import me.custommobs.Text;
import me.custommobs.model.MobVisuals;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntConsumer;

/**
 * Vorthak, el Centinela de Ceniza.
 * Un Wither Skeleton colosal con 4 fases, habilidades propias, barra de jefe y loot custom.
 *
 * Fase III: ritual de los Heraldos (jefe inmune + 2 mini bosses + cuenta atrás; si se agota, Sentencia = one-shot).
 * Fase IV: meteoros, guadaña giratoria, combos, aura mortal y "Último Aliento".
 */
public final class VorthakBoss {

    public static final String ID = "vorthak";

    private static final double BEAM_LEN = 14.0;

    // ------------------------------------------------------------------ fases

    public enum Phase {
        ONE("<gray>Fase I</gray> <dark_gray>·</dark_gray> <white>Centinela de Ceniza</white>",
                BossBar.Color.WHITE, BossBar.Overlay.NOTCHED_10, 14, 0.27, 1.00, 1.00),
        TWO("<gray>Fase II</gray> <dark_gray>·</dark_gray> <gold>Furia Infernal</gold>",
                BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10, 18, 0.31, 0.85, 1.20),
        THREE("<gray>Fase III</gray> <dark_gray>·</dark_gray> <light_purple>Juicio de los Heraldos</light_purple>",
                BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_12, 22, 0.34, 0.70, 1.40),
        FOUR("<gray>Fase IV</gray> <dark_gray>·</dark_gray> <dark_red><bold>Último Aliento</bold></dark_red>",
                BossBar.Color.RED, BossBar.Overlay.NOTCHED_20, 36, 0.40, 0.45, 1.90);

        final String title;
        final BossBar.Color color;
        final BossBar.Overlay overlay;
        final double attack;   // daño melee (atributo)
        final double speed;    // velocidad de movimiento
        final double cd;       // multiplicador de cooldowns (menor = más rápido)
        final double dmg;      // multiplicador del daño de habilidades

        Phase(String title, BossBar.Color color, BossBar.Overlay overlay,
              double attack, double speed, double cd, double dmg) {
            this.title = title;
            this.color = color;
            this.overlay = overlay;
            this.attack = attack;
            this.speed = speed;
            this.cd = cd;
            this.dmg = dmg;
        }
    }

    private enum Ability {
        SLAM(1, 140),
        SUMMON(1, 420),
        NOVA(2, 230),
        SKULLS(2, 170),
        LEAP(2, 210),
        BLINK(3, 190),
        LIGHTNING(3, 270),
        PULL(3, 320),
        AEGIS(3, 900),
        STORM(4, 220),
        METEORS(4, 260),
        SCYTHE(4, 320),
        ERUPTION(1, 190),
        FIREWALL(2, 310),
        RIFTS(3, 440),
        SENTENCE(4, 400);

        final int minPhase;
        final int cd;

        Ability(int minPhase, int cd) {
            this.minPhase = minPhase;
            this.cd = cd;
        }
    }

    // ------------------------------------------------------------------ estado

    private final CustomMobsPlugin plugin;
    private final BossManager manager;
    private final WitherSkeleton entity;
    private final BossBar bar;
    private final BossBar ritualBar;
    private final Set<UUID> shown = new HashSet<>();
    private final Set<UUID> minions = new HashSet<>();
    private final Map<UUID, Integer> heralds = new HashMap<>();   // uuid -> tipo (0 brasas, 1 vacío)
    private final Map<UUID, Long> heraldNext = new HashMap<>();
    private final Map<UUID, BossBar> heraldBars = new HashMap<>();
    private final Map<Ability, Long> cooldowns = new EnumMap<>(Ability.class);
    private final Map<UUID, Double> damageDealt = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    private Phase phase;
    private BukkitTask task;
    private boolean transitioning;
    private boolean dead;
    private boolean lastStandUsed;
    private int ritualState;      // 0 pendiente, 1 activo, 2 sentencia, 3 terminado
    private long ritualEnd;
    private long ritualTotal;
    private long now;
    private long busyUntil;
    private long shieldUntil;
    private long stunUntil;
    private long idle;

    private VorthakBoss(CustomMobsPlugin plugin, BossManager manager, WitherSkeleton entity, Phase phase) {
        this.plugin = plugin;
        this.manager = manager;
        this.entity = entity;
        this.phase = phase;
        this.bar = BossBar.bossBar(Component.text("Vorthak"), 1f, phase.color, phase.overlay);
        this.ritualBar = BossBar.bossBar(Component.text("Ritual"), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    }

    // ------------------------------------------------------------------ creación

    static VorthakBoss spawn(CustomMobsPlugin plugin, BossManager manager, Location loc) {
        double maxHp = plugin.getConfig().getDouble("vorthak.health", 1000.0);
        WitherSkeleton ws = loc.getWorld().spawn(loc, WitherSkeleton.class, e -> {
            e.customName(Text.mm("<gradient:#ff4d4d:#ffb347><bold>Vorthak</bold></gradient>"));
            e.setCustomNameVisible(false);
            e.setPersistent(true);
            e.setRemoveWhenFarAway(false);
            e.setCanPickupItems(false);
            e.getPersistentDataContainer().set(manager.bossKey, PersistentDataType.STRING, ID);
            e.getPersistentDataContainer().set(manager.phaseKey, PersistentDataType.INTEGER, 0);
            e.getPersistentDataContainer().set(manager.ritualKey, PersistentDataType.INTEGER, 0);
            setAttr(e, Attribute.MAX_HEALTH, maxHp);
            e.setHealth(maxHp);
            equip(e);
        });
        VorthakBoss boss = new VorthakBoss(plugin, manager, ws, Phase.ONE);
        boss.applyStats();
        boss.start();
        boss.intro();
        return boss;
    }

    static VorthakBoss adopt(CustomMobsPlugin plugin, BossManager manager, WitherSkeleton ws, Phase phase, int ritual) {
        VorthakBoss boss = new VorthakBoss(plugin, manager, ws, phase);
        boss.applyStats();
        ws.setAI(true);
        boss.start();
        if (phase == Phase.THREE && (ritual == 1 || ritual == 2)) {
            // el ritual se interrumpió (reinicio / descarga): se reinicia con Heraldos nuevos
            boss.later(40, boss::startRitual);
        } else {
            boss.ritualState = ritual;
        }
        return boss;
    }

    private static void equip(WitherSkeleton e) {
        EntityEquipment eq = e.getEquipment();
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta m = sword.getItemMeta();
        m.setEnchantmentGlintOverride(true);
        sword.setItemMeta(m);
        eq.setItemInMainHand(sword);
        eq.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
        eq.setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
        eq.setLeggings(new ItemStack(Material.NETHERITE_LEGGINGS));
        eq.setBoots(new ItemStack(Material.NETHERITE_BOOTS));
        eq.setItemInMainHandDropChance(0f);
        eq.setHelmetDropChance(0f);
        eq.setChestplateDropChance(0f);
        eq.setLeggingsDropChance(0f);
        eq.setBootsDropChance(0f);
    }

    private static void setAttr(LivingEntity e, Attribute attr, double value) {
        AttributeInstance inst = e.getAttribute(attr);
        if (inst != null) {
            inst.setBaseValue(value);
        }
    }

    private void applyStats() {
        setAttr(entity, Attribute.ATTACK_DAMAGE, phase.attack);
        setAttr(entity, Attribute.MOVEMENT_SPEED, phase.speed);
        setAttr(entity, Attribute.KNOCKBACK_RESISTANCE, 1.0);
        setAttr(entity, Attribute.FOLLOW_RANGE, 48.0);
        setAttr(entity, Attribute.SCALE, 1.7);
        setAttr(entity, Attribute.STEP_HEIGHT, 1.2);
        bar.color(phase.color);
        bar.overlay(phase.overlay);
    }

    private void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
        applyModel();
    }

    /**
     * Vorthak se dibuja con su modelo 3D propio (resource pack): el Wither Skeleton real queda invisible y sin voz.
     * Como la invisibilidad no oculta armadura ni objeto en mano, el equipo vanilla se retira y su protección
     * (netherita completa) se pasa a atributos para no cambiar el equilibrio del combate.
     */
    private void applyModel() {
        entity.setSilent(true);
        MobVisuals vis = plugin.visuals();
        if (vis == null || !vis.has("vorthak")) return;
        EntityEquipment eq = entity.getEquipment();
        if (eq != null) {
            eq.setHelmet(null);
            eq.setChestplate(null);
            eq.setLeggings(null);
            eq.setBoots(null);
            eq.setItemInMainHand(vis.invisibleItem(Material.NETHERITE_SWORD));
            eq.setItemInMainHandDropChance(0f);
        }
        setAttr(entity, Attribute.ARMOR, 20.0);
        setAttr(entity, Attribute.ARMOR_TOUGHNESS, 12.0);
        vis.attach(entity, phaseModel(), false);
    }

    /** Cada fase tiene su propio modelo: vorthak (I), vorthak2 (II), vorthak3 (III), vorthak4 (IV). */
    private String phaseModel() {
        MobVisuals vis = plugin.visuals();
        String id = phase.ordinal() == 0 ? "vorthak" : "vorthak" + (phase.ordinal() + 1);
        return vis != null && vis.has(id) ? id : "vorthak";
    }

    /** Cambia el modelo con un destello al transformarse. */
    private void swapModel() {
        MobVisuals vis = plugin.visuals();
        if (vis == null || !vis.has("vorthak")) return;
        vis.attach(entity, phaseModel(), false);
        Location l = entity.getLocation().add(0, 2, 0);
        l.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, l, 1, 0, 0, 0, 0);
        l.getWorld().spawnParticle(Particle.END_ROD, l, 60, 1.2, 1.8, 1.2, 0.2);
    }

    /** Gesto de ataque del modelo (alza el mandoble) al lanzar una habilidad. */
    private void swing() {
        MobVisuals vis = plugin.visuals();
        if (vis != null) vis.attack(entity.getUniqueId(), 10);
    }

    // ---- voz propia (el Wither Skeleton real va en silencio)
    private long nextVoice;
    private Location lastStep;
    private double stepAcc;
    private long lastHurtFx;

    private void voiceTick() {
        Location l = entity.getLocation();
        if (lastStep != null && lastStep.getWorld() != null && lastStep.getWorld().equals(l.getWorld())) {
            double dx = l.getX() - lastStep.getX();
            double dz = l.getZ() - lastStep.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < 3.0) stepAcc += d;
            if (stepAcc >= 2.6) {
                stepAcc = 0;
                Sfx.play(l, "entity.iron_golem.step", 1.6f, 0.5f);
                Sfx.play(l, "block.netherite_block.step", 1.2f, 0.6f);
            }
        }
        lastStep = l;
        if (now >= nextVoice) {
            nextVoice = now + 120 + ThreadLocalRandom.current().nextInt(140);
            Sfx.play(l, "entity.wither.ambient", 1.6f, 0.45f);
        }
    }

    /** Quejido al recibir un golpe (con un pequeño freno para no saturar). */
    public void hurtFx() {
        long t = System.currentTimeMillis();
        if (t - lastHurtFx < 350) return;
        lastHurtFx = t;
        Sfx.play(entity.getLocation(), "entity.iron_golem.hurt", 1.5f, 0.5f);
        Sfx.play(entity.getLocation(), "entity.wither.hurt", 1.0f, 0.6f);
    }

    /** Se detiene sin matar a la entidad (descarga de chunk, apagado). */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        clearHeraldBars();
        for (UUID id : shown) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.hideBossBar(bar);
                p.hideBossBar(ritualBar);
            }
        }
        shown.clear();
    }

    /** Elimina al jefe y a sus súbditos (comando killall). */
    public void discard() {
        stop();
        killMinions();
        entity.remove();
    }

    public WitherSkeleton entity() {
        return entity;
    }

    public boolean isTransitioning() {
        return transitioning;
    }

    public boolean isShielded() {
        return now < shieldUntil;
    }

    /** Inmune durante el ritual de Heraldos y la Sentencia. */
    public boolean isImmune() {
        return ritualState == 1 || ritualState == 2;
    }

    /** Aturdido tras completar el ritual: recibe +50% de daño. */
    public boolean isStunned() {
        return now < stunUntil;
    }

    public Phase phase() {
        return phase;
    }

    public void recordDamage(Player p, double amount) {
        damageDealt.merge(p.getUniqueId(), amount, Double::sum);
        names.put(p.getUniqueId(), p.getName());
    }

    // ------------------------------------------------------------------ utilidades

    private boolean alive() {
        return !dead && entity.isValid() && !entity.isDead();
    }

    private void later(int ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (alive()) r.run();
        }, ticks);
    }

    private void repeat(int period, int times, IntConsumer step, Runnable done) {
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (!alive()) {
                    cancel();
                    return;
                }
                if (i >= times) {
                    cancel();
                    if (done != null) done.run();
                    return;
                }
                step.accept(i++);
            }
        }.runTaskTimer(plugin, 0L, period);
    }

    private List<Player> playersAt(Location center, double radius) {
        List<Player> out = new ArrayList<>();
        for (Player p : center.getWorld().getNearbyPlayers(center, radius)) {
            GameMode gm = p.getGameMode();
            if ((gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) && !p.isDead()) {
                out.add(p);
            }
        }
        return out;
    }

    private List<Player> players(double radius) {
        return playersAt(entity.getLocation(), radius);
    }

    private Player nearest(Location loc, List<Player> list) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : list) {
            if (!p.getWorld().equals(loc.getWorld())) continue;
            double d = p.getLocation().distanceSquared(loc);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private void hurt(Player p, double base) {
        p.damage(base * phase.dmg, entity);
    }

    private void knock(Player p, Location from, double horizontal, double vertical) {
        Vector d = p.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (d.lengthSquared() < 1.0E-4) d = new Vector(1, 0, 0);
        d.normalize().multiply(horizontal).setY(vertical);
        p.setVelocity(d);
    }

    private void ring(Location c, double r, int points, Particle particle) {
        World w = c.getWorld();
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points;
            w.spawnParticle(particle, c.getX() + Math.cos(a) * r, c.getY() + 0.15, c.getZ() + Math.sin(a) * r,
                    1, 0, 0, 0, 0);
        }
    }

    private void sound(Location l, Sound s, float vol, float pitch) {
        l.getWorld().playSound(l, s, vol, pitch);
    }

    private void wither(Player p, int ticks) {
        wither(p, ticks, 0);
    }

    private void wither(Player p, int ticks, int amplifier) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, ticks, amplifier));
    }

    private void titleAll(double radius, String title, String subtitle, int staySeconds) {
        Title t = Title.title(Text.mm(title), Text.mm(subtitle),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(staySeconds), Duration.ofMillis(800)));
        for (Player p : players(radius)) {
            p.showTitle(t);
        }
    }

    private void persistRitual() {
        entity.getPersistentDataContainer().set(manager.ritualKey, PersistentDataType.INTEGER,
                ritualState == 2 ? 1 : ritualState);
    }

    // ------------------------------------------------------------------ bucle principal

    private void tick() {
        if (!entity.isValid()) {
            stop();
            manager.unregister(entity.getUniqueId());
            return;
        }
        now += 2;
        updateBar();
        ambient();
        voiceTick();
        musicTick();
        if (transitioning) return;

        checkPhase();
        if (transitioning) return;

        List<Player> near = players(48);
        if (near.isEmpty()) {
            idle += 2;
            if (idle > 200 && now % 20 == 0) {
                double max = maxHealth();
                entity.setHealth(Math.min(max, entity.getHealth() + max * 0.01));
            }
            return;
        }
        idle = 0;
        acquireTarget(near);

        // Aura de Ceniza (Fase IV): marchita a quien se quede cerca
        if (phase == Phase.FOUR && now % 20 == 0) {
            ring(entity.getLocation(), 7, 48, Particle.SOUL_FIRE_FLAME);
            for (Player p : near) {
                if (p.getLocation().distanceSquared(entity.getLocation()) <= 49) {
                    wither(p, 60, 1);
                }
            }
        }

        // Último Aliento: una sola vez, al borde de la muerte
        if (phase == Phase.FOUR && !lastStandUsed
                && entity.getHealth() / maxHealth() <= plugin.getConfig().getDouble("vorthak.last-stand-threshold", 0.07)) {
            lastStand();
            return;
        }

        if (ritualState == 1) {
            // Vorthak queda inmóvil y sin atacar mientras dura el ritual
            entity.setAI(false);
            entity.setTarget(null);
            ritualTick(near);
            return;
        }
        if (ritualState == 2 || now < stunUntil) return;
        if (now < busyUntil) return;
        castNext(near);
    }

    private double maxHealth() {
        AttributeInstance a = entity.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 1000.0 : a.getValue();
    }

    private void acquireTarget(List<Player> near) {
        LivingEntity t = entity.getTarget();
        if (t != null && t.isValid() && !t.isDead()) return;
        Location loc = entity.getLocation();
        near.stream()
                .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(loc)))
                .ifPresent(entity::setTarget);
    }

    private void updateBar() {
        double max = maxHealth();
        float prog = (float) Math.max(0.0, Math.min(1.0, entity.getHealth() / max));
        bar.progress(prog);
        String status = "";
        if (isImmune()) status = " <light_purple>⛨ Inmune</light_purple>";
        else if (isShielded()) status = " <aqua>🛡 Égida</aqua>";
        else if (isStunned()) status = " <yellow>✦ Aturdido</yellow>";
        bar.name(Text.mm("<dark_gray>☠</dark_gray> <gradient:#ff4d4d:#ffb347><bold>VORTHAK</bold></gradient> "
                + "<dark_gray>·</dark_gray> " + phase.title
                + " <dark_gray>[</dark_gray><white>" + (int) Math.ceil(entity.getHealth()) + "</white>"
                + "<gray>/</gray><white>" + (int) max + "</white><dark_gray>]</dark_gray>" + status));

        if (ritualState == 1) {
            long left = Math.max(0, ritualEnd - now);
            ritualBar.progress((float) Math.max(0.0, Math.min(1.0, left / (double) Math.max(1, ritualTotal))));
            ritualBar.name(Text.mm("<red><bold>⌛ SENTENCIA EN " + (left / 20) + "s</bold></red> "
                    + "<dark_gray>·</dark_gray> <light_purple>Heraldos vivos: " + heralds.size() + "</light_purple>"));

            // una barra de vida por cada Heraldo
            for (Map.Entry<UUID, Integer> en : heralds.entrySet()) {
                Entity ent = Bukkit.getEntity(en.getKey());
                if (!(ent instanceof WitherSkeleton h) || !h.isValid()) continue;
                int kind = en.getValue();
                BossBar hb = heraldBars.get(en.getKey());
                if (hb == null) {
                    hb = BossBar.bossBar(Component.text("Heraldo"), 1f,
                            kind == 0 ? BossBar.Color.YELLOW : BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
                    heraldBars.put(en.getKey(), hb);
                    showToAll(hb);
                }
                AttributeInstance hm = h.getAttribute(Attribute.MAX_HEALTH);
                double hmax = hm == null ? 250.0 : hm.getValue();
                hb.progress((float) Math.max(0.0, Math.min(1.0, h.getHealth() / hmax)));
                hb.name(Text.mm((kind == 0
                        ? "<gradient:#ff8c00:#ff3d00><bold>Heraldo de Brasas</bold></gradient>"
                        : "<gradient:#b388ff:#6a1b9a><bold>Heraldo del Vacío</bold></gradient>")
                        + " <dark_gray>[</dark_gray><white>" + (int) Math.ceil(h.getHealth()) + "</white>"
                        + "<gray>/</gray><white>" + (int) hmax + "</white><dark_gray>]</dark_gray>"));
            }
            heraldBars.entrySet().removeIf(en -> {
                if (heralds.containsKey(en.getKey())) return false;
                hideFromAll(en.getValue());
                return true;
            });
        }

        Location loc = entity.getLocation();
        for (Player p : entity.getWorld().getPlayers()) {
            boolean inRange = p.getLocation().distanceSquared(loc) <= 80 * 80;
            if (inRange) {
                if (shown.add(p.getUniqueId())) {
                    p.showBossBar(bar);
                    if (ritualState == 1) p.showBossBar(ritualBar);
                    for (BossBar hb : heraldBars.values()) p.showBossBar(hb);
                }
            } else if (shown.remove(p.getUniqueId())) {
                p.hideBossBar(bar);
                p.hideBossBar(ritualBar);
                for (BossBar hb : heraldBars.values()) p.hideBossBar(hb);
            }
        }
    }

    private void showToAll(BossBar b) {
        for (UUID id : shown) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.showBossBar(b);
        }
    }

    private void hideFromAll(BossBar b) {
        for (UUID id : shown) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(b);
        }
    }

    private void clearHeraldBars() {
        for (BossBar b : heraldBars.values()) hideFromAll(b);
        heraldBars.clear();
    }

    private void setRitualBarVisible(boolean visible) {
        for (UUID id : shown) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (visible) p.showBossBar(ritualBar);
            else p.hideBossBar(ritualBar);
        }
    }

    private void ambient() {
        World w = entity.getWorld();
        Location c = entity.getLocation().add(0, 1.6, 0);
        w.spawnParticle(Particle.SOUL, c, 2, 0.6, 1.0, 0.6, 0.01);
        switch (phase) {
            case TWO -> w.spawnParticle(Particle.FLAME, c, 3, 0.6, 1.0, 0.6, 0.01);
            case THREE -> w.spawnParticle(Particle.REVERSE_PORTAL, c, 6, 0.6, 1.0, 0.6, 0.05);
            case FOUR -> {
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, c, 6, 0.7, 1.2, 0.7, 0.02);
                w.spawnParticle(Particle.LARGE_SMOKE, c, 2, 0.6, 1.0, 0.6, 0.01);
            }
            default -> { }
        }
        if (isShielded()) {
            w.spawnParticle(Particle.END_ROD, c, 8, 0.9, 1.3, 0.9, 0.02);
        }
        if (isImmune()) {
            w.spawnParticle(Particle.END_ROD, c, 10, 1.0, 1.4, 1.0, 0.03);
            w.spawnParticle(Particle.ENCHANT, c, 12, 1.0, 1.4, 1.0, 0.5);
        }
        if (isStunned()) {
            w.spawnParticle(Particle.CRIT, c.clone().add(0, 1, 0), 6, 0.5, 0.3, 0.5, 0.1);
        }
    }

    // ------------------------------------------------------------------ fases

    private Phase phaseFor(double fraction) {
        List<Double> th = plugin.getConfig().getDoubleList("vorthak.phase-thresholds");
        double t2 = th.size() > 0 ? th.get(0) : 0.70;
        double t3 = th.size() > 1 ? th.get(1) : 0.40;
        double t4 = th.size() > 2 ? th.get(2) : 0.15;
        if (fraction <= t4) return Phase.FOUR;
        if (fraction <= t3) return Phase.THREE;
        if (fraction <= t2) return Phase.TWO;
        return Phase.ONE;
    }

    public void checkPhase() {
        if (transitioning || !alive()) return;
        Phase target = phaseFor(entity.getHealth() / maxHealth());
        if (target.ordinal() > phase.ordinal()) {
            enterPhase(target);
        }
    }

    private void enterPhase(Phase next) {
        transitioning = true;
        entity.setAI(false);
        entity.setVelocity(new Vector(0, 0, 0));
        Location c = entity.getLocation();

        Title title = Title.title(
                Text.mm(next.title),
                Text.mm("<dark_gray>Vorthak se transforma...</dark_gray>"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800)));
        for (Player p : players(80)) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, 0.6f);
        }
        sound(c, Sound.ENTITY_WITHER_SPAWN, 2f, 0.6f);

        // Hélice ascendente de almas durante 3 segundos
        repeat(2, 30, i -> {
            Location base = entity.getLocation();
            World w = base.getWorld();
            double h = (i / 30.0) * 4.0;
            for (int k = 0; k < 3; k++) {
                double a = i * 0.45 + k * (Math.PI * 2 / 3);
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, base.getX() + Math.cos(a) * 1.6, base.getY() + h,
                        base.getZ() + Math.sin(a) * 1.6, 1, 0, 0, 0, 0);
                w.spawnParticle(Particle.SOUL, base.getX() + Math.cos(a) * 2.2, base.getY() + h * 0.6,
                        base.getZ() + Math.sin(a) * 2.2, 1, 0, 0, 0, 0.02);
            }
            if (i % 6 == 0) sound(base, Sound.ENTITY_WITHER_AMBIENT, 1.5f, 0.5f + i * 0.02f);
        }, () -> {
            phase = next;
            entity.getPersistentDataContainer().set(manager.phaseKey, PersistentDataType.INTEGER, next.ordinal());
            applyStats();
            swapModel();

            Location loc = entity.getLocation();
            World w = loc.getWorld();
            w.spawnParticle(Particle.EXPLOSION_EMITTER, loc.clone().add(0, 1, 0), 2, 0.5, 0.5, 0.5, 0);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(0, 1, 0), 120, 1.5, 1.5, 1.5, 0.15);
            sound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.5f);
            for (Player p : players(12)) {
                knock(p, loc, 1.6, 0.6);
            }
            entity.setAI(true);
            transitioning = false;
            busyUntil = now + 30;
            for (Ability a : Ability.values()) {
                cooldowns.put(a, now + 40);
            }

            if (next == Phase.THREE && ritualState == 0) {
                startRitual();
            } else {
                if (ritualState == 1 || ritualState == 2) {
                    // por si acaso: si saltó de fase con el ritual abierto, se cierra
                    killHeralds();
                    setRitualBarVisible(false);
                    ritualState = 3;
                    persistRitual();
                }
                summon(true);
            }
        });
    }


    // ------------------------------------------------------------------ música de fondo por fase

    /*
     * Música generada con bloques de notas (sin resource pack). Cada fase tiene un patrón de 16 pasos; cada paso es una lista
     * "letra:tono" separada por comas. B=bajo, D=bombo, S=caja, H=hi-hat, L=campana, P=pling, C=carillón, G=didgeridoo.
     * Suena en la categoría MÚSICA, así que cada jugador puede bajarla/quitarla con su slider de música.
     */
    private static final String[][] SONGS = {
            // I · Centinela de Ceniza: pulso grave y campanas lejanas
            {"B:0.5,D:0.7", "", "", "", "L:1.5", "", "", "", "B:0.595,D:0.7", "", "", "", "L:1.19", "", "G:0.5", ""},
            // II · Furia Infernal: ritmo marcial y riff de bajo
            {"D:1,B:0.5", "", "B:0.5", "H:2", "S:1,B:0.595", "", "B:0.5", "H:2", "D:1,B:0.667", "", "D:1,B:0.595", "H:2", "S:1,B:0.5", "H:2", "B:0.707", "D:1"},
            // III · Juicio de los Heraldos: arpegio etéreo sobre un zumbido
            {"G:0.5,C:1.5", "P:1.19", "C:1.78", "P:1.5", "C:2", "P:1.78", "G:0.56,C:1.5", "P:1.26", "C:1.89", "P:1.59", "C:2", "P:1.89", "G:0.5,C:1.33", "P:1.12", "C:1.68", "P:1.33"},
            // IV · Último Aliento: latido acelerado y alarma
            {"D:0.8,B:0.5", "P:2", "D:0.8", "P:2", "S:1,B:0.595", "P:2", "D:0.8,B:0.5", "P:2", "D:0.8,B:0.667", "P:2", "D:0.8", "P:2", "S:1,B:0.707", "P:2", "D:0.8,B:0.595", "P:1.78"},
    };
    private static final int[] SONG_STEP = {8, 6, 6, 4};   // ticks por paso (más rápido en cada fase)
    private int musicStep;
    private long nextMusic;

    private static Sound instrument(char c) {
        return switch (c) {
            case 'B' -> Sound.BLOCK_NOTE_BLOCK_BASS;
            case 'D' -> Sound.BLOCK_NOTE_BLOCK_BASEDRUM;
            case 'S' -> Sound.BLOCK_NOTE_BLOCK_SNARE;
            case 'H' -> Sound.BLOCK_NOTE_BLOCK_HAT;
            case 'L' -> Sound.BLOCK_NOTE_BLOCK_BELL;
            case 'P' -> Sound.BLOCK_NOTE_BLOCK_PLING;
            case 'C' -> Sound.BLOCK_NOTE_BLOCK_CHIME;
            case 'G' -> Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO;
            default -> null;
        };
    }

    private void musicTick() {
        if (!plugin.getConfig().getBoolean("vorthak.music", true) || dead || now < nextMusic) return;
        int ph = phase.ordinal();
        nextMusic = now + SONG_STEP[ph];
        String step = SONGS[ph][musicStep++ % 16];
        if (step.isEmpty()) return;
        float vol = ph == 3 ? 0.9f : 0.7f;
        for (Player p : players(60)) {
            for (String note : step.split(",")) {
                Sound s = instrument(note.charAt(0));
                if (s == null) continue;
                p.playSound(p.getLocation(), s, SoundCategory.MUSIC, vol, Float.parseFloat(note.substring(2)));
            }
        }
    }

    // ------------------------------------------------------------------ cámara de la entrada (cinemática)

    private record Cam(Location start, boolean allowFlight, boolean flying) {
    }

    private final Map<UUID, Cam> cams = new HashMap<>();

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double ease(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /**
     * Cámara de cine de 4 s sin cambiar el modo de juego (no choca con el sistema de muertes): los jugadores vuelan
     * por una trayectoria (acercamiento, órbita en contrapicado y empuje final) mirando siempre a la cabeza de Vorthak.
     * El zoom se logra con el FOV que produce la lentitud, que va subiendo; durante el rugido la cámara tiembla.
     * Al terminar cada jugador vuelve exactamente a donde estaba.
     */
    private void cinematicStart() {
        cams.clear();
        for (Player p : players(80)) {
            cams.put(p.getUniqueId(), new Cam(p.getLocation().clone(), p.getAllowFlight(), p.isFlying()));
            p.setAllowFlight(true);
            p.setFlying(true);
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 120, 4, false, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0, false, false, false));
        }
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= 80 || cams.isEmpty() || !entity.isValid()) {
                    cinematicEnd();
                    cancel();
                    return;
                }
                Location c = entity.getLocation();
                Location head = entity.getEyeLocation();
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (Map.Entry<UUID, Cam> en : cams.entrySet()) {
                    Player p = Bukkit.getPlayer(en.getKey());
                    Location s = en.getValue().start();
                    if (p == null || !p.isOnline() || s.getWorld() == null || !s.getWorld().equals(c.getWorld())) continue;
                    double a0 = Math.atan2(s.getZ() - c.getZ(), s.getX() - c.getX());
                    double r0 = Math.max(7, Math.min(24, Math.hypot(s.getX() - c.getX(), s.getZ() - c.getZ())));
                    double a = a0, r, y;
                    if (t < 30) {                       // 1) acercamiento
                        double e = ease(t / 30.0);
                        r = lerp(r0, 9, e);
                        y = lerp(s.getY(), c.getY() + 1.2, e);
                    } else if (t < 60) {                // 2) órbita en contrapicado (la cámara sube mirando hacia arriba)
                        double u = ease((t - 30) / 30.0);
                        a = a0 + u * Math.PI / 2;
                        r = lerp(9, 6.5, u);
                        y = c.getY() + 1.2 + u * 1.8;
                    } else {                            // 3) empuje final durante el rugido
                        double u = ease((t - 60) / 20.0);
                        a = a0 + Math.PI / 2;
                        r = lerp(6.5, 5.2, u);
                        y = c.getY() + 3.0;
                    }
                    Location pos = new Location(c.getWorld(), c.getX() + Math.cos(a) * r, y, c.getZ() + Math.sin(a) * r);
                    Vector d = head.toVector().subtract(pos.toVector().add(new Vector(0, 1.62, 0)));
                    float yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
                    float pitch = (float) -Math.toDegrees(Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ())));
                    if (t >= 44 && t <= 68) {           // temblor del rugido
                        yaw += (float) rnd.nextDouble(-1.5, 1.5);
                        pitch += (float) rnd.nextDouble(-1.0, 1.0);
                    }
                    pos.setYaw(yaw);
                    pos.setPitch(Math.max(-85f, Math.min(85f, pitch)));
                    p.teleport(pos);
                    p.setFallDistance(0f);
                    if (t % 4 == 0) {                   // zoom progresivo (FOV): sube la lentitud sin llegar a bloquear la vista
                        int amp = t < 30 ? -1 : (t < 60 ? (t - 30) / 8 : 4 + (t - 60) / 10);
                        if (amp >= 0) {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 8, Math.min(amp, 5), false, false, false));
                        }
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void cinematicEnd() {
        for (Map.Entry<UUID, Cam> en : cams.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !p.isOnline()) continue;
            Cam cam = en.getValue();
            p.removePotionEffect(PotionEffectType.SLOWNESS);
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
            p.clearTitle();   // quita las franjas de cine
            p.setFlying(cam.flying());
            p.setAllowFlight(cam.allowFlight());
            if (cam.start().getWorld() != null) p.teleport(cam.start());
            p.setFallDistance(0f);
        }
        cams.clear();
    }

    private void intro() {
        transitioning = true;
        entity.setAI(false);
        Location c = entity.getLocation();
        MobVisuals pack = plugin.visuals();
        boolean bars = plugin.getConfig().getBoolean("vorthak.letterbox", true) && pack != null && pack.enabled();
        Title title = bars
                // franjas de cine (glifo del resource pack) en el título y el nombre en el subtítulo, dentro del encuadre
                ? Title.title(Component.text("\uE000", NamedTextColor.WHITE).font(net.kyori.adventure.key.Key.key("custommobs", "cinema")),
                        Text.mm("<gradient:#ff4d4d:#ffb347><bold>VORTHAK</bold></gradient> <dark_gray>·</dark_gray> <gray>El Centinela de Ceniza despierta</gray>"),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3300), Duration.ofMillis(400)))
                : Title.title(
                        Text.mm("<gradient:#ff4d4d:#ffb347><bold>VORTHAK</bold></gradient>"),
                        Text.mm("<gray>El Centinela de Ceniza despierta</gray>"),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(800)));
        for (Player p : players(80)) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 0.7f);
        }
        c.getWorld().strikeLightningEffect(c);
        cinematicStart();
        repeat(2, 40, i -> {
            Location base = entity.getLocation();
            ring(base, 1.0 + i * 0.15, 24, Particle.SOUL_FIRE_FLAME);
            base.getWorld().spawnParticle(Particle.SOUL, base.clone().add(0, 1, 0), 4, 0.6, 1.0, 0.6, 0.03);
            if (i == 4) sound(base, Sound.BLOCK_BEACON_ACTIVATE, 1.6f, 0.5f);
            if (i == 20) {
                sound(base, Sound.ENTITY_RAVAGER_ROAR, 2f, 0.5f);
                sound(base, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.6f, 0.6f);
                base.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, base.clone().add(0, 1, 0), 2, 1, 0.5, 1, 0);
            }
            if (i >= 22 && i <= 34 && i % 3 == 0) sound(base, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 0.5f);
        }, () -> {
            cinematicEnd();
            entity.setAI(true);
            transitioning = false;
            busyUntil = now + 40;
            for (Ability a : Ability.values()) {
                cooldowns.put(a, now + 60);
            }
        });
    }

    // ------------------------------------------------------------------ ritual de los Heraldos (Fase III)

    private void startRitual() {
        if (!alive()) return;
        killHeralds();
        heralds.clear();
        heraldNext.clear();

        ritualState = 1;
        ritualTotal = plugin.getConfig().getInt("ritual.time-limit-seconds", 60) * 20L;
        ritualEnd = now + ritualTotal;
        persistRitual();
        setRitualBarVisible(true);
        entity.setAI(false);
        entity.setTarget(null);
        entity.setVelocity(new Vector(0, 0, 0));

        double hp = plugin.getConfig().getDouble("ritual.herald-health", 250.0);
        Location c = entity.getLocation();
        for (int i = 0; i < 2; i++) {
            double a = Math.PI / 2 + Math.PI * i;
            Location spot = c.clone().add(Math.cos(a) * 6, 0, Math.sin(a) * 6);
            if (!spot.getBlock().isPassable() || !spot.clone().add(0, 1, 0).getBlock().isPassable()) {
                spot = c.clone();
            }
            spawnHerald(spot, i, hp);
        }

        sound(c, Sound.ENTITY_WITHER_SPAWN, 2f, 0.5f);
        sound(c, Sound.BLOCK_BEACON_ACTIVATE, 2f, 0.5f);
        titleAll(80, "<light_purple><bold>RITUAL DE LOS HERALDOS</bold></light_purple>",
                "<gray>Vorthak está inmóvil e inmune. Mata a ambos en <red>" + (ritualTotal / 20) + "s</red> o ejecutará su Sentencia</gray>", 4);
        for (Player p : players(80)) {
            p.sendMessage(Text.mm("<dark_gray>[</dark_gray><light_purple>Vorthak</light_purple><dark_gray>]</dark_gray> "
                    + "<gray>Si el tiempo se agota, la Sentencia es <red>daño letal</red>. Solo un <gold>Tótem de la Inmortalidad</gold> te salvará.</gray>"));
        }
    }

    private void spawnHerald(Location spot, int kind, double hp) {
        World w = spot.getWorld();
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, spot.clone().add(0, 1, 0), 60, 0.5, 1.0, 0.5, 0.08);
        WitherSkeleton h = w.spawn(spot, WitherSkeleton.class, e -> {
            e.customName(Text.mm(kind == 0
                    ? "<gradient:#ff8c00:#ff3d00><bold>Heraldo de Brasas</bold></gradient>"
                    : "<gradient:#b388ff:#6a1b9a><bold>Heraldo del Vacío</bold></gradient>"));
            e.setCustomNameVisible(true);
            e.setGlowing(true);
            e.setPersistent(false);
            e.setRemoveWhenFarAway(false);
            e.setCanPickupItems(false);
            e.getPersistentDataContainer().set(manager.heraldKey, PersistentDataType.STRING,
                    entity.getUniqueId().toString());
            setAttr(e, Attribute.MAX_HEALTH, hp);
            e.setHealth(hp);
            setAttr(e, Attribute.ATTACK_DAMAGE, 14.0);
            setAttr(e, Attribute.MOVEMENT_SPEED, 0.30);
            setAttr(e, Attribute.KNOCKBACK_RESISTANCE, 0.8);
            setAttr(e, Attribute.FOLLOW_RANGE, 40.0);
            setAttr(e, Attribute.SCALE, 1.35);
            EntityEquipment eq = e.getEquipment();
            eq.setItemInMainHand(new ItemStack(kind == 0 ? Material.NETHERITE_SWORD : Material.DIAMOND_SWORD));
            eq.setHelmet(new ItemStack(kind == 0 ? Material.NETHERITE_HELMET : Material.DIAMOND_HELMET));
            eq.setChestplate(new ItemStack(kind == 0 ? Material.NETHERITE_CHESTPLATE : Material.DIAMOND_CHESTPLATE));
            eq.setItemInMainHandDropChance(0f);
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setBootsDropChance(0f);
        });
        heralds.put(h.getUniqueId(), kind);
        heraldNext.put(h.getUniqueId(), now + 60);
        Player t = nearest(spot, players(48));
        if (t != null) h.setTarget(t);
    }

    private void ritualTick(List<Player> near) {
        heralds.keySet().removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || !e.isValid();
        });
        if (heralds.isEmpty()) {
            endRitual();
            return;
        }
        long left = ritualEnd - now;
        if (left <= 0) {
            judgement();
            return;
        }
        // cuenta atrás sonora los últimos 10 segundos
        if (left <= 200 && now % 20 == 0) {
            float pitch = 0.8f + (200 - left) / 200f * 1.2f;
            for (Player p : players(80)) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f, pitch);
            }
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Map.Entry<UUID, Integer> en : heralds.entrySet()) {
            Entity ent = Bukkit.getEntity(en.getKey());
            if (!(ent instanceof WitherSkeleton h)) continue;
            LivingEntity tg = h.getTarget();
            if (tg == null || !tg.isValid() || tg.isDead()) {
                Player p = nearest(h.getLocation(), near);
                if (p != null) h.setTarget(p);
            }
            long next = heraldNext.getOrDefault(en.getKey(), now + 60);
            if (now < next) continue;
            if (en.getValue() == 0) {
                heraldNext.put(en.getKey(), now + 110 + rnd.nextInt(30));
                heraldSlam(h);
            } else {
                heraldNext.put(en.getKey(), now + 90 + rnd.nextInt(30));
                heraldVolley(h, near);
            }
        }
    }

    /** Heraldo de Brasas: pisotón de fuego con telegrafía. */
    private void heraldSlam(WitherSkeleton h) {
        sound(h.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.2f, 0.9f);
        repeat(2, 10, i -> {
            if (h.isValid()) ring(h.getLocation(), 4.5, 26, Particle.FLAME);
        }, () -> {
            if (!h.isValid()) return;
            Location loc = h.getLocation();
            World w = loc.getWorld();
            w.spawnParticle(Particle.EXPLOSION, loc, 3, 1.5, 0.2, 1.5, 0);
            w.spawnParticle(Particle.FLAME, loc, 50, 2, 0.2, 2, 0.08);
            sound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.9f);
            for (Player p : playersAt(loc, 5)) {
                if (Math.abs(p.getLocation().getY() - loc.getY()) < 3) {
                    p.damage(8 * phase.dmg, h);
                    knock(p, loc, 0.9, 0.6);
                    p.setFireTicks(80);
                }
            }
        });
    }

    /** Heraldo del Vacío: ráfaga de 3 calaveras dirigidas. */
    private void heraldVolley(WitherSkeleton h, List<Player> near) {
        Player t = nearest(h.getLocation(), near);
        if (t == null) return;
        sound(h.getLocation(), Sound.ENTITY_WITHER_SHOOT, 1.2f, 1.2f);
        repeat(6, 3, v -> {
            if (!h.isValid() || !t.isOnline() || t.isDead()) return;
            Location eye = h.getEyeLocation();
            Vector dir = t.getEyeLocation().toVector().subtract(eye.toVector()).normalize();
            h.getWorld().spawn(eye.clone().add(dir.clone().multiply(1.5)), WitherSkull.class, sk -> {
                sk.setShooter(h);
                sk.setDirection(dir);
                sk.setYield(0f);
                sk.setIsIncendiary(false);
                sk.setCharged(false);
            });
        }, null);
    }

    /** Ambos Heraldos han caído: el jefe queda aturdido y vulnerable. */
    private void endRitual() {
        ritualState = 3;
        persistRitual();
        setRitualBarVisible(false);
        clearHeraldBars();
        heralds.clear();

        Location c = entity.getLocation();
        World w = c.getWorld();
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c.clone().add(0, 1, 0), 2, 0.5, 0.5, 0.5, 0);
        w.spawnParticle(Particle.END_ROD, c.clone().add(0, 1.5, 0), 80, 1.2, 1.5, 1.2, 0.2);
        sound(c, Sound.ENTITY_WITHER_BREAK_BLOCK, 2f, 0.6f);
        sound(c, Sound.BLOCK_BEACON_DEACTIVATE, 2f, 0.6f);

        entity.setAI(false);
        stunUntil = now + 100;
        busyUntil = now + 120;
        later(100, () -> entity.setAI(true));
        titleAll(80, "<green><bold>HERALDOS DERROTADOS</bold></green>",
                "<yellow>Vorthak está aturdido: ¡ataca! (+50% de daño)</yellow>", 3);
        for (Player p : players(80)) {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
        }
    }

    /** Se acabó el tiempo: carga 4 segundos y ejecuta la Sentencia (daño letal, solo lo salva un tótem). */
    private void judgement() {
        ritualState = 2;
        setRitualBarVisible(false);
        clearHeraldBars();
        entity.setAI(false);
        busyUntil = now + 200;
        Location c = entity.getLocation();
        sound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.4f);
        titleAll(80, "<dark_red><bold>SENTENCIA</bold></dark_red>",
                "<red>Equipa un Tótem de la Inmortalidad... ahora.</red>", 3);

        repeat(2, 40, i -> {
            Location base = entity.getLocation();
            ring(base, 1.0 + (i % 20) * 0.4, 30, Particle.SOUL_FIRE_FLAME);
            for (Player p : players(80)) {
                p.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, p.getLocation().add(0, 1, 0), 6, 0.4, 0.8, 0.4, 0.02);
                if (i % 10 == 0) p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.6f, 1f);
            }
        }, () -> {
            killHeralds();
            heralds.clear();
            sound(entity.getLocation(), Sound.ENTITY_WITHER_DEATH, 2f, 0.5f);
            for (Player p : players(80)) {
                p.getWorld().strikeLightningEffect(p.getLocation());
                p.damage(1000.0, entity); // letal: el tótem lo cancela
            }
            double max = maxHealth();
            double heal = max * plugin.getConfig().getDouble("ritual.fail-heal-percent", 0.10);
            entity.setHealth(Math.min(max, entity.getHealth() + heal));
            ritualState = 3;
            persistRitual();
            entity.setAI(true);
            busyUntil = now + 60;
        });
    }

    private void killHeralds() {
        clearHeraldBars();
        for (Entity en : entity.getNearbyEntities(70, 70, 70)) {
            if (en instanceof WitherSkeleton ws && entity.getUniqueId().equals(manager.heraldOwnerId(ws))) {
                ws.getWorld().spawnParticle(Particle.SOUL, ws.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.05);
                ws.remove();
            }
        }
        for (UUID id : heralds.keySet()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
    }

    // ------------------------------------------------------------------ habilidades

    private void castNext(List<Player> near) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Ability> pool = new ArrayList<>();
        for (Ability a : Ability.values()) {
            if (phase.ordinal() + 1 < a.minPhase || now < cooldowns.getOrDefault(a, 0L)) continue;
            // durante el ritual el jefe solo usa ataques a distancia/área para presionar
            if (ritualState == 1 && !(a == Ability.SLAM || a == Ability.SKULLS
                    || a == Ability.LIGHTNING || a == Ability.NOVA)) continue;
            pool.add(a);
        }
        Collections.shuffle(pool);
        for (Ability first : pool) {
            if (!cast(first, near)) continue;
            cooldowns.put(first, now + (long) (first.cd * phase.cd));

            // Fase IV: combo con un segundo ataque a distancia
            if (phase == Phase.FOUR && rnd.nextDouble() < 0.35) {
                for (Ability second : pool) {
                    if (second == first) continue;
                    if (!(second == Ability.SKULLS || second == Ability.LIGHTNING || second == Ability.METEORS)) continue;
                    long keep = busyUntil;
                    if (cast(second, near)) {
                        cooldowns.put(second, now + (long) (second.cd * phase.cd));
                        busyUntil = Math.max(keep, busyUntil);
                        break;
                    }
                }
            }
            return;
        }
    }

    private boolean cast(Ability a, List<Player> near) {
        boolean ok = castAbility(a, near);
        if (ok) swing();
        return ok;
    }

    private boolean castAbility(Ability a, List<Player> near) {
        return switch (a) {
            case SLAM -> slam();
            case SUMMON -> summon(false);
            case NOVA -> nova();
            case SKULLS -> skulls();
            case LEAP -> leap();
            case BLINK -> blink();
            case LIGHTNING -> lightning(near);
            case PULL -> pull(near);
            case AEGIS -> aegis();
            case STORM -> storm(near);
            case METEORS -> meteors(near);
            case SCYTHE -> scythe();
            case ERUPTION -> eruption(near);
            case FIREWALL -> firewall(near);
            case RIFTS -> rifts(near);
            case SENTENCE -> sentence(near);
        };
    }

    private double distTo(LivingEntity t) {
        return t.getLocation().distance(entity.getLocation());
    }


    // ------------------------------------------------------------------ habilidades nuevas

    /** Erupciones de Ceniza (I+): marca el suelo bajo hasta 3 jugadores; tras ~1,2 s el suelo estalla. Hay que moverse. */
    private boolean eruption(List<Player> near) {
        List<Player> targets = new ArrayList<>(near);
        if (targets.isEmpty()) return false;
        Collections.shuffle(targets);
        if (targets.size() > 3) targets = new ArrayList<>(targets.subList(0, 3));
        busyUntil = now + 30;
        sound(entity.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.6f, 0.5f);
        for (Player t : targets) {
            Location spot = t.getLocation().clone();
            spot.setY(Math.floor(spot.getY()));
            repeat(2, 12, i -> {
                ring(spot, 2.4, 20, Particle.FLAME);
                ring(spot, 2.4 * (1 - i / 12.0), 12, Particle.SOUL_FIRE_FLAME);
                if (i % 4 == 0) sound(spot, Sound.BLOCK_FIRE_AMBIENT, 1f, 0.6f + i * 0.05f);
            }, () -> {
                World w = spot.getWorld();
                w.spawnParticle(Particle.LAVA, spot.clone().add(0, 0.5, 0), 40, 1.2, 0.3, 1.2, 0);
                w.spawnParticle(Particle.EXPLOSION, spot.clone().add(0, 0.5, 0), 2, 0.8, 0.2, 0.8, 0);
                w.spawnParticle(Particle.FLAME, spot.clone().add(0, 0.3, 0), 80, 1.2, 1.6, 1.2, 0.12);
                sound(spot, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.8f);
                sound(spot, Sound.ITEM_FIRECHARGE_USE, 1.2f, 0.7f);
                for (Player p : playersAt(spot, 2.6)) {
                    hurt(p, 9);
                    p.setVelocity(new Vector(0, 0.85, 0));
                    p.setFireTicks(Math.max(p.getFireTicks(), 40));
                }
            });
        }
        return true;
    }

    /** Muralla Ardiente (II+): una línea de fuego barre la sala. Se esquiva SALTANDO (no daña en el aire). */
    private boolean firewall(List<Player> near) {
        Player t = nearest(entity.getLocation(), near);
        if (t == null) return false;
        Location o = entity.getLocation().clone();
        Vector dir = t.getLocation().toVector().subtract(o.toVector()).setY(0);
        if (dir.lengthSquared() < 1.0E-4) return false;
        dir.normalize();
        Vector side = new Vector(-dir.getZ(), 0, dir.getX());
        busyUntil = now + 50;
        entity.setAI(false);
        sound(o, Sound.ENTITY_RAVAGER_ROAR, 1.6f, 0.5f);
        // telegrafía: línea de humo/llama frente al jefe
        repeat(2, 8, i -> {
            for (int k = -9; k <= 9; k++) {
                Location l = o.clone().add(dir.clone().multiply(3)).add(side.clone().multiply(k));
                l.getWorld().spawnParticle(Particle.SMALL_FLAME, l.add(0, 0.2, 0), 1, 0.1, 0.05, 0.1, 0);
            }
        }, () -> {
            entity.setAI(true);
            Set<UUID> hit = new HashSet<>();
            sound(o, Sound.ENTITY_BLAZE_SHOOT, 2f, 0.4f);
            repeat(1, 26, i -> {
                double d = 3 + i * 0.9;
                World w = o.getWorld();
                for (int k = -9; k <= 9; k++) {
                    Location l = o.clone().add(dir.clone().multiply(d)).add(side.clone().multiply(k)).add(0, 0.2, 0);
                    w.spawnParticle(Particle.FLAME, l, 2, 0.1, 0.5, 0.1, 0.01);
                    if (k % 3 == 0) w.spawnParticle(Particle.LAVA, l, 1, 0.1, 0.1, 0.1, 0);
                }
                if (i % 5 == 0) sound(o.clone().add(dir.clone().multiply(d)), Sound.BLOCK_FIRE_AMBIENT, 1.4f, 0.5f);
                for (Player p : playersAt(o, 40)) {
                    if (hit.contains(p.getUniqueId())) continue;
                    Vector rel = p.getLocation().toVector().subtract(o.toVector());
                    double along = rel.dot(dir);
                    double across = Math.abs(rel.dot(side));
                    boolean airborne = p.getLocation().getY() - o.getY() > 0.9;
                    if (Math.abs(along - d) < 1.4 && across < 9.5 && !airborne) {
                        hit.add(p.getUniqueId());
                        hurt(p, 8);
                        p.setVelocity(dir.clone().multiply(0.8).setY(0.45));
                        p.setFireTicks(Math.max(p.getFireTicks(), 60));
                        sound(p.getLocation(), Sound.ENTITY_PLAYER_HURT_ON_FIRE, 1f, 1f);
                    }
                }
            }, null);
        });
        return true;
    }

    /** Grietas del Vacío (III+): zonas que ralentizan y dañan durante 6 s. Obligan a no quedarse quieto. */
    private boolean rifts(List<Player> near) {
        if (near.isEmpty()) return false;
        busyUntil = now + 30;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        sound(entity.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 1.2f, 1.4f);
        List<Location> spots = new ArrayList<>();
        for (int i = 0; i < Math.min(3, 1 + near.size()); i++) {
            Player t = near.get(rnd.nextInt(near.size()));
            Location l = t.getLocation().clone().add(rnd.nextDouble(-3, 3), 0, rnd.nextDouble(-3, 3));
            l.setY(Math.floor(t.getLocation().getY()));
            spots.add(l);
        }
        repeat(5, 24, i -> {
            for (Location s : spots) {
                World w = s.getWorld();
                ring(s, 3.0, 24, Particle.REVERSE_PORTAL);
                ring(s, 1.5, 12, Particle.PORTAL);
                w.spawnParticle(Particle.SQUID_INK, s.clone().add(0, 0.2, 0), 3, 1.2, 0.1, 1.2, 0.01);
                if (i % 4 == 0) {
                    sound(s, Sound.BLOCK_PORTAL_AMBIENT, 0.8f, 1.3f);
                    for (Player p : playersAt(s, 3.1)) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, true));
                        p.damage(2.5 * phase.dmg, entity);
                    }
                }
            }
        }, null);
        return true;
    }

    /** Sentencia (IV): marca a un jugador; a los ~2 s explota donde estaba. Quien no esté cerca de él se salva. */
    private boolean sentence(List<Player> near) {
        if (near.isEmpty()) return false;
        Player t = near.get(ThreadLocalRandom.current().nextInt(near.size()));
        busyUntil = now + 20;
        t.showTitle(Title.title(Text.mm("<dark_red><bold>SENTENCIADO</bold></dark_red>"),
                Text.mm("<gray>¡Aléjate de tus compañeros!</gray>"),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1300), Duration.ofMillis(300))));
        t.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 50, 0, false, false));
        sound(t.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.7f, 1.8f);
        Location[] fix = {t.getLocation().clone()};
        repeat(2, 26, i -> {
            if (i < 18 && t.isOnline() && !t.isDead()) fix[0] = t.getLocation().clone();   // sigue al objetivo y se fija en los últimos ~0,8 s
            double r = 4.0;
            ring(fix[0], r, 28, i < 18 ? Particle.SOUL_FIRE_FLAME : Particle.FLAME);
            ring(fix[0], r * (1 - i / 26.0), 16, Particle.END_ROD);
            if (i % 4 == 0) sound(fix[0], Sound.BLOCK_NOTE_BLOCK_BELL, 1.4f, 0.5f + i * 0.04f);
        }, () -> {
            Location c = fix[0];
            World w = c.getWorld();
            w.spawnParticle(Particle.EXPLOSION_EMITTER, c.clone().add(0, 1, 0), 2, 0.6, 0.4, 0.6, 0);
            w.spawnParticle(Particle.END_ROD, c.clone().add(0, 1, 0), 120, 2, 1.5, 2, 0.25);
            sound(c, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.6f);
            sound(c, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.4f, 1.2f);
            for (Player p : playersAt(c, 4.2)) {
                hurt(p, near.size() <= 1 ? 9 : 14);   // en solitario no hay forma de repartirse: menos daño
                knock(p, c, 1.4, 0.7);
            }
        });
        return true;
    }

    /** Golpe de Ceniza: onda cercana con telegrafía de 1s. */
    private boolean slam() {
        LivingEntity t = entity.getTarget();
        if (t == null || distTo(t) > 8) return false;
        busyUntil = now + 40;
        entity.setAI(false);
        Location c = entity.getLocation();
        sound(c, Sound.ENTITY_RAVAGER_ROAR, 1.5f, 0.6f);
        repeat(2, 10, i -> ring(entity.getLocation(), 5, 30, Particle.SOUL_FIRE_FLAME), () -> {
            Location loc = entity.getLocation();
            World w = loc.getWorld();
            w.spawnParticle(Particle.EXPLOSION, loc, 4, 1.8, 0.2, 1.8, 0);
            w.spawnParticle(Particle.SOUL, loc, 60, 2.5, 0.3, 2.5, 0.06);
            sound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.7f);
            for (Player p : players(5.5)) {
                if (Math.abs(p.getLocation().getY() - loc.getY()) < 3) {
                    hurt(p, 10);
                    knock(p, loc, 0.9, 0.7);
                    wither(p, 60);
                }
            }
            entity.setAI(true);
        });
        return true;
    }

    /** Almas Cautivas: invoca súbditos. */
    private boolean summon(boolean forced) {
        minions.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || !e.isValid();
        });
        int cap = plugin.getConfig().getInt("vorthak.minion-cap", 6);
        if (!forced && minions.size() >= cap) return false;
        if (!forced) busyUntil = now + 30;

        int count = Math.min(2 + phase.ordinal(), Math.max(0, cap - minions.size()));
        if (forced) count = 2 + phase.ordinal();
        Location c = entity.getLocation();
        World w = c.getWorld();
        sound(c, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.5f, 0.6f);
        for (int i = 0; i < count; i++) {
            double a = (Math.PI * 2 * i) / Math.max(1, count);
            Location spot = c.clone().add(Math.cos(a) * 3.5, 0, Math.sin(a) * 3.5);
            spot.setY(c.getY());
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, spot, 25, 0.3, 0.6, 0.3, 0.05);
            WitherSkeleton m = w.spawn(spot, WitherSkeleton.class, e -> {
                e.customName(Text.mm("<gray>Alma Cautiva</gray>"));
                e.setCustomNameVisible(true);
                e.setRemoveWhenFarAway(true);
                e.getPersistentDataContainer().set(manager.minionKey, PersistentDataType.STRING,
                        entity.getUniqueId().toString());
                setAttr(e, Attribute.MAX_HEALTH, 50.0);
                e.setHealth(50.0);
                setAttr(e, Attribute.ATTACK_DAMAGE, 8.0);
                EntityEquipment eq = e.getEquipment();
                eq.setItemInMainHandDropChance(0f);
                eq.setHelmetDropChance(0f);
                eq.setChestplateDropChance(0f);
                eq.setLeggingsDropChance(0f);
                eq.setBootsDropChance(0f);
            });
            MobVisuals vis = plugin.visuals();
            if (vis != null && vis.has("espectro")) {
                // las Almas Cautivas se ven como Espectros (silenciosas, sin espada visible)
                m.setSilent(true);
                EntityEquipment meq = m.getEquipment();
                if (meq != null) meq.setItemInMainHand(vis.invisibleItem(Material.STONE_SWORD));
                vis.attach(m, "espectro", false);
            }
            LivingEntity t = entity.getTarget();
            if (t != null) m.setTarget(t);
            minions.add(m.getUniqueId());
        }
        return true;
    }

    /** Onda de Almas: anillo expansivo que se esquiva saltando. */
    private boolean nova() {
        busyUntil = now + 60;
        Location c = entity.getLocation();
        sound(c, Sound.ENTITY_WITHER_SHOOT, 2f, 0.5f);
        entity.setAI(false);
        Set<UUID> hit = new HashSet<>();
        repeat(2, 7, i -> ring(entity.getLocation(), 1.5, 16, Particle.SOUL_FIRE_FLAME), () -> {
            Location origin = entity.getLocation();
            sound(origin, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, 1.4f);
            entity.setAI(true);
            repeat(2, 14, i -> {
                double r = 1.5 + i * 1.1;
                ring(origin, r, (int) (r * 8), Particle.SOUL_FIRE_FLAME);
                ring(origin, r, (int) (r * 4), Particle.SOUL);
                for (Player p : players(r + 3)) {
                    Location pl = p.getLocation();
                    double dx = pl.getX() - origin.getX();
                    double dz = pl.getZ() - origin.getZ();
                    double d = Math.sqrt(dx * dx + dz * dz);
                    boolean onGround = pl.getY() - origin.getY() < 1.0;
                    if (Math.abs(d - r) <= 1.6 && onGround && hit.add(p.getUniqueId())) {
                        hurt(p, 9);
                        knock(p, origin, 0.8, 0.5);
                        wither(p, 60);
                    }
                }
            }, null);
        });
        return true;
    }

    /** Cráneos Marchitos: abanico de calaveras. */
    private boolean skulls() {
        LivingEntity t = entity.getTarget();
        if (t == null || distTo(t) > 40) return false;
        int volleys = phase.ordinal() >= 2 ? 3 : 1;
        busyUntil = now + 14 + volleys * 8;
        sound(entity.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 2f, 0.8f);
        repeat(8, volleys, v -> {
            if (!t.isValid()) return;
            Location eye = entity.getEyeLocation();
            Vector base = t.getEyeLocation().toVector().subtract(eye.toVector()).normalize();
            for (int i = -2; i <= 2; i++) {
                Vector dir = base.clone().rotateAroundY(Math.toRadians(i * 11));
                boolean charged = i == 0;
                entity.getWorld().spawn(eye.clone().add(dir.clone().multiply(1.8)), WitherSkull.class, sk -> {
                    sk.setShooter(entity);
                    sk.setDirection(dir);
                    sk.setYield(0f);
                    sk.setIsIncendiary(false);
                    sk.setCharged(charged);
                });
            }
            sound(eye, Sound.ENTITY_WITHER_SHOOT, 1.5f, 1.0f);
        }, null);
        return true;
    }

    /** Salto Infernal: se abalanza sobre el objetivo y aplasta el suelo. */
    private boolean leap() {
        LivingEntity t = entity.getTarget();
        if (t == null) return false;
        double d = distTo(t);
        if (d < 6 || d > 26) return false;
        busyUntil = now + 60;
        entity.setAI(false);
        Location c = entity.getLocation();
        sound(c, Sound.ENTITY_RAVAGER_ROAR, 1.5f, 0.8f);
        c.getWorld().spawnParticle(Particle.FLAME, c, 40, 1, 0.1, 1, 0.08);
        later(12, () -> {
            entity.setAI(true);
            Vector v = t.getLocation().toVector().subtract(entity.getLocation().toVector()).setY(0);
            double len = Math.max(0.01, v.length());
            v.normalize().multiply(Math.min(len * 0.09 + 0.4, 2.0)).setY(0.85);
            entity.setVelocity(v);
            boolean[] landed = {false};
            repeat(2, 20, i -> {
                if (landed[0] || i < 3 || !entity.isOnGround()) return;
                landed[0] = true;
                Location loc = entity.getLocation();
                World w = loc.getWorld();
                w.spawnParticle(Particle.EXPLOSION, loc, 3, 1.5, 0.2, 1.5, 0);
                w.spawnParticle(Particle.FLAME, loc, 60, 2, 0.2, 2, 0.1);
                sound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.8f, 0.6f);
                for (Player p : players(4.5)) {
                    hurt(p, 9);
                    knock(p, loc, 0.9, 0.6);
                    p.setFireTicks(60);
                }
            }, null);
        });
        return true;
    }

    /** Parpadeo del Vacío: se teletransporta a la espalda del objetivo y lo corta. */
    private boolean blink() {
        LivingEntity t = entity.getTarget();
        if (!(t instanceof Player target) || distTo(t) > 32) return false;

        Vector back = target.getLocation().getDirection().setY(0);
        if (back.lengthSquared() < 1.0E-4) back = new Vector(0, 0, 1);
        back.normalize().multiply(-2.2);
        Location dest = target.getLocation().add(back);
        if (!dest.getBlock().isPassable() || !dest.clone().add(0, 1, 0).getBlock().isPassable()
                || !dest.clone().add(0, 2, 0).getBlock().isPassable()) {
            dest = target.getLocation().add(back.clone().multiply(-1));
            if (!dest.getBlock().isPassable() || !dest.clone().add(0, 1, 0).getBlock().isPassable()
                    || !dest.clone().add(0, 2, 0).getBlock().isPassable()) {
                return false;
            }
        }
        busyUntil = now + 30;
        Location from = entity.getLocation();
        World w = from.getWorld();
        w.spawnParticle(Particle.PORTAL, from.clone().add(0, 1.5, 0), 80, 0.8, 1.5, 0.8, 0.6);
        sound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.5f);

        dest.setDirection(target.getLocation().toVector().subtract(dest.toVector()));
        entity.teleport(dest);
        w.spawnParticle(Particle.PORTAL, dest.clone().add(0, 1.5, 0), 80, 0.8, 1.5, 0.8, 0.6);
        sound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.5f);

        later(8, () -> {
            if (!target.isOnline() || target.isDead()) return;
            if (target.getLocation().distanceSquared(entity.getLocation()) <= 16) {
                hurt(target, 8);
                wither(target, 80);
                sound(entity.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.6f);
                entity.getWorld().spawnParticle(Particle.SWEEP_ATTACK, target.getLocation().add(0, 1, 0),
                        3, 0.5, 0.3, 0.5, 0);
            }
        });
        return true;
    }

    /** Juicio del Rayo: marca el suelo y fulmina tras 1.5s. */
    private boolean lightning(List<Player> near) {
        if (near.isEmpty()) return false;
        int count = phase == Phase.FOUR ? 5 : 2;
        List<Player> pool = new ArrayList<>(near);
        Collections.shuffle(pool);
        busyUntil = now + 50;
        sound(entity.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.2f, 1.4f);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int k = 0; k < count; k++) {
            Player p = pool.get(k % pool.size());
            Location mark = p.getLocation().clone();
            if (k >= pool.size()) {
                mark.add(rnd.nextDouble(-5, 5), 0, rnd.nextDouble(-5, 5));
            }
            repeat(2, 14, i -> {
                ring(mark, 3, 24, Particle.ELECTRIC_SPARK);
                ring(mark, 3.0 * (i + 1) / 14.0, 12, Particle.SOUL_FIRE_FLAME);
            }, () -> {
                World w = mark.getWorld();
                w.strikeLightningEffect(mark);
                w.spawnParticle(Particle.EXPLOSION, mark, 2, 1, 0.2, 1, 0);
                for (Player v : players(60)) {
                    if (v.getWorld().equals(w) && v.getLocation().distanceSquared(mark) <= 9) {
                        hurt(v, 12);
                        v.setFireTicks(80);
                        knock(v, mark, 0.5, 0.5);
                    }
                }
            });
        }
        return true;
    }

    /** Colapso Gravitatorio: arrastra a todos hacia él y detona. */
    private boolean pull(List<Player> near) {
        boolean any = false;
        for (Player p : near) {
            double d = distTo(p);
            if (d >= 4 && d <= 16) {
                any = true;
                break;
            }
        }
        if (!any) return false;
        busyUntil = now + 90;
        sound(entity.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.5f, 0.4f);

        repeat(2, 8, i -> {
            Location c = entity.getLocation().add(0, 1, 0);
            c.getWorld().spawnParticle(Particle.REVERSE_PORTAL, c, 60, 5, 1.5, 5, 0.3);
            ring(entity.getLocation(), 10 - i, 30, Particle.SCULK_SOUL);
        }, () -> repeat(2, 7, j -> {
            Location c = entity.getLocation();
            for (Player p : players(16)) {
                Vector v = c.toVector().subtract(p.getLocation().toVector()).setY(0);
                if (v.lengthSquared() > 6) {
                    v.normalize().multiply(0.9).setY(0.18);
                    p.setVelocity(v);
                }
            }
        }, () -> {
            Location c = entity.getLocation();
            World w = c.getWorld();
            w.spawnParticle(Particle.EXPLOSION, c.clone().add(0, 1, 0), 4, 1.5, 0.5, 1.5, 0);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, c.clone().add(0, 1, 0), 80, 1.5, 1, 1.5, 0.2);
            sound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.8f, 0.5f);
            for (Player p : players(4.5)) {
                hurt(p, 8);
                knock(p, c, 1.1, 0.7);
                wither(p, 80);
            }
        }));
        return true;
    }

    /** Égida Umbral: escudo temporal que reduce muchísimo el daño recibido. */
    private boolean aegis() {
        busyUntil = now + 10;
        shieldUntil = now + 120;
        Location c = entity.getLocation();
        sound(c, Sound.BLOCK_BEACON_ACTIVATE, 2f, 0.6f);
        for (Player p : players(40)) {
            p.sendActionBar(Text.mm("<aqua>🛡 Vorthak invoca su Égida: resiste casi todo el daño durante 6s</aqua>"));
        }
        return true;
    }

    /** Tormenta de Almas (Fase IV): lluvia de calaveras sobre los jugadores. */
    private boolean storm(List<Player> near) {
        if (near.isEmpty()) return false;
        busyUntil = now + 130;
        sound(entity.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.5f, 1.2f);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        repeat(4, 30, i -> {
            List<Player> alive = players(50);
            if (alive.isEmpty()) return;
            for (int k = 0; k < 2; k++) {
                Player p = alive.get(rnd.nextInt(alive.size()));
                Location ground = p.getLocation().add(rnd.nextDouble(-4, 4), 0, rnd.nextDouble(-4, 4));
                ring(ground, 1.5, 14, Particle.SOUL_FIRE_FLAME);
                Location sky = ground.clone().add(0, 12, 0);
                entity.getWorld().spawn(sky, WitherSkull.class, sk -> {
                    sk.setShooter(entity);
                    sk.setDirection(new Vector(0, -1, 0));
                    sk.setYield(0f);
                    sk.setIsIncendiary(false);
                    sk.setCharged(true);
                });
            }
        }, null);
        return true;
    }

    /** Lluvia de Meteoros (Fase IV): zonas marcadas que explotan tras ~1.2s. Hay que moverse sin parar. */
    private boolean meteors(List<Player> near) {
        if (near.isEmpty()) return false;
        busyUntil = now + 100;
        sound(entity.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 2f, 0.5f);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        repeat(8, 10, i -> {
            List<Player> ps = players(50);
            if (ps.isEmpty()) return;
            for (int k = 0; k < 2; k++) {
                Player p = ps.get(rnd.nextInt(ps.size()));
                Location mark = p.getLocation().clone().add(rnd.nextDouble(-3, 3), 0, rnd.nextDouble(-3, 3));
                meteor(mark);
            }
        }, null);
        return true;
    }

    private void meteor(Location mark) {
        World w = mark.getWorld();
        repeat(2, 12, i -> {
            ring(mark, 3, 20, Particle.FLAME);
            ring(mark, 3.0 * (i + 1) / 12.0, 10, Particle.SOUL_FIRE_FLAME);
            w.spawnParticle(Particle.FLAME, mark.clone().add(0, 12 - i, 0), 6, 0.3, 0.3, 0.3, 0.02);
            w.spawnParticle(Particle.LARGE_SMOKE, mark.clone().add(0, 12 - i, 0), 3, 0.3, 0.3, 0.3, 0.01);
        }, () -> {
            w.spawnParticle(Particle.EXPLOSION, mark, 3, 1, 0.2, 1, 0);
            w.spawnParticle(Particle.FLAME, mark, 50, 1.5, 0.3, 1.5, 0.1);
            sound(mark, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.8f);
            for (Player v : players(60)) {
                if (v.getWorld().equals(w) && v.getLocation().distanceSquared(mark) <= 12.25) {
                    hurt(v, 11);
                    v.setFireTicks(80);
                    knock(v, mark, 0.7, 0.5);
                }
            }
        });
    }

    /** Guadaña de Ceniza (Fase IV): dos rayos que giran alrededor del jefe. Se esquivan saltando o rodeando. */
    private boolean scythe() {
        busyUntil = now + 130;
        entity.setAI(false);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double[] ang = {rnd.nextDouble(Math.PI * 2)};
        double dir = rnd.nextBoolean() ? 1 : -1;
        Map<UUID, Long> cool = new HashMap<>();
        sound(entity.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.8f, 0.5f);
        repeat(2, 12, i -> drawArms(entity.getLocation(), ang[0], Particle.SOUL), () -> {
            sound(entity.getLocation(), Sound.ENTITY_WITHER_SHOOT, 2f, 0.4f);
            repeat(2, 50, i -> {
                ang[0] += dir * 0.17;
                Location c = entity.getLocation();
                drawArms(c, ang[0], Particle.SOUL_FIRE_FLAME);
                beamDamage(c, ang[0], cool);
                if (i % 8 == 0) sound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.5f);
            }, () -> entity.setAI(true));
        });
        return true;
    }

    private void drawArms(Location c, double a, Particle particle) {
        World w = c.getWorld();
        for (int arm = 0; arm < 2; arm++) {
            double ang = a + arm * Math.PI;
            double ux = Math.cos(ang);
            double uz = Math.sin(ang);
            for (double d = 1.5; d <= BEAM_LEN; d += 1.0) {
                w.spawnParticle(particle, c.getX() + ux * d, c.getY() + 0.4, c.getZ() + uz * d, 1, 0, 0, 0, 0);
            }
        }
    }

    private void beamDamage(Location c, double a, Map<UUID, Long> cool) {
        for (Player p : players(BEAM_LEN + 2)) {
            Location pl = p.getLocation();
            if (pl.getY() - c.getY() >= 1.1) continue; // saltando por encima
            double dx = pl.getX() - c.getX();
            double dz = pl.getZ() - c.getZ();
            for (int arm = 0; arm < 2; arm++) {
                double ang = a + arm * Math.PI;
                double ux = Math.cos(ang);
                double uz = Math.sin(ang);
                double along = dx * ux + dz * uz;
                double across = Math.abs(dx * uz - dz * ux);
                if (along >= 1.2 && along <= BEAM_LEN && across <= 1.3
                        && now >= cool.getOrDefault(p.getUniqueId(), 0L)) {
                    cool.put(p.getUniqueId(), now + 20);
                    hurt(p, 9);
                    knock(p, c, 0.8, 0.5);
                    wither(p, 60);
                    break;
                }
            }
        }
    }

    /** Último Aliento (Fase IV, una vez): égida, súbditos, onda de almas y rayos. */
    private void lastStand() {
        lastStandUsed = true;
        shieldUntil = now + 100;
        sound(entity.getLocation(), Sound.ENTITY_WITHER_SPAWN, 2f, 0.5f);
        titleAll(80, "<dark_red><bold>¡ÚLTIMO ALIENTO!</bold></dark_red>",
                "<gray>Vorthak se aferra a la vida...</gray>", 3);
        summon(true);
        nova();
        busyUntil = now + 120;
        later(30, () -> {
            List<Player> l = players(48);
            if (!l.isEmpty()) lightning(l);
            busyUntil = now + 90;
        });
    }

    // ------------------------------------------------------------------ muerte y loot

    private void killMinions() {
        for (UUID id : minions) {
            Entity m = Bukkit.getEntity(id);
            if (m != null) {
                m.getWorld().spawnParticle(Particle.SOUL, m.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.05);
                m.remove();
            }
        }
        minions.clear();
        killHeralds();
        heralds.clear();
    }

    /** Muerte épica: el cuerpo se deshace en ceniza y una columna de luz sube durante ~4 s. */
    private void ashFinale(Location base) {
        World w = base.getWorld();
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (i++ >= 40) {
                    cancel();
                    return;
                }
                double h = i * 0.18;
                w.spawnParticle(Particle.ASH, base.clone().add(0, 1 + h * 0.4, 0), 40, 1.2, 1.6, 1.2, 0.02);
                w.spawnParticle(Particle.LARGE_SMOKE, base.clone().add(0, 0.5 + h * 0.3, 0), 6, 0.9, 0.9, 0.9, 0.02);
                w.spawnParticle(Particle.END_ROD, base.clone().add(0, h, 0), 4, 0.15, 0.4, 0.15, 0.02);
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, base.clone().add(0, 0.3 + h * 0.5, 0), 5, 0.8, 0.6, 0.8, 0.03);
                if (i % 8 == 0) {
                    w.playSound(base, Sound.BLOCK_BEACON_DEACTIVATE, 1.4f, 0.6f + i * 0.015f);
                    w.playSound(base, Sound.ENTITY_PHANTOM_DEATH, 0.8f, 0.5f);
                }
                if (i == 38) w.playSound(base, Sound.BLOCK_BEACON_ACTIVATE, 1.6f, 1.4f);
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    /** Botín de Vorthak. En la dungeon se tira una vez POR JUGADOR (cada uno lo recoge de su cofre personal). */
    public static List<ItemStack> rollLoot(CustomMobsPlugin plugin, BossManager manager) {
        List<ItemStack> out = new ArrayList<>();
        out.add(BossItems.heart(manager));
        out.add(new ItemStack(Material.NETHERITE_SCRAP, ThreadLocalRandom.current().nextInt(4, 9)));
        out.add(new ItemStack(Material.WITHER_SKELETON_SKULL, 3));
        out.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2));
        if (ThreadLocalRandom.current().nextDouble() < plugin.getConfig().getDouble("vorthak.blade-drop-chance", 0.25)) {
            out.add(BossItems.blade(manager));
        }
        out.addAll(BossItems.rollBooks(plugin));
        return out;
    }

    public boolean isDead() {
        return dead;
    }

    public void onDeath(EntityDeathEvent e) {
        dead = true;
        stop();
        killMinions();

        e.getDrops().clear();
        e.setDroppedExp(plugin.getConfig().getInt("vorthak.xp", 4000));
        e.getDrops().addAll(rollLoot(plugin, manager));

        Location loc = entity.getLocation();
        World w = loc.getWorld();
        w.strikeLightningEffect(loc);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, loc.clone().add(0, 1, 0), 3, 1, 1, 1, 0);
        w.spawnParticle(Particle.SOUL, loc.clone().add(0, 1, 0), 200, 2, 2, 2, 0.15);
        sound(loc, Sound.ENTITY_WITHER_DEATH, 2f, 0.7f);
        ashFinale(loc.clone());

        Title title = Title.title(
                Text.mm("<gradient:#ffd86b:#ff9a3c><bold>VORTHAK HA CAÍDO</bold></gradient>"),
                Text.mm("<gray>Las cenizas se apagan...</gray>"),
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofSeconds(1)));
        List<Player> witnesses = new ArrayList<>(w.getNearbyPlayers(loc, 80));
        for (Player p : witnesses) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            p.sendMessage(Text.mm("<dark_gray>━━━━━━━━ <gradient:#ff4d4d:#ffb347><bold>VORTHAK</bold></gradient>"
                    + " <dark_gray>━━━━━━━━</dark_gray>"));
            p.sendMessage(Text.mm("<gray>Mayor daño infligido:</gray>"));
            List<Map.Entry<UUID, Double>> top = new ArrayList<>(damageDealt.entrySet());
            top.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
            String[] medals = {"<gold>#1</gold>", "<gray>#2</gray>", "<#cd7f32>#3</#cd7f32>"};
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                Map.Entry<UUID, Double> en = top.get(i);
                p.sendMessage(Text.mm(medals[i] + " ").append(
                        Component.text(names.getOrDefault(en.getKey(), "?"), NamedTextColor.WHITE)
                                .append(Component.text("  " + (int) Math.round(en.getValue()) + " ❤", NamedTextColor.RED))));
            }
        }
        manager.unregister(entity.getUniqueId());
    }
}
