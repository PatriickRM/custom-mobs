package me.custommobs.boss;

import me.custommobs.CustomMobsPlugin;
import me.custommobs.Text;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BossListener implements Listener {

    private final CustomMobsPlugin plugin;
    private final BossManager bosses;
    private final Map<UUID, Long> warnCooldown = new HashMap<>();

    public BossListener(CustomMobsPlugin plugin, BossManager bosses) {
        this.plugin = plugin;
        this.bosses = bosses;
    }

    private static Player playerFrom(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    /** Reglas de daño: solo los jugadores con el equipo requerido pueden herir al jefe y a sus Heraldos. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossDamage(EntityDamageEvent e) {
        VorthakBoss boss = bosses.get(e.getEntity());
        boolean herald = false;
        if (boss == null) {
            boss = bosses.heraldOwner(e.getEntity());
            herald = boss != null;
        }
        if (boss == null) return;

        // Comandos de admin (/kill) y vacío siempre funcionan
        DamageCause cause = e.getCause();
        if (cause == DamageCause.KILL || cause == DamageCause.VOID) return;

        Location at = e.getEntity().getLocation();

        // Transición de fase o ritual / sentencia: el jefe es inmune
        if (!herald && (boss.isTransitioning() || boss.isImmune())) {
            e.setCancelled(true);
            e.getEntity().getWorld().playSound(at, Sound.ITEM_SHIELD_BLOCK, 1f, 0.6f);
            if (boss.isImmune() && e instanceof EntityDamageByEntityEvent be) {
                Player src = playerFrom(be.getDamager());
                if (src != null) warn(src, "Vorthak es inmune: ¡derrota a los Heraldos!", at);
            }
            return;
        }

        if (!(e instanceof EntityDamageByEntityEvent byEntity)) {
            // fuego, lava, explosiones, pociones, caídas... no le hacen nada
            e.setCancelled(true);
            return;
        }

        Entity damager = byEntity.getDamager();
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player shooter) {
            e.setCancelled(true);
            warn(shooter, plugin.getConfig().getBoolean("gear.allow-netherite", true)
                    ? "Solo una espada de diamante o netherite puede herirlo"
                    : "Solo una espada de diamante puede herirlo", at);
            return;
        }
        if (!(damager instanceof Player player)) {
            e.setCancelled(true);
            return;
        }
        if (cause != DamageCause.ENTITY_ATTACK && cause != DamageCause.ENTITY_SWEEP_ATTACK) {
            e.setCancelled(true);
            return;
        }

        if (plugin.getConfig().getBoolean("gear.enabled", true)) {
            GearCheck.Result r = GearCheck.check(player,
                    plugin.getConfig().getInt("gear.min-protection", 3),
                    plugin.getConfig().getInt("gear.min-sharpness", 5),
                    plugin.getConfig().getBoolean("gear.allow-netherite", true),
                    plugin.getConfig().getInt("gear.netherite-sword-min-sharpness", 0));
            if (!r.ok()) {
                e.setCancelled(true);
                warn(player, r.reason(), at);
                return;
            }
        }

        double dmg = Math.min(e.getDamage(), plugin.getConfig().getDouble("vorthak.max-damage-per-hit", 40.0));
        // Segadora / Hoja de Ceniza: más daño contra Vorthak y sus Heraldos
        dmg *= BossItems.vorthakBonus(plugin, bosses, player.getInventory().getItemInMainHand());
        if (!herald) {
            if (boss.isShielded()) {
                dmg *= 0.15;
                e.getEntity().getWorld().playSound(at, Sound.ITEM_SHIELD_BLOCK, 1f, 0.8f);
            }
            if (boss.isStunned()) {
                dmg *= 1.5; // tras el ritual queda aturdido y recibe más daño
            }
        }
        e.setDamage(dmg);
    }

    private void warn(Player p, String reason, Location at) {
        long t = System.currentTimeMillis();
        Long last = warnCooldown.get(p.getUniqueId());
        if (last != null && t - last < 1500) return;
        warnCooldown.put(p.getUniqueId(), t);
        p.sendActionBar(Text.mm("<red>✖ " + reason + "</red>"));
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1.6f);
        at.getWorld().playSound(at, Sound.ITEM_SHIELD_BLOCK, 1f, 0.7f);
    }

    /** Registra el daño real y comprueba cambios de fase. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDamaged(EntityDamageEvent e) {
        VorthakBoss boss = bosses.get(e.getEntity());
        if (boss == null) return;
        if (e instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player p) {
            boss.recordDamage(p, e.getFinalDamage());
        }
        Bukkit.getScheduler().runTask(plugin, boss::checkPhase);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        VorthakBoss boss = bosses.get(e.getEntity());
        if (boss != null) {
            boss.onDeath(e);
            return;
        }
        if (bosses.isMinion(e.getEntity()) || bosses.isHerald(e.getEntity())) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }

    /** Las calaveras del jefe y de sus Heraldos no destruyen bloques. */
    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        if (e.getEntity() instanceof WitherSkull skull && skull.getShooter() instanceof Entity shooter
                && (bosses.get(shooter) != null || bosses.isHerald(shooter))) {
            e.blockList().clear();
        }
    }

    /** Efecto visual de la Segadora de Ceniza al golpear. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAshHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!BossItems.SWORD.equals(BossItems.idOf(bosses, p.getInventory().getItemInMainHand()))) return;
        Location loc = e.getEntity().getLocation().add(0, e.getEntity().getHeight() * 0.6, 0);
        loc.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 14, 0.3, 0.4, 0.3, 0.05);
        loc.getWorld().spawnParticle(Particle.SOUL, loc, 4, 0.3, 0.4, 0.3, 0.02);
        loc.getWorld().playSound(loc, Sound.ENTITY_WITHER_HURT, 0.3f, 1.6f);
    }

    /** Al recoger un Corazón de Ceniza se desbloquea la receta en el libro de recetas. */
    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p
                && BossItems.HEART.equals(BossItems.idOf(bosses, e.getItem().getItemStack()))) {
            p.discoverRecipe(bosses.swordRecipeKey);
        }
    }

    /** El Corazón de Ceniza solo sirve para la Segadora (no para faros, etc.). */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        Recipe r = e.getRecipe();
        if (r == null) return;
        if (r instanceof Keyed k && k.getKey().equals(bosses.swordRecipeKey)) {
            ItemStack[] m = e.getInventory().getMatrix();
            ItemStack center = m.length > 4 ? m[4] : null;
            if (!BossItems.HEART.equals(BossItems.idOf(bosses, center))) {
                e.getInventory().setResult(null); // nether star normal: no vale
            }
            return;
        }
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (it != null && BossItems.HEART.equals(BossItems.idOf(bosses, it))) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    /** Re-adopta al jefe cuando sus chunks vuelven a cargarse. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            bosses.adopt(en);
        }
    }
}
