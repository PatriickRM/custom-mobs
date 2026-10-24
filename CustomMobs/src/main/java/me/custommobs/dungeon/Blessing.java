package me.custommobs.dungeon;

import org.bukkit.Color;
import org.bukkit.Material;

import java.util.Locale;

/**
 * Bendiciones que cada jugador elige entre salas. Duran lo que dure la partida
 * (si la dungeon se reinicia tras una derrota, también se reinician).
 *
 * <p>Cada bendición pertenece a una {@link School escuela}. Reunir 2 o 4 niveles de la misma escuela
 * activa una sinergia. La rareza decide con qué frecuencia aparece y cuándo puede salir.
 * Las fórmulas están aquí (métodos estáticos) para que la descripción del menú y el efecto real
 * usen siempre los mismos números.</p>
 */
public enum Blessing {

    // ------------------------------------------------------------------ CENIZA
    FURY("Furia de Ceniza", Material.BLAZE_POWDER, School.CENIZA, Rarity.COMMON, 5, 10, 0, null),
    EMBER_BRAND("Marca de Brasas", Material.MAGMA_CREAM, School.CENIZA, Rarity.COMMON, 3, 8, 0, null),
    SWIFT("Pies de Ceniza", Material.FEATHER, School.CENIZA, Rarity.COMMON, 2, 7, 0, null),
    TRAIL("Estela Ígnea", Material.FIRE_CHARGE, School.CENIZA, Rarity.RARE, 3, 6, 1, null),
    STORM("Tormenta de Ceniza", Material.NETHER_STAR, School.CENIZA, Rarity.LEGENDARY, 2, 2, 5, null),

    // ------------------------------------------------------------------ ALMAS
    SOUL_HARVEST("Cosecha de Almas", Material.SOUL_LANTERN, School.ALMAS, Rarity.COMMON, 3, 8, 0, null),
    CHAIN("Rayo Encadenado", Material.LIGHTNING_ROD, School.ALMAS, Rarity.RARE, 3, 6, 1, null),
    NOVA("Explosión de Almas", Material.FIREWORK_STAR, School.ALMAS, Rarity.RARE, 3, 5, 1, null),
    GUARD("Guardianes Espectrales", Material.SHIELD, School.ALMAS, Rarity.EPIC, 3, 3, 3, null),

    // ------------------------------------------------------------------ SANGRE
    VAMPIRE("Filo Vampírico", Material.GHAST_TEAR, School.SANGRE, Rarity.COMMON, 3, 8, 0, null),
    VITALITY("Vitalidad", Material.GOLDEN_APPLE, School.SANGRE, Rarity.COMMON, 5, 10, 0, null),
    REGEN("Aliento de Vida", Material.GLISTERING_MELON_SLICE, School.SANGRE, Rarity.COMMON, 3, 7, 0, null),
    THORNS("Espinas de Sangre", Material.POINTED_DRIPSTONE, School.SANGRE, Rarity.COMMON, 3, 7, 0, null),
    PACT("Pacto de Sangre", Material.WITHER_ROSE, School.SANGRE, Rarity.RARE, 2, 5, 0, null),
    BLOODLUST("Sed de Sangre", Material.REDSTONE, School.SANGRE, Rarity.RARE, 3, 6, 1, null),
    SECOND_WIND("Segundo Aliento", Material.ENCHANTED_GOLDEN_APPLE, School.SANGRE, Rarity.EPIC, 1, 3, 3, null),

    // ------------------------------------------------------------------ VACÍO
    IRON("Piel de Obsidiana", Material.OBSIDIAN, School.VACIO, Rarity.COMMON, 4, 10, 0, null),
    DASH("Paso del Vacío", Material.ENDER_PEARL, School.VACIO, Rarity.RARE, 3, 8, 0, null),
    ECHO("Eco del Vacío", Material.ECHO_SHARD, School.VACIO, Rarity.RARE, 2, 5, 1, DASH),
    PHASE("Evasión Sombría", Material.PHANTOM_MEMBRANE, School.VACIO, Rarity.RARE, 3, 6, 1, null),

