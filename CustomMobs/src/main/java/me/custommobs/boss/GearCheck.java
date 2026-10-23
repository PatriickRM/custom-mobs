package me.custommobs.boss;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Comprueba si un jugador tiene el equipo mínimo para herir al jefe. */
public final class GearCheck {

    public record Result(boolean ok, String reason) {}

    private static final Material[] DIAMOND = {
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE,
            Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS
    };
    private static final Material[] NETHERITE = {
            Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE,
            Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS
    };

    private GearCheck() {}

    /**
     * @param minProtection          Protección mínima en CADA pieza (diamante o netherite, se pueden mezclar)
     * @param minSharpness           Afilado mínimo si usa espada de diamante
     * @param allowNetherite         permite armadura y espada de netherite
     * @param netheriteMinSharpness  Afilado mínimo si usa espada de netherite (0 = cualquier encantamiento / ninguno)
     */
    public static Result check(Player p, int minProtection, int minSharpness,
                               boolean allowNetherite, int netheriteMinSharpness) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] pieces = {inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()};

        for (int i = 0; i < pieces.length; i++) {
            ItemStack piece = pieces[i];
            boolean typeOk = piece != null && (piece.getType() == DIAMOND[i]
                    || (allowNetherite && piece.getType() == NETHERITE[i]));
            if (!typeOk) {
                return new Result(false, allowNetherite
                        ? "Necesitas armadura de diamante o netherite completa"
                        : "Necesitas armadura de diamante completa");
            }
            if (piece.getEnchantmentLevel(Enchantment.PROTECTION) < minProtection) {
                return new Result(false, "Toda tu armadura necesita Protección " + roman(minProtection) + "+");
            }
        }

        ItemStack hand = inv.getItemInMainHand();
        Material type = hand.getType();
        if (type == Material.DIAMOND_SWORD) {
            if (hand.getEnchantmentLevel(Enchantment.SHARPNESS) < minSharpness) {
                return new Result(false, "Tu espada de diamante necesita Afilado " + roman(minSharpness) + "+");
            }
            return new Result(true, "");
        }
        if (allowNetherite && type == Material.NETHERITE_SWORD) {
            if (netheriteMinSharpness > 0 && hand.getEnchantmentLevel(Enchantment.SHARPNESS) < netheriteMinSharpness) {
                return new Result(false, "Tu espada de netherite necesita Afilado " + roman(netheriteMinSharpness) + "+");
            }
            return new Result(true, "");
        }
        return new Result(false, allowNetherite
                ? "Solo una espada de diamante o netherite puede herirlo"
                : "Solo una espada de diamante puede herirlo");
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }
}
