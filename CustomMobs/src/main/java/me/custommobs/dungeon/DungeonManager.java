package me.custommobs.dungeon;

import me.custommobs.CustomMobsPlugin;
import me.custommobs.Text;
import me.custommobs.boss.BossManager;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Gestor de dungeons: mundo vacío, cola del lobby, instancias activas, cooldowns y récords.
 * Equivalente a BossManager pero para las partidas de dungeon.
 */
public final class DungeonManager {

    public static final String PREFIX = "<dark_gray>[</dark_gray><gradient:#ff4d4d:#ffb347><bold>Dungeon</bold></gradient><dark_gray>]</dark_gray> ";

    private record Pending(Location loc, GameMode mode) {}

    private final CustomMobsPlugin plugin;
    private final BossManager bosses;
    public final NamespacedKey mobKey;
    public final NamespacedKey eliteKey;

    private DungeonConfig cfg;
    private DungeonData data;
    private World world;
    private boolean enabled;
    private int nextId = 1;
    private int countdown = -1;
    private BukkitTask queueTask;

    private final Map<Integer, DungeonInstance> slots = new HashMap<>();
    private final Map<UUID, DungeonInstance> byPlayer = new HashMap<>();
    private final Map<UUID, DungeonInstance> byMob = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Set<UUID> queue = new LinkedHashSet<>();

    public DungeonManager(CustomMobsPlugin plugin, BossManager bosses) {
        this.plugin = plugin;
        this.bosses = bosses;
        this.mobKey = new NamespacedKey(plugin, "dungeon_mob");
        this.eliteKey = new NamespacedKey(plugin, "dungeon_elite");
    }

    // ------------------------------------------------------------------ arranque / apagado

    public void enable() {
        cfg = new DungeonConfig(plugin);
        data = new DungeonData(plugin);

        PluginCommand pc = plugin.getCommand("dungeon");
        if (pc != null) {
            DungeonCommand cmd = new DungeonCommand(plugin, this);
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        } else {
            plugin.getLogger().warning("Falta el comando 'dungeon' en plugin.yml.");
        }

        if (!cfg.b("enabled")) {
            plugin.getLogger().info("Dungeon desactivada en dungeon.yml.");
            return;
        }
        if (!createWorld()) return;
        enabled = true;
        Bukkit.getPluginManager().registerEvents(new DungeonListener(plugin, this), plugin);
        queueTask = Bukkit.getScheduler().runTaskTimer(plugin, this::queueTick, 20L, 20L);
        plugin.getLogger().info("Dungeon lista (mundo '" + world.getName() + "').");
    }

    public void shutdown() {
        if (queueTask != null) {
            queueTask.cancel();
            queueTask = null;
        }
        for (DungeonInstance inst : new ArrayList<>(slots.values())) inst.shutdown(true);
        slots.clear();
        byPlayer.clear();
        byMob.clear();
        queue.clear();
        if (world != null) {
            for (Player p : new ArrayList<>(world.getPlayers())) {
                p.setGameMode(GameMode.SURVIVAL);
                p.teleport(fallback());
            }
            String name = world.getName();
            if (Bukkit.unloadWorld(world, false)) {
                deleteFolder(new File(Bukkit.getWorldContainer(), name));
            }
            world = null;
        }
        if (data != null) data.save();
    }

    /** Recarga dungeon.yml (no afecta a partidas en curso). */
    public void reload() {
        cfg.reload();
    }

