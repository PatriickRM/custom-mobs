package me.custommobs.dungeon;

/** Tipos de sala de la dungeon. */
public enum RoomType {
    START("Inicio", "Punto de partida"),
    WAVES("Oleadas", "Sobrevive a varias oleadas de enemigos. Los élites sueltan mejor botín."),
    TRAP("Sala Trampa", "Activa las balizas mientras el suelo ataca. Cuantas más activas, más rápido."),
    PUZZLE("Acertijo", "Un reto distinto cada partida: vigilantes, orbes o cristales."),
    MINIBOSS("Mini Jefe", "Un jefe con mecánica propia. Da una bendición extra."),
    REST("Descanso", "Recupera vidas y abre el cofre antes de Vorthak."),
    VORTHAK("Vorthak", "El Centinela de Ceniza."),
    ALTAR("Altar de Almas", "Defiende el círculo mientras el altar se carga. Más gente dentro = más rápido."),
    VAULT("Cámara de Reliquias", "Cofres con riesgo y recompensa: emboscada, maldición o botín seguro."),
    SWEEP("Faro Giratorio", "Recoge las llaves esquivando los rayos giratorios."),
    MERCHANT("Mercader Fantasma", "Sin combate: compra reliquias y pociones con almas o con vidas."),
    EVENT("Evento", "Una decisión con riesgo y recompensa. Cada uno elige su destino.");

    /** Nombre que se muestra al elegir camino. */
    public final String title;
    /** Descripción corta para el jugador. */
    public final String blurb;

    RoomType(String title, String blurb) {
        this.title = title;
        this.blurb = blurb;
    }

    public static RoomType parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
