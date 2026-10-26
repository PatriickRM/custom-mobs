package me.custommobs.dungeon;

import me.custommobs.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Niveles de Calor (dificultad opcional, estilo "ascensión"). Cada jugador elige su nivel en el menú del Heraldo de
 * las Sombras; el grupo juega con la <b>media</b> redondeada de las elecciones. Los niveles son acumulativos y se
 * desbloquean uno a uno al completar la dungeon con el Calor anterior. A cambio de más riesgo, el puntaje sube.
 *
 * <p>Nivel 1: enemigos con más vida · 2: enemigos con más daño · 3: más élites · 4: una vida menos ·
 * 5: una oleada extra en las salas de oleadas.</p>
 */
final class HeatSystem {

    static final int MAX = 5;

    private static final String[] NAMES = {
            "Sin Calor", "Marea de Ceniza", "Colmillos Afilados", "Élites Voraces", "Vidas Contadas", "Sin Tregua"};

    private final DungeonInstance inst;
    private final DungeonConfig cfg;
    /** Elección de cada jugador (se conserva entre reinicios de la partida). */
    private final Map<UUID, Integer> choice = new HashMap<>();
    /** Nivel de la partida: se fija al abrir las puertas de la sala de inicio. */
    private int level;

    HeatSystem(DungeonInstance inst, DungeonConfig cfg) {
        this.inst = inst;
        this.cfg = cfg;
    }

    boolean enabled() {
        return cfg.b("heat.enabled");
    }

    int level() {
        return level;
    }

    String name(int lvl) {
        return NAMES[Math.max(0, Math.min(MAX, lvl))];
    }

    /** Se llama al reiniciar la partida: el nivel se vuelve a fijar al salir de la sala de inicio. */
    void resetAll() {
        level = 0;
    }

    // ------------------------------------------------------------------ efectos (multiplicadores)

    double hpMul() {
        return level >= 1 ? 1.0 + cfg.d("heat.health-bonus") : 1.0;
    }

    double dmgMul() {
        return level >= 2 ? 1.0 + cfg.d("heat.damage-bonus") : 1.0;
    }

    double eliteMul() {
        return level >= 3 ? Math.max(1.0, cfg.d("heat.elite-multiplier")) : 1.0;
    }

    int extraWaves() {
        return level >= 5 ? Math.max(0, cfg.i("heat.extra-waves")) : 0;
    }

    double scoreMul() {
        return 1.0 + Math.max(0.0, cfg.d("heat.score-bonus-per-level")) * level;
    }

    // ------------------------------------------------------------------ elección y desbloqueo

    int unlocked(UUID u) {
        return Math.max(0, Math.min(MAX, inst.heatUnlocked(u)));
    }

    int chosen(UUID u) {
        return Math.min(choice.getOrDefault(u, 0), unlocked(u));
    }

    /** Pasa al siguiente nivel disponible del jugador (tras el último vuelve a 0). */
    void cycle(Player p) {
        UUID u = p.getUniqueId();
        int max = unlocked(u);
        if (max <= 0) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
            p.sendMessage(Text.mm(DungeonManager.PREFIX
                    + "<gray>Completa la dungeon para desbloquear el <red>Calor 1</red>.</gray>"));
            return;
        }
        int cur = chosen(u);
        int next = cur >= max ? 0 : cur + 1;
        choice.put(u, next);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 0.8f + 0.15f * next);
    }

    /** Media redondeada de lo que han elegido los jugadores vivos. */
    int groupChoice() {
        List<Player> al = inst.aliveOnline();
        if (al.isEmpty()) return 0;
        int sum = 0;
        for (Player p : al) sum += chosen(p.getUniqueId());
        return (int) Math.round(sum / (double) al.size());
    }

    /** Fija el nivel de la partida y aplica sus efectos permanentes (la vida de menos). */
    void lock() {
        level = enabled() ? groupChoice() : 0;
        if (level <= 0) return;
        if (level >= 4) {
            for (DungeonInstance.PState ps : inst.memberStates().values()) {
                if (inst.maxLives() + ps.bonusLives <= 1) continue;
                ps.bonusLives--;
                ps.lives = Math.max(1, ps.lives - 1);
            }
        }
        inst.broadcast("<red><bold>Calor " + level + "</bold></red> <gray>— " + name(level) + "</gray>");
    }

    /**
     * Al completar la dungeon se desbloquea el siguiente nivel a quienes jugaron en el máximo que tenían (o más).
     * Nunca se salta un nivel: como mucho se gana uno por partida.
     */
    void onComplete(UUID u) {
        if (!enabled() || level < unlocked(u) || level >= MAX) return;
        int to = Math.min(level + 1, unlocked(u) + 1);
        if (to <= unlocked(u)) return;
        inst.unlockHeat(u, to);
        Player p = org.bukkit.Bukkit.getPlayer(u);
        if (p != null) {
            p.sendMessage(Text.mm(DungeonManager.PREFIX + "<red><bold>¡Calor " + to + " desbloqueado!</bold></red> <gray>"
                    + name(to) + "</gray>"));
        }
    }

    // ------------------------------------------------------------------ menú

    private String describe(int lvl) {
        return switch (lvl) {
            case 1 -> "Enemigos con +" + pct(cfg.d("heat.health-bonus")) + "% de vida";
            case 2 -> "Enemigos con +" + pct(cfg.d("heat.damage-bonus")) + "% de daño";
            case 3 -> "Aparecen x" + String.format(java.util.Locale.ROOT, "%.1f", cfg.d("heat.elite-multiplier"))
                    + " más élites";
            case 4 -> "Una vida menos para todos";
            case 5 -> "+" + cfg.i("heat.extra-waves") + " oleada en las salas de oleadas";
            default -> "";
        };
    }

    private static int pct(double v) {
        return (int) Math.round(v * 100.0);
    }

    /** Botón del menú del Heraldo. */
    ItemStack item(Player p) {
        UUID u = p.getUniqueId();
        int cur = chosen(u);
        int max = unlocked(u);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Más riesgo, más puntaje. Los niveles se suman.");
        lore.add("");
        for (int l = 1; l <= MAX; l++) {
            String line = "Calor " + l + " · " + name(l) + ": " + describe(l);
            if (l > max) lore.add("<dark_gray>✖ " + line);
            else if (l <= cur) lore.add("<red>✔ " + line);
            else lore.add("<gray>○ " + line);
        }
        lore.add("");
        lore.add("<white>Tu elección: <red>" + cur + "<gray> · Desbloqueado: <white>" + max + "<gray>/" + MAX);
        lore.add("<white>Calor del grupo: <red>" + groupChoice() + " <dark_gray>(media del equipo)");
        if (cur > 0) lore.add("<gold>Puntaje x" + String.format(java.util.Locale.ROOT, "%.2f", 1.0
                + Math.max(0.0, cfg.d("heat.score-bonus-per-level")) * cur) + " <dark_gray>(si el grupo juega a tu nivel)");
        lore.add("");
        lore.add(max <= 0 ? "<red>✖ Completa la dungeon para desbloquear" : "<green>▸ Clic para cambiar de nivel");
        ItemStack it = new ItemStack(cur > 0 ? Material.MAGMA_BLOCK : Material.CAMPFIRE);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item("<red><bold>Calor: " + name(cur) + "</bold></red>"));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(s.isEmpty() ? Component.empty() : Text.item(s));
        meta.lore(l);
        if (cur > 0) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }
}
