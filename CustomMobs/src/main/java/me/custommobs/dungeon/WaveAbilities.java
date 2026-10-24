package me.custommobs.dungeon;

import me.custommobs.model.MobVisuals;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Habilidades propias de cada enemigo de las salas de oleadas. */
public final class WaveAbilities implements Listener {

    /** Identificadores de arquetipo (coinciden con el id del modelo). */
    public static final String CENIZO = "cenizo", CALCINADO = "calcinado", MARCHITO = "marchito",
            BRUTO = "bruto", ESPECTRO = "espectro", SACERDOTE = "sacerdote", BESTIA = "bestia";

    private static final class State {
        final String kind;
        final LivingEntity ent;
        final DungeonInstance inst;
        int cooldown;

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
        int first = switch (kind) {
            case BRUTO -> 120;
            case ESPECTRO -> 100;
            case SACERDOTE -> 80;
            default -> 0;
        };
        states.put(e.getUniqueId(), new State(kind, e, inst, first + (int) (Math.random() * 40)));
    }

    private String kindOf(Entity e) {
        State s = states.get(e.getUniqueId());
        return s == null ? null : s.kind;
    }

    // ------------------------------------------------------------------ habilidades activas

    private void tick() {
        Iterator<State> it = states.values().iterator();
        while (it.hasNext()) {
            State s = it.next();
            LivingEntity e = s.ent;
            if (!e.isValid() || e.isDead()) {
                it.remove();
                continue;
            }
            if (s.cooldown > 0) {
                s.cooldown -= 5;
                continue;
            }
            DungeonInstance inst = s.inst;
            LivingEntity target = e instanceof Mob m ? m.getTarget() : null;
            switch (s.kind) {
                case BRUTO -> {
                    if (target != null && target.getLocation().distanceSquared(e.getLocation()) < 8 * 8) {
                        Location at = e.getLocation();
                        visuals.attack(e.getUniqueId(), 20);
                        at.getWorld().playSound(at, Sound.ENTITY_RAVAGER_ROAR, 1f, 0.7f);
                        inst.hazard(at.clone(), 24, 4.0, 7.0);
                        s.cooldown = 180;
                    }
                }
                case ESPECTRO -> {
                    if (target != null && target.getLocation().distanceSquared(e.getLocation()) > 6 * 6) {
                        Location tl = target.getLocation();
                        Vector back = tl.getDirection().setY(0);
                        if (back.lengthSquared() < 1.0e-4) back = new Vector(0, 0, 1);
                        Location dest = tl.clone().subtract(back.normalize().multiply(2.0));
                        dest.setYaw(tl.getYaw() + 180f);
                        Location from = e.getLocation();
                        from.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, from.clone().add(0, 1, 0), 24, 0.3, 0.6, 0.3, 0.05);
                        e.teleport(dest);
                        dest.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, dest.clone().add(0, 1, 0), 24, 0.3, 0.6, 0.3, 0.05);
                        dest.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.6f);
                        s.cooldown = 160;
                    }
                }
                case SACERDOTE -> {
                    boolean healed = false;
                    for (Entity n : e.getNearbyEntities(8, 4, 8)) {
                        if (!(n instanceof LivingEntity ally) || n instanceof Player) continue;
                        if (manager.byMob(ally.getUniqueId()) != inst || n.getUniqueId().equals(e.getUniqueId())) continue;
                        double max = ally.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                        if (ally.getHealth() >= max) continue;
                        ally.setHealth(Math.min(max, ally.getHealth() + 8));
                        ally.getWorld().spawnParticle(Particle.HEART, ally.getLocation().add(0, 1.6, 0), 3, 0.3, 0.2, 0.3, 0);
                        healed = true;
                    }
                    if (healed) {
                        visuals.attack(e.getUniqueId(), 12);
                        e.getWorld().spawnParticle(Particle.SOUL, e.getLocation().add(0, 1, 0), 30, 1.5, 0.6, 1.5, 0.02);
                        e.getWorld().playSound(e.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.4f);
                        s.cooldown = 140;
                    } else {
                        s.cooldown = 40;
                    }
                }
                default -> s.cooldown = 1000;
            }
        }
    }

    // ------------------------------------------------------------------ habilidades pasivas

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        String k = kindOf(e.getEntity());
        if (!CALCINADO.equals(k)) return;
        e.getProjectile().setFireTicks(200);
        if (e.getProjectile() instanceof AbstractArrow a) a.setDamage(a.getDamage() + 0.5);
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        State s = states.remove(e.getEntity().getUniqueId());
        if (s == null) return;
        Location at = e.getEntity().getLocation();
        if (CENIZO.equals(s.kind)) {
            at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 1, 0), 30, 0.8, 0.6, 0.8, 0.02);
            for (Entity n : at.getWorld().getNearbyEntities(at, 3, 2, 3)) {
                if (n instanceof Player p) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0));
            }
        } else if (BESTIA.equals(s.kind)) {
            DungeonInstance inst = s.inst;
            {
                at.getWorld().playSound(at, Sound.ENTITY_BLAZE_SHOOT, 1f, 0.6f);
                inst.hazard(at.clone(), 30, 4.0, 6.0);
            }
        }
    }
}
