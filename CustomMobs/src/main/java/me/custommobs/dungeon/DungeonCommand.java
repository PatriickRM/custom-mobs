package me.custommobs.dungeon;

import me.custommobs.CustomMobsPlugin;
import me.custommobs.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /dungeon join | start | leave | status | top | reset | reload | setlobby | clearcooldown */
public final class DungeonCommand implements CommandExecutor, TabCompleter {

    private static final List<String> PLAYER_SUBS = List.of("join", "start", "leave", "status", "top", "bless", "relic", "path", "help");
    private static final List<String> ADMIN_SUBS = List.of("reset", "reload", "setlobby", "clearcooldown", "list");

    private final CustomMobsPlugin plugin;
    private final DungeonManager manager;

    public DungeonCommand(CustomMobsPlugin plugin, DungeonManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private void send(CommandSender s, String mm) {
        s.sendMessage(Text.mm(DungeonManager.PREFIX + mm));
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("dungeon.admin")) return true;
        send(s, "<red>No tienes permiso.</red>");
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join" -> {
                if (!(sender instanceof Player p)) return playerOnly(sender);
                String err = manager.join(p);
                if (err != null) send(p, "<red>" + err + "</red>");
                else send(p, "<green>Estás en la cola.</green> <gray>Usa <white>/dungeon start</white> para empezar ya.</gray>");
            }
            case "start" -> {
                if (!(sender instanceof Player p)) return playerOnly(sender);
                String err = manager.forceStart(p);
                if (err != null) send(p, "<red>" + err + "</red>");
            }
            case "leave" -> {
                if (!(sender instanceof Player p)) return playerOnly(sender);
                String err = manager.leave(p);
                if (err != null) send(p, "<red>" + err + "</red>");
            }
            case "bless" -> {
                if (!(sender instanceof Player p)) return playerOnly(sender);
                DungeonInstance inst = manager.of(p);
                if (inst == null || !inst.openBlessingMenu(p)) send(p, "<red>No tienes ninguna bendición pendiente.</red>");
            }
            case "relic" -> {
                if (!(sender instanceof Player p)) return playerOnly(sender);
                DungeonInstance inst = manager.of(p);
                if (inst == null || !inst.openRelicMenu(p)) send(p, "<red>No tienes ninguna reliquia pendiente.</red>");
            }
            case "path" -> {
                if (!(sender instanceof Player p)) return playerOnly(sender);
                DungeonInstance inst = manager.of(p);
                if (inst == null) {
                    send(p, "<red>No estás en una dungeon.</red>");
                } else if (args.length < 2) {
                    send(p, "<red>Uso: /dungeon path <número></red>");
                } else {
                    int n;
                    try {
                        n = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ex) {
                        n = -1;
                    }
                    String err = inst.vote(p, n);
                    if (err != null) send(p, "<red>" + err + "</red>");
                }
            }
            case "status" -> status(sender);
            case "top" -> top(sender, args.length > 1 && args[1].equalsIgnoreCase("time"));
            case "list" -> {
                if (!admin(sender)) return true;
                List<DungeonInstance> list = manager.instances();
                send(sender, "<gray>Partidas activas: <white>" + list.size() + "</white> · en cola: <white>" + manager.queueSize() + "</white></gray>");
                for (DungeonInstance i : list) {
                    send(sender, "<dark_gray>▸</dark_gray> <gray>" + i.describe() + " — " + String.join(", ", i.memberNames()) + "</gray>");
                }
            }
            case "reset" -> {
                if (!admin(sender)) return true;
                manager.resetAll();
                send(sender, "<green>Todas las partidas y la cola han sido reiniciadas.</green>");
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                manager.reload();
                send(sender, "<green>dungeon.yml recargado.</green> <gray>(Las partidas en curso no cambian.)</gray>");
            }
            case "setlobby" -> {
                if (!admin(sender)) return true;
                if (!(sender instanceof Player p)) return playerOnly(sender);
                manager.setLobby(p.getLocation());
                send(p, "<green>Lobby de la dungeon fijado aquí.</green> <gray>Los jugadores volverán a este punto al terminar.</gray>");
            }
            case "clearcooldown" -> {
                if (!admin(sender)) return true;
                if (args.length < 2) {
                    send(sender, "<red>Uso: /dungeon clearcooldown <jugador></red>");
                    return true;
                }
                OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
                manager.data().clearCooldown(op.getUniqueId());
                send(sender, "<green>Cooldown de <white>" + args[1] + "</white> eliminado.</green>");
            }
            default -> help(sender);
        }
        return true;
    }

    private boolean playerOnly(CommandSender s) {
        send(s, "<red>Solo para jugadores.</red>");
        return true;
    }

    private void help(CommandSender s) {
        send(s, "<gray>Comandos:</gray>");
        s.sendMessage(Text.mm("<dark_gray>▸</dark_gray> <white>/dungeon join</white> <gray>— entrar en la cola</gray>"));
        s.sendMessage(Text.mm("<dark_gray>▸</dark_gray> <white>/dungeon start</white> <gray>— empezar ya (con el grupo en cola)</gray>"));
        s.sendMessage(Text.mm("<dark_gray>▸</dark_gray> <white>/dungeon leave</white> <gray>— salir de la cola o de la dungeon</gray>"));
        s.sendMessage(Text.mm("<dark_gray>▸</dark_gray> <white>/dungeon bless</white> <gray>— elegir tu bendición pendiente</gray>"));
        s.sendMessage(Text.mm("<dark_gray>▸</dark_gray> <white>/dungeon relic</white> <gray>— elegir tu reliquia pendiente</gray>"));
        s.sendMessage(Text.mm("<dark_gray>▸</dark_gray> <white>/dungeon path <n></white> <gray>— votar el camino cuando hay dos salas</gray>"));
        s.sendMessage(Text.mm("<dark_gray>▸</dark_gray> <white>/dungeon status</white> <gray>— estado y tu cooldown</gray>"));
        s.sendMessage(Text.mm("<dark_gray>▸</dark_gray> <white>/dungeon top</white> <gray>— mejores puntajes</gray>"));
        if (s.hasPermission("dungeon.admin")) {
            s.sendMessage(Text.mm("<dark_gray>▸</dark_gray> <white>/dungeon reset | reload | setlobby | list | clearcooldown <jugador></white> <gray>(admin)</gray>"));
        }
    }

    private void status(CommandSender s) {
        if (!manager.isEnabled()) {
            send(s, "<red>La dungeon está desactivada.</red>");
            return;
        }
        send(s, "<gray>En cola: <white>" + manager.queueSize() + "</white>/" + manager.cfg().i("lobby.max-players")
                + " · partidas activas: <white>" + manager.instances().size() + "</white></gray>");
        Modifier m = Modifier.today(manager.cfg().s("modifiers.force"), manager.cfg().b("modifiers.enabled"));
        if (m != null) send(s, "<gray>Modificador de hoy: <light_purple><bold>" + m.title + "</bold></light_purple> — " + m.description + "</gray>");
        if (s instanceof Player p) {
            DungeonInstance inst = manager.of(p);
            if (inst != null) send(p, "<gray>Estás en una dungeon · tiempo <aqua>" + inst.elapsedSeconds() / 60 + "m " + inst.elapsedSeconds() % 60 + "s</aqua></gray>");
            long left = manager.data().cooldownLeftMillis(p.getUniqueId());
            if (left > 0) send(p, "<gray>Cooldown: <white>" + (left / 60000) + " min " + ((left / 1000) % 60) + " s</white></gray>");
            else send(p, "<green>Puedes entrar ahora.</green>");
        }
    }

    private void top(CommandSender s, boolean byTime) {
        List<DungeonData.Record> top = manager.data().top(byTime ? 1000 : 5);
        if (byTime) {
            top = new ArrayList<>(top);
            top.removeIf(r -> r.bestTimeSeconds() <= 0);
            top.sort(java.util.Comparator.comparingInt(DungeonData.Record::bestTimeSeconds));
            if (top.size() > 5) top = new ArrayList<>(top.subList(0, 5));
        }
        if (top.isEmpty()) {
            send(s, "<gray>Aún no hay récords.</gray>");
            return;
        }
        send(s, byTime ? "<gradient:#7be6f4:#27b3d6><bold>Más rápidos</bold></gradient> <gray>(/dungeon top)</gray>"
                : "<gradient:#ffd86b:#ff9a3c><bold>Mejores puntajes</bold></gradient> <gray>(/dungeon top time)</gray>");
        int i = 1;
        for (DungeonData.Record r : top) {
            s.sendMessage(Text.mm("<gold>#" + i++ + "</gold> <white>" + r.name() + "</white> <gray>— <white>" + r.bestScore()
                    + "</white> pts · mejor tiempo <aqua>" + r.bestTimeSeconds() / 60 + "m " + r.bestTimeSeconds() % 60
                    + "s</aqua> · partidas " + r.runs() + "</gray>"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String pre = args[0].toLowerCase(Locale.ROOT);
            for (String s : PLAYER_SUBS) if (s.startsWith(pre)) out.add(s);
            if (sender.hasPermission("dungeon.admin")) {
                for (String s : ADMIN_SUBS) if (s.startsWith(pre)) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("clearcooldown") && sender.hasPermission("dungeon.admin")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }
}
