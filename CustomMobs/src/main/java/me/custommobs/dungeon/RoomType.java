package me.custommobs.dungeon;

/** Tipos de sala de la dungeon. */
public enum RoomType {
    START, WAVES, TRAP, PUZZLE, MINIBOSS, REST, VORTHAK, ALTAR, VAULT, SWEEP;

    public static RoomType parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
