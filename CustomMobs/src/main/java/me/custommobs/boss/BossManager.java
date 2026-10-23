package me.custommobs.boss;

import me.custommobs.CustomMobsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Registro de jefes activos. Pensado para añadir más mobs custom en el futuro. */
public final class BossManager {

    private final CustomMobsPlugin plugin;
    private final Map<UUID, VorthakBoss> active = new HashMap<>();

    public final NamespacedKey bossKey;
    public final NamespacedKey phaseKey;
    public final NamespacedKey minionKey;
    public final NamespacedKey heraldKey;
    public final NamespacedKey ritualKey;
    public final NamespacedKey itemKey;
    public final NamespacedKey swordRecipeKey;

    public BossManager(CustomMobsPlugin plugin) {
        this.plugin = plugin;
        this.bossKey = new NamespacedKey(plugin, "boss_id");
        this.phaseKey = new NamespacedKey(plugin, "boss_phase");
        this.minionKey = new NamespacedKey(plugin, "minion_of");
        this.heraldKey = new NamespacedKey(plugin, "herald_of");
        this.ritualKey = new NamespacedKey(plugin, "boss_ritual");
        this.itemKey = new NamespacedKey(plugin, "item_id");
        this.swordRecipeKey = new NamespacedKey(plugin, "ash_sword_recipe");
        registerRecipes();
    }

    /** Segadora de Ceniza: 4 lingotes de netherite + calavera de Wither + Corazón de Ceniza + espada de netherite. */
    private void registerRecipes() {
        Bukkit.removeRecipe(swordRecipeKey);
        ShapedRecipe r = new ShapedRecipe(swordRecipeKey, BossItems.ashSword(this));
        r.shape("NKN", "NHN", " S ");
        r.setIngredient('N', Material.NETHERITE_INGOT);
        r.setIngredient('K', Material.WITHER_SKELETON_SKULL);
        r.setIngredient('S', Material.NETHERITE_SWORD);
        r.setIngredient('H', new RecipeChoice.MaterialChoice(Material.NETHER_STAR)); // se valida en BossListener
        Bukkit.addRecipe(r);
    }

    public VorthakBoss spawnVorthak(Location loc) {
        VorthakBoss boss = VorthakBoss.spawn(plugin, this, loc);
        active.put(boss.entity().getUniqueId(), boss);
        return boss;
    }

    /** Adopta una entidad marcada como jefe (tras reinicio o carga de chunk). */
    public void adopt(Entity entity) {
        if (!(entity instanceof WitherSkeleton ws)) return;
        String id = ws.getPersistentDataContainer().get(bossKey, PersistentDataType.STRING);
        if (!VorthakBoss.ID.equals(id)) return;
        if (active.containsKey(ws.getUniqueId())) return;
        Integer ord = ws.getPersistentDataContainer().get(phaseKey, PersistentDataType.INTEGER);
        Integer rit = ws.getPersistentDataContainer().get(ritualKey, PersistentDataType.INTEGER);
        VorthakBoss.Phase phase = VorthakBoss.Phase.values()[Math.max(0, Math.min(3, ord == null ? 0 : ord))];
        VorthakBoss boss = VorthakBoss.adopt(plugin, this, ws, phase, rit == null ? 0 : rit);
        active.put(ws.getUniqueId(), boss);
    }

    public void adoptLoaded() {
        for (World w : plugin.getServer().getWorlds()) {
            for (WitherSkeleton ws : w.getEntitiesByClass(WitherSkeleton.class)) {
                adopt(ws);
            }
        }
    }

    public VorthakBoss get(Entity entity) {
        return entity == null ? null : active.get(entity.getUniqueId());
    }

    public VorthakBoss get(UUID id) {
        return id == null ? null : active.get(id);
    }

    public boolean isMinion(Entity entity) {
        return entity.getPersistentDataContainer().has(minionKey, PersistentDataType.STRING);
    }

    public boolean isHerald(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(heraldKey, PersistentDataType.STRING);
    }

    /** UUID del jefe dueño de este Heraldo, o null. */
    public UUID heraldOwnerId(Entity entity) {
        if (entity == null) return null;
        String s = entity.getPersistentDataContainer().get(heraldKey, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Jefe dueño de este Heraldo, o null si no es un Heraldo. */
    public VorthakBoss heraldOwner(Entity entity) {
        return get(heraldOwnerId(entity));
    }

    public void unregister(UUID id) {
        active.remove(id);
    }

    public List<VorthakBoss> all() {
        return new ArrayList<>(active.values());
    }

    public int killAll() {
        List<VorthakBoss> list = all();
        for (VorthakBoss b : list) {
            b.discard();
        }
        active.clear();
        return list.size();
    }

    public void shutdown() {
        Bukkit.removeRecipe(swordRecipeKey);
        for (VorthakBoss b : all()) {
            b.stop();
        }
        active.clear();
    }
}
