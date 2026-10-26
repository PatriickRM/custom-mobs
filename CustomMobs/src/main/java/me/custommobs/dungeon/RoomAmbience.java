package me.custommobs.dungeon;

import me.custommobs.CustomMobsPlugin;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ambiente de la sala activa: cristales/objetos flotantes que giran y respiran (BlockDisplay) y partículas
 * propias de cada tipo de sala alrededor de los jugadores. Todo es temporal y se retira al superar la sala.
 */
final class RoomAmbience {

    private static final class Prop {
        BlockDisplay display;
        double phase;
        float angle;
        float scale;
    }

    private final CustomMobsPlugin plugin;
    private final DungeonInstance inst;
    private final DungeonConfig cfg;
    private final World world;
    private final List<Prop> props = new ArrayList<>();
    private DungeonBuilder.Room room;

    RoomAmbience(CustomMobsPlugin plugin, DungeonInstance inst, DungeonConfig cfg, World world) {
        this.plugin = plugin;
        this.inst = inst;
        this.cfg = cfg;
        this.world = world;
    }

    // ------------------------------------------------------------------ props

    /** Coloca los objetos flotantes de la sala que se acaba de activar. */
    void enter(DungeonBuilder.Room r) {
        clear();
        if (!cfg.b("fx.ambience") || r == null || r.type == RoomType.START) return;
        room = r;
        Material block = propBlock(r.type);
        boolean big = r.type == RoomType.VORTHAK;
        int count = big ? 12 : 8;
        double rx = Math.max(2.0, (r.x1 - r.x0) / 2.0 - 2.0);
        double rz = Math.max(2.0, (r.z1 - r.z0) / 2.0 - 2.0);
        double cx = r.cx() + 0.5;
        double cz = r.cz + 0.5;
        double y = r.floorY + 1 + Math.min(Math.max(2, r.height - 2), 4);
        for (int i = 0; i < count; i++) {
            double ang = (Math.PI * 2.0) * i / count;
            Location at = new Location(world, cx + Math.cos(ang) * rx, y + (i % 2 == 0 ? 0.0 : 0.8), cz + Math.sin(ang) * rz);
            Prop p = new Prop();
            p.phase = i * 0.9;
            p.scale = big ? 0.9f : 0.55f;
            final float scale = p.scale;
            p.display = world.spawn(at, BlockDisplay.class, d -> {
                d.setBlock(block.createBlockData());
                d.setPersistent(false);
                d.setViewRange(1.2f);
                d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(40);
                d.setTransformationMatrix(matrix(0f, 0f, scale));
            });
            props.add(p);
        }
    }

    /** Retira los objetos flotantes (al superar la sala o cerrar la partida). */
    void clear() {
        for (Prop p : props) {
            if (p.display != null && p.display.isValid()) p.display.remove();
        }
        props.clear();
        room = null;
    }

    private static Matrix4f matrix(float angleDeg, float bob, float scale) {
        // El bloque se centra en su origen para que gire sobre sí mismo.
        return new Matrix4f()
                .translate(0f, bob, 0f)
                .rotateY((float) Math.toRadians(angleDeg))
                .rotateX((float) Math.toRadians(18f))
                .scale(scale)
                .translate(-0.5f, -0.5f, -0.5f);
    }

    private static Material propBlock(RoomType t) {
        return switch (t) {
            case WAVES -> Material.CRYING_OBSIDIAN;
            case TRAP -> Material.MAGMA_BLOCK;
            case PUZZLE -> Material.SEA_LANTERN;
            case ALTAR -> Material.AMETHYST_BLOCK;
            case VAULT -> Material.GOLD_BLOCK;
            case MERCHANT -> Material.SCULK_CATALYST;
            case EVENT -> Material.RESPAWN_ANCHOR;
            case MINIBOSS -> Material.RED_NETHER_BRICKS;
            case REST -> Material.SHROOMLIGHT;
            case VORTHAK -> Material.CRYING_OBSIDIAN;
            default -> Material.BLACKSTONE;
        };
    }

    // ------------------------------------------------------------------ bucle

    /** Se llama en cada tick de la dungeon (cada 2 ticks del servidor). */
    void tick(long t) {
        if (room == null || inst.state() != DungeonInstance.State.RUNNING) return;
        if (t % 40 == 0) {
            for (Prop p : props) {
                if (p.display == null || !p.display.isValid()) continue;
                p.angle += 90f;
                p.phase += 1.3;
                float bob = (float) Math.sin(p.phase) * 0.35f;
                p.display.setInterpolationDelay(0);
                p.display.setInterpolationDuration(40);
                p.display.setTransformationMatrix(matrix(p.angle, bob, p.scale));
            }
        }
        if (t % 10 == 0) ambientParticles();
    }

