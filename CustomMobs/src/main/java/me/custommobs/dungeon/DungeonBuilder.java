package me.custommobs.dungeon;

import me.custommobs.CustomMobsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.PointedDripstone;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Genera la dungeon bloque a bloque con paleta de ceniza (deepslate, basalto, obsidiana, soul fire).
 * La construcción y el borrado se reparten en varios ticks para no congelar el servidor.
 */
public final class DungeonBuilder {

    /** Largo del pasillo entre salas. */
    public static final int CORRIDOR = 7;

    // ------------------------------------------------------------------ geometría

    public static final class Room {
        public final int index;
        public final RoomType type;
        public final int x0, x1, z0, z1;   // interior (inclusive)
        public final int floorY;           // capa del suelo; se camina en floorY + 1
        public final int height;           // alto interior
        public final int westWall, eastWall;
        public final int cz;
        /** Centros (x, z) de las placas del acertijo. */
        public final List<int[]> pads = new ArrayList<>();
        /** Posición del cofre de descanso. */
        public int[] chest;
        /** Puntos de anclaje de objetivos (cristales, faros...): centros (x, z) con el suelo ya marcado. */
        public final List<int[]> anchors = new ArrayList<>();

        Room(int index, RoomType type, int x0, int x1, int z0, int z1, int floorY, int height,
             int westWall, int eastWall, int cz) {
            this.index = index;
            this.type = type;
            this.x0 = x0;
            this.x1 = x1;
            this.z0 = z0;
            this.z1 = z1;
            this.floorY = floorY;
            this.height = height;
            this.westWall = westWall;
            this.eastWall = eastWall;
            this.cz = cz;
        }

        public int cx() {
            return (x0 + x1) / 2;
        }

        public int half() {
            return (x1 - x0) / 2;
        }

        public Location center(World w) {
            return new Location(w, cx() + 0.5, floorY + 1, cz + 0.5);
        }

        /** Punto de entrada (junto a la puerta oeste, mirando al este). */
        public Location entrance(World w) {
            return new Location(w, x0 + 2.5, floorY + 1, cz + 0.5, -90f, 0f);
        }

        public boolean contains(Location l) {
            int bx = l.getBlockX();
            int by = l.getBlockY();
            int bz = l.getBlockZ();
            return bx >= x0 && bx <= x1 && bz >= z0 && bz <= z1 && by >= floorY && by <= floorY + height + 1;
        }

        /** Bloques de la reja de la pared oeste (3x3). */
        public List<int[]> gateBlocks() {
            List<int[]> out = new ArrayList<>();
            for (int y = floorY + 1; y <= floorY + 3; y++) {
                for (int z = cz - 1; z <= cz + 1; z++) {
                    out.add(new int[]{westWall, y, z});
                }
            }
            return out;
        }
    }

    private record Op(int x, int y, int z, BlockData data, boolean physics) {}

    // ------------------------------------------------------------------ estado

    private final CustomMobsPlugin plugin;
    private final World world;
    private final DungeonConfig cfg;
    private final Random rnd;
    private final Biome biome;
    private final List<Room> rooms = new ArrayList<>();
    private final List<Op> ops = new ArrayList<>();
    private final Set<Long> ticketed = new HashSet<>();
    private final int oy;
    private int minX, maxX, minZ, maxZ;
    private int placed;
    private volatile boolean cancelled;
    private volatile boolean released;
    private boolean planned;

    public DungeonBuilder(CustomMobsPlugin plugin, World world, DungeonConfig cfg, List<RoomType> types,
                          int ox, int oy, int oz, long seed, Biome biome) {
        this.biome = biome == null ? Biome.ASHEN : biome;
        this.plugin = plugin;
        this.world = world;
        this.cfg = cfg;
        this.rnd = new Random(seed);
        this.oy = oy;

        int cursor = ox;
        int maxHalf = 0;
        for (int i = 0; i < types.size(); i++) {
            RoomType t = types.get(i);
            int size = cfg.roomSize(t);
            int half = (size - 1) / 2;
            maxHalf = Math.max(maxHalf, half);
            int x0 = cursor + 1;
            int x1 = cursor + size;
            rooms.add(new Room(i, t, x0, x1, oz - half, oz + half, oy, cfg.roomHeight(t), cursor, x1 + 1, oz));
            cursor = x1 + 1 + CORRIDOR + 1;
        }
        this.minX = ox - 2;
        this.maxX = cursor;
        this.minZ = oz - maxHalf - 3;
        this.maxZ = oz + maxHalf + 3;
    }

    public List<Room> rooms() {
        return rooms;
    }

    public double progress() {
        return ops.isEmpty() ? 0 : Math.min(1.0, placed / (double) ops.size());
    }

    // ------------------------------------------------------------------ colocación de bloques

    private void set(int x, int y, int z, Material m) {
        ops.add(new Op(x, y, z, biome.map(m).createBlockData(), false));
    }

    private void set(int x, int y, int z, BlockData d) {
        ops.add(new Op(x, y, z, themed(d), false));
    }

