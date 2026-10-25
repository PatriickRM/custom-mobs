package me.custommobs.dungeon;

import me.custommobs.Sfx;
import me.custommobs.Text;
import me.custommobs.model.MobVisuals;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Habilidades y voz propias de cada enemigo de las salas de oleadas.
 *
 * <ul>
 *   <li><b>Cenizo</b>: al quedar herido se prende la mecha y estalla en brasas (¡matadlo antes o alejaos!).</li>
 *   <li><b>Arquero Calcinado</b>: salva de 3 flechas en abanico y salto evasivo si te acercas.</li>
 *   <li><b>Marchito</b>: aura de peste que ralentiza y charco de peste al morir.</li>
 *   <li><b>Bruto Forjado</b>: golpe de suelo telegrafiado y embestida a distancia.</li>
 *   <li><b>Espectro</b>: aparece a tu espalda con un tajo espectral y a veces atraviesa los golpes.</li>
 *   <li><b>Sacerdote de Almas</b>: cura y blinda a sus aliados y marca tu posición con círculos de almas.</li>
 *   <li><b>Bestia de Ceniza</b>: salto demoledor con onda de choque y se enfurece al 50% de vida.</li>
 * </ul>
 * Todos van en silencio (setSilent) y suenan con las voces de {@link #voice(String)}.
 */
public final class WaveAbilities implements Listener {

    /** Identificadores de arquetipo (coinciden con el id del modelo). */
    public static final String CENIZO = "cenizo", CALCINADO = "calcinado", MARCHITO = "marchito",
            BRUTO = "bruto", ESPECTRO = "espectro", SACERDOTE = "sacerdote", BESTIA = "bestia";

    /** Voz de un enemigo: claves de sonido de Minecraft + tono. */
    private record Voice(String ambient, float ambientPitch, String hurt, float hurtPitch,
                         String death, float deathPitch, String step, float stepPitch, float stepVol, double stride) {}

    private static Voice voice(String kind) {
        return switch (kind) {
            case CENIZO -> new Voice("entity.phantom.ambient", 0.5f, "entity.vex.hurt", 0.55f,
                    "entity.phantom.death", 0.6f, "block.gravel.step", 0.7f, 0.5f, 1.3);
            case CALCINADO -> new Voice("entity.blaze.ambient", 1.5f, "entity.blaze.hurt", 1.25f,
                    "entity.blaze.death", 1.2f, "block.bone_block.step", 1.0f, 0.45f, 1.2);
            case MARCHITO -> new Voice("entity.wither.ambient", 1.7f, "entity.wither.hurt", 1.8f,
                    "entity.wither.death", 1.9f, "block.sand.step", 0.8f, 0.5f, 1.2);
            case BRUTO -> new Voice("entity.piglin_brute.ambient", 0.7f, "entity.piglin_brute.hurt", 0.8f,
                    "entity.piglin_brute.death", 0.7f, "entity.iron_golem.step", 0.8f, 0.7f, 1.5);
            case ESPECTRO -> new Voice("entity.vex.ambient", 0.6f, "entity.vex.hurt", 0.8f,
                    "entity.vex.death", 0.7f, "block.soul_sand.step", 1.2f, 0.25f, 1.6);
            case SACERDOTE -> new Voice("entity.evoker.ambient", 0.8f, "entity.evoker.hurt", 0.9f,
                    "entity.evoker.death", 0.8f, "block.wool.step", 1.0f, 0.4f, 1.4);
            default -> new Voice("entity.zoglin.ambient", 0.6f, "entity.zoglin.hurt", 0.7f,
                    "entity.zoglin.death", 0.6f, "entity.hoglin.step", 0.7f, 0.9f, 1.8);
        };
    }

    private static final class State {
        final String kind;
        final LivingEntity ent;
        final DungeonInstance inst;
        int cooldown;
        // estado propio de cada arquetipo
        boolean armed;        // cenizo: mecha encendida
        int fuse;             // cenizo: ticks hasta estallar
        boolean enraged;      // bestia: ya se enfureció
        // voz
        int ambientIn = 60 + (int) (Math.random() * 100);
        Location lastLoc;
        double stepDist;
        long lastHurt;

        State(String kind, LivingEntity ent, DungeonInstance inst, int cooldown) {
            this.kind = kind;
            this.ent = ent;
            this.inst = inst;
            this.cooldown = cooldown;
        }
    }

    private final JavaPlugin plugin;
    private final DungeonManager manager;
    private final MobVisuals visuals;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public WaveAbilities(JavaPlugin plugin, DungeonManager manager, MobVisuals visuals) {
        this.plugin = plugin;
        this.manager = manager;
        this.visuals = visuals;
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
    }

    public void register(LivingEntity e, String kind, DungeonInstance inst) {
        e.setSilent(true); // sin la voz vanilla (zombi, esqueleto...): suena la propia de cada arquetipo
        int first = switch (kind) {
            case BRUTO -> 120;
            case ESPECTRO -> 100;
            case SACERDOTE -> 80;
            case CALCINADO -> 60;
            case BESTIA -> 90;
            default -> 0;
        };
        states.put(e.getUniqueId(), new State(kind, e, inst, first + (int) (Math.random() * 40)));
    }

    private String kindOf(Entity e) {
        State s = states.get(e.getUniqueId());
        return s == null ? null : s.kind;
    }

    // ------------------------------------------------------------------ utilidades

    private static double maxHp(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? e.getHealth() : a.getValue();
    }

    private static List<Player> playersNear(DungeonInstance inst, Location loc, double radius) {
        List<Player> out = new ArrayList<>();
        double r2 = radius * radius;
        for (Player p : inst.aliveOnline()) {
            if (p.getWorld().equals(loc.getWorld()) && p.getLocation().distanceSquared(loc) <= r2) out.add(p);
        }
        return out;
    }

    private static void knock(Player p, Location from, double horizontal, double vertical) {
        Vector v = p.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (v.lengthSquared() < 1.0e-4) v = new Vector(0, 0, 1);
        p.setVelocity(v.normalize().multiply(horizontal).setY(vertical));
    }

    private static boolean gone(LivingEntity e) {
        return e == null || !e.isValid() || e.isDead();
    }

    private static void ring(Location c, double r, int points, Particle particle) {
        World w = c.getWorld();
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            w.spawnParticle(particle, c.getX() + Math.cos(a) * r, c.getY() + 0.15, c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------------ bucle (cada 5 ticks)

    private void tick() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Iterator<State> it = states.values().iterator();
        while (it.hasNext()) {
            State s = it.next();
            LivingEntity e = s.ent;
            if (gone(e)) {
                it.remove();
                continue;
            }
            Location here = e.getLocation();
            Voice v = voice(s.kind);

            // pasos propios (el mob va en silencio)
            if (s.lastLoc != null && s.lastLoc.getWorld() != null && s.lastLoc.getWorld().equals(here.getWorld())) {
                double dx = here.getX() - s.lastLoc.getX();
                double dz = here.getZ() - s.lastLoc.getZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d < 3.0) s.stepDist += d;
                if (s.stepDist >= v.stride()) {
                    s.stepDist = 0;
                    Sfx.play(here, v.step(), v.stepVol(), v.stepPitch() + (float) rnd.nextDouble(-0.1, 0.1));
                }
            }
            s.lastLoc = here;

            // voz ambiental
            s.ambientIn -= 5;
            if (s.ambientIn <= 0) {
                s.ambientIn = 120 + rnd.nextInt(200);
                Sfx.play(here, v.ambient(), 0.7f, v.ambientPitch() + (float) rnd.nextDouble(-0.08, 0.08));
            }

            // comportamientos continuos (no dependen del enfriamiento)
            if (CENIZO.equals(s.kind)) cenizoTick(s);
            else if (BESTIA.equals(s.kind)) bestiaEnrage(s);
            else if (MARCHITO.equals(s.kind)) aura(s);
            if (gone(e)) continue;

            if (s.cooldown > 0) {
                s.cooldown -= 5;
                continue;
            }
            LivingEntity target = e instanceof Mob m ? m.getTarget() : null;
            switch (s.kind) {
                case CALCINADO -> calcinado(s, target);
                case BRUTO -> bruto(s, target);
                case ESPECTRO -> espectro(s, target);
                case SACERDOTE -> sacerdote(s, target);
                case BESTIA -> bestia(s, target);
                default -> s.cooldown = 1000;
            }
        }
    }

    // ------------------------------------------------------------------ CENIZO: mecha de brasas

    private void cenizoTick(State s) {
        LivingEntity e = s.ent;
        Location at = e.getLocation();
        World w = at.getWorld();
        if (!s.armed && e.getHealth() <= maxHp(e) * 0.4) {
            s.armed = true;
            s.fuse = 40;
            e.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 1, false, false));
            visuals.attack(e.getUniqueId(), 16);
            Sfx.play(at, "entity.creeper.primed", 1.3f, 0.7f);
            for (Player p : playersNear(s.inst, at, 14)) {
                p.sendActionBar(Text.mm("<red>⚠</red> <white>¡Un Cenizo está a punto de estallar!</white>"));
            }
        }
        if (!s.armed) return;
        s.fuse -= 5;
        w.spawnParticle(Particle.FLAME, at.clone().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.03);
        w.spawnParticle(Particle.SMOKE, at.clone().add(0, 1.4, 0), 4, 0.2, 0.3, 0.2, 0.01);
        if (s.fuse % 10 == 0) Sfx.play(at, "entity.creeper.primed", 0.7f, 1.4f);
        if (s.fuse > 0) return;

        // estallido de brasas
        Location c = at.clone().add(0, 1, 0);
        w.spawnParticle(Particle.EXPLOSION, c, 3, 0.8, 0.4, 0.8, 0);
        w.spawnParticle(Particle.FLAME, c, 70, 1.6, 0.6, 1.6, 0.12);
        w.spawnParticle(Particle.ASH, c, 60, 2.0, 1.0, 2.0, 0.05);
        Sfx.play(at, "entity.generic.explode", 1.1f, 1.3f);
        AttributeInstance atk = e.getAttribute(Attribute.ATTACK_DAMAGE);
        double dmg = (atk == null ? 5.0 : atk.getValue()) * 1.4 + 2.0;
        for (Player p : playersNear(s.inst, at, 3.8)) {
            s.inst.hazardHit(p, dmg);
            p.setFireTicks(60);
            knock(p, at, 1.1, 0.45);
        }
        s.armed = false;
        s.cooldown = 1000;
        e.setHealth(0.0);
    }

    // ------------------------------------------------------------------ CALCINADO: salva y salto evasivo

    private void calcinado(State s, LivingEntity target) {
        LivingEntity e = s.ent;
        if (target == null) {
            s.cooldown = 20;
            return;
        }
        Location here = e.getLocation();
        double d2 = target.getLocation().distanceSquared(here);
        if (d2 < 4.5 * 4.5) {
            // si te le acercas, da un salto hacia atrás y sigue disparando
            Vector away = here.toVector().subtract(target.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1.0e-4) away = new Vector(1, 0, 0);
            e.setVelocity(away.normalize().multiply(0.95).setY(0.5));
            here.getWorld().spawnParticle(Particle.FLAME, here.clone().add(0, 0.2, 0), 25, 0.3, 0.1, 0.3, 0.06);
            Sfx.play(here, "entity.blaze.shoot", 0.6f, 1.7f);
            s.cooldown = 90;
        } else if (d2 < 30 * 30 && e.hasLineOfSight(target)) {
            visuals.attack(e.getUniqueId(), 12);
            Sfx.play(here, "entity.blaze.shoot", 1.0f, 0.8f);
            here.getWorld().spawnParticle(Particle.FLAME, e.getEyeLocation(), 20, 0.3, 0.3, 0.3, 0.04);
            s.inst.later(10, () -> volley(e, target));
            s.cooldown = 140;
        } else {
            s.cooldown = 20;
        }
    }

    /** Tres flechas incendiarias en abanico. */
    private void volley(LivingEntity shooter, LivingEntity target) {
        if (gone(shooter) || gone(target)) return;
        Location eye = shooter.getEyeLocation();
        Vector dir = target.getEyeLocation().toVector().subtract(eye.toVector());
        if (dir.lengthSquared() < 1.0e-4) return;
        dir.normalize();
        for (int i = -1; i <= 1; i++) {
            Vector vel = dir.clone().rotateAroundY(Math.toRadians(i * 11.0)).multiply(1.9);
            Arrow a = shooter.launchProjectile(Arrow.class, vel);
            quiet(a);
        }
        Sfx.play(eye, "entity.blaze.shoot", 1.0f, 1.2f);
    }

    // ------------------------------------------------------------------ MARCHITO: aura de peste

    /** Pulso de peste cada ~1,25 s: ralentiza a quien se quede pegado al Marchito. */
    private void aura(State s) {
        LivingEntity e = s.ent;
        if (s.cooldown > 0) return;
        Location at = e.getLocation();
        ring(at, 4.0, 14, Particle.ASH);
        ring(at, 4.0, 8, Particle.SOUL);
        for (Player p : playersNear(s.inst, at, 4.0)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 0, false, true));
        }
        s.cooldown = 25;
    }

    // ------------------------------------------------------------------ BRUTO: golpe de suelo y embestida

    private void bruto(State s, LivingEntity target) {
        LivingEntity e = s.ent;
        if (target == null) {
            s.cooldown = 20;
            return;
        }
        Location at = e.getLocation();
        double d2 = target.getLocation().distanceSquared(at);
        if (d2 < 8 * 8) {
            visuals.attack(e.getUniqueId(), 20);
            Sfx.play(at, "entity.ravager.roar", 1f, 0.7f);
            s.inst.hazard(at.clone(), 24, 4.0, 7.0);
            s.cooldown = 180;
        } else if (d2 < 16 * 16 && e.hasLineOfSight(target)) {
            // embestida: se agacha, ruge y se lanza en línea recta
            visuals.attack(e.getUniqueId(), 20);
            Sfx.play(at, "entity.ravager.roar", 1f, 1.0f);
            at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 0.3, 0), 18, 0.6, 0.1, 0.6, 0.03);
            Vector dir = target.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (dir.lengthSquared() < 1.0e-4) dir = new Vector(0, 0, 1);
            final Vector charge = dir.normalize();
            s.inst.later(14, () -> {
                if (gone(e)) return;
                e.setVelocity(charge.clone().multiply(1.6).setY(0.25));
                Sfx.play(e.getLocation(), "entity.iron_golem.attack", 1.2f, 0.6f);
                final boolean[] hit = {false};
                s.inst.repeat(2, 9, i -> {
                    if (hit[0] || gone(e)) return;
                    Location l = e.getLocation();
                    l.getWorld().spawnParticle(Particle.LARGE_SMOKE, l.clone().add(0, 0.4, 0), 4, 0.4, 0.2, 0.4, 0.02);
                    for (Player p : playersNear(s.inst, l, 2.3)) {
                        hit[0] = true;
                        s.inst.hazardHit(p, 8.0);
                        knock(p, l, 1.5, 0.55);
                        l.getWorld().spawnParticle(Particle.EXPLOSION, p.getLocation().add(0, 1, 0), 2, 0.3, 0.3, 0.3, 0);
                        Sfx.play(l, "entity.generic.explode", 0.8f, 1.2f);
                    }
                }, null);
            });
            s.cooldown = 200;
        } else {
            s.cooldown = 20;
        }
    }

    // ------------------------------------------------------------------ ESPECTRO: tajo por la espalda

    private void espectro(State s, LivingEntity target) {
        LivingEntity e = s.ent;
        if (target == null || target.getLocation().distanceSquared(e.getLocation()) <= 6 * 6) {
            s.cooldown = 10;
            return;
        }
        Location tl = target.getLocation();
        Vector back = tl.getDirection().setY(0);
        if (back.lengthSquared() < 1.0e-4) back = new Vector(0, 0, 1);
        Location dest = tl.clone().subtract(back.normalize().multiply(2.0));
        dest.setYaw(tl.getYaw() + 180f);
        Location from = e.getLocation();
        from.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, from.clone().add(0, 1, 0), 24, 0.3, 0.6, 0.3, 0.05);
        e.teleport(dest);
        dest.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, dest.clone().add(0, 1, 0), 24, 0.3, 0.6, 0.3, 0.05);
        Sfx.play(dest, "entity.enderman.teleport", 0.8f, 0.6f);
        // tajo espectral poco después de aparecer (da tiempo a girarse)
        visuals.attack(e.getUniqueId(), 12);
        s.inst.later(12, () -> {
            if (gone(e)) return;
            Location l = e.getLocation();
            l.getWorld().spawnParticle(Particle.SWEEP_ATTACK, l.clone().add(l.getDirection().multiply(1.6)).add(0, 1, 0), 3, 0.6, 0.2, 0.6, 0);
            l.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, l.clone().add(0, 1, 0), 20, 1.2, 0.4, 1.2, 0.05);
            Sfx.play(l, "entity.player.attack.sweep", 1.0f, 0.6f);
            for (Player p : playersNear(s.inst, l, 3.4)) {
                s.inst.hazardHit(p, 5.0);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true));
            }
        });
        s.cooldown = 160;
    }

    // ------------------------------------------------------------------ SACERDOTE: cura, blinda y marca

    private void sacerdote(State s, LivingEntity target) {
        LivingEntity e = s.ent;
        DungeonInstance inst = s.inst;
        boolean healed = false;
        boolean shielded = false;
        for (Entity n : e.getNearbyEntities(8, 4, 8)) {
            if (!(n instanceof LivingEntity ally) || n instanceof Player) continue;
            if (manager.byMob(ally.getUniqueId()) != inst || n.getUniqueId().equals(e.getUniqueId())) continue;
            double max = maxHp(ally);
            if (ally.getHealth() < max) {
                ally.setHealth(Math.min(max, ally.getHealth() + 8));
                ally.getWorld().spawnParticle(Particle.HEART, ally.getLocation().add(0, 1.6, 0), 3, 0.3, 0.2, 0.3, 0);
                healed = true;
            } else if (!ally.hasPotionEffect(PotionEffectType.ABSORPTION)) {
                ally.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 160, 1, false, true));
                ally.getWorld().spawnParticle(Particle.END_ROD, ally.getLocation().add(0, 1.2, 0), 10, 0.3, 0.5, 0.3, 0.02);
                shielded = true;
            }
        }
        if (healed || shielded) {
            visuals.attack(e.getUniqueId(), 12);
            e.getWorld().spawnParticle(Particle.SOUL, e.getLocation().add(0, 1, 0), 30, 1.5, 0.6, 1.5, 0.02);
            Sfx.play(e.getLocation(), "block.beacon.power_select", 0.8f, 1.4f);
            s.cooldown = 140;
        } else if (target != null && target.getLocation().distanceSquared(e.getLocation()) < 22 * 22) {
            // sin nadie a quien ayudar: marca tu posición con un círculo de almas
            Location tl = target.getLocation();
            visuals.attack(e.getUniqueId(), 12);
            Sfx.play(e.getLocation(), "entity.evoker.cast_spell", 1.0f, 1.1f);
            e.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, e.getEyeLocation(), 20, 0.3, 0.3, 0.3, 0.05);
            inst.hazard(tl.clone(), 22, 2.4, 6.0);
            s.cooldown = 70;
        } else {
            s.cooldown = 40;
        }
    }

    // ------------------------------------------------------------------ BESTIA: salto demoledor y furia

    private void bestiaEnrage(State s) {
        LivingEntity e = s.ent;
        if (s.enraged || e.getHealth() > maxHp(e) * 0.5) return;
        s.enraged = true;
        Location at = e.getLocation();
        e.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0, false, false));
        e.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0, false, false));
        visuals.attack(e.getUniqueId(), 20);
        Sfx.play(at, "entity.ravager.roar", 1.4f, 0.6f);
        at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(0, 1, 0), 60, 0.8, 0.8, 0.8, 0.08);
        for (Player p : playersNear(s.inst, at, 14)) {
            p.sendActionBar(Text.mm("<gold>🔥</gold> <white>¡La Bestia de Ceniza se ha enfurecido!</white>"));
        }
    }

    private void bestia(State s, LivingEntity target) {
        LivingEntity e = s.ent;
        if (target == null) {
            s.cooldown = 20;
            return;
        }
        Location at = e.getLocation();
        double d2 = target.getLocation().distanceSquared(at);
        if (d2 > 7 * 7 && d2 < 18 * 18 && e.hasLineOfSight(target)) {
            visuals.attack(e.getUniqueId(), 16);
            Sfx.play(at, "entity.ravager.roar", 1.2f, 0.8f);
            at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(0, 0.3, 0), 30, 0.8, 0.1, 0.8, 0.06);
            Vector dir = target.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (dir.lengthSquared() < 1.0e-4) dir = new Vector(0, 0, 1);
            final Vector jump = dir.normalize();
            s.inst.later(12, () -> {
                if (gone(e)) return;
                e.setVelocity(jump.clone().multiply(1.15).setY(0.8));
            });
            // aterrizaje: onda de choque telegrafiada
            s.inst.later(30, () -> {
                if (gone(e)) return;
                Location l = e.getLocation();
                l.getWorld().spawnParticle(Particle.EXPLOSION, l.clone().add(0, 0.3, 0), 3, 1.2, 0.1, 1.2, 0);
                Sfx.play(l, "entity.generic.explode", 1.0f, 0.7f);
                for (Player p : playersNear(s.inst, l, 2.6)) knock(p, l, 1.0, 0.5);
                s.inst.hazard(l.clone(), 8, 3.4, 8.0);
            });
            s.cooldown = 170;
        } else {
            s.cooldown = 25;
        }
    }

    // ------------------------------------------------------------------ voz: daño y muerte

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        State s = states.get(e.getEntity().getUniqueId());
        if (s == null || e.getFinalDamage() <= 0) return;
        long now = System.currentTimeMillis();
        if (now - s.lastHurt < 300) return;
        s.lastHurt = now;
        Voice v = voice(s.kind);
        Sfx.play(e.getEntity().getLocation(), v.hurt(), 0.9f, v.hurtPitch() + (float) ThreadLocalRandom.current().nextDouble(-0.1, 0.1));
    }

    // ------------------------------------------------------------------ habilidades pasivas

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        String k = kindOf(e.getEntity());
        if (!CALCINADO.equals(k)) return;
        // sin llama en la flecha (molestaba a la vista): el fuego se aplica al impactar, ver onHit
        if (e.getProjectile() instanceof AbstractArrow a) {
            a.setDamage(a.getDamage() + 0.5);
            quiet(a);
        }
    }

    /** La flecha del Calcinado no arde, no se puede recoger y desaparece al impactar (no queda clavada). */
    private static void quiet(AbstractArrow a) {
        a.setFireTicks(0);
        a.setVisualFire(false);
        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        a.setPierceLevel(0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onArrowLand(org.bukkit.event.entity.ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow a)) return;
        if (!(a.getShooter() instanceof Entity sh) || !CALCINADO.equals(kindOf(sh))) return;
        Location at = a.getLocation();
        if (at.getWorld() != null) at.getWorld().spawnParticle(Particle.SMOKE, at, 4, 0.05, 0.05, 0.05, 0.01);
        Bukkit.getScheduler().runTask(plugin, () -> {
            a.remove();
            if (e.getHitEntity() instanceof Player p) p.setArrowsInBody(0);
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        String k = kindOf(e.getDamager());
        if (MARCHITO.equals(k)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 80, 0));
        } else if (e.getDamager() instanceof AbstractArrow a && a.getShooter() instanceof Entity sh && CALCINADO.equals(kindOf(sh))) {
            p.setFireTicks(Math.max(p.getFireTicks(), 60));
        }
    }

    /** Espectro: a veces el golpe lo atraviesa (intangible). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpectreHit(EntityDamageByEntityEvent e) {
        if (!ESPECTRO.equals(kindOf(e.getEntity()))) return;
        boolean fromPlayer = e.getDamager() instanceof Player
                || (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player);
        if (!fromPlayer || ThreadLocalRandom.current().nextDouble() >= 0.18) return;
        e.setCancelled(true);
        Location at = e.getEntity().getLocation().add(0, 1, 0);
        at.getWorld().spawnParticle(Particle.SOUL, at, 18, 0.3, 0.6, 0.3, 0.04);
        Sfx.play(at, "entity.vex.charge", 0.8f, 1.3f);
        if (e.getDamager() instanceof Player p) p.sendActionBar(Text.mm("<aqua>El Espectro se vuelve intangible...</aqua>"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        State s = states.remove(e.getEntity().getUniqueId());
        if (s == null) return;
        Location at = e.getEntity().getLocation();
        World w = at.getWorld();
        Voice v = voice(s.kind);
        Sfx.play(at, v.death(), 1.0f, v.deathPitch());
        switch (s.kind) {
            case CENIZO -> {
                w.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 1, 0), 30, 0.8, 0.6, 0.8, 0.02);
                for (Player p : playersNear(s.inst, at, 3.0)) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
            }
            case MARCHITO -> {
                // charco de peste: marchita a quien lo pise unos segundos
                final Location pool = at.clone();
                Sfx.play(at, "entity.wither.shoot", 0.7f, 1.6f);
                s.inst.repeat(10, 12, i -> {
                    ring(pool, 3.0, 16, Particle.SOUL);
                    pool.getWorld().spawnParticle(Particle.SMOKE, pool.clone().add(0, 0.3, 0), 10, 1.4, 0.2, 1.4, 0.01);
                    for (Player p : playersNear(s.inst, pool, 3.0)) {
                        p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 40, 0, false, true));
                    }
                }, null);
            }
            case ESPECTRO -> {
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, at.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.08);
                Sfx.play(at, "entity.enderman.teleport", 0.6f, 1.6f);
            }
            case SACERDOTE -> {
                // su último gesto: cura a los aliados cercanos
                for (Entity n : w.getNearbyEntities(at, 6, 3, 6)) {
                    if (!(n instanceof LivingEntity ally) || n instanceof Player) continue;
                    if (manager.byMob(ally.getUniqueId()) != s.inst) continue;
                    ally.setHealth(Math.min(maxHp(ally), ally.getHealth() + 15));
                    ally.getWorld().spawnParticle(Particle.HEART, ally.getLocation().add(0, 1.6, 0), 4, 0.3, 0.2, 0.3, 0);
                }
                w.spawnParticle(Particle.SOUL, at.clone().add(0, 1, 0), 40, 2.0, 0.6, 2.0, 0.03);
            }
            case BESTIA -> {
                Sfx.play(at, "entity.blaze.shoot", 1f, 0.6f);
                s.inst.hazard(at.clone(), 30, 4.0, 6.0);
            }
            default -> { }
        }
    }
}
