package me.custommobs;

import me.custommobs.boss.BossListener;
import me.custommobs.boss.BossManager;
import me.custommobs.command.MobCommand;
import me.custommobs.dungeon.DungeonManager;
import me.custommobs.dungeon.WaveAbilities;
import me.custommobs.model.MobVisuals;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class CustomMobsPlugin extends JavaPlugin {

    private BossManager bosses;
    private DungeonManager dungeons;

    public DungeonManager dungeons() {
        return dungeons;
    }

    private MobVisuals visuals;
    private WaveAbilities abilities;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bosses = new BossManager(this);
        getServer().getPluginManager().registerEvents(new BossListener(this, bosses), this);

        PluginCommand cmd = getCommand("mob");
        if (cmd != null) {
            MobCommand handler = new MobCommand(this, bosses);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        // Dungeon: crea el mundo vacío y registra eventos y el comando /dungeon
        dungeons = new DungeonManager(this, bosses);
        dungeons.enable();

        // Modelos 3D (resource pack) y habilidades de los enemigos de oleadas
        visuals = new MobVisuals(this);
        visuals.enable();
        abilities = new WaveAbilities(this, dungeons, visuals);
        abilities.enable();

        // Re-adopta jefes que sigan cargados (por ejemplo tras /reload)
        bosses.adoptLoaded();
        getLogger().info("CustomMobs activado. Usa /mob spawn vorthak o /dungeon start");
    }

    @Override
    public void onDisable() {
        // Primero la dungeon (usa el BossManager para retirar a Vorthak) y luego los jefes
        if (visuals != null) {
            visuals.shutdown();
        }
        if (dungeons != null) {
            dungeons.shutdown();
        }
        if (bosses != null) {
            bosses.shutdown();
        }
    }

    public MobVisuals visuals() {
        return visuals;
    }

    public WaveAbilities abilities() {
        return abilities;
    }
}
