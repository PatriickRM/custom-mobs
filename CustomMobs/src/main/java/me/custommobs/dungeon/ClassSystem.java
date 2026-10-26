package me.custommobs.dungeon;

import me.custommobs.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Sala de inicio: el Heraldo de las Sombras (NPC). Cada jugador habla con él (clic derecho), elige su clase y
 * confirma que está listo. Cuando todos lo están hay una cuenta atrás corta, se aplican los kits y se abren las puertas.
 * Si alguien tarda demasiado se le asigna una clase al azar.
 */
final class ClassSystem {

    /** Menú de clases (el holder permite reconocer el inventario en el listener). */
    static final class Menu implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int[] SLOTS = {10, 12, 14, 16};
    private static final int SLOT_INFO = 4;
    private static final int SLOT_READY = 22;

    private static final class St {
        StartClass pick;
        boolean ready;
    }

    private final DungeonInstance inst;
    private final DungeonConfig cfg;
    private final World world;
    private final Random rnd = new Random();
    private final Map<UUID, St> states = new HashMap<>();
    private Villager npc;
    private Location npcAt;
    private long deadline;
    private long readyAt = -1;

    ClassSystem(DungeonInstance inst, DungeonConfig cfg, World world) {
        this.inst = inst;
        this.cfg = cfg;
        this.world = world;
    }

    boolean enabled() {
        return cfg.b("classes.enabled");
    }

    private St st(UUID u) {
        return states.computeIfAbsent(u, k -> new St());
    }

    // ------------------------------------------------------------------ NPC y ciclo

