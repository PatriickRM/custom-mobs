package me.custommobs.dungeon;

import me.custommobs.Text;
import org.bukkit.Axis;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Arcos de portal para elegir camino: al limpiar una sala con varias salidas aparecen portales junto a la puerta,
 * cada uno con el nombre de la sala que hay detrás. Se vota <b>entrando</b> en uno (también sirve el chat).
 * A veces una de las salidas es un portal misterioso (¿qué habrá detrás?) con premio extra.
 */
final class PathPortals {

    private static final class Arch {
        double x;
        double z;
        double y;
        final List<org.bukkit.entity.Entity> parts = new ArrayList<>();
        TextDisplay label;
        String title;
        String tag;
    }

    private final World world;
    private final List<Arch> arches = new ArrayList<>();

    PathPortals(World world) {
        this.world = world;
    }

    boolean active() {
        return !arches.isEmpty();
    }

    private static String tagOf(RoomType t) {
        return switch (t) {
            case WAVES -> "Combate · botín de élites";
            case TRAP -> "Peligro · el suelo ataca";
            case PUZZLE -> "Ingenio";
            case ALTAR -> "Defensa · reliquia";
            case VAULT -> "Riesgo y tesoro";
            case SWEEP -> "Reflejos";
            case MERCHANT -> "Tienda · sin combate";
            case EVENT -> "Decisión arriesgada";
            case MINIBOSS -> "Jefe · bendición";
            case REST -> "Descanso";
            default -> t.blurb;
        };
    }

    /** Coloca un arco por opción junto a la salida este de la sala {@code from}. */
    void show(DungeonBuilder.Room from, List<RoomType> types, int mystery) {
        clear();
        int n = types.size();
        double x = from.x1 - 4 + 0.5;
        for (int k = 0; k < n; k++) {
            Arch a = new Arch();
            a.x = x;
            a.z = from.cz + 0.5 + (k - (n - 1) / 2.0) * (n == 2 ? 9.0 : 8.0);
            a.y = from.floorY + 1;
            boolean hidden = k == mystery;
            a.title = hidden ? "<light_purple><bold>???</bold></light_purple>" : "<gold><bold>" + types.get(k).title + "</bold></gold>";
            a.tag = hidden ? "<gray>Desconocido · ¿premio extra?</gray>" : "<gray>" + tagOf(types.get(k)) + "</gray>";
            Material core = hidden ? Material.CRYING_OBSIDIAN : Material.POLISHED_BASALT;
            // pilares y dintel
            a.parts.add(block(a.x, a.y, a.z - 2.1, core, new Matrix4f().translate(-0.4f, 0f, -0.4f).scale(0.8f, 5f, 0.8f)));
            a.parts.add(block(a.x, a.y, a.z + 2.1, core, new Matrix4f().translate(-0.4f, 0f, -0.4f).scale(0.8f, 5f, 0.8f)));
            a.parts.add(block(a.x, a.y + 4.2, a.z, Material.GILDED_BLACKSTONE,
                    new Matrix4f().translate(-0.45f, 0f, -2.6f).scale(0.9f, 0.9f, 5.2f)));
            // cortina de portal (plano en Z)
            Orientable portal = (Orientable) Material.NETHER_PORTAL.createBlockData();
            portal.setAxis(Axis.Z);
            BlockDisplay curtain = world.spawn(new Location(world, a.x, a.y, a.z), BlockDisplay.class, d -> {
                d.setBlock(portal);
                d.setPersistent(false);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(2.0f);
                d.setTransformationMatrix(new Matrix4f().translate(-0.5f, 0f, -1.5f).scale(1f, 4.2f, 3f));
            });
            a.parts.add(curtain);
            a.label = world.spawn(new Location(world, a.x, a.y + 5.9, a.z), TextDisplay.class, td -> {
                td.setBillboard(Display.Billboard.CENTER);
                td.setShadowed(true);
                td.setDefaultBackground(false);
                td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                td.setPersistent(false);
                td.setViewRange(3.0f);
            });
            arches.add(a);
            refresh(arches.size() - 1, 0);
        }
    }

    private org.bukkit.entity.Entity block(double x, double y, double z, Material m, Matrix4f mat) {
        return world.spawn(new Location(world, x, y, z), BlockDisplay.class, d -> {
            d.setBlock(m.createBlockData());
            d.setPersistent(false);
            d.setBrightness(new Display.Brightness(12, 12));
            d.setViewRange(2.0f);
            d.setTransformationMatrix(mat);
        });
    }

    /** Actualiza el cartel de una opción con el número de votos. */
    void refresh(int k, int votes) {
        if (k < 0 || k >= arches.size()) return;
        Arch a = arches.get(k);
        if (a.label == null || !a.label.isValid()) return;
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < votes; i++) dots.append("●");
        a.label.text(Text.mm(a.title + "\n" + a.tag + (votes > 0 ? "\n<green>" + dots + "</green>" : "")));
    }

    /** Opción cuyo arco pisa el jugador, o -1. */
    int zoneOf(Player p) {
        Location l = p.getLocation();
        for (int k = 0; k < arches.size(); k++) {
            Arch a = arches.get(k);
            if (Math.abs(l.getX() - a.x) <= 1.4 && Math.abs(l.getZ() - a.z) <= 1.9 && Math.abs(l.getY() - a.y) <= 3.0) return k;
        }
        return -1;
    }

    /** Partículas de la cortina (cada ~10 ticks). */
    void particles() {
        for (Arch a : arches) {
            world.spawnParticle(Particle.REVERSE_PORTAL, a.x, a.y + 2.0, a.z, 8, 0.2, 1.6, 1.2, 0.2);
        }
    }

    void clear() {
        for (Arch a : arches) {
            for (org.bukkit.entity.Entity e : a.parts) if (e.isValid()) e.remove();
            if (a.label != null && a.label.isValid()) a.label.remove();
        }
        arches.clear();
    }
}
