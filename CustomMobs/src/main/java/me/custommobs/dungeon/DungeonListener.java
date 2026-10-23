package me.custommobs.dungeon;

import me.custommobs.CustomMobsPlugin;
import me.custommobs.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;

/** Protección de la zona, reglas de muerte/vidas y enrutado de eventos hacia la instancia. */
public final class DungeonListener implements Listener {

    private final CustomMobsPlugin plugin;
    private final DungeonManager manager;

    public DungeonListener(CustomMobsPlugin plugin, DungeonManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private boolean inDungeon(org.bukkit.World w) {
        return manager.isDungeonWorld(w);
    }

    /** Admins en creativo pueden construir/editar dentro de la dungeon. */
    private boolean bypass(Player p) {
        return p.hasPermission("dungeon.admin") && p.getGameMode() == GameMode.CREATIVE;
    }

    private static Player playerFrom(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    // ------------------------------------------------------------------ conexión

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) manager.handleJoin(p);
        }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        manager.handleQuit(e.getPlayer());
    }

    // ------------------------------------------------------------------ muerte y vidas

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        DungeonInstance inst = manager.of(p);
        if (inst == null) return;
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.deathMessage(null);
        inst.onPlayerDeath(p);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && p.isDead()) p.spigot().respawn();
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        DungeonInstance inst = manager.of(p);
        if (inst == null) return;
        e.setRespawnLocation(inst.respawnLocation(p));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) inst.afterRespawn(p);
        }, 2L);
    }

    // ------------------------------------------------------------------ enemigos

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(EntityDeathEvent e) {
        DungeonInstance inst = manager.byMob(e.getEntity().getUniqueId());
        if (inst != null) inst.onMobDeath(e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!inDungeon(e.getEntity().getWorld())) return;
        if (e.getEntity() instanceof Player p) {
            DungeonInstance inst = manager.of(p);
            if (inst != null) {
                double m = inst.damageTakenMultiplier(p);
                if (inst.modifier() == Modifier.GLASS) m *= 1.3;
                if (m != 1.0) e.setDamage(e.getDamage() * m);
            }
            return;
        }
        DungeonInstance inst = manager.byMob(e.getEntity().getUniqueId());
        if (inst != null) inst.onMobDamaged(e);
    }

    /** Sin PvP dentro de la dungeon. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!inDungeon(e.getEntity().getWorld())) return;
        if (e.getEntity() instanceof Player && playerFrom(e.getDamager()) != null) e.setCancelled(true);
    }

    /** Bendiciones: multiplicador de daño infligido a los enemigos. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlessedHit(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player || !inDungeon(e.getEntity().getWorld())) return;
        Player p = playerFrom(e.getDamager());
        if (p == null) return;
        DungeonInstance inst = manager.of(p);
        if (inst == null) return;
        double m = inst.damageMultiplier(p);
        if (m != 1.0) e.setDamage(e.getDamage() * m);
    }

    /** Menú de bendiciones. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof DungeonInstance.BlessingMenu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        DungeonInstance inst = manager.of(p);
        if (inst != null) inst.chooseBlessingBySlot(p, e.getSlot());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof DungeonInstance.BlessingMenu) e.setCancelled(true);
    }

    /** Cuenta el daño infligido por cada jugador (para el puntaje). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageDealt(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player || !inDungeon(e.getEntity().getWorld())) return;
        Player p = playerFrom(e.getDamager());
        if (p == null) return;
        DungeonInstance inst = manager.of(p);
        if (inst != null) {
            inst.addDamage(p, e.getFinalDamage());
            inst.lifesteal(p, e.getFinalDamage());
        }
    }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        if (!inDungeon(e.getLocation().getWorld())) return;
        switch (e.getSpawnReason()) {
            case NATURAL, CHUNK_GEN, REINFORCEMENTS, JOCKEY, TRAP -> e.setCancelled(true);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ protección de la zona

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (inDungeon(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (inDungeon(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (inDungeon(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (inDungeon(e.getBlock().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (inDungeon(e.getRightClicked().getWorld()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHanging(HangingBreakEvent e) {
        if (inDungeon(e.getEntity().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent e) {
        if (inDungeon(e.getBlock().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (inDungeon(e.getEntity().getWorld())) e.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (inDungeon(e.getBlock().getWorld())) e.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (inDungeon(e.getBlock().getWorld())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (inDungeon(e.getBlock().getWorld())) e.setCancelled(true);
    }

    /** Sin perlas ni frutos de chorus para saltarse salas. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!inDungeon(e.getFrom().getWorld()) || bypass(e.getPlayer())) return;
        String c = e.getCause().name();
        if (c.equals("ENDER_PEARL") || c.equals("CHORUS_FRUIT")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Text.mm(DungeonManager.PREFIX + "<red>No puedes teletransportarte dentro de la dungeon.</red>"));
        }
    }

    /** Dentro de una partida solo se permiten unos pocos comandos. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (manager.of(p) == null || p.hasPermission("dungeon.admin")) return;
        if (!manager.cfg().b("players.block-commands")) return;
        String msg = e.getMessage();
        String label = msg.length() > 1 ? msg.substring(1).split(" ")[0].toLowerCase() : "";
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        List<String> allowed = manager.cfg().list("players.allowed-commands");
        for (String a : allowed) {
            if (a.equalsIgnoreCase(label)) return;
        }
        e.setCancelled(true);
        p.sendMessage(Text.mm(DungeonManager.PREFIX + "<red>No puedes usar ese comando dentro de la dungeon.</red> <gray>Usa <white>/dungeon leave</white> para salir.</gray>"));
    }
}