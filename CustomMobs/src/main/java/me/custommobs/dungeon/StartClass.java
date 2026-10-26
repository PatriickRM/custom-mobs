package me.custommobs.dungeon;

import org.bukkit.Material;

/**
 * Clases de inicio. Son DATOS: cada clase da bendiciones iniciales (incluido el dash), una reliquia opcional,
 * un ajuste de vidas y brasas de partida. Todo se apoya en los sistemas existentes (bendiciones, reliquias, Mercader).
 */
public enum StartClass {

    BERSERKER("Berserker", Material.NETHERITE_AXE, "<red>",
            "Cuanta más furia acumulas, más duro golpeas. Frágil, pero letal y con un dash que cura.",
            new Grant[]{new Grant(Blessing.FURY, 2), new Grant(Blessing.VAMPIRE, 1),
                    new Grant(Blessing.DASH, 1), new Grant(Blessing.BLOODDASH, 1)},
            null, -1, 0, "Embestida Carmesí: el siguiente golpe tras el dash hace más daño y cura."),

    VOID_HUNTER("Cazador del Vacío", Material.ENDER_EYE, "<light_purple>",
            "Rápido y esquivo. Su dash ya corta a los enemigos y su primer golpe a cada presa es brutal.",
            new Grant[]{new Grant(Blessing.DASH, 3), new Grant(Blessing.PHASE, 1), new Grant(Blessing.SWIFT, 1)},
            Relic.HUNTER_SEAL, 0, 0, "Paso del Vacío: dash veloz con invulnerabilidad más larga."),

    NECROMANCER("Nigromante", Material.WITHER_SKELETON_SKULL, "<dark_aqua>",
            "Cada muerte alimenta su poder. Empieza con brasas para comprar en el Mercader.",
            new Grant[]{new Grant(Blessing.SOUL_HARVEST, 2), new Grant(Blessing.CHAIN, 1), new Grant(Blessing.DASH, 1)},
            Relic.SOUL_LANTERN, 0, 10, "Dash básico: rápido y sin florituras."),

    GUARDIAN("Guardián", Material.SHIELD, "<gold>",
            "Aguanta lo que sea y devuelve el golpe. Una vida extra y un escudo que se recarga solo.",
            new Grant[]{new Grant(Blessing.IRON, 2), new Grant(Blessing.THORNS, 1), new Grant(Blessing.REGEN, 1)},
            Relic.ASH_AEGIS, 1, 0, null);

    /** Bendición inicial con nivel. */
    public record Grant(Blessing blessing, int level) { }

    public final String title;
    public final Material icon;
    public final String color;
    public final String tagline;
    public final Grant[] grants;
    /** Reliquia inicial (puede ser null). */
    public final Relic relic;
    /** Vidas extra (+) o menos (-) respecto a lo normal. */
    public final int lifeDelta;
    /** Brasas iniciales para el Mercader. */
    public final int souls;
    /** Descripción del dash (null = la clase no tiene dash). */
    public final String dashNote;

    StartClass(String title, Material icon, String color, String tagline, Grant[] grants, Relic relic,
               int lifeDelta, int souls, String dashNote) {
        this.title = title;
        this.icon = icon;
        this.color = color;
        this.tagline = tagline;
        this.grants = grants;
        this.relic = relic;
        this.lifeDelta = lifeDelta;
        this.souls = souls;
        this.dashNote = dashNote;
    }
}