    // ------------------------------------------------------------------ NEUTRAL
    LIFE("Corazón Extra", Material.TOTEM_OF_UNDYING, School.NINGUNA, Rarity.EPIC, 2, 3, 2, null),
    BERSERK("Furia Desatada", Material.NETHERITE_AXE, School.NINGUNA, Rarity.RARE, 1, 5, 1, null),

    // ------------------------------------------------------------------ NUEVAS (por escuela)
    BLEED("Hemorragia", Material.IRON_SWORD, School.SANGRE, Rarity.RARE, 3, 6, 1, null),
    FEAST("Banquete Carmesí", Material.ROTTEN_FLESH, School.SANGRE, Rarity.LEGENDARY, 2, 2, 4, null),
    JUDGMENT("Juicio de Almas", Material.TRIDENT, School.ALMAS, Rarity.LEGENDARY, 2, 2, 4, null),
    SINGULARITY("Singularidad", Material.ENDER_EYE, School.VACIO, Rarity.LEGENDARY, 2, 2, 4, null),

    // ------------------------------------------------------------------ EQUIPO (solo salen en grupo)
    BANNER("Estandarte de Guerra", Material.WHITE_BANNER, School.EQUIPO, Rarity.RARE, 3, 7, 1, null),
    MEDIC("Médico de Campaña", Material.GLISTERING_MELON_SLICE, School.EQUIPO, Rarity.RARE, 2, 5, 1, null),

    // ------------------------------------------------------------------ HÍBRIDAS (piden dos escuelas)
    FIREWALK("Paso Ígneo", Material.BLAZE_ROD, School.HIBRIDA, Rarity.EPIC, 2, 4, 2, null),
    BLOODDASH("Dash Carmesí", Material.CRIMSON_FUNGUS, School.HIBRIDA, Rarity.EPIC, 2, 4, 2, null);

    // ================================================================== escuelas y rarezas

    /** Escuela: agrupa bendiciones y da sinergias al acumular 2 y 4 niveles. */
    public enum School {
        CENIZA("Ceniza", "<gold>", 255, 140, 30,
                "Ignición", "Tus golpes siempre prenden y marcan al enemigo.",
                "Pira", "Los enemigos que mueren en llamas explotan (8 de daño, radio 3.5)."),
        ALMAS("Almas", "<aqua>", 80, 220, 255,
                "Eco de Almas", "Cada muerte que causas te cura 1 ❤.",
                "Legión", "Cadena, Explosión y Guardianes ganan +50% de potencia."),
        SANGRE("Sangre", "<red>", 200, 20, 30,
                "Sangre Caliente", "+50% de robo de vida y regeneras 1 de vida cada 5 s.",
                "Frenesí Carmesí", "Con menos del 40% de vida: +30% de daño y +20% de velocidad."),
        VACIO("Vacío", "<light_purple>", 170, 80, 255,
                "Paso Latente", "Desbloqueas el Dash (nivel 1) y su recarga baja un 20%.",
                "Cazador del Vacío", "Tu dash daña a quien atraviesas y activa un Eco (+40%)."),
        NINGUNA("Neutral", "<gray>", 200, 200, 200, "", "", "", ""),
        EQUIPO("Equipo", "<green>", 90, 230, 120, "", "", "", ""),
        HIBRIDA("Híbrida", "<#dc78be>", 220, 120, 190, "", "", "", "");

        public final String label;
        public final String color;
        private final int r;
        private final int g;
        private final int b;
        public final String tier1Name;
        public final String tier1Desc;
        public final String tier2Name;
        public final String tier2Desc;

        School(String label, String color, int r, int g, int b,
               String tier1Name, String tier1Desc, String tier2Name, String tier2Desc) {
            this.label = label;
            this.color = color;
            this.r = r;
            this.g = g;
            this.b = b;
            this.tier1Name = tier1Name;
            this.tier1Desc = tier1Desc;
            this.tier2Name = tier2Name;
            this.tier2Desc = tier2Desc;
        }

        /** ¿Tiene sinergias por acumulación (2 y 4 niveles)? */
        public boolean hasSynergy() {
            return this != NINGUNA && this != EQUIPO && this != HIBRIDA;
        }

