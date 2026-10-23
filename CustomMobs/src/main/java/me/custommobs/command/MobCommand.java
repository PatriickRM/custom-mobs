package me.custommobs.command;

import me.custommobs.CustomMobsPlugin;
import me.custommobs.Text;
import me.custommobs.boss.BossManager;
import me.custommobs.boss.VorthakBoss;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

public final class MobCommand implements TabExecutor {

    private final CustomMobsPlugin plugin;
    private final BossManager bosses;

    public MobCommand(CustomMobsPlugin plugin, BossManager bosses) {
        this.plugin = plugin;
        this.bosses = bosses;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Text.mm("<gray>Uso: <white>/mob <spawn|list|killall|reload></white>"));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "spawn" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase(VorthakBoss.ID)) {
                    sender.sendMessage(Text.mm("<gray>Mobs disponibles: <white>vorthak</white>"));
                    return true;
                }
                Location loc;
                if (args.length >= 6) {
                    World w = plugin.getServer().getWorld(args[2]);
                    if (w == null) {
                        sender.sendMessage(Text.mm("<red>Mundo no encontrado.</red>"));
                        return true;
                    }
                    try {
                        loc = new Location(w, Double.parseDouble(args[3]), Double.parseDouble(args[4]),
                                Double.parseDouble(args[5]));
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(Text.mm("<red>Coordenadas inválidas.</red>"));
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    loc = p.getLocation();
                } else {
                    sender.sendMessage(Text.mm("<red>Desde consola: /mob spawn vorthak <mundo> <x> <y> <z></red>"));
                    return true;
                }
                bosses.spawnVorthak(loc);
                sender.sendMessage(Text.mm("<gradient:#ff4d4d:#ffb347>Vorthak</gradient> <gray>ha despertado.</gray>"));
            }
            case "list" -> {
                List<VorthakBoss> all = bosses.all();
                sender.sendMessage(Text.mm("<gray>Jefes activos: <white>" + all.size() + "</white>"));
                for (VorthakBoss b : all) {
                    Location l = b.entity().getLocation();
                    sender.sendMessage(Text.mm("<dark_gray>-</dark_gray> <white>Vorthak</white> <gray>" + b.phase().name()
                            + " · " + (int) b.entity().getHealth() + " HP · " + l.getWorld().getName() + " "
                            + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + "</gray>"));
                }
            }
            case "killall" -> {
                int n = bosses.killAll();
                sender.sendMessage(Text.mm("<gray>Jefes eliminados: <white>" + n + "</white>"));
            }
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(Text.mm("<green>Configuración recargada.</green>"));
            }
            default -> sender.sendMessage(Text.mm("<red>Subcomando desconocido.</red>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return List.of("spawn", "list", "killall", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) return List.of("vorthak");
        return List.of();
    }
}