    /** Prepara la sala de inicio: limpia elecciones y coloca al Heraldo. Se llama al empezar y al reiniciar. */
    void begin(DungeonBuilder.Room r, long tick) {
        removeNpc();
        states.clear();
        readyAt = -1;
        if (!enabled()) return;
        deadline = tick + Math.max(20, cfg.i("classes.timeout-seconds")) * 20L;
        npcAt = r.center(world).add(4.5, 0, 0);
        npcAt.setYaw(90f);
        npc = world.spawn(npcAt, Villager.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setCollidable(false);
            v.setPersistent(false);
            v.setRemoveWhenFarAway(false);
            v.setProfession(Villager.Profession.CLERIC);
            v.customName(Text.mm("<gradient:#c471ed:#7f7fd5><bold>Heraldo de las Sombras</bold></gradient>"));
            v.setCustomNameVisible(true);
        });
        for (Player p : inst.onlineMembers()) {
            p.sendMessage(Text.mm(DungeonManager.PREFIX
                            + "<light_purple>Habla con el Heraldo de las Sombras</light_purple> <gray>para elegir tu clase y confirmar que estás listo.</gray> ")
                    .append(Text.mm("<green><bold>[Elegir clase]</bold></green>")
                            .clickEvent(ClickEvent.runCommand("/dungeon class"))));
        }
    }

    boolean isNpc(Entity e) {
        return npc != null && e.getUniqueId().equals(npc.getUniqueId());
    }

    private void removeNpc() {
        if (npc != null && npc.isValid()) npc.remove();
        npc = null;
    }

    void resetAll() {
        removeNpc();
        states.clear();
        readyAt = -1;
    }

    void ambient(long tick) {
        if (npcAt == null || tick % 10 != 0) return;
        Location l = npcAt.clone().add(0, 1.2, 0);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 3, 0.4, 0.6, 0.4, 0.01);
        world.spawnParticle(Particle.ENCHANT, l, 6, 0.6, 0.8, 0.6, 0.5);
    }

    String hint(long tick) {
        List<Player> al = inst.aliveOnline();
        int ready = 0;
        for (Player p : al) {
            St s = states.get(p.getUniqueId());
            if (s != null && s.ready) ready++;
        }
        if (readyAt >= 0) {
            long left = Math.max(0, readyAt + cfg.i("classes.start-delay-seconds") * 20L - tick);
            return "¡Todos listos! Empieza en " + (left / 20 + 1) + "s";
        }
        return "Habla con el Heraldo (/dungeon class) · listos " + ready + "/" + al.size()
                + " · " + Math.max(0, (deadline - tick) / 20) + "s";
    }

    /** @return true cuando todos están listos y acabó la cuenta atrás (o se agotó el tiempo). */
    boolean shouldStart(long tick) {
        List<Player> al = inst.aliveOnline();
        if (al.isEmpty()) return false;
        if (tick >= deadline) {
            for (Player p : al) {
                St s = st(p.getUniqueId());
                if (s.pick == null) s.pick = StartClass.values()[rnd.nextInt(StartClass.values().length)];
                s.ready = true;
            }
        }
        boolean all = true;
        for (Player p : al) {
            St s = states.get(p.getUniqueId());
            if (s == null || !s.ready) {
                all = false;
                break;
            }
        }
        if (!all) {
            readyAt = -1;
            return false;
        }
        if (readyAt < 0) {
            readyAt = tick;
            inst.soundAll(Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
        }
        long left = readyAt + Math.max(2, cfg.i("classes.start-delay-seconds")) * 20L - tick;
        if (left <= 0) return true;
        if (left % 20 == 0 && left / 20 <= 3) {
            inst.titleAll("<yellow><bold>" + (left / 20) + "</bold></yellow>", "<gray>Prepárate...</gray>");
            inst.soundAll(Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f + (3 - left / 20) * 0.15f);
        }
        return false;
    }

    /** Entrega los kits a todos y retira al Heraldo. */
    void apply() {
        for (Player p : inst.aliveOnline()) {
            St s = st(p.getUniqueId());
            if (s.pick == null) s.pick = StartClass.values()[rnd.nextInt(StartClass.values().length)];
            grantKit(p, s.pick);
        }
        if (npcAt != null) {
            world.spawnParticle(Particle.LARGE_SMOKE, npcAt.clone().add(0, 1, 0), 25, 0.3, 0.6, 0.3, 0.03);
            world.playSound(npcAt, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 0.8f);
        }
        removeNpc();
    }

    private void grantKit(Player p, StartClass c) {
        UUID u = p.getUniqueId();
        DungeonInstance.PState ps = inst.pstate(u);
        if (ps == null) return;
        for (StartClass.Grant g : c.grants) inst.blessings().grantStart(p, g.blessing(), g.level());
        if (c.lifeDelta != 0) {
            ps.bonusLives += c.lifeDelta;
            ps.lives = Math.max(1, ps.lives + c.lifeDelta);
        }
        if (c.relic != null) inst.relics().grant(u, c.relic, false);
        if (c.souls > 0) inst.merchant().addSouls(u, c.souls);
        p.showTitle(net.kyori.adventure.title.Title.title(
                Text.mm(c.color + "<bold>" + c.title.toUpperCase(Locale.ROOT) + "</bold>"),
                Text.mm("<gray>Tu clase de esta partida</gray>")));
        p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 0.5f, 1.4f);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1.2, 0), 25, 0.4, 0.7, 0.4, 0.3);
        inst.broadcast("<white>" + ps.name + "</white> <gray>será</gray> " + c.color + "<bold>" + c.title + "</bold>");
    }

    // ------------------------------------------------------------------ menú

    boolean openMenu(Player p) {
        DungeonInstance.PState ps = inst.pstate(p.getUniqueId());
        if (!enabled() || npc == null || inst.activeRoomIndex() != 0 || ps == null || ps.eliminated) return false;
        St s = st(p.getUniqueId());
        Menu menu = new Menu();
        Inventory inv = Bukkit.createInventory(menu, 27,
                Text.mm("<gradient:#c471ed:#7f7fd5><bold>⚔ Elige tu clase ⚔</bold></gradient>"));
        menu.inv = inv;
        render(inv, s);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.2f);
        return true;
    }

    private void render(Inventory inv, St s) {
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.space());
        filler.setItemMeta(fm);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        List<Player> al = inst.aliveOnline();
        int ready = 0;
        for (Player p : al) {
            St o = states.get(p.getUniqueId());
            if (o != null && o.ready) ready++;
        }
        inv.setItem(SLOT_INFO, item(Material.BOOK, "<light_purple><bold>Heraldo de las Sombras</bold></light_purple>", List.of(
                "<gray>Elige una clase y confirma.",
                "<gray>Las puertas se abren cuando todos",
                "<gray>estén listos.",
                "",
                "<white>Equipo listo: <green>" + ready + "<gray>/" + al.size()), false));

        StartClass[] all = StartClass.values();
        for (int i = 0; i < all.length && i < SLOTS.length; i++) {
            inv.setItem(SLOTS[i], classItem(all[i], s.pick == all[i]));
        }
        boolean can = s.pick != null;
        inv.setItem(SLOT_READY, item(s.ready ? Material.LIME_DYE : Material.GRAY_DYE,
                s.ready ? "<green><bold>Listo ✔</bold></green>" : "<yellow><bold>Estoy listo</bold></yellow>", List.of(
                        can ? "<gray>Clic para " + (s.ready ? "cancelar." : "confirmar.")
                                : "<red>Elige primero una clase."), false));
    }

    private ItemStack classItem(StartClass c, boolean selected) {
        List<String> lore = new ArrayList<>();
        for (String line : RelicSystem.wrap(c.tagline, 36)) lore.add("<gray>" + line);
        lore.add("");
        lore.add("<white>Bendiciones iniciales:");
        for (StartClass.Grant g : c.grants) {
            lore.add("  " + g.blessing().rarity.color + "• " + g.blessing().title + " <gray>nv " + g.level());
        }
        if (c.relic != null) lore.add("<white>Reliquia: " + c.relic.tier.color + c.relic.title);
        if (c.lifeDelta > 0) lore.add("<green>+" + c.lifeDelta + " vida");
        if (c.lifeDelta < 0) lore.add("<red>" + c.lifeDelta + " vida");
        if (c.souls > 0) lore.add("<gold>◆ +" + c.souls + " brasas iniciales");
        lore.add("<light_purple>Dash (F): <gray>" + (c.dashNote != null ? c.dashNote : "no tiene (consíguelo con bendiciones)"));
        lore.add("");
        lore.add(selected ? "<yellow>✔ Seleccionada" : "<green>▸ Clic para elegir");
        return item(c.icon, c.color + "<bold>" + c.title + "</bold>", lore, selected);
    }

    private ItemStack item(Material m, String name, List<String> lore, boolean glow) {
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

    // ------------------------------------------------------------------ clics

    void click(Player p, int slot) {
        if (npc == null || inst.activeRoomIndex() != 0) return;
        UUID u = p.getUniqueId();
        DungeonInstance.PState ps = inst.pstate(u);
        if (ps == null || ps.eliminated) return;
        St s = st(u);
        StartClass[] all = StartClass.values();
        boolean changed = false;
        for (int i = 0; i < all.length && i < SLOTS.length; i++) {
            if (SLOTS[i] != slot) continue;
            s.pick = all[i];
            s.ready = false;
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.3f);
            changed = true;
        }
        if (slot == SLOT_READY) {
            if (s.pick == null) {
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
                return;
            }
            s.ready = !s.ready;
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, s.ready ? 1.5f : 0.9f);
            changed = true;
            if (s.ready) {
                inst.broadcast("<white>" + ps.name + "</white> <gray>está listo como</gray> " + s.pick.color
                        + "<bold>" + s.pick.title + "</bold>");
                inst.later(1, () -> {
                    if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
                });
            }
        }
        if (!changed) return;
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof Menu) render(top, s);
    }
}