        public Color rgb() {
            return Color.fromRGB(r, g, b);
        }

        public String tierName(int tier) {
            return tier >= 2 ? tier2Name : tier1Name;
        }

        public String tierDesc(int tier) {
            return tier >= 2 ? tier2Desc : tier1Desc;
        }
    }

    /** Rareza: color del menú, brillo y frecuencia. */
    public enum Rarity {
        COMMON("Común", "<white>", Material.LIGHT_GRAY_STAINED_GLASS_PANE),
        RARE("Rara", "<aqua>", Material.LIGHT_BLUE_STAINED_GLASS_PANE),
        EPIC("Épica", "<light_purple>", Material.PURPLE_STAINED_GLASS_PANE),
        LEGENDARY("Legendaria", "<gold>", Material.ORANGE_STAINED_GLASS_PANE);

        public final String label;
        public final String color;
        public final Material pane;

        Rarity(String label, String color, Material pane) {
            this.label = label;
            this.color = color;
            this.pane = pane;
        }
    }

    // ================================================================== datos

    public final String title;
    public final Material icon;
    public final School school;
    public final Rarity rarity;
    /** Veces máximas que se puede elegir. */
    public final int max;
    /** Peso al sortear las opciones (más alto = sale más). */
    public final int weight;
    /** Sala mínima (ordinal) a partir de la cual puede aparecer. */
    public final int minRoom;
    /** Otra bendición (o su equivalente por sinergia) que debe tener el jugador antes. */
    public final Blessing requires;
    /** Color MiniMessage (el de su escuela). */
    public final String color;

    Blessing(String title, Material icon, School school, Rarity rarity, int max, int weight,
             int minRoom, Blessing requires) {
        this.title = title;
        this.icon = icon;
        this.school = school;
        this.rarity = rarity;
        this.max = max;
        this.weight = weight;
        this.minRoom = minRoom;
        this.requires = requires;
        this.color = school.color;
    }

    /** Solo tiene sentido en grupo: no se ofrece en solitario. */
    public boolean teamOnly() {
        return this == BANNER || this == MEDIC;
    }

    /** Escuelas en las que hay que tener ya al menos 1 nivel para que salga (híbridas). */
    public School[] needSchools() {
        return switch (this) {
            case FIREWALK -> new School[]{School.CENIZA, School.VACIO};
            case BLOODDASH -> new School[]{School.SANGRE, School.VACIO};
            default -> new School[0];
        };
    }

    // ================================================================== fórmulas (compartidas)

    public static double bleedDps(int l) {
        return 2.0 + 1.5 * l;
    }

    public static int bleedSeconds(int l) {
        return 3 + l;
    }

    public static double feastSelf(int l) {
        return 2.0 + 2.0 * l;
    }

    public static double feastAlly(int l) {
        return 1.0 + 1.0 * l;
    }

    public static double judgmentDamage(int l) {
        return 10 + 6 * l;
    }

    public static int judgmentTargets(int l) {
        return 2 + l;
    }

    public static int judgmentCooldownTicks(int l) {
        return 200 - 40 * l;
    }

    public static double singularityDamage(int l) {
        return 4 + 3 * l;
    }

    public static double singularityRadius(int l) {
        return 5.0 + 1.0 * l;
    }

    public static int singularityCooldownTicks(int l) {
        return 360 - 60 * l;
    }

    public static double banner(int l) {
        return 0.08 * l;
    }

    public static double medic(int l) {
        return 1.0 * l;
    }

    public static double berserkDealt() {
        return 0.40;
    }

    public static double berserkTaken() {
        return 0.25;
    }

    public static double firewalkDamage(int l) {
        return 6 + 4 * l;
    }

    public static double bloodDashHeal(int l) {
        return 3.0 * l;
    }

    public static double fury(int l) {
        return 0.12 * l;
    }

    public static double brandBonus(int l) {
        return 0.10 * l;
    }

    public static int brandSeconds(int l) {
        return 3 * l;
    }

    public static double swift(int l) {
        return 0.15 * l;
    }

    public static double trailDamage(int l) {
        return 3 + 2 * l;
    }