    /** Aplica la paleta del bioma conservando el estado del bloque (orientación, mitad...). */
    private BlockData themed(BlockData d) {
        Material to = biome.map(d.getMaterial());
        if (to == d.getMaterial()) return d;
        try {
            String s = d.getAsString();
            int i = s.indexOf('[');
            return Bukkit.createBlockData(to.getKey() + (i >= 0 ? s.substring(i) : ""));
        } catch (IllegalArgumentException ex) {
            return d;
        }
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    set(x, y, z, m);
                }
            }
        }
    }

    /** Registra una posición extra para que se borre al limpiar (cofre final, portal...). */
    public void track(int x, int y, int z) {
        ops.add(new Op(x, y, z, Material.AIR.createBlockData(), false));
    }

    // ------------------------------------------------------------------ paleta

    private Material wall() {
        int r = rnd.nextInt(100);
        if (r < 60) return Material.DEEPSLATE_BRICKS;
        if (r < 78) return Material.CRACKED_DEEPSLATE_BRICKS;
        if (r < 91) return Material.POLISHED_BLACKSTONE_BRICKS;
        if (r < 97) return Material.CRACKED_POLISHED_BLACKSTONE_BRICKS;
        return Material.BLACKSTONE;
    }

    private Material floor(int x, int z) {
        int r = rnd.nextInt(100);
        if (r < 7) return Material.CRACKED_DEEPSLATE_TILES;
        if (r < 11) return Material.BLACKSTONE;
        return ((x + z) & 1) == 0 ? Material.DEEPSLATE_TILES : Material.POLISHED_BLACKSTONE_BRICKS;
    }

    private Material ceiling() {
        return rnd.nextInt(100) < 12 ? Material.CRACKED_DEEPSLATE_TILES : Material.DEEPSLATE_TILES;
    }

    // ------------------------------------------------------------------ plan

    private void plan() {
        if (planned) return;
        planned = true;

        for (Room r : rooms) {
            shell(r);
            lights(r);
            decorate(r);
            style(r);
        }
        // pasillos
        for (int i = 0; i < rooms.size() - 1; i++) {
            corridor(rooms.get(i), rooms.get(i + 1));
        }
        // aperturas de puerta + marcos
        for (Room r : rooms) {
            if (r.index > 0) opening(r.westWall, r);
            if (r.index < rooms.size() - 1) opening(r.eastWall, r);
        }
        // rejas al final (con física para que se conecten a las paredes)
        for (Room r : rooms) {
            if (r.index == 0) continue;
            for (int[] b : r.gateBlocks()) {
                ops.add(new Op(b[0], b[1], b[2], Material.IRON_BARS.createBlockData(), true));
            }
        }
    }

    private void shell(Room r) {
        int x0 = r.x0 - 1, x1 = r.x1 + 1, z0 = r.z0 - 1, z1 = r.z1 + 1;
        int fy = r.floorY, cy = r.floorY + r.height + 1;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                set(x, fy, z, floor(x, z));
                set(x, cy, z, ceiling());
            }
        }
        for (int y = fy + 1; y < cy; y++) {
            for (int x = x0; x <= x1; x++) {
                set(x, y, z0, wall());
                set(x, y, z1, wall());
            }
            for (int z = z0 + 1; z < z1; z++) {
                set(x0, y, z, wall());
                set(x1, y, z, wall());
            }
        }
        // friso decorativo a media altura
        int band = fy + Math.min(3, r.height - 1);
        for (int x = x0; x <= x1; x++) {
            set(x, band, z0, Material.CHISELED_DEEPSLATE);
            set(x, band, z1, Material.CHISELED_DEEPSLATE);
        }
        for (int z = z0 + 1; z < z1; z++) {
            set(x0, band, z, Material.CHISELED_DEEPSLATE);
            set(x1, band, z, Material.CHISELED_DEEPSLATE);
        }
    }

    /** Bloque de luz invisible (nivel 15): ilumina sin verse. */
    private void glow(int x, int y, int z) {
        org.bukkit.block.data.type.Light l = (org.bukkit.block.data.type.Light) Material.LIGHT.createBlockData();
        l.setLevel(Math.max(1, Math.min(15, cfg.i("lighting.level"))));
        set(x, y, z, l);
    }

    /** Rejilla de luz invisible sobre la cabeza de jugadores y jefe: las salas ya no quedan a oscuras. */
    private void fillLight(Room r) {
        if (!cfg.b("lighting.enabled")) return;
        int y = r.floorY + Math.min(5, r.height - 1);
        for (int x = r.x0 + 1; x <= r.x1 - 1; x += 3) {
            for (int z = r.z0 + 1; z <= r.z1 - 1; z += 3) {
                glow(x, y, z);
            }
        }
    }

    private void lights(Room r) {
        fillLight(r);
        int cy = r.floorY + r.height + 1;
        for (int x = r.x0 + 2; x <= r.x1 - 2; x += 6) {
            for (int z = r.z0 + 2; z <= r.z1 - 2; z += 6) {
                set(x, cy, z, Material.SHROOMLIGHT);
                int lx = x + 3, lz = z + 3;
                if (lx <= r.x1 - 1 && lz <= r.z1 - 1) {
                    org.bukkit.block.data.type.Lantern lantern =
                            (org.bukkit.block.data.type.Lantern) Material.SOUL_LANTERN.createBlockData();
                    lantern.setHanging(true);
                    set(lx, cy - 1, lz, lantern);
                }
            }
        }
    }

    private void corridor(Room a, Room b) {
        int cz = a.cz;
        int xs = a.eastWall;      // incluye ambas paredes de sala
        int xe = b.westWall;
        int fy = a.floorY;
        for (int x = xs; x <= xe; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                set(x, fy, z, floor(x, z));
                set(x, fy + 5, z, ceiling());
            }
            for (int y = fy + 1; y <= fy + 4; y++) {
                set(x, y, cz - 2, wall());
                set(x, y, cz + 2, wall());
            }
        }
        int mid = (xs + xe) / 2;
        set(mid, fy + 5, cz, Material.SHROOMLIGHT);
        set(xs + 2, fy + 5, cz, Material.SHROOMLIGHT);
        set(xe - 2, fy + 5, cz, Material.SHROOMLIGHT);
        if (cfg.b("lighting.enabled")) for (int x = xs + 1; x < xe; x += 3) glow(x, fy + 3, cz);
    }

    /** Abre un hueco de 3x3 en una pared y le pone marco. */
    private void opening(int wallX, Room r) {
        int fy = r.floorY, cz = r.cz;
        for (int y = fy + 1; y <= fy + 3; y++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                set(wallX, y, z, Material.AIR);
            }
        }
        for (int z = cz - 2; z <= cz + 2; z++) {
            set(wallX, fy + 4, z, Material.CHISELED_POLISHED_BLACKSTONE);
        }
        for (int y = fy + 1; y <= fy + 3; y++) {
            set(wallX, y, cz - 2, Material.CHISELED_POLISHED_BLACKSTONE);
            set(wallX, y, cz + 2, Material.CHISELED_POLISHED_BLACKSTONE);
        }
    }

    // ------------------------------------------------------------------ decoración

    private void pillar(int x, int z, Room r) {
        int fy = r.floorY;
        int top = fy + r.height;
        for (int y = fy + 1; y <= top; y++) {
            set(x, y, z, Material.POLISHED_BASALT);
        }
        set(x, fy + 1, z, Material.CHISELED_POLISHED_BLACKSTONE);
        set(x, top, z, Material.CHISELED_POLISHED_BLACKSTONE);
        set(x, top - 1, z, Material.CRYING_OBSIDIAN);
    }

    private void brazier(int x, int z, Room r) {
        set(x, r.floorY + 1, z, Material.SOUL_SOIL);
        set(x, r.floorY + 2, z, Material.SOUL_FIRE);
    }

    private void cobwebs(Room r, int count) {
        int top = r.floorY + r.height;
        set(r.x0, top, r.z0, Material.COBWEB);
        set(r.x0, top, r.z1, Material.COBWEB);
        set(r.x1, top, r.z0, Material.COBWEB);
        set(r.x1, top, r.z1, Material.COBWEB);
        for (int i = 0; i < count; i++) {
            int x = r.x0 + rnd.nextInt(r.x1 - r.x0 + 1);
            boolean north = rnd.nextBoolean();
            set(x, top - rnd.nextInt(2), north ? r.z0 : r.z1, Material.COBWEB);
        }
    }

    private void emblem(Room r, double ringA, double ringB) {
        int cx = r.cx();
        for (int x = r.x0; x <= r.x1; x++) {
            for (int z = r.z0; z <= r.z1; z++) {
                double d = Math.hypot(x - cx, z - r.cz);
                int dx = Math.abs(x - cx), dz = Math.abs(z - r.cz);
                if (d < ringA - 1.2) {
                    set(x, r.floorY, z, ((x + z) & 1) == 0 ? Material.OBSIDIAN : Material.BLACKSTONE);
                } else if (Math.abs(d - ringA) < 0.75) {
                    set(x, r.floorY, z, Material.CRYING_OBSIDIAN);
                } else if (Math.abs(d - ringB) < 0.75) {
                    set(x, r.floorY, z, Material.GILDED_BLACKSTONE);
                } else if ((dx == 0 || dz == 0 || dx == dz) && d < ringB && d > ringA) {
                    set(x, r.floorY, z, Material.RED_NETHER_BRICKS);
                }
            }
        }
    }

    private void decorate(Room r) {
        int cx = r.cx();
        int h = r.half();
        switch (r.type) {
            case START -> {
                brazier(r.x0, r.z0, r);
                brazier(r.x0, r.z1, r);
                brazier(r.x1, r.z0, r);
                brazier(r.x1, r.z1, r);
                emblem(r, 3.5, 5.5);
                cobwebs(r, 2);
            }
            case WAVES -> {
                int o = h - 4;
                pillar(cx - o, r.cz - o, r);
                pillar(cx - o, r.cz + o, r);
                pillar(cx + o, r.cz - o, r);
                pillar(cx + o, r.cz + o, r);
                brazier(r.x0, r.z0, r);
                brazier(r.x0, r.z1, r);
                brazier(r.x1, r.z0, r);
                brazier(r.x1, r.z1, r);
                cobwebs(r, 6);
            }
            case TRAP -> {
                // rejilla de baldosas doradas en el suelo y estalactitas en el techo
                for (int x = r.x0; x <= r.x1; x += 4) {
                    for (int z = r.z0; z <= r.z1; z++) {
                        set(x, r.floorY, z, Material.GILDED_BLACKSTONE);
                    }
                }
                for (int z = r.z0; z <= r.z1; z += 4) {
                    for (int x = r.x0; x <= r.x1; x++) {
                        set(x, r.floorY, z, Material.GILDED_BLACKSTONE);
                    }
                }
                int top = r.floorY + r.height;
                for (int i = 0; i < 28; i++) {
                    int x = r.x0 + rnd.nextInt(r.x1 - r.x0 + 1);
                    int z = r.z0 + rnd.nextInt(r.z1 - r.z0 + 1);
                    PointedDripstone pd = (PointedDripstone) Material.POINTED_DRIPSTONE.createBlockData();
                    pd.setVerticalDirection(BlockFace.DOWN);
                    set(x, top, z, pd);
                }
                // balizas del objetivo (3): se activan quedándose encima
                int trapRad = Math.max(4, Math.min(8, h - 4));
                for (int k = 0; k < 3; k++) {
                    double a = Math.PI / 2 + k * (Math.PI * 2 / 3);
                    int px = cx + (int) Math.round(Math.cos(a) * trapRad);
                    int pz = r.cz + (int) Math.round(Math.sin(a) * trapRad);
                    r.pads.add(new int[]{px, pz});
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            set(px + dx, r.floorY, pz + dz, dx == 0 && dz == 0
                                    ? Material.CRYING_OBSIDIAN : Material.POLISHED_BLACKSTONE);
                        }
                    }
                }
                brazier(r.x0, r.z0, r);
                brazier(r.x1, r.z1, r);
                cobwebs(r, 4);
            }
            case PUZZLE -> {
                // 4 puntos de anclaje en cruz (norte, este, sur, oeste): fragmentos, pedestales o cristales según el acertijo
                int rad = Math.max(4, Math.min(8, h - 4));
                int[][] padOffs = {{0, -rad}, {rad, 0}, {0, rad}, {-rad, 0}};
                for (int k = 0; k < 4; k++) {
                    int px = cx + padOffs[k][0];
                    int pz = r.cz + padOffs[k][1];
                    r.pads.add(new int[]{px, pz});
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            set(px + dx, r.floorY, pz + dz, dx == 0 && dz == 0
                                    ? Material.GILDED_BLACKSTONE : Material.BLACKSTONE);
                        }
                    }
                }
                int o = h - 4;
                pillar(cx - o, r.cz - o, r);
                pillar(cx + o, r.cz + o, r);
                pillar(cx - o, r.cz + o, r);
                pillar(cx + o, r.cz - o, r);
                brazier(r.x0, r.z0, r);
                brazier(r.x1, r.z1, r);
                cobwebs(r, 5);
            }
            case ALTAR -> {
                // altar central con anillo de zona (radio ~6) y pilares
                emblem(r, 3.5, 6.5);
                set(cx, r.floorY + 1, r.cz, Material.SOUL_CAMPFIRE);
                int o = h - 3;
                for (int k = 0; k < 6; k++) {
                    double a = k * Math.PI / 3;
                    pillar(cx + (int) Math.round(Math.cos(a) * o), r.cz + (int) Math.round(Math.sin(a) * o), r);
                }
                brazier(r.x0, r.z0, r);
                brazier(r.x0, r.z1, r);
                brazier(r.x1, r.z0, r);
                brazier(r.x1, r.z1, r);
                cobwebs(r, 6);
            }
            case VAULT -> {
                // 4 reliquias (cofres) en las esquinas interiores; se abren canalizando junto a ellas
                int o = Math.max(4, h - 4);
                int[][] offs = {{-o, -o}, {o, -o}, {-o, o}, {o, o}};
                for (int[] of : offs) {
                    int px = cx + of[0];
                    int pz = r.cz + of[1];
                    r.pads.add(new int[]{px, pz});
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            set(px + dx, r.floorY, pz + dz, Material.GILDED_BLACKSTONE);
                        }
                    }
                    org.bukkit.block.data.type.Chest ch =
                            (org.bukkit.block.data.type.Chest) Material.CHEST.createBlockData();
                    ch.setFacing(of[0] < 0 ? BlockFace.EAST : BlockFace.WEST);
                    set(px, r.floorY + 1, pz, ch);
                }
                emblem(r, 2.5, 4.5);
                brazier(r.x0, r.z0, r);
                brazier(r.x0, r.z1, r);
                brazier(r.x1, r.z0, r);
                brazier(r.x1, r.z1, r);
                cobwebs(r, 10);
            }
            case EVENT -> {
                // pedestal central de obsidiana llorosa con una piedra de ofrenda, cuatro pilares y llamas del alma
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        set(cx + dx, r.floorY, r.cz + dz, Math.abs(dx) + Math.abs(dz) >= 4
                                ? Material.POLISHED_BLACKSTONE_BRICKS : Material.CRYING_OBSIDIAN);
                    }
                }
                set(cx, r.floorY + 1, r.cz, Material.LODESTONE);
                set(cx, r.floorY + 2, r.cz, Material.SOUL_LANTERN);
                int o = Math.max(4, h - 3);
                pillar(cx - o, r.cz - o, r);
                pillar(cx + o, r.cz + o, r);
                pillar(cx - o, r.cz + o, r);
                pillar(cx + o, r.cz - o, r);
                emblem(r, 3.5, 5.5);
                brazier(r.x0, r.z0, r);
                brazier(r.x0, r.z1, r);
                brazier(r.x1, r.z0, r);
                brazier(r.x1, r.z1, r);
                cobwebs(r, 7);
            }
            case MERCHANT -> {
                // plataforma del mercader: suelo de ladrillo con centro dorado, 4 farolas de almas y hoguera
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        set(cx + dx, r.floorY, r.cz + dz, dx == 0 && dz == 0
                                ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS);
                    }
                }
                for (int sx = -1; sx <= 1; sx += 2) {
                    for (int sz = -1; sz <= 1; sz += 2) {
                        set(cx + sx * 3, r.floorY + 1, r.cz + sz * 3, Material.POLISHED_BLACKSTONE_WALL);
                        set(cx + sx * 3, r.floorY + 2, r.cz + sz * 3, Material.SOUL_LANTERN);
                    }
                }
                set(cx, r.floorY + 1, r.cz, Material.SOUL_CAMPFIRE);
                emblem(r, 3.5, 5.5);
                brazier(r.x0, r.z0, r);
                brazier(r.x0, r.z1, r);
                brazier(r.x1, r.z0, r);
                brazier(r.x1, r.z1, r);
                cobwebs(r, 5);
            }
            case SWEEP -> {
                // 4 llaves en las esquinas y un faro en el centro
                int o = Math.max(5, h - 3);
                int[][] offs = {{-o, -o}, {o, -o}, {o, o}, {-o, o}};
                for (int[] of : offs) {
                    int px = cx + of[0];
                    int pz = r.cz + of[1];
                    r.pads.add(new int[]{px, pz});
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            set(px + dx, r.floorY, pz + dz, dx == 0 && dz == 0
                                    ? Material.CRYING_OBSIDIAN : Material.POLISHED_BLACKSTONE);
                        }
                    }
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        set(cx + dx, r.floorY, r.cz + dz, Material.OBSIDIAN);
                    }
                }
                for (int y = r.floorY + 1; y <= r.floorY + 3; y++) {
                    set(cx, y, r.cz, y == r.floorY + 3 ? Material.SEA_LANTERN : Material.POLISHED_BLACKSTONE);
                }
                brazier(r.x0, r.z0, r);
                brazier(r.x1, r.z1, r);
                cobwebs(r, 4);
            }
            case MINIBOSS -> {
                emblem(r, 5.5, 10.5);
                int o = h - 3;
                for (int k = 0; k < 8; k++) {
                    double a = k * Math.PI / 4;
                    pillar(cx + (int) Math.round(Math.cos(a) * o), r.cz + (int) Math.round(Math.sin(a) * o), r);
                }
                brazier(r.x0, r.z0, r);
                brazier(r.x0, r.z1, r);
                brazier(r.x1, r.z0, r);
                brazier(r.x1, r.z1, r);
                cobwebs(r, 8);
            }
            case REST -> {
                // plataforma clara con hoguera de almas y cofre
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        set(cx + dx, r.floorY, r.cz + dz, Material.POLISHED_BLACKSTONE_BRICKS);
                    }
                }
                set(cx, r.floorY + 1, r.cz, Material.SOUL_CAMPFIRE);
                org.bukkit.block.data.type.Chest chest =
                        (org.bukkit.block.data.type.Chest) Material.CHEST.createBlockData();
                chest.setFacing(BlockFace.NORTH);
                r.chest = new int[]{cx, r.floorY + 1, r.z1};
                set(r.chest[0], r.chest[1], r.chest[2], chest);
                brazier(r.x0, r.z0, r);
                brazier(r.x0, r.z1, r);
                brazier(r.x1, r.z0, r);
                brazier(r.x1, r.z1, r);
            }
            case VORTHAK -> {
                emblem(r, 6.5, 13.5);
                int o = h - 4;
                for (int k = 0; k < 8; k++) {
                    double a = k * Math.PI / 4;
                    pillar(cx + (int) Math.round(Math.cos(a) * o), r.cz + (int) Math.round(Math.sin(a) * o), r);
                }
                int ring = 12;
                for (int k = 0; k < 4; k++) {
                    double a = Math.PI / 4 + k * Math.PI / 2;
                    brazier(cx + (int) Math.round(Math.cos(a) * ring), r.cz + (int) Math.round(Math.sin(a) * ring), r);
                }
                brazier(r.x0, r.z0, r);
                brazier(r.x0, r.z1, r);
                brazier(r.x1, r.z0, r);
                brazier(r.x1, r.z1, r);
                cobwebs(r, 14);
            }
        }
    }

    // ------------------------------------------------------------------ diseño (molduras, pilastras, arañas, coliseo, trono)

    private void stair(int x, int y, int z, Material m, BlockFace facing, boolean top) {
        org.bukkit.block.data.type.Stairs d = (org.bukkit.block.data.type.Stairs) m.createBlockData();
        d.setFacing(facing);
        d.setHalf(top ? org.bukkit.block.data.Bisected.Half.TOP : org.bukkit.block.data.Bisected.Half.BOTTOM);
        set(x, y, z, d);
    }

    private void slab(int x, int y, int z, Material m, boolean top) {
        org.bukkit.block.data.type.Slab d = (org.bukkit.block.data.type.Slab) m.createBlockData();
        d.setType(top ? org.bukkit.block.data.type.Slab.Type.TOP : org.bukkit.block.data.type.Slab.Type.BOTTOM);
        set(x, y, z, d);
    }

    private void hangingLantern(int x, int y, int z) {
        org.bukkit.block.data.type.Lantern l = (org.bukkit.block.data.type.Lantern) Material.SOUL_LANTERN.createBlockData();
        l.setHanging(true);
        set(x, y, z, l);
    }

    /** Pasada final de diseño de cada sala (se coloca después de la decoración y por tanto manda sobre ella). */
    private void style(Room r) {
        cornice(r);
        ribs(r);
        pilasters(r);
        banners(r);
        chandeliers(r);
        switch (r.type) {
            case WAVES -> {
                coliseum(r);
                architecture(r);
            }
            case VORTHAK -> throne(r);
            default -> { }
        }
    }

    /** Moldura de escaleras invertidas donde la pared se une con el techo. */
    private void cornice(Room r) {
        if (r.height < 6) return;
        int top = r.floorY + r.height;
        Material m = Material.DEEPSLATE_BRICK_STAIRS;
        for (int x = r.x0; x <= r.x1; x++) {
            stair(x, top, r.z0, m, BlockFace.NORTH, true);
            stair(x, top, r.z1, m, BlockFace.SOUTH, true);
        }
        for (int z = r.z0 + 1; z <= r.z1 - 1; z++) {
            stair(r.x0, top, z, m, BlockFace.WEST, true);
            stair(r.x1, top, z, m, BlockFace.EAST, true);
        }
    }

    /** Nervaduras de losas que cruzan el techo cada 6 bloques (no se solapan con las luces). */
    private void ribs(Room r) {
        if (r.height < 6) return;
        int top = r.floorY + r.height;
        for (int x = r.x0 + 2; x <= r.x1 - 2; x += 6) {
            for (int z = r.z0 + 1; z <= r.z1 - 1; z++) {
                slab(x, top, z, Material.DEEPSLATE_BRICK_SLAB, true);
            }
        }
    }

    /** Pilastras de basalto pegadas a las paredes (dejan libres las puertas y el cofre de descanso). */
    private void pilasters(Room r) {
        for (int x = r.x0 + 2; x <= r.x1 - 2; x += 4) {
            for (int z : new int[]{r.z0, r.z1}) {
                if (r.chest != null && r.chest[0] == x && r.chest[2] == z) continue;   // no tapar el cofre
                pillar(x, z, r);
            }
        }
        for (int z = r.z0 + 2; z <= r.z1 - 2; z += 4) {
            if (Math.abs(z - r.cz) <= 3) continue;                                      // hueco de las puertas
            pillar(r.x0, z, r);
            pillar(r.x1, z, r);
        }
    }

    /** Estandartes carmesí colgados entre las pilastras de las paredes norte y sur. */
    private void banners(Room r) {
        if (r.height < 8) return;
        int fy = r.floorY;
        for (int x = r.x0 + 4; x <= r.x1 - 4; x += 4) {
            for (int z : new int[]{r.z0, r.z1}) {
                if (r.chest != null && r.chest[0] == x && r.chest[2] == z) continue;
                set(x, fy + 4, z, Material.GILDED_BLACKSTONE);
                for (int y = fy + 5; y <= fy + 7; y++) set(x, y, z, Material.RED_WOOL);
                set(x, fy + 8, z, Material.GILDED_BLACKSTONE);
            }
        }
    }

    /** Araña de almas: cadena de muro, cruz de losas y cinco linternas de fuego de alma colgando. */
    private void chandelier(Room r, int x, int z) {
        int top = r.floorY + r.height;
        set(x, top, z, Material.POLISHED_BLACKSTONE_WALL);
        set(x, top - 1, z, Material.POLISHED_BLACKSTONE_WALL);
        set(x, top - 2, z, Material.POLISHED_BLACKSTONE_BRICKS);
        hangingLantern(x, top - 3, z);
        int[][] arms = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] a : arms) {
            slab(x + a[0], top - 2, z + a[1], Material.POLISHED_BLACKSTONE_BRICK_SLAB, true);
            hangingLantern(x + a[0], top - 3, z + a[1]);
        }
    }

    private void chandeliers(Room r) {
        if (r.height < 8) return;
        int cx = r.cx();
        if (r.type == RoomType.START || r.type == RoomType.REST || r.type == RoomType.MERCHANT
                || r.type == RoomType.EVENT) {
            chandelier(r, cx, r.cz);
            return;
        }
        int q = Math.max(3, r.half() / 2);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                chandelier(r, cx + sx * q, r.cz + sz * q);
            }
        }
    }

    /** Sala de oleadas: arena de coliseo con anillos rúnicos en el suelo y bancos de piedra en las gradas. */
    private void coliseum(Room r) {
        emblem(r, 4.5, 9.0);
        int fy = r.floorY;
        for (int x = r.x0 + 1; x <= r.x1 - 1; x++) {
            if ((x - r.x0 - 2) % 4 == 0) continue;           // ahí van las pilastras
            stair(x, fy + 1, r.z0, Material.BLACKSTONE_STAIRS, BlockFace.NORTH, false);
            stair(x, fy + 1, r.z1, Material.BLACKSTONE_STAIRS, BlockFace.SOUTH, false);
        }
    }

    // ------------------------------------------------------------------ arquitectura de las arenas

    /** Pasillo despejado entre la puerta oeste y la este (y el sitio donde aparecen los jugadores). */
    private boolean lane(Room r, int x, int z) {
        return Math.abs(z - r.cz) <= 3 && (x - r.x0 < 8 || r.x1 - x < 8);
    }

    /** Un punto donde se puede construir: lejos de paredes, pasillos, centro y anclajes de objetivo. */
    private boolean free(Room r, int x, int z) {
        if (x < r.x0 + 3 || x > r.x1 - 3 || z < r.z0 + 3 || z > r.z1 - 3) return false;
        if (lane(r, x, z)) return false;
        if (Math.hypot(x - r.cx(), z - r.cz) < 7.0) return false;
        for (int[] a : r.anchors) {
            if (Math.hypot(x - a[0], z - a[1]) < 5.0) return false;
        }
        return true;
    }

    /** Columna de basalto con base y capitel; broken = parte superior desmoronada. */
    private void column(Room r, int x, int z, int h, boolean broken) {
        int fy = r.floorY;
        int top = Math.min(r.floorY + r.height, fy + h);
        for (int y = fy + 1; y <= top; y++) {
            boolean crack = broken && y > top - 2 && rnd.nextInt(3) == 0;
            set(x, y, z, crack ? Material.CRACKED_POLISHED_BLACKSTONE_BRICKS : Material.POLISHED_BASALT);
        }
        set(x, fy + 1, z, Material.CHISELED_POLISHED_BLACKSTONE);
        if (!broken) set(x, top, z, Material.CHISELED_POLISHED_BLACKSTONE);
    }

    /** Variante de arena al azar + anclajes de objetivo + cadenas colgantes. */
    private void architecture(Room r) {
        placeAnchors(r);
        Set<Long> taken = new HashSet<>();
        switch (rnd.nextInt(4)) {
            case 0 -> ruins(r, taken);
            case 1 -> terraces(r, taken);
            case 2 -> cathedral(r, taken);
            default -> ringArches(r, taken);
        }
        hangingChains(r);
    }

    private void placeAnchors(Room r) {
        double rad = Math.min(r.half() - 6.0, r.half() * 0.62);
        if (rad < 5.0) return;
        int[] angles = {-90, -35, 35, 90, 145, -145};
        int fy = r.floorY;
        for (int deg : angles) {
            double a = Math.toRadians(deg);
            int ax = r.cx() + (int) Math.round(Math.cos(a) * rad);
            int az = r.cz + (int) Math.round(Math.sin(a) * rad);
            r.anchors.add(new int[]{ax, az});
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int d = Math.max(Math.abs(dx), Math.abs(dz));
                    if (d == 2 && Math.abs(dx) + Math.abs(dz) == 4) continue;
                    set(ax + dx, fy, az + dz, d == 0 ? Material.CRYING_OBSIDIAN
                            : d == 1 ? Material.POLISHED_BLACKSTONE : Material.GILDED_BLACKSTONE);
                }
            }
        }
    }

    /** Ruinas: columnas rotas, muros bajos y escombros repartidos por la arena (cobertura). */
    private void ruins(Room r, Set<Long> taken) {
        int fy = r.floorY;
        int placed = 0;
        for (int tries = 0; tries < 260 && placed < 18; tries++) {
            int x = r.x0 + 3 + rnd.nextInt(Math.max(1, r.x1 - r.x0 - 5));
            int z = r.z0 + 3 + rnd.nextInt(Math.max(1, r.z1 - r.z0 - 5));
            int kind = rnd.nextInt(3);
            if (kind == 0) {
                if (!free(r, x, z) || taken.contains(key(x, z))) continue;
                column(r, x, z, 3 + rnd.nextInt(Math.max(1, r.height - 5)), true);
                taken.add(key(x, z));
            } else if (kind == 1) {
                boolean alongX = rnd.nextBoolean();
                int len = 4 + rnd.nextInt(5);
                boolean ok = true;
                for (int i = 0; i < len && ok; i++) {
                    int xx = alongX ? x + i : x, zz = alongX ? z : z + i;
                    ok = free(r, xx, zz) && !taken.contains(key(xx, zz));
                }
                if (!ok) continue;
                for (int i = 0; i < len; i++) {
                    int xx = alongX ? x + i : x, zz = alongX ? z : z + i;
                    int hgt = 2 + (i % 3 == 0 ? 1 : 0) + (rnd.nextInt(5) == 0 ? -1 : 0);
                    for (int y = 1; y <= hgt; y++) {
                        set(xx, fy + y, zz, rnd.nextInt(4) == 0 ? Material.CRACKED_DEEPSLATE_BRICKS : Material.DEEPSLATE_BRICKS);
                    }
                    taken.add(key(xx, zz));
                }
            } else {
                if (!free(r, x, z) || taken.contains(key(x, z))) continue;
                for (int dx = 0; dx <= 1; dx++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        if (rnd.nextInt(3) == 0) continue;
                        set(x + dx, fy + 1, z + dz, rnd.nextBoolean() ? Material.COBBLED_DEEPSLATE : Material.DEEPSLATE_BRICKS);
                        taken.add(key(x + dx, z + dz));
                    }
                }
            }
            placed++;
        }
    }

    /** Terrazas: cuatro plataformas elevadas en las esquinas con escalera hacia el centro. */
    private void terraces(Room r, Set<Long> taken) {
        int fy = r.floorY;
        int span = 9;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int px0 = sx < 0 ? r.x0 + 3 : r.x1 - 3 - (span - 1);
                int pz0 = sz < 0 ? r.z0 + 3 : r.z1 - 3 - (span - 1);
                if (px0 + span - 1 > r.x1 - 3 || pz0 + span - 1 > r.z1 - 3) continue;
                for (int x = px0; x < px0 + span; x++) {
                    for (int z = pz0; z < pz0 + span; z++) {
                        for (int y = 1; y <= 3; y++) {
                            boolean edge = x == px0 || x == px0 + span - 1 || z == pz0 || z == pz0 + span - 1;
                            set(x, fy + y, z, y == 3 ? (((x + z) & 1) == 0 ? Material.POLISHED_BLACKSTONE_BRICKS : Material.DEEPSLATE_TILES)
                                    : edge ? Material.DEEPSLATE_BRICKS : Material.COBBLED_DEEPSLATE);
                        }
                        taken.add(key(x, z));
                    }
                }
                // escalera de 3 peldaños por el lado que mira al centro (en Z), ancho 3
                int mx = px0 + span / 2;
                int stepZ = sz < 0 ? pz0 + span : pz0 - 1;       // primer bloque fuera de la plataforma
                int dir = sz < 0 ? 1 : -1;                        // hacia fuera del borde
                BlockFace face = sz < 0 ? BlockFace.SOUTH : BlockFace.NORTH;
                for (int k = 0; k < 3; k++) {
                    int zz = stepZ + dir * k;
                    int yy = fy + 3 - k;
                    for (int dx = -1; dx <= 1; dx++) {
                        stair(mx + dx, yy, zz, Material.POLISHED_BLACKSTONE_BRICK_STAIRS, face, false);
                        for (int y = fy + 1; y < yy; y++) set(mx + dx, y, zz, Material.COBBLED_DEEPSLATE);
                        taken.add(key(mx + dx, zz));
                    }
                }
                // faroles en las esquinas exteriores
                int ex = sx < 0 ? px0 : px0 + span - 1;
                int ez = sz < 0 ? pz0 : pz0 + span - 1;
                set(ex, fy + 4, ez, Material.POLISHED_BLACKSTONE_WALL);
                set(ex, fy + 5, ez, Material.SOUL_LANTERN);
            }
        }
    }

    /** Catedral: dos hileras de columnas altas a cada lado de una nave central. */
    private void cathedral(Room r, Set<Long> taken) {
        for (int side = -1; side <= 1; side += 2) {
            int z = r.cz + side * 9;
            for (int x = r.x0 + 10; x <= r.x1 - 10; x += 6) {
                if (!free(r, x, z) || !free(r, x + 1, z + 1)) continue;
                for (int dx = 0; dx <= 1; dx++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        column(r, x + dx, z + dz, r.height, false);
                        taken.add(key(x + dx, z + dz));
                    }
                }
                // capitel con linterna colgante
                int top = r.floorY + r.height;
                if (top - 2 > r.floorY) hangingLantern(x, top - 2, z - side);
            }
        }
        // altares bajos de ruinas en las esquinas
        int fy = r.floorY;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int x = sx < 0 ? r.x0 + 4 : r.x1 - 5;
                int z = sz < 0 ? r.z0 + 4 : r.z1 - 5;
                for (int dx = 0; dx <= 1; dx++) {
                    for (int dz = 0; dz <= 1; dz++) {
                        set(x + dx, fy + 1, z + dz, Material.POLISHED_BLACKSTONE_BRICKS);
                        taken.add(key(x + dx, z + dz));
                    }
                }
                set(x, fy + 2, z, Material.SOUL_CAMPFIRE);
            }
        }
    }

    /** Arcos: ocho arcos de piedra en anillo alrededor del centro. */
    private void ringArches(Room r, Set<Long> taken) {
        double rad = Math.min(r.half() - 8.0, r.half() * 0.42);
        if (rad < 7.5) return;
        int fy = r.floorY;
        for (int k = 0; k < 8; k++) {
            double a = Math.toRadians(22.5 + 45.0 * k);
            int x = r.cx() + (int) Math.round(Math.cos(a) * rad);
            int z = r.cz + (int) Math.round(Math.sin(a) * rad);
            boolean alongZ = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a));
            int h = Math.min(7, r.height - 3);
            boolean ok = true;
            for (int i = -2; i <= 2 && ok; i++) {
                int xx = alongZ ? x : x + i, zz = alongZ ? z + i : z;
                ok = free(r, xx, zz) || Math.hypot(xx - r.cx(), zz - r.cz) >= 7.0 && !lane(r, xx, zz)
                        && xx >= r.x0 + 3 && xx <= r.x1 - 3 && zz >= r.z0 + 3 && zz <= r.z1 - 3;
            }
            if (!ok) continue;
            int ax = alongZ ? x : x - 2, bx = alongZ ? x : x + 2;
            int az = alongZ ? z - 2 : z, bz = alongZ ? z + 2 : z;
            column(r, ax, az, h, false);
            column(r, bx, bz, h, false);
            for (int i = -2; i <= 2; i++) {
                int xx = alongZ ? x : x + i, zz = alongZ ? z + i : z;
                set(xx, fy + h + 1, zz, Math.abs(i) == 2 ? Material.CHISELED_POLISHED_BLACKSTONE
                        : i == 0 ? Material.GILDED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS);
                taken.add(key(xx, zz));
            }
            if (fy + h < fy + r.height) set(x, fy + h, alongZ ? z : z, Material.SOUL_LANTERN);
        }
    }

    /** Cadenas colgando del techo (con linterna a veces). */
    private void hangingChains(Room r) {
        int top = r.floorY + r.height;
        for (int i = 0; i < 22; i++) {
            int x = r.x0 + 2 + rnd.nextInt(Math.max(1, r.x1 - r.x0 - 3));
            int z = r.z0 + 2 + rnd.nextInt(Math.max(1, r.z1 - r.z0 - 3));
            int len = 2 + rnd.nextInt(4);
            for (int y = 0; y < len; y++) set(x, top - 1 - y, z, Material.CHAIN);
            if (rnd.nextInt(3) == 0) hangingLantern(x, top - 1 - len, z);
        }
    }

    /** Sala de Vorthak: rosetón exterior de runas y un trono de obsidiana contra la pared este. */
    private void throne(Room r) {
        int cx = r.cx();
        int fy = r.floorY;
        // anillo exterior de runas
        for (int x = r.x0; x <= r.x1; x++) {
            for (int z = r.z0; z <= r.z1; z++) {
                double d = Math.hypot(x - cx, z - r.cz);
                if (Math.abs(d - 17.0) < 0.7) set(x, fy, z, Material.CRYING_OBSIDIAN);
                else if (Math.abs(d - 16.0) < 0.5) set(x, fy, z, Material.GILDED_BLACKSTONE);
            }
        }
        // estrado bajo
        int tx = r.x1;
        for (int x = tx - 4; x <= tx; x++) {
            for (int dz = -4; dz <= 4; dz++) {
                slab(x, fy + 1, r.cz + dz, Material.POLISHED_BLACKSTONE_BRICK_SLAB, false);
            }
        }
        // respaldo con forma de cuernos
        for (int dz = -2; dz <= 2; dz++) {
            int topY = fy + 11 - 2 * Math.abs(dz);
            for (int y = fy + 1; y <= topY; y++) {
                set(tx, y, r.cz + dz, y == topY ? Material.CRYING_OBSIDIAN : Material.POLISHED_BLACKSTONE_BRICKS);
            }
        }
        set(tx, fy + 8, r.cz - 1, Material.GILDED_BLACKSTONE);
        set(tx, fy + 8, r.cz + 1, Material.GILDED_BLACKSTONE);
        // asiento y reposabrazos
        stair(tx - 1, fy + 1, r.cz, Material.POLISHED_BLACKSTONE_BRICK_STAIRS, BlockFace.EAST, false);
        set(tx - 1, fy + 1, r.cz - 1, Material.POLISHED_BLACKSTONE_BRICKS);
        set(tx - 1, fy + 1, r.cz + 1, Material.POLISHED_BLACKSTONE_BRICKS);
        // braseros de fuego de alma flanqueando el trono
        brazier(tx - 1, r.cz - 4, r);
        brazier(tx - 1, r.cz + 4, r);
    }

    // ------------------------------------------------------------------ chunks y ejecución

    private List<int[]> chunkList() {
        List<int[]> out = new ArrayList<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                out.add(new int[]{cx, cz});
            }
        }
        return out;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private void preload(Runnable then) {
        List<int[]> list = chunkList();
        AtomicInteger left = new AtomicInteger(list.size());
        for (int[] c : list) {
            world.getChunkAtAsync(c[0], c[1]).thenAccept(ch -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!released) {
                    if (ticketed.add(key(c[0], c[1]))) {
                        world.addPluginChunkTicket(c[0], c[1], plugin);
                    }
                }
                if (left.decrementAndGet() == 0) then.run();
            }));
        }
    }

    /** Libera los tickets de chunk (los chunks podrán descargarse). */
    public void release() {
        released = true;
        for (long k : new ArrayList<>(ticketed)) {
            int x = (int) (k >> 32);
            int z = (int) k;
            world.removePluginChunkTicket(x, z, plugin);
        }
        ticketed.clear();
    }

    public void cancel() {
        cancelled = true;
    }

    /** Carga los chunks y construye la dungeon por tandas. */
    public void build(Runnable done) {
        plan();
        preload(() -> {
            if (cancelled) {
                done.run();
                return;
            }
            final int perTick = Math.max(200, cfg.i("blocks-per-tick"));
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (cancelled) {
                        cancel();
                        done.run();
                        return;
                    }
                    int end = Math.min(ops.size(), placed + perTick);
                    for (; placed < end; placed++) {
                        Op o = ops.get(placed);
                        world.getBlockAt(o.x(), o.y(), o.z()).setBlockData(o.data(), o.physics());
                    }
                    if (placed >= ops.size()) {
                        cancel();
                        done.run();
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L);
        });
    }

    /** Borra todo lo construido (por tandas) y libera los chunks. */
    public void cleanup(Runnable done) {
        cancelled = true;
        final int perTick = Math.max(500, cfg.i("blocks-per-tick") * 2);
        final int[] idx = {0};
        final int limit = ops.size();
        if (limit == 0) {
            release();
            if (done != null) done.run();
            return;
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                int end = Math.min(ops.size(), idx[0] + perTick);
                for (; idx[0] < end; idx[0]++) {
                    Op o = ops.get(idx[0]);
                    world.getBlockAt(o.x(), o.y(), o.z()).setType(Material.AIR, false);
                }
                if (idx[0] >= ops.size()) {
                    cancel();
                    release();
                    if (done != null) done.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}
