package me.custommobs.dungeon;

import org.bukkit.Material;

/**
 * Mejoras que cada jugador elige entre salas. Duran lo que dure la partida
 * (si la dungeon se reinicia tras una derrota, también se reinician).
 */
public enum Blessing {
    FURY("Furia de Ceniza", Material.BLAZE_POWDER, "<gold>", 5, 10,
            "+12% de daño infligido"),
    VAMPIRE("Filo Vampírico", Material.GHAST_TEAR, "<red>", 3, 8,
            "Recuperas vida al golpear"),
    SWIFT("Pies de Ceniza", Material.FEATHER, "<aqua>", 2, 7,
            "+15% de velocidad de movimiento"),
    IRON("Piel de Obsidiana", Material.SHIELD, "<gray>", 4, 10,
            "-10% de daño recibido"),
    VITALITY("Vitalidad", Material.GOLDEN_APPLE, "<green>", 5, 10,
            "+4 de vida máxima (2 corazones)"),
    LIFE("Corazón Extra", Material.TOTEM_OF_UNDYING, "<light_purple>", 2, 4,
            "+1 vida en la dungeon"),
    REGEN("Aliento de Vida", Material.GLISTERING_MELON_SLICE, "<yellow>", 3, 7,
            "Regeneras 1 de vida cada 5 segundos"),
    PACT("Pacto de Sangre", Material.WITHER_ROSE, "<dark_red>", 2, 5,
            "+25% de daño, pero -4 de vida máxima");

    public final String title;
    public final Material icon;
    public final String color;
    /** Veces máximas que se puede elegir. */
    public final int max;
    /** Peso al sortear las opciones (más alto = sale más). */
    public final int weight;
    public final String desc;

    Blessing(String title, Material icon, String color, int max, int weight, String desc) {
        this.title = title;
        this.icon = icon;
        this.color = color;
        this.max = max;
        this.weight = weight;
        this.desc = desc;
    }
}