    public static int trailSeconds(int l) {
        return 3 + l;
    }

    public static double stormDamage(int l) {
        return 6 + 6 * l;
    }

    public static double stormRadius(int l) {
        return 5 + 1.5 * l;
    }

    public static int stormCooldownTicks(int l) {
        return 280 - 60 * l;
    }

    /** Vida de absorción por muerte (en puntos de vida; 2 = un corazón). */
    public static double harvestAbsorb(int l) {
        return 2.0 * l;
    }

    public static double harvestCap(int l) {
        return 6.0 + 4.0 * l;
    }

    public static double chainChance(int l) {
        return 0.20 + 0.10 * l;
    }

    public static int chainJumps(int l) {
        return 1 + l;
    }

    public static double novaDamage(int l) {
        return 6 + 4 * l;
    }

    public static double novaRadius(int l) {
        return 3.0 + 0.5 * l;
    }

    public static int guardRechargeTicks(int l) {
        return 360 - 40 * l;
    }

    public static double vampire(int l) {
        return 0.04 * l;
    }

    public static double pact(int l) {
        return 0.25 * l;
    }

    /** Daño extra por cada acumulación de frenesí. */
    public static double bloodlust(int l) {
        return 0.03 * l;
    }

    public static double thorns(int l) {
        return 0.25 * l;
    }

    public static double iron(int l) {
        return 0.10 * l;
    }

    public static int dashCooldownTicks(int l) {
        return l <= 1 ? 140 : l == 2 ? 100 : 70;
    }

    public static int dashInvulTicks(int l) {
        return 10 + 4 * l;
    }

    public static double echo(int l) {
        return 0.5 * l;
    }

    public static double phase(int l) {
        return 0.08 * l;
    }

    // ================================================================== descripciones

    private static String pct(double v) {
        return String.valueOf(Math.round(v * 100));
    }

