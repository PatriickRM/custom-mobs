package me.custommobs;

import org.bukkit.Location;
import org.bukkit.SoundCategory;

/**
 * Sonidos por clave de texto ("entity.wither.ambient"). Así los mobs custom no usan la voz del mob vanilla en que se
 * basan (zombie, esqueleto...) y, si una clave no existe en la versión del servidor, simplemente no suena (no falla).
 */
public final class Sfx {

    private Sfx() {}

    public static void play(Location at, String key, float volume, float pitch) {
        if (at == null || at.getWorld() == null || key == null || key.isEmpty()) return;
        at.getWorld().playSound(at, key, SoundCategory.HOSTILE, volume, pitch);
    }
}
