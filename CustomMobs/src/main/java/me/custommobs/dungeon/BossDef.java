package me.custommobs.dungeon;

import org.bukkit.Material;

import java.util.List;

/**
 * Registro de jefes de la dungeon. Cada entrada de {@link #ALL} aparece sola como un pedestal en el Hub
 * (los no disponibles se muestran sellados) y se puede elegir al entrar en la cola.
 *
 * <p><b>Añadir un jefe:</b> copia una línea de {@link #ALL}, ponle id, nombre, icono y descripción, y marca
 * {@code available = true} cuando su combate esté implementado (hoy solo Vorthak tiene combate:
 * {@code DungeonInstance.spawnVorthak}).</p>
 */
public record BossDef(String id, String title, String color, Material icon, List<String> blurb, boolean available) {

    public static final BossDef VORTHAK = new BossDef("vorthak", "Vorthak", "<red>", Material.WITHER_SKELETON_SKULL,
            List.of("El señor de la dungeon.", "Combate en cuatro fases con una arena que cambia.",
                    "Cuanto más rápido llegues, más fuerte despierta."), true);

    public static final List<BossDef> ALL = List.of(
            VORTHAK,
            new BossDef("sello_2", "???", "<dark_gray>", Material.OBSIDIAN,
                    List.of("Un sello antiguo bloquea este camino.", "Algo despertará aquí en el futuro."), false),
            new BossDef("sello_3", "???", "<dark_gray>", Material.CRYING_OBSIDIAN,
                    List.of("Un sello antiguo bloquea este camino.", "Algo despertará aquí en el futuro."), false));

    public static final BossDef DEFAULT = VORTHAK;

    public static BossDef byId(String id) {
        for (BossDef b : ALL) if (b.id.equalsIgnoreCase(id)) return b;
        return null;
    }
}