    private static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1.0E-9) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String secs(int ticks) {
        return num(ticks / 20.0);
    }

    /** Descripción del efecto al nivel indicado (líneas separadas por salto de línea, MiniMessage). */
    public String desc(int l) {
        return switch (this) {
            case FURY -> "+<white>" + pct(fury(l)) + "%</white> de daño infligido.\nCada golpe lanza chispas de ceniza.";
            case EMBER_BRAND -> "Tus golpes prenden y marcan al enemigo (<white>" + brandSeconds(l) + "s</white>).\n"
                    + "Los marcados reciben <white>+" + pct(brandBonus(l)) + "%</white> de daño.";
            case SWIFT -> "+<white>" + pct(swift(l)) + "%</white> de velocidad de movimiento.\nDejas brasas al correr.";
            case TRAIL -> "Al esprintar dejas una estela de fuego que\ndaña a los enemigos (<white>" + num(trailDamage(l))
                    + "</white> de daño, dura <white>" + trailSeconds(l) + "s</white>).";
            case STORM -> "Cada <white>" + secs(stormCooldownTicks(l)) + "s</white> una onda de fuego se expande a tu\n"
                    + "alrededor: <white>" + num(stormDamage(l)) + "</white> de daño, radio <white>" + num(stormRadius(l))
                    + "</white>.\nSolo se lanza si hay enemigos cerca.";
            case SOUL_HARVEST -> "Cada muerte libera un alma que te da\n<white>" + num(harvestAbsorb(l) / 2)
                    + "</white> ❤ de absorción (máx. <white>" + num(harvestCap(l) / 2) + "</white> ❤).";
            case CHAIN -> "<white>" + pct(chainChance(l)) + "%</white> de probabilidad de que tu golpe salte\na <white>"
                    + chainJumps(l) + "</white> enemigo(s) cercano(s) (50% del daño).";
            case NOVA -> "Los enemigos que matas explotan:\n<white>" + num(novaDamage(l)) + "</white> de daño en radio <white>"
                    + num(novaRadius(l)) + "</white>.";
            case GUARD -> "<white>" + l + "</white> espíritu(s) orbitan a tu alrededor y bloquean\nun golpe cada uno. Recarga: <white>"
                    + secs(guardRechargeTicks(l)) + "s</white> cada uno.";
            case VAMPIRE -> "Recuperas <white>" + pct(vampire(l)) + "%</white> del daño infligido como vida\n(máx. 4 por golpe).";
            case VITALITY -> "+<white>4</white> de vida máxima (2 ❤).";
            case REGEN -> "Regeneras <white>" + l + "</white> de vida cada 5 segundos.";
            case THORNS -> "Reflejas <white>" + pct(thorns(l)) + "%</white> del daño cuerpo a cuerpo\nque te hacen los enemigos.";
            case PACT -> "<white>+" + pct(pact(l)) + "%</white> de daño, pero <white>-" + (4 * l) + "</white> de vida máxima.";
            case BLOODLUST -> "Cada muerte te da Frenesí (máx. 5, dura 6 s):\n<white>+" + pct(bloodlust(l))
                    + "%</white> de daño por acumulación.";
            case SECOND_WIND -> "Si un golpe fuera a matarte, sobrevives con el 40% de\nvida y una onda repele a los enemigos.\n"
                    + "Recarga: <white>100 s</white>.";
            case IRON -> "<white>-" + pct(iron(l)) + "%</white> de daño recibido.";
            case DASH -> "Pulsa <white>F</white> (cambiar de mano) para hacer un dash.\nInvulnerable <white>"
                    + secs(dashInvulTicks(l)) + "s</white>. Recarga: <white>" + secs(dashCooldownTicks(l)) + "s</white>."
                    + (l >= 3 ? "\nAtraviesas y dañas a los enemigos." : "");
            case ECHO -> "Tras un dash, tu siguiente golpe (3 s)\nhace <white>+" + pct(echo(l)) + "%</white> de daño.";
            case PHASE -> "<white>" + pct(phase(l)) + "%</white> de probabilidad de esquivar\nun ataque enemigo.";
            case LIFE -> "<white>+1</white> vida en la dungeon.";
            case BERSERK -> "<white>+" + pct(berserkDealt()) + "%</white> de daño infligido,\npero recibes <white>+" + pct(berserkTaken())
                    + "%</white> de daño.\n<red>Alto riesgo, alta recompensa.</red>";
            case BLEED -> "Tus golpes causan sangrado: <white>" + num(bleedDps(l)) + "</white> de daño por segundo\ndurante <white>"
                    + bleedSeconds(l) + "s</white> (se renueva al golpear).";
            case FEAST -> "Cada muerte te cura <white>" + num(feastSelf(l)) + "</white> de vida y a tus aliados\ncercanos <white>"
                    + num(feastAlly(l)) + "</white>.";
            case JUDGMENT -> "Cada <white>" + secs(judgmentCooldownTicks(l)) + "s</white> cae un rayo sobre los <white>"
                    + judgmentTargets(l) + "</white> enemigos más\ncercanos: <white>" + num(judgmentDamage(l)) + "</white> de daño cada uno.";
            case SINGULARITY -> "Cada <white>" + secs(singularityCooldownTicks(l)) + "s</white> abres un agujero negro que atrae\n"
                    + "a los enemigos (radio <white>" + num(singularityRadius(l)) + "</white>) y les daña <white>"
                    + num(singularityDamage(l)) + "</white> por segundo.";
            case BANNER -> "<green>EQUIPO.</green> Tú y tus aliados a menos de 10 bloques\ncausáis <white>+" + pct(banner(l))
                    + "%</white> de daño por cada portador del estandarte.";
            case MEDIC -> "<green>EQUIPO.</green> Revives a tus compañeros <white>" + (1 + (int) medic(l))
                    + "x</white> más rápido\ny vuelven con más vida.";
            case FIREWALK -> "<gold>Ceniza</gold> + <light_purple>Vacío</light_purple>: tu dash deja un rastro de fuego que\ndaña y marca a quien atraviesas (<white>"
                    + num(firewalkDamage(l)) + "</white> de daño).";
            case BLOODDASH -> "<red>Sangre</red> + <light_purple>Vacío</light_purple>: tras un dash, tu siguiente golpe hace\n+40% de daño y te cura <white>"
                    + num(bloodDashHeal(l)) + "</white> de vida.";
        };
    }
}
