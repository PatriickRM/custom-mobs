package me.custommobs.boss;

import me.custommobs.CustomMobsPlugin;
import me.custommobs.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Fábrica de todos los items custom del jefe. */
public final class BossItems {

    public static final String HEART = "ash_heart";
    public static final String BLADE = "ash_blade";
    public static final String SWORD = "ash_sword";

    private BossItems() {}

    // ------------------------------------------------------------------ identificación

    public static String idOf(BossManager m, ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(m.itemKey, PersistentDataType.STRING);
    }

    /** Multiplicador de daño contra Vorthak y sus Heraldos según el arma en mano. */
    public static double vorthakBonus(CustomMobsPlugin plugin, BossManager m, ItemStack hand) {
        String id = idOf(m, hand);
        if (SWORD.equals(id)) return plugin.getConfig().getDouble("ash-sword.vorthak-damage-multiplier", 1.35);
        if (BLADE.equals(id)) return plugin.getConfig().getDouble("blade.vorthak-damage-multiplier", 1.20);
        return 1.0;
    }

    // ------------------------------------------------------------------ items

    public static ItemStack heart(BossManager m) {
        ItemStack it = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item("<gradient:#ff5f6d:#ffc371><bold>Corazón de Ceniza</bold></gradient>"));
        meta.lore(List.of(
                Text.item("<dark_gray>Aún late con el fuego de Vorthak.</dark_gray>"),
                Text.item(""),
                Text.item("<gray>Úsalo en una mesa de crafteo para forjar</gray>"),
                Text.item("<gray>la <gold>Segadora de Ceniza</gold>.</gray>"),
                Text.item(""),
                Text.item("<gold>Trofeo de jefe</gold>")));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(m.itemKey, PersistentDataType.STRING, HEART);
        it.setItemMeta(meta);
        return it;
    }

    /** Arma de drop (rara). */
    public static ItemStack blade(BossManager m) {
        ItemStack it = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item("<gradient:#ff4d4d:#ffb347><bold>Hoja de Ceniza</bold></gradient>"));
        meta.lore(List.of(
                Text.item("<dark_gray>Forjada en el último aliento de Vorthak.</dark_gray>"),
                Text.item(""),
                Text.item("<dark_gray>▸</dark_gray> <gray>+20% de daño contra <red>Vorthak</red></gray>"),
                Text.item(""),
                Text.item("<gold>Arma de jefe</gold>")));
        meta.addEnchant(Enchantment.SHARPNESS, 6, true);
        meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
        meta.addEnchant(Enchantment.LOOTING, 4, true);
        meta.addEnchant(Enchantment.UNBREAKING, 5, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);
        meta.getPersistentDataContainer().set(m.itemKey, PersistentDataType.STRING, BLADE);
        it.setItemMeta(meta);
        return it;
    }

    /** Arma crafteable con el Corazón de Ceniza. */
    public static ItemStack ashSword(BossManager m) {
        ItemStack it = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item("<gradient:#ff3d00:#ffd86b><bold>✦ Segadora de Ceniza ✦</bold></gradient>"));
        meta.lore(List.of(
                Text.item("<dark_gray>Forjada con el corazón de un centinela caído.</dark_gray>"),
                Text.item("<dark_gray>Sus brasas nunca se apagan.</dark_gray>"),
                Text.item(""),
                Text.item("<gold>Cicatriz de Ceniza</gold>"),
                Text.item("<dark_gray>▸</dark_gray> <gray>+35% de daño contra <red>Vorthak</red> y sus <light_purple>Heraldos</light_purple></gray>"),
                Text.item("<dark_gray>▸</dark_gray> <gray>Cada golpe libera <aqua>almas ardientes</aqua></gray>"),
                Text.item(""),
                Text.item("<gradient:#ff3d00:#ffd86b>✦ Arma legendaria ✦</gradient>")));
        meta.addEnchant(Enchantment.SHARPNESS, 6, true);
        meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
        meta.addEnchant(Enchantment.SWEEPING_EDGE, 3, true);
        meta.addEnchant(Enchantment.LOOTING, 4, true);
        meta.addEnchant(Enchantment.UNBREAKING, 5, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);
        meta.getPersistentDataContainer().set(m.itemKey, PersistentDataType.STRING, SWORD);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack book(Enchantment ench, int level) {
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) it.getItemMeta();
        meta.addStoredEnchant(ench, level, true);
        meta.lore(List.of(
                Text.item(""),
                Text.item("<gold>Botín de Vorthak</gold>")));
        it.setItemMeta(meta);
        return it;
    }

    // ------------------------------------------------------------------ drops por porcentaje

    private record BookDrop(String enchant, int level, double chance) {}

    /**
     * Tira los dados de cada libro (cada uno es independiente). Se configura en drops.books del config:
     * <pre>
     * drops:
     *   books:
     *     - {enchant: protection, level: 5, chance: 0.30}
     * </pre>
     */
    public static List<ItemStack> rollBooks(CustomMobsPlugin plugin) {
        List<BookDrop> table = new ArrayList<>();
        for (Map<?, ?> row : plugin.getConfig().getMapList("drops.books")) {
            try {
                String en = String.valueOf(row.get("enchant"));
                int lv = (int) Double.parseDouble(String.valueOf(row.get("level")));
                double ch = Double.parseDouble(String.valueOf(row.get("chance")));
                table.add(new BookDrop(en, lv, ch));
            } catch (RuntimeException ignored) {
                // fila mal escrita: se ignora
            }
        }
        if (table.isEmpty()) {
            table.add(new BookDrop("protection", 5, 0.30));
            table.add(new BookDrop("sharpness", 6, 0.20));
            table.add(new BookDrop("sharpness", 7, 0.05));
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<ItemStack> out = new ArrayList<>();
        for (BookDrop d : table) {
            if (rnd.nextDouble() >= d.chance()) continue;
            Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(d.enchant().toLowerCase()));
            if (ench == null) {
                plugin.getLogger().warning("drops.books: encantamiento desconocido '" + d.enchant() + "'");
                continue;
            }
            out.add(book(ench, d.level()));
        }
        return out;
    }
}
