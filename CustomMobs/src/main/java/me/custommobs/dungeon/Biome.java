package me.custommobs.dungeon;

import org.bukkit.Material;
import org.bukkit.Particle;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bioma de la dungeon: re-viste la paleta de bloques de las salas y añade partículas propias.
 * El bioma se elige al crear la partida (config {@code biome}: ashen, frost, void, crimson o random).
 * Solo se sustituyen bloques por otros de la misma forma (bloque, escalera, losa, muro).
 */
enum Biome {
    ASHEN("Catacumbas de Ceniza", "<gray>", null, Map.of()),
    FROST("Cripta Helada", "<aqua>", Particle.SNOWFLAKE, Map.ofEntries(
            Map.entry(Material.DEEPSLATE_BRICKS, Material.STONE_BRICKS),
            Map.entry(Material.CRACKED_DEEPSLATE_BRICKS, Material.CRACKED_STONE_BRICKS),
            Map.entry(Material.DEEPSLATE_TILES, Material.CALCITE),
            Map.entry(Material.CRACKED_DEEPSLATE_TILES, Material.TUFF),
            Map.entry(Material.POLISHED_BLACKSTONE_BRICKS, Material.PRISMARINE_BRICKS),
            Map.entry(Material.CRACKED_POLISHED_BLACKSTONE_BRICKS, Material.PRISMARINE),
            Map.entry(Material.BLACKSTONE, Material.COBBLESTONE),
            Map.entry(Material.GILDED_BLACKSTONE, Material.PRISMARINE),
            Map.entry(Material.DEEPSLATE_BRICK_STAIRS, Material.STONE_BRICK_STAIRS),
            Map.entry(Material.DEEPSLATE_BRICK_SLAB, Material.STONE_BRICK_SLAB),
            Map.entry(Material.POLISHED_BLACKSTONE_BRICK_STAIRS, Material.PRISMARINE_BRICK_STAIRS),
            Map.entry(Material.POLISHED_BLACKSTONE_BRICK_SLAB, Material.PRISMARINE_BRICK_SLAB),
            Map.entry(Material.BLACKSTONE_STAIRS, Material.COBBLESTONE_STAIRS))),
    VOID("Santuario del Vacío", "<light_purple>", Particle.PORTAL, Map.ofEntries(
            Map.entry(Material.DEEPSLATE_BRICKS, Material.END_STONE_BRICKS),
            Map.entry(Material.CRACKED_DEEPSLATE_BRICKS, Material.END_STONE_BRICKS),
            Map.entry(Material.DEEPSLATE_TILES, Material.OBSIDIAN),
            Map.entry(Material.CRACKED_DEEPSLATE_TILES, Material.CRYING_OBSIDIAN),
            Map.entry(Material.POLISHED_BLACKSTONE_BRICKS, Material.PURPUR_BLOCK),
            Map.entry(Material.CRACKED_POLISHED_BLACKSTONE_BRICKS, Material.PURPUR_BLOCK),
            Map.entry(Material.GILDED_BLACKSTONE, Material.AMETHYST_BLOCK),
            Map.entry(Material.DEEPSLATE_BRICK_STAIRS, Material.END_STONE_BRICK_STAIRS),
            Map.entry(Material.DEEPSLATE_BRICK_SLAB, Material.END_STONE_BRICK_SLAB),
            Map.entry(Material.POLISHED_BLACKSTONE_BRICK_STAIRS, Material.PURPUR_STAIRS),
            Map.entry(Material.POLISHED_BLACKSTONE_BRICK_SLAB, Material.PURPUR_SLAB),
            Map.entry(Material.POLISHED_BLACKSTONE_WALL, Material.END_STONE_BRICK_WALL),
            Map.entry(Material.BLACKSTONE_STAIRS, Material.END_STONE_BRICK_STAIRS))),
    CRIMSON("Forja Carmesí", "<red>", Particle.CRIMSON_SPORE, Map.ofEntries(
            Map.entry(Material.DEEPSLATE_BRICKS, Material.NETHER_BRICKS),
            Map.entry(Material.CRACKED_DEEPSLATE_BRICKS, Material.CRACKED_NETHER_BRICKS),
            Map.entry(Material.DEEPSLATE_TILES, Material.RED_NETHER_BRICKS),
            Map.entry(Material.CRACKED_DEEPSLATE_TILES, Material.CRACKED_NETHER_BRICKS),
            Map.entry(Material.POLISHED_BLACKSTONE_BRICKS, Material.NETHER_BRICKS),
            Map.entry(Material.CRACKED_POLISHED_BLACKSTONE_BRICKS, Material.CRACKED_NETHER_BRICKS),
            Map.entry(Material.DEEPSLATE_BRICK_STAIRS, Material.NETHER_BRICK_STAIRS),
            Map.entry(Material.DEEPSLATE_BRICK_SLAB, Material.NETHER_BRICK_SLAB),
            Map.entry(Material.POLISHED_BLACKSTONE_BRICK_STAIRS, Material.RED_NETHER_BRICK_STAIRS),
            Map.entry(Material.POLISHED_BLACKSTONE_BRICK_SLAB, Material.RED_NETHER_BRICK_SLAB),
            Map.entry(Material.POLISHED_BLACKSTONE_WALL, Material.RED_NETHER_BRICK_WALL)));

    final String title;
    final String color;
    final Particle particle;
    private final Map<Material, Material> map;

    Biome(String title, String color, Particle particle, Map<Material, Material> map) {
        this.title = title;
        this.color = color;
        this.particle = particle;
        this.map = new EnumMap<>(Material.class);
        this.map.putAll(map);
    }

    Material map(Material m) {
        return map.getOrDefault(m, m);
    }

    /** Resuelve el valor de config (random = al azar). */
    static Biome of(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        for (Biome b : values()) if (b.name().toLowerCase(Locale.ROOT).equals(n)) return b;
        return values()[ThreadLocalRandom.current().nextInt(values().length)];
    }
}
