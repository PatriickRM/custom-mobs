package me.custommobs.dungeon;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

/** Generador de mundo vacío: no genera nada. */
public final class VoidGenerator extends ChunkGenerator {

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, 100, 0.5);
    }
}
