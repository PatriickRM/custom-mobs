package me.custommobs.dungeon;

import java.time.LocalDate;

/** Modificador diario: cambia las reglas de la dungeon. */
public enum Modifier {
    HORDE("Horda", "<red>+40% de enemigos en cada oleada</red>"),
    ELITE("Élites", "<gold>Muchos más enemigos élite (mejor botín)</gold>"),
    FRENZY("Frenesí", "<yellow>Los enemigos son más rápidos</yellow>"),
    GLASS("Cristal", "<aqua>Recibes +30% de daño</aqua>"),
    BLESSED("Bendición", "<green>+1 vida para todos</green>");

    public final String title;
    public final String description;

    Modifier(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public static Modifier today(String forced, boolean enabled) {
        if (!enabled) return null;
        if (forced != null && !forced.isBlank()) {
            try {
                return valueOf(forced.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // se ignora y se usa el del día
            }
        }
        Modifier[] all = values();
        return all[(int) (LocalDate.now().toEpochDay() % all.length)];
    }
}