    private void ambientParticles() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Player p : inst.aliveOnline()) {
            if (!room.contains(p.getLocation())) continue;
            Location base = p.getLocation();
            for (int i = 0; i < 3; i++) {
                double x = base.getX() + r.nextDouble(-9, 9);
                double y = base.getY() + r.nextDouble(0.3, 5.0);
                double z = base.getZ() + r.nextDouble(-9, 9);
                spawnFor(room.type, x, y, z);
            }
        }
    }

    private void spawnFor(RoomType type, double x, double y, double z) {
        switch (type) {
            case WAVES -> {
                world.spawnParticle(Particle.ASH, x, y, z, 2, 0.3, 0.2, 0.3, 0);
                if (ThreadLocalRandom.current().nextInt(4) == 0) world.spawnParticle(Particle.SMALL_FLAME, x, y - 0.5, z, 1, 0.1, 0.1, 0.1, 0.01);
            }
            case TRAP -> {
                world.spawnParticle(Particle.DUST, x, y, z, 1, 0.2, 0.2, 0.2, 0,
                        new Particle.DustOptions(Color.fromRGB(220, 40, 20), 1.1f));
                world.spawnParticle(Particle.ASH, x, y, z, 1, 0.3, 0.2, 0.3, 0);
            }
            case PUZZLE -> world.spawnParticle(Particle.ENCHANT, x, y - 1.0, z, 3, 0.4, 0.4, 0.4, 0.3);
            case ALTAR -> {
                world.spawnParticle(Particle.SOUL, x, y - 0.5, z, 1, 0.2, 0.3, 0.2, 0.01);
                world.spawnParticle(Particle.DUST, x, y, z, 1, 0.2, 0.2, 0.2, 0,
                        new Particle.DustOptions(Color.fromRGB(150, 80, 255), 1.0f));
            }
            case VAULT -> world.spawnParticle(Particle.DUST, x, y, z, 2, 0.3, 0.3, 0.3, 0,
                    new Particle.DustOptions(Color.fromRGB(255, 205, 70), 0.9f));
            case SWEEP -> world.spawnParticle(Particle.ELECTRIC_SPARK, x, y, z, 1, 0.3, 0.3, 0.3, 0.02);
            case EVENT -> {
                world.spawnParticle(Particle.ENCHANT, x, y - 0.5, z, 2, 0.4, 0.4, 0.4, 0.4);
                world.spawnParticle(Particle.DUST, x, y, z, 1, 0.2, 0.2, 0.2, 0,
                        new Particle.DustOptions(Color.fromRGB(170, 40, 90), 1.0f));
            }
            case MERCHANT -> {
                world.spawnParticle(Particle.SOUL, x, y - 0.5, z, 1, 0.2, 0.3, 0.2, 0.01);
                world.spawnParticle(Particle.DUST, x, y, z, 1, 0.2, 0.2, 0.2, 0,
                        new Particle.DustOptions(Color.fromRGB(90, 220, 255), 1.0f));
            }
            case MINIBOSS -> {
                world.spawnParticle(Particle.ASH, x, y, z, 2, 0.3, 0.2, 0.3, 0);
                world.spawnParticle(Particle.DUST, x, y, z, 1, 0.2, 0.2, 0.2, 0,
                        new Particle.DustOptions(Color.fromRGB(150, 15, 25), 1.2f));
            }
            case REST -> {
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, x, y - 1.5, z, 1, 0.1, 0.1, 0.1, 0.005);
                world.spawnParticle(Particle.END_ROD, x, y, z, 1, 0.3, 0.3, 0.3, 0.005);
            }
            case VORTHAK -> {
                world.spawnParticle(Particle.ASH, x, y, z, 3, 0.4, 0.3, 0.4, 0);
                world.spawnParticle(Particle.REVERSE_PORTAL, x, y - 0.5, z, 1, 0.2, 0.3, 0.2, 0.02);
                if (ThreadLocalRandom.current().nextInt(3) == 0) world.spawnParticle(Particle.LARGE_SMOKE, x, y - 1.0, z, 1, 0.3, 0.2, 0.3, 0.005);
            }
            default -> { }
        }
    }
}
