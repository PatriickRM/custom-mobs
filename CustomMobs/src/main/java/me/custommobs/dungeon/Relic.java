package me.custommobs.dungeon;

import org.bukkit.Material;

/**
 * Reliquias de la dungeon: objetos pasivos que cambian las reglas de la partida (no suben de nivel).
 * Cada jugador puede tener cada reliquia una sola vez. Algunas son "malditas": dan un gran poder a cambio de un coste.
 */
public enum Relic {
    EMBER_HEART("Corazón de Brasa", Material.MAGMA_CREAM, Tier.COMMON,
            "Los enemigos que matas estallan en llamas y prenden a los cercanos."),
    SOUL_LANTERN("Linterna de Almas", Material.SOUL_LANTERN, Tier.COMMON,
            "Cada muerte te da un alma (máx. 12): +2,5% de daño por alma. Si te hieren, pierdes la mitad."),
    CRIMSON_CHALICE("Cáliz Carmesí", Material.HONEY_BOTTLE, Tier.COMMON,
            "Cada enemigo que mates te cura (más si es élite)."),
    WAR_DRUM("Tambor de Guerra", Material.NOTE_BLOCK, Tier.COMMON,
            "Cada muerte te da velocidad unos segundos; encadena kills para correr más."),
    HUNTER_SEAL("Sello del Cazador", Material.SPYGLASS, Tier.COMMON,
            "El primer golpe a un enemigo con la vida casi llena hace +60% de daño."),
    EXECUTIONER_MASK("Máscara del Verdugo", Material.WITHER_SKELETON_SKULL, Tier.RARE,
            "+80% de daño contra enemigos con menos del 30% de vida."),
    VOID_NEEDLE("Aguja del Vacío", Material.ENDER_EYE, Tier.RARE,
            "18% de probabilidad al golpear de lanzar un rayo del vacío a otro enemigo cercano."),
    ASH_AEGIS("Égida de Ceniza", Material.SHIELD, Tier.RARE,
            "Cada 15 s recuperas un escudo de 2 corazones de absorción."),
    MIRROR_RING("Anillo de Espejo", Material.AMETHYST_SHARD, Tier.RARE,
            "12% de probabilidad de anular por completo un golpe enemigo."),
    GLASS_FANG("Colmillo de Cristal", Material.PRISMARINE_SHARD, Tier.CURSED,
            "+35% de daño infligido, pero recibes +25% de daño."),
    SOUL_REAPER("Segador de Almas", Material.WITHER_ROSE, Tier.LEGENDARY,
            "Cada 8ª muerte desata una onda de almas que daña y empuja a los enemigos cercanos."),
    PHOENIX_FEATHER("Pluma de Fénix", Material.FEATHER, Tier.LEGENDARY,
            "Obtienes una vida extra permanente.");

    /** Rareza (peso de aparición y color). */
    public enum Tier {
        COMMON("Común", "<white>", 10, Material.LIGHT_GRAY_STAINED_GLASS_PANE),
        RARE("Rara", "<aqua>", 6, Material.CYAN_STAINED_GLASS_PANE),
        CURSED("Maldita", "<dark_red>", 4, Material.RED_STAINED_GLASS_PANE),
        LEGENDARY("Legendaria", "<gold>", 2, Material.ORANGE_STAINED_GLASS_PANE);

        public final String label;
        public final String color;
        public final int weight;
        public final Material pane;

        Tier(String label, String color, int weight, Material pane) {
            this.label = label;
            this.color = color;
            this.weight = weight;
            this.pane = pane;
        }
    }

    public final String title;
    public final Material icon;
    public final Tier tier;
    public final String description;

    Relic(String title, Material icon, Tier tier, String description) {
        this.title = title;
        this.icon = icon;
        this.tier = tier;
        this.description = description;
    }
}