    private boolean createWorld() {
        String name = cfg.s("world");
        World existing = Bukkit.getWorld(name);
        if (existing == null) {
            String main = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
            if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")
                    || name.equalsIgnoreCase(main)) {
                plugin.getLogger().severe("dungeon.yml: nombre de mundo no válido ('" + name + "').");
                return false;
            }
            // mundo desechable: se limpia por si quedó algo de una caída anterior
            deleteFolder(new File(Bukkit.getWorldContainer(), name));
            WorldCreator wc = new WorldCreator(name);
            wc.generator(new VoidGenerator());
            wc.environment(World.Environment.NORMAL);
            wc.generateStructures(false);
            existing = wc.createWorld();
        }
        if (existing == null) {
            plugin.getLogger().severe("No se pudo crear el mundo de la dungeon.");
            return false;
        }
        world = existing;
        world.setAutoSave(false);
        world.setDifficulty(Difficulty.HARD);
        world.setPVP(false);
        world.setStorm(false);
        world.setThundering(false);
        world.setSpawnLocation(0, 100, 0);
        return true;
    }

    private void deleteFolder(File dir) {
        if (!dir.exists()) return;
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // se reintentará en el siguiente arranque
                }
            });
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo limpiar " + dir.getName() + ": " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ acceso

    public CustomMobsPlugin plugin() {
        return plugin;
    }

    public BossManager bosses() {
        return bosses;
    }

    public DungeonConfig cfg() {
        return cfg;
    }

    public DungeonData data() {
        return data;
    }

    public World world() {
        return world;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isDungeonWorld(World w) {
        return world != null && w != null && w.equals(world);
    }

    public DungeonInstance of(Player p) {
        return byPlayer.get(p.getUniqueId());
    }

    public DungeonInstance byMob(UUID id) {
        return byMob.get(id);
    }

    public void indexMob(UUID id, DungeonInstance inst) {
        byMob.put(id, inst);
    }

    public void unindexMob(UUID id) {
        if (id != null) byMob.remove(id);
    }

    public void dropMobs(DungeonInstance inst) {
        byMob.values().removeIf(i -> i == inst);
    }

    public void unmapPlayer(UUID id) {
        byPlayer.remove(id);
    }

    public void addPending(UUID id, Location loc, GameMode mode) {
        pending.put(id, new Pending(loc == null ? fallback() : loc, mode));
    }

    public void instanceClosed(DungeonInstance inst) {
        slots.remove(inst.slot());
    }

    public List<DungeonInstance> instances() {
        return new ArrayList<>(slots.values());
    }

    public int queueSize() {
        return queue.size();
    }

    public boolean inQueue(UUID id) {
        return queue.contains(id);
    }

    // ------------------------------------------------------------------ ubicaciones

    public Location lobbyLocation() {
        String s = data.lobby();
        if (s == null || s.isBlank()) return null;
        try {
            String[] p = s.split(";");
            World w = Bukkit.getWorld(p[0]);
            if (w == null) return null;
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public void setLobby(Location l) {
        data.lobby(l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch());
    }

    /** Dónde vuelve el jugador al terminar: el lobby si existe, o el sitio desde el que entró. */
    public Location returnPoint(Player p) {
        Location lobby = lobbyLocation();
        return lobby != null ? lobby : p.getLocation().clone();
    }

    public Location fallback() {
        Location lobby = lobbyLocation();
        if (lobby != null) return lobby;
        for (World w : Bukkit.getWorlds()) {
            if (!w.equals(world)) return w.getSpawnLocation();
        }
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    // ------------------------------------------------------------------ cooldown y equipo

    public void applyCooldown(UUID id, long minutes) {
        Player p = Bukkit.getPlayer(id);
        if (p != null && p.hasPermission("dungeon.admin")) return;
        data.setCooldown(id, minutes);
    }

    /** Devuelve el motivo por el que el equipo no sirve contra Vorthak, o null si está bien. */
    public String gearProblem(Player p) {
        if (!cfg.b("lobby.require-gear") || !plugin.getConfig().getBoolean("gear.enabled", true)) return null;
        int minProt = plugin.getConfig().getInt("gear.min-protection", 3);
        int minSharp = plugin.getConfig().getInt("gear.min-sharpness", 5);
        boolean nether = plugin.getConfig().getBoolean("gear.allow-netherite", true);
        int nSharp = plugin.getConfig().getInt("gear.netherite-sword-min-sharpness", 0);

        Material[] dia = {Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS};
        Material[] net = {Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS};
        PlayerInventory inv = p.getInventory();
        ItemStack[] pieces = {inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()};
        for (int i = 0; i < 4; i++) {
            ItemStack it = pieces[i];
            boolean ok = it != null && (it.getType() == dia[i] || (nether && it.getType() == net[i]));
            if (!ok) return nether ? "Necesitas armadura de diamante o netherite completa" : "Necesitas armadura de diamante completa";
            if (it.getEnchantmentLevel(Enchantment.PROTECTION) < minProt) {
                return "Toda tu armadura necesita Protección " + minProt + "+";
            }
        }
        for (ItemStack it : inv.getContents()) {
            if (it == null) continue;
            if (it.getType() == Material.DIAMOND_SWORD && it.getEnchantmentLevel(Enchantment.SHARPNESS) >= minSharp) return null;
            if (nether && it.getType() == Material.NETHERITE_SWORD
                    && (nSharp <= 0 || it.getEnchantmentLevel(Enchantment.SHARPNESS) >= nSharp)) return null;
        }
        return nether ? "Necesitas una espada de diamante (Afilado " + minSharp + "+) o de netherite en el inventario"
                : "Necesitas una espada de diamante (Afilado " + minSharp + "+) en el inventario";
    }

    // ------------------------------------------------------------------ cola del lobby

    private void msg(Player p, String mm) {
        p.sendMessage(Text.mm(PREFIX + mm));
    }

    private void msgQueue(String mm) {
        for (UUID u : queue) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) msg(p, mm);
        }
    }

    /** @return mensaje de error, o null si se unió. */
    public String join(Player p) {
        if (!enabled) return "La dungeon no está disponible.";
        if (byPlayer.containsKey(p.getUniqueId())) return "Ya estás dentro de una dungeon.";
        if (queue.contains(p.getUniqueId())) return "Ya estás en la cola.";
        if (isDungeonWorld(p.getWorld())) return "No puedes unirte desde dentro de la dungeon.";
        long left = data.cooldownLeftMillis(p.getUniqueId());
        if (left > 0 && !p.hasPermission("dungeon.admin")) {
            long m = left / 60000;
            return "Debes esperar " + (m >= 1 ? m + " min" : (left / 1000) + " s") + " para volver a entrar.";
        }
        String gear = gearProblem(p);
        if (gear != null) return gear + ".";
        int max = cfg.i("lobby.max-players");
        if (queue.size() >= max) return "La cola está llena (" + max + " jugadores).";
        queue.add(p.getUniqueId());
        int min = cfg.i("lobby.min-players");
        msgQueue("<white>" + p.getName() + "</white> <gray>se une a la dungeon</gray> <dark_gray>(" + queue.size() + "/" + max + ")</dark_gray>"
                + (queue.size() < min ? " <gray>— faltan <white>" + (min - queue.size()) + "</white> jugador(es)</gray>" : ""));
        return null;
    }

    public String leaveQueue(Player p) {
        if (!queue.remove(p.getUniqueId())) return "No estás en la cola.";
        msg(p, "<gray>Has salido de la cola.</gray>");
        msgQueue("<white>" + p.getName() + "</white> <gray>ha salido de la cola.</gray>");
        return null;
    }

    /** Adelanta el comienzo (cuenta atrás de 3 s) si ya hay jugadores suficientes. */
    public String forceStart(Player p) {
        if (!queue.contains(p.getUniqueId())) {
            String err = join(p);
            if (err != null) return err;
        }
        int min = cfg.i("lobby.min-players");
        if (queue.size() < min) return "Faltan jugadores: mínimo " + min + ".";
        countdown = 3;
        return null;
    }

    private void queueTick() {
        queue.removeIf(u -> Bukkit.getPlayer(u) == null);
        int min = cfg.i("lobby.min-players");
        int max = cfg.i("lobby.max-players");
        if (queue.isEmpty() || queue.size() < min) {
            countdown = -1;
            return;
        }
        if (countdown < 0) {
            countdown = cfg.i("lobby.countdown-seconds");
            msgQueue("<green>La dungeon empieza en <white>" + countdown + "s</white>.</green> <gray>Usa <white>/dungeon start</white> para adelantarlo.</gray>");
        }
        if (queue.size() >= max && countdown > 5) countdown = 5;
        if (countdown <= 0) {
            countdown = -1;
            launch();
            return;
        }
        if (countdown <= 5 || countdown == 10 || countdown == 20) {
            for (UUID u : queue) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) continue;
                p.showTitle(Title.title(Text.mm("<yellow><bold>" + countdown + "</bold></yellow>"),
                        Text.mm("<gray>La dungeon se está formando</gray>"),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(100))));
            }
        }
        countdown--;
    }

    private void launch() {
        List<Player> players = new ArrayList<>();
        for (UUID u : queue) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) players.add(p);
        }
        if (players.size() < cfg.i("lobby.min-players")) return;
        int slot = -1;
        for (int i = 0; i < Math.max(1, cfg.i("max-instances")); i++) {
            if (!slots.containsKey(i)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            msgQueue("<yellow>Todas las dungeons están ocupadas. Esperando un hueco...</yellow>");
            countdown = 5;
            return;
        }
        queue.clear();
        Modifier mod = Modifier.today(cfg.s("modifiers.force"), cfg.b("modifiers.enabled"));
        DungeonInstance inst = new DungeonInstance(plugin, this, nextId++, slot, players, mod);
        slots.put(slot, inst);
        for (Player p : players) {
            byPlayer.put(p.getUniqueId(), inst);
            msg(p, "<gray>Formando la dungeon con <white>" + players.size() + "</white> jugador(es)... no entrará nadie más.</gray>");
        }
        inst.begin();
    }

    // ------------------------------------------------------------------ admin

    public void resetAll() {
        for (DungeonInstance inst : new ArrayList<>(slots.values())) inst.close();
        queue.clear();
        countdown = -1;
    }

    public String leave(Player p) {
        DungeonInstance inst = byPlayer.get(p.getUniqueId());
        if (inst != null) {
            inst.leave(p);
            return null;
        }
        return leaveQueue(p);
    }

    // ------------------------------------------------------------------ conexión

    public void handleJoin(Player p) {
        Pending pd = pending.remove(p.getUniqueId());
        if (pd != null) {
            p.setGameMode(pd.mode());
            p.teleport(pd.loc());
            msg(p, "<gray>La dungeon terminó mientras estabas desconectado.</gray>");
            return;
        }
        DungeonInstance inst = byPlayer.get(p.getUniqueId());
        if (inst != null) {
            inst.rejoin(p);
            return;
        }
        if (isDungeonWorld(p.getWorld())) {
            p.setGameMode(GameMode.SURVIVAL);
            p.teleport(fallback());
        }
    }

    public void handleQuit(Player p) {
        queue.remove(p.getUniqueId());
        DungeonInstance inst = byPlayer.get(p.getUniqueId());
        if (inst != null) inst.playerQuit(p);
    }
}
