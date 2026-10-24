package me.custommobs.model;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Definiciones de modelos cargadas de mobmodels.yml (generado por tools/mobgen). */
public final class ModelDefs {

    public static final class Node {
        public String id;
        public int parent = -1;
        public float px, py, pz;          // pivote en píxeles, espacio del mob
        public float rx, ry, rz;          // rotación de reposo (grados)
        public String model;              // clave item_model (null si no tiene cajas)
        public boolean glow;
        public float walkAmp, walkPhase;
        public float idleAmp, idleSpeed = 1f, idlePhase;
        public char idleAxis = 'x';
        public float atk;
        public boolean look;
        public float orbitR, orbitSpeed, orbitPhase;
        public float hoverAmp, hoverSpeed = 1f;
        public char spinAxis = 0;
        public float spinSpeed;
    }

    public static final class Def {
        public String id;
        public String name;
        public float scale = 1f;
        public final List<Node> nodes = new ArrayList<>();
    }

    private final Map<String, Def> defs = new HashMap<>();

    public static ModelDefs load(org.bukkit.plugin.Plugin plugin) {
        ModelDefs m = new ModelDefs();
        try (InputStream in = plugin.getResource("mobmodels.yml")) {
            if (in == null) return m;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            ConfigurationSection mobs = y.getConfigurationSection("mobs");
            if (mobs == null) return m;
            for (String id : mobs.getKeys(false)) {
                ConfigurationSection c = mobs.getConfigurationSection(id);
                if (c == null) continue;
                Def d = new Def();
                d.id = id;
                d.name = c.getString("name", id);
                d.scale = (float) c.getDouble("scale", 1.0);
                Map<String, Integer> index = new HashMap<>();
                List<?> list = c.getList("nodes", List.of());
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> raw)) continue;
                    Node n = new Node();
                    n.id = String.valueOf(raw.get("id"));
                    Object par = raw.get("parent");
                    n.parent = par == null || "null".equals(String.valueOf(par)) ? -1
                            : index.getOrDefault(String.valueOf(par), -1);
                    float[] p = vec(raw.get("pivot"));
                    n.px = p[0]; n.py = p[1]; n.pz = p[2];
                    float[] r = vec(raw.get("rest"));
                    n.rx = r[0]; n.ry = r[1]; n.rz = r[2];
                    Object mod = raw.get("model");
                    n.model = mod == null || "null".equals(String.valueOf(mod)) ? null : String.valueOf(mod);
                    n.glow = bool(raw.get("glow"));
                    n.walkAmp = f(raw.get("walk_amp"), 0);
                    n.walkPhase = f(raw.get("walk_phase"), 0);
                    n.idleAmp = f(raw.get("idle_amp"), 0);
                    n.idleSpeed = f(raw.get("idle_speed"), 1);
                    n.idlePhase = f(raw.get("idle_phase"), 0);
                    n.idleAxis = ch(raw.get("idle_axis"), 'x');
                    n.atk = f(raw.get("atk"), 0);
                    n.look = bool(raw.get("look"));
                    n.orbitR = f(raw.get("orbit_r"), 0);
                    n.orbitSpeed = f(raw.get("orbit_speed"), 0);
                    n.orbitPhase = f(raw.get("orbit_phase"), 0);
                    n.hoverAmp = f(raw.get("hover_amp"), 0);
                    n.hoverSpeed = f(raw.get("hover_speed"), 1);
                    n.spinAxis = ch(raw.get("spin_axis"), (char) 0);
                    n.spinSpeed = f(raw.get("spin_speed"), 0);
                    index.put(n.id, d.nodes.size());
                    d.nodes.add(n);
                }
                m.defs.put(id, d);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("No se pudo leer mobmodels.yml: " + ex.getMessage());
        }
        return m;
    }

    public Def get(String id) {
        return defs.get(id);
    }

    public int size() {
        return defs.size();
    }

    private static float[] vec(Object o) {
        float[] r = new float[3];
        if (o instanceof List<?> l) {
            for (int i = 0; i < 3 && i < l.size(); i++) r[i] = f(l.get(i), 0);
        }
        return r;
    }

    private static float f(Object o, float def) {
        return o instanceof Number n ? n.floatValue() : def;
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : "true".equals(String.valueOf(o));
    }

    private static char ch(Object o, char def) {
        if (o == null) return def;
        String s = String.valueOf(o);
        return s.isEmpty() ? def : Character.toLowerCase(s.charAt(0));
    }
}
