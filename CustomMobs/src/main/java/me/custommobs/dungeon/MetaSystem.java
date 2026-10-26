package me.custommobs.dungeon;

import me.custommobs.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Meta-progresión: cada partida (ganada o perdida) da <b>Ecos</b>, una moneda permanente que se gasta en mejoras
 * que acompañan al jugador en todas sus partidas futuras. No tiene estado propio: todo vive en {@link DungeonData}.
 */
final class MetaSystem {

    /** Mejoras permanentes. {@code costs[i]} es el precio de pasar al nivel i+1. */
    enum Perk {
        PURSE("Bolsa de Brasas", Material.GOLD_NUGGET, "Empiezas cada partida con +10 brasas por nivel.", 20, 45, 90),
        FORTUNE("Fortuna", Material.RABBIT_FOOT, "+1 relanzamiento de bendiciones por nivel.", 30, 60, 120),
        SECOND_BREATH("Segundo Aliento", Material.TOTEM_OF_UNDYING, "Empiezas con una vida extra.", 150),
        LEGACY("Legado", Material.ECHO_SHARD, "Empiezas con una reliquia común al azar.", 100);

        final String title;
        final Material icon;
        final String desc;
        final int[] costs;

        Perk(String title, Material icon, String desc, int... costs) {
            this.title = title;
            this.icon = icon;
            this.desc = desc;
            this.costs = costs;
        }

        int max() {
            return costs.length;
        }

        String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Titular del inventario (para reconocer el menú en los eventos). */
    static final class Menu implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int SLOT_INFO = 4;
    private static final int[] SLOTS = {10, 12, 14, 16};

    private final DungeonManager manager;

    MetaSystem(DungeonManager manager) {
        this.manager = manager;
    }

    boolean enabled() {
        return manager.cfg().b("meta.enabled");
    }

    // ------------------------------------------------------------------ ecos

    /** Ecos ganados al terminar la partida: por sala superada, bonus por victoria y por nivel de Calor. */
    void award(UUID u, int cleared, boolean won, int heatLevel) {
        if (!enabled()) return;
        DungeonConfig c = manager.cfg();
        int n = Math.max(0, cleared) * Math.max(0, c.i("meta.per-room"));
        if (won) n += Math.max(0, c.i("meta.win-bonus")) + Math.max(0, heatLevel) * Math.max(0, c.i("meta.per-heat-level"));
        if (n <= 0) return;
        manager.data().addEchoes(u, n);
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            p.sendMessage(Text.mm(DungeonManager.PREFIX + "<aqua><bold>+" + n + " Ecos</bold></aqua> <gray>(total "
                    + manager.data().echoes(u) + "). Gástalos con <white>/dungeon meta</white>.</gray>"));
        }
    }

    // ------------------------------------------------------------------ efectos al empezar

    /** Aplica las mejoras permanentes de cada jugador al abrirse las puertas. */
    void applyStart(DungeonInstance inst) {
        if (!enabled()) return;
        DungeonData d = manager.data();
        for (Player p : inst.aliveOnline()) {
            UUID u = p.getUniqueId();
            DungeonInstance.PState ps = inst.pstate(u);
            if (ps == null) continue;
            int purse = d.perkLevel(u, Perk.PURSE.key());
            if (purse > 0) inst.merchant().addSouls(u, purse * 10);
            int fortune = d.perkLevel(u, Perk.FORTUNE.key());
            if (fortune > 0) ps.rerolls += fortune;
            if (d.perkLevel(u, Perk.SECOND_BREATH.key()) > 0) {
                ps.bonusLives++;
                ps.lives++;
            }
            if (d.perkLevel(u, Perk.LEGACY.key()) > 0) {
                List<Relic> pool = new ArrayList<>();
                for (Relic r : Relic.values()) if (r.tier == Relic.Tier.COMMON) pool.add(r);
                if (!pool.isEmpty()) {
                    inst.relics().grant(u, pool.get(ThreadLocalRandom.current().nextInt(pool.size())), true);
                }
            }
        }
    }

    // ------------------------------------------------------------------ menú

    ItemStack button(Player p) {
        return item(Material.ECHO_SHARD, "<aqua><bold>Santuario de Ecos</bold></aqua>", List.of(
                "<gray>Mejoras permanentes para todas",
                "<gray>tus partidas.",
                "",
                "<white>Ecos: <aqua>" + manager.data().echoes(p.getUniqueId()),
                "",
                "<green>▸ Clic para abrir"), false);
    }

    void open(Player p) {
        if (!enabled()) {
            p.sendMessage(Text.mm(DungeonManager.PREFIX + "<red>Las mejoras permanentes están desactivadas.</red>"));
            return;
        }
        Menu menu = new Menu();
        Inventory inv = Bukkit.createInventory(menu, 27, Text.mm("<gradient:#4facfe:#00f2fe><bold>✦ Santuario de Ecos ✦</bold></gradient>"));
        menu.inv = inv;
        render(inv, p);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.2f);
    }

    private void render(Inventory inv, Player p) {
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.space());
        filler.setItemMeta(fm);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        UUID u = p.getUniqueId();
        int echoes = manager.data().echoes(u);
        inv.setItem(SLOT_INFO, item(Material.ECHO_SHARD, "<aqua><bold>Ecos: " + echoes + "</bold></aqua>", List.of(
                "<gray>Ganas Ecos en cada partida:",
                "<gray>por sala superada y un bonus",
                "<gray>por victoria y por Calor."), false));
        Perk[] all = Perk.values();
        for (int i = 0; i < all.length && i < SLOTS.length; i++) {
            Perk perk = all[i];
            int lvl = manager.data().perkLevel(u, perk.key());
            List<String> lore = new ArrayList<>();
            for (String line : RelicSystem.wrap(perk.desc, 36)) lore.add("<gray>" + line);
            lore.add("");
            lore.add("<white>Nivel: <aqua>" + lvl + "<gray>/" + perk.max());
            if (lvl >= perk.max()) {
                lore.add("<green>✔ Al máximo");
            } else {
                int cost = perk.costs[lvl];
                lore.add("<white>Siguiente: <aqua>" + cost + " Ecos");
                lore.add(echoes >= cost ? "<green>▸ Clic para mejorar" : "<red>✖ Ecos insuficientes");
            }
            inv.setItem(SLOTS[i], item(perk.icon, "<aqua><bold>" + perk.title + "</bold>", lore, lvl > 0));
        }
    }

    void click(Player p, int slot) {
        Perk[] all = Perk.values();
        for (int i = 0; i < all.length && i < SLOTS.length; i++) {
            if (SLOTS[i] != slot) continue;
            Perk perk = all[i];
            UUID u = p.getUniqueId();
            int lvl = manager.data().perkLevel(u, perk.key());
            if (lvl >= perk.max()) return;
            int cost = perk.costs[lvl];
            if (manager.data().echoes(u) < cost) {
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
                return;
            }
            manager.data().addEchoes(u, -cost);
            manager.data().setPerkLevel(u, perk.key(), lvl + 1);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
            p.sendMessage(Text.mm(DungeonManager.PREFIX + "<aqua><bold>" + perk.title + "</bold></aqua> <gray>nivel "
                    + (lvl + 1) + ". Se aplica desde tu próxima partida.</gray>"));
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof Menu) render(top, p);
            return;
        }
    }

    private static ItemStack item(Material m, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item(name));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(s.isEmpty() ? Component.empty() : Text.item(s));
        meta.lore(l);
        if (glow) meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }
}
