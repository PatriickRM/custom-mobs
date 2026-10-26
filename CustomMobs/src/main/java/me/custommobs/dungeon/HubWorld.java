package me.custommobs.dungeon;

import me.custommobs.CustomMobsPlugin;
import me.custommobs.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Matrix4f;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Hub de la dungeon: una isla flotante decorada en un mundo vacío propio donde esperan los jugadores.
 * Tiene un pedestal por cada jefe de {@link BossDef#ALL}, el Santuario de Ecos, un Salón de la Fama y
 * un cristal central con el estado de la cola. El mundo es desechable: se construye al arrancar y se borra al apagar.
 */
final class HubWorld {

    private static final int R = 24;
    private static final int Y = 64;

    private static final class Pedestal {
        final BossDef def;
        final int x;
        final int z;
        ItemDisplay icon;
        float angle;

        Pedestal(BossDef def, int x, int z) {
            this.def = def;
            this.x = x;
            this.z = z;
        }
    }

    /** Titular del menú de jefe. */
    static final class Menu implements InventoryHolder {
        Inventory inv;
        BossDef def;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int SHRINE_X = 13;
    private static final int SHRINE_Z = 4;

    private final CustomMobsPlugin plugin;
    private final DungeonManager manager;
    private World world;
    private Location spawn;
    private final List<Pedestal> pedestals = new ArrayList<>();
    private TextDisplay queueText;
    private TextDisplay fameText;
    private BukkitTask slow;
    private BukkitTask fast;
    private long ticks;

    HubWorld(DungeonManager manager) {
        this.manager = manager;
        this.plugin = manager.plugin();
    }

    World world() {
        return world;
    }

    Location spawn() {
        return spawn == null ? null : spawn.clone();
    }

    boolean isHub(World w) {
        return world != null && w != null && w.equals(world);
    }

    // ------------------------------------------------------------------ creación

    boolean create() {
        String name = manager.cfg().s("hub.world");
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")
                || name.equalsIgnoreCase(manager.cfg().s("world"))) {
            plugin.getLogger().warning("dungeon.yml: nombre de mundo del hub no válido ('" + name + "').");
            return false;
        }
        World existing = Bukkit.getWorld(name);
        if (existing == null) {
            manager.deleteWorldFolder(new File(Bukkit.getWorldContainer(), name));
            WorldCreator wc = new WorldCreator(name);
            wc.generator(new VoidGenerator());
            wc.environment(World.Environment.NORMAL);
            wc.generateStructures(false);
            existing = wc.createWorld();
        }
        if (existing == null) {
            plugin.getLogger().warning("No se pudo crear el mundo del hub.");
            return false;
        }
        world = existing;
        world.setAutoSave(false);
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setPVP(false);
        world.setStorm(false);
        world.setThundering(false);
        world.setTime(18000);
        spawn = new Location(world, 0.5, Y + 1, 17.5, 180f, 0f);
        world.setSpawnLocation(0, Y + 1, 17);
        for (BossDef b : BossDef.ALL) {
            if (pedestals.size() >= 5) break;
            pedestals.add(new Pedestal(b, 0, 0));
        }
        layoutPedestals();
        build();
        decorate();
        fast = Bukkit.getScheduler().runTaskTimer(plugin, this::fastTick, 20L, 20L);
        slow = Bukkit.getScheduler().runTaskTimer(plugin, this::slowTick, 40L, 100L);
        plugin.getLogger().info("Hub de la dungeon listo (mundo '" + world.getName() + "').");
        return true;
    }

    void shutdown() {
        if (fast != null) fast.cancel();
        if (slow != null) slow.cancel();
        fast = null;
        slow = null;
        if (world == null) return;
        Location out = Bukkit.getWorlds().get(0).getSpawnLocation();
        for (Player p : new ArrayList<>(world.getPlayers())) {
            p.setGameMode(GameMode.SURVIVAL);
            p.teleport(out);
        }
        String name = world.getName();
        if (Bukkit.unloadWorld(world, false)) {
            manager.deleteWorldFolder(new File(Bukkit.getWorldContainer(), name));
        }
        world = null;
    }

    private void layoutPedestals() {
        int n = pedestals.size();
        List<Pedestal> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(n == 1 ? 0.0 : -50.0 + 100.0 * i / (n - 1));
            int x = (int) Math.round(15 * Math.sin(a));
            int z = (int) Math.round(-15 * Math.cos(a));
            out.add(new Pedestal(pedestals.get(i).def, x, z));
        }
        pedestals.clear();
        pedestals.addAll(out);
    }

    // ------------------------------------------------------------------ construcción

    private void put(int x, int y, int z, Material m) {
        world.getBlockAt(x, y, z).setType(m, false);
    }

    private Material floor(int x, int z, double d) {
        if (d < 3.5) return Material.CRYING_OBSIDIAN;
        if (d < 4.5) return Material.GILDED_BLACKSTONE;
        if (d < 6.0) return Material.POLISHED_BLACKSTONE;
        if (d >= 9.5 && d < 10.5) return Material.GILDED_BLACKSTONE;
        if (d >= 20.5 && d < 21.5) return Material.CHISELED_POLISHED_BLACKSTONE;
        if (d >= 21.5) return Material.POLISHED_BLACKSTONE;
        if (Math.floorMod(x, 6) == 0 && Math.floorMod(z, 6) == 0) return Material.SHROOMLIGHT;
        return ((x + z) & 1) == 0 ? Material.DEEPSLATE_TILES : Material.POLISHED_BLACKSTONE_BRICKS;
    }

    private void build() {
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                double d = Math.sqrt((double) x * x + (double) z * z);
                if (d > R) continue;
                put(x, Y, z, floor(x, z, d));
                int depth = (int) ((R - d) * 0.55) + 2;
                for (int k = 1; k <= depth; k++) {
                    boolean rough = Math.floorMod(x * 31 + z * 17 + k * 7, 5) == 0;
                    put(x, Y - k, z, k >= depth - 1 ? Material.BLACKSTONE : rough ? Material.COBBLED_DEEPSLATE : Material.DEEPSLATE);
                }
                if (d >= 23.0) {
                    put(x, Y + 1, z, Material.POLISHED_BLACKSTONE_BRICKS);
                    put(x, Y + 2, z, Material.POLISHED_BLACKSTONE_BRICKS);
                }
            }
        }
        // pilares con linternas en el borde
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30.0);
            int px = (int) Math.round(23.0 * Math.cos(a));
            int pz = (int) Math.round(23.0 * Math.sin(a));
            for (int y = Y + 1; y <= Y + 6; y++) put(px, y, pz, Material.POLISHED_BLACKSTONE_BRICKS);
            put(px, Y + 7, pz, Material.SOUL_LANTERN);
        }
        // altar central
        put(0, Y + 1, 0, Material.CHISELED_POLISHED_BLACKSTONE);
        put(0, Y + 2, 0, Material.CRYING_OBSIDIAN);
        int[][] posts = {{5, 5}, {-5, 5}, {5, -5}, {-5, -5}};
        for (int[] p : posts) {
            for (int y = Y + 1; y <= Y + 3; y++) put(p[0], y, p[1], Material.POLISHED_BLACKSTONE_BRICKS);
            put(p[0], Y + 4, p[1], Material.SOUL_CAMPFIRE);
        }
        // pedestales de jefe
        for (Pedestal pd : pedestals) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) put(pd.x + dx, Y, pd.z + dz, Material.BLACKSTONE);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) put(pd.x + dx, Y, pd.z + dz, Material.POLISHED_BLACKSTONE_BRICKS);
            }
            put(pd.x, Y, pd.z, Material.GILDED_BLACKSTONE);
            put(pd.x, Y + 1, pd.z, pd.def.available() ? Material.CHISELED_POLISHED_BLACKSTONE : Material.OBSIDIAN);
            put(pd.x - 2, Y + 1, pd.z, pd.def.available() ? Material.SOUL_CAMPFIRE : Material.SOUL_LANTERN);
            put(pd.x + 2, Y + 1, pd.z, pd.def.available() ? Material.SOUL_CAMPFIRE : Material.SOUL_LANTERN);
        }
        // santuario de Ecos
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) put(SHRINE_X + dx, Y, SHRINE_Z + dz, Material.GILDED_BLACKSTONE);
        }
        put(SHRINE_X, Y + 1, SHRINE_Z, Material.LODESTONE);
        put(SHRINE_X - 1, Y + 1, SHRINE_Z - 1, Material.SOUL_LANTERN);
        put(SHRINE_X + 1, Y + 1, SHRINE_Z + 1, Material.SOUL_LANTERN);
        // salón de la fama
        put(-13, Y, 4, Material.GILDED_BLACKSTONE);
        for (int y = Y + 1; y <= Y + 3; y++) put(-13, y, 4, Material.POLISHED_BLACKSTONE_BRICKS);
        put(-13, Y + 4, 4, Material.SOUL_LANTERN);
        // luz suave (bloques de luz invisibles)
        var light = Bukkit.createBlockData(Material.LIGHT, "[level=12]");
        for (int x = -18; x <= 18; x += 6) {
            for (int z = -18; z <= 18; z += 6) {
                if (Math.sqrt((double) x * x + (double) z * z) > 21) continue;
                world.getBlockAt(x, Y + 3, z).setBlockData(light, false);
            }
        }
    }

    // ------------------------------------------------------------------ decoración (entidades)

    private TextDisplay text(double x, double y, double z, String mm, float scale) {
        return world.spawn(new Location(world, x, y, z), TextDisplay.class, td -> {
            td.text(Text.mm(mm));
            td.setBillboard(Display.Billboard.CENTER);
            td.setShadowed(true);
            td.setDefaultBackground(false);
            td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            td.setPersistent(false);
            td.setViewRange(2.0f);
            td.setTransformationMatrix(new Matrix4f().scale(scale));
        });
    }

    private void decorate() {
        // cristal central
        world.spawn(new Location(world, 0.5, Y + 4.0, 0.5), EnderCrystal.class, c -> {
            c.setShowingBottom(false);
            c.setInvulnerable(true);
            c.setPersistent(false);
        });
        text(0.5, Y + 8.0, 0.5, "<gradient:#ff4d4d:#ffb347><bold>DUNGEON DE VORTHAK</bold></gradient>", 1.6f);
        queueText = text(0.5, Y + 7.0, 0.5, "<gray>Cola: <white>0</white></gray>", 1.0f);
        for (Pedestal pd : pedestals) {
            BossDef b = pd.def;
            text(pd.x + 0.5, Y + 4.3, pd.z + 0.5, b.color() + "<bold>" + b.title() + "</bold>", 1.5f);
            text(pd.x + 0.5, Y + 3.7, pd.z + 0.5, b.available() ? "<green>▸ Clic derecho para entrar</green>" : "<dark_gray>Sellado</dark_gray>", 0.9f);
            ItemStack it = new ItemStack(b.icon());
            pd.icon = world.spawn(new Location(world, pd.x + 0.5, Y + 2.9, pd.z + 0.5), ItemDisplay.class, d -> {
                d.setItemStack(it);
                d.setPersistent(false);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(20);
                d.setTransformationMatrix(new Matrix4f().scale(1.3f));
            });
        }
        text(SHRINE_X + 0.5, Y + 3.3, SHRINE_Z + 0.5, "<aqua><bold>Santuario de Ecos</bold></aqua>", 1.3f);
        text(SHRINE_X + 0.5, Y + 2.8, SHRINE_Z + 0.5, "<gray>Clic derecho: mejoras permanentes</gray>", 0.8f);
        fameText = text(-12.5, Y + 6.0, 4.5, "", 1.0f);
        text(-12.5, Y + 7.6, 4.5, "<gold><bold>✦ Salón de la Fama ✦</bold></gold>", 1.3f);
        refreshFame();
        text(0.5, Y + 3.2, 14.5, "<gray>Elige un jefe en los pedestales del norte</gray>", 1.0f);
    }

    private void refreshFame() {
        if (fameText == null || !fameText.isValid()) return;
        List<DungeonData.Record> top = manager.data().top(3);
        StringBuilder sb = new StringBuilder();
        if (top.isEmpty()) sb.append("<gray>Aún no hay récords</gray>");
        String[] medal = {"<gold>①", "<white>②", "<#cd7f32>③"};
        for (int i = 0; i < top.size(); i++) {
            DungeonData.Record r = top.get(i);
            if (i > 0) sb.append("\n");
            sb.append(medal[i]).append(" <white>").append(r.name()).append("</white> <gray>— ").append(r.bestScore()).append(" pts</gray>");
        }
        fameText.text(Text.mm(sb.toString()));
    }

    // ------------------------------------------------------------------ bucles

    private void fastTick() {
        if (world == null) return;
        ticks += 20;
        world.setTime(18000);
        if (queueText != null && queueText.isValid()) {
            int q = manager.queueSize();
            queueText.text(Text.mm(q <= 0 ? "<gray>Nadie en la cola</gray>"
                    : "<green>En cola: <white>" + q + "/" + manager.cfg().i("lobby.max-players") + "</white></green>"));
        }
        for (Pedestal pd : pedestals) {
            if (pd.icon != null && pd.icon.isValid()) {
                pd.angle += 90f;
                float bob = (float) Math.sin(ticks / 20.0 + pd.x) * 0.12f;
                pd.icon.setInterpolationDelay(0);
                pd.icon.setInterpolationDuration(20);
                pd.icon.setTransformationMatrix(new Matrix4f().translate(0f, bob, 0f)
                        .rotateY((float) Math.toRadians(pd.angle)).scale(1.3f));
            }
            if (pd.def.available()) {
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, pd.x + 0.5, Y + 2.2, pd.z + 0.5, 2, 0.4, 0.3, 0.4, 0.01);
            } else {
                world.spawnParticle(Particle.ASH, pd.x + 0.5, Y + 2.2, pd.z + 0.5, 4, 0.5, 0.4, 0.5, 0);
            }
        }
        world.spawnParticle(Particle.END_ROD, 0.5, Y + 3.0, 0.5, 4, 0.5, 1.2, 0.5, 0.01);
        world.spawnParticle(Particle.ENCHANT, SHRINE_X + 0.5, Y + 2.5, SHRINE_Z + 0.5, 6, 0.4, 0.4, 0.4, 0.5);
    }

    private void slowTick() {
        if (world == null) return;
        refreshFame();
    }

    // ------------------------------------------------------------------ interacción

    /** @return true si el clic fue sobre algo del hub. */
    boolean interact(Player p, Block b) {
        if (b == null || !isHub(b.getWorld())) return false;
        for (Pedestal pd : pedestals) {
            if (b.getX() == pd.x && b.getZ() == pd.z && b.getY() == Y + 1) {
                openBoss(p, pd.def);
                return true;
            }
        }
        if (b.getX() == SHRINE_X && b.getZ() == SHRINE_Z && b.getY() == Y + 1) {
            new MetaSystem(manager).open(p);
            return true;
        }
        return false;
    }

    void openBoss(Player p, BossDef def) {
        if (!def.available()) {
            p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 0.7f);
            p.sendMessage(Text.mm(DungeonManager.PREFIX + "<gray>Este camino está <dark_gray>sellado</dark_gray>. Algo despertará aquí en el futuro.</gray>"));
            return;
        }
        Menu menu = new Menu();
        menu.def = def;
        Inventory inv = Bukkit.createInventory(menu, 27, Text.mm(def.color() + "<bold>⚔ " + def.title() + " ⚔</bold>"));
        menu.inv = inv;
        render(inv, p, def);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.1f);
    }

    private void render(Inventory inv, Player p, BossDef def) {
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.space());
        filler.setItemMeta(fm);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        boolean in = manager.inQueue(p.getUniqueId());
        List<String> lore = new ArrayList<>();
        for (String l : def.blurb()) lore.add("<gray>" + l);
        lore.add("");
        lore.add("<white>En cola: <green>" + manager.queueSize() + "<gray>/" + manager.cfg().i("lobby.max-players"));
        lore.add("<white>Mínimo para empezar: <yellow>" + manager.cfg().i("lobby.min-players"));
        lore.add("");
        lore.add(in ? "<yellow>✔ Ya estás en la cola" : "<green>▸ Clic para entrar en la cola");
        inv.setItem(13, item(def.icon(), def.color() + "<bold>" + def.title() + "</bold>", lore, in));
        inv.setItem(22, item(Material.BARRIER, "<red><bold>Salir de la cola</bold></red>", List.of(
                in ? "<gray>Clic para salir." : "<dark_gray>No estás en la cola."), false));
        inv.setItem(4, item(Material.CLOCK, "<white><bold>Adelantar el inicio</bold></white>", List.of(
                "<gray>Empieza ya con el grupo en cola.", "", in ? "<green>▸ Clic" : "<dark_gray>Entra primero en la cola."), false));
    }

    void click(Player p, Menu menu, int slot) {
        if (slot == 13) {
            String err = manager.join(p, menu.def);
            if (err != null) {
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
                p.sendMessage(Text.mm(DungeonManager.PREFIX + "<red>" + err + "</red>"));
            } else {
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.3f);
                p.sendMessage(Text.mm(DungeonManager.PREFIX + "<green>Estás en la cola de <white>" + menu.def.title() + "</white>.</green>"));
            }
        } else if (slot == 22) {
            String err = manager.leaveQueue(p);
            if (err != null) p.sendMessage(Text.mm(DungeonManager.PREFIX + "<red>" + err + "</red>"));
        } else if (slot == 4) {
            String err = manager.forceStart(p);
            if (err != null) p.sendMessage(Text.mm(DungeonManager.PREFIX + "<red>" + err + "</red>"));
            else {
                p.closeInventory();
                return;
            }
        } else {
            return;
        }
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getHolder() == menu) render(top, p, menu.def);
    }

    private static ItemStack item(Material m, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(s.isEmpty() ? Component.empty() : Text.item(s));
        meta.lore(l);
        if (glow) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }
}
