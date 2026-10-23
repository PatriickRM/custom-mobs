package me.custommobs.dungeon;

import me.custommobs.CustomMobsPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Datos persistentes: cooldowns, récords y lobby (dungeon-data.yml). */
public final class DungeonData {

    public record Record(String name, int bestScore, int bestTimeSeconds, int runs) {}

    private final CustomMobsPlugin plugin;
    private final File file;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Record> records = new HashMap<>();
    private String lobby = "";

    public DungeonData(CustomMobsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "dungeon-data.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        lobby = y.getString("lobby", "");
        ConfigurationSection cd = y.getConfigurationSection("cooldowns");
        if (cd != null) {
            for (String k : cd.getKeys(false)) {
                try {
                    cooldowns.put(UUID.fromString(k), cd.getLong(k));
                } catch (IllegalArgumentException ignored) {
                    // clave inválida
                }
            }
        }
        ConfigurationSection rc = y.getConfigurationSection("records");
        if (rc != null) {
            for (String k : rc.getKeys(false)) {
                try {
                    ConfigurationSection s = rc.getConfigurationSection(k);
                    if (s == null) continue;
                    records.put(UUID.fromString(k), new Record(s.getString("name", "?"),
                            s.getInt("score"), s.getInt("time"), s.getInt("runs")));
                } catch (IllegalArgumentException ignored) {
                    // clave inválida
                }
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("lobby", lobby);
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : cooldowns.entrySet()) {
            if (e.getValue() > now) y.set("cooldowns." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, Record> e : records.entrySet()) {
            Record r = e.getValue();
            String p = "records." + e.getKey();
            y.set(p + ".name", r.name());
            y.set(p + ".score", r.bestScore());
            y.set(p + ".time", r.bestTimeSeconds());
            y.set(p + ".runs", r.runs());
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo guardar dungeon-data.yml: " + ex.getMessage());
        }
    }

    public String lobby() {
        return lobby;
    }

    public void lobby(String value) {
        this.lobby = value;
        save();
    }

    public long cooldownLeftMillis(UUID id) {
        Long until = cooldowns.get(id);
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    public void setCooldown(UUID id, long minutes) {
        if (minutes <= 0) {
            cooldowns.remove(id);
        } else {
            cooldowns.put(id, System.currentTimeMillis() + minutes * 60_000L);
        }
        save();
    }

    public void clearCooldown(UUID id) {
        cooldowns.remove(id);
        save();
    }

    public void recordRun(UUID id, String name, int score, int seconds) {
        Record old = records.get(id);
        int best = old == null ? score : Math.max(old.bestScore(), score);
        int bestTime = old == null ? seconds : Math.min(old.bestTimeSeconds(), seconds);
        records.put(id, new Record(name, best, bestTime, old == null ? 1 : old.runs() + 1));
        save();
    }

    public List<Record> top(int n) {
        List<Record> list = new ArrayList<>(records.values());
        list.sort((a, b) -> Integer.compare(b.bestScore(), a.bestScore()));
        return list.subList(0, Math.min(n, list.size()));
    }
}
