package me.custommobs.dungeon;

import me.custommobs.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mercader Fantasma: sala sin combate donde cada jugador gasta ALMAS (se ganan matando enemigos) o VIDAS.
 *
 * <p>Es independiente del resto: solo usa {@link RelicSystem#rollFor} / {@link RelicSystem#grant} para vender
 * reliquias, y {@link DungeonInstance.PState#lives} para cobrar vidas. Todos los precios salen de dungeon.yml
 * (sección merchant). Las almas se conservan entre salas durante la partida.</p>
 */
final class MerchantSystem {

    /** Menú de la tienda (el holder permite reconocer el inventario en el listener). */
    static final class Menu implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int SIZE = 36;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_SOUL_RELIC = 10;
    private static final int SLOT_BLOOD_RELIC = 12;
    private static final int SLOT_HEAL = 14;
    private static final int SLOT_LIFE = 16;
    private static final int SLOT_PACT = 22;
    private static final int SLOT_READY = 31;

    /** Estado de cada jugador. Las almas duran toda la partida; el stock se renueva en cada visita. */
    private static final class St {
        int souls;
        Relic soulRelic;
        Relic bloodRelic;
        boolean soulSold;
        boolean bloodSold;
        boolean lifeBought;
        boolean pactDone;
        int heals;
        boolean ready;
    }

    private final DungeonInstance inst;
    private final DungeonConfig cfg;
    private final World world;
    private final Map<UUID, St> states = new HashMap<>();
    private boolean open;
    private long endTick;
    private TextDisplay sign;

    MerchantSystem(DungeonInstance inst, DungeonConfig cfg, World world) {
        this.inst = inst;
        this.cfg = cfg;
        this.world = world;
    }

    private St st(UUID u) {
        return states.computeIfAbsent(u, k -> new St());
    }

    void resetAll() {
        states.clear();
        open = false;
        removeSign();
    }

    boolean enabled() {
        return cfg.b("merchant.enabled");
    }

    // ------------------------------------------------------------------ almas

    /** Cada enemigo muerto da almas a quien lo mata. */
    void onKill(Player killer, boolean elite) {
        if (!enabled()) return;
        int n = elite ? cfg.i("merchant.souls-per-elite") : cfg.i("merchant.souls-per-kill");
        if (n > 0) st(killer.getUniqueId()).souls += n;
    }

    /** Almas para todos los jugadores vivos (mini jefe...). */
    void grantAll(int n) {
        if (!enabled() || n <= 0) return;
        for (Player p : inst.aliveOnline()) st(p.getUniqueId()).souls += n;
    }

    int souls(UUID u) {
        St s = states.get(u);
        return s == null ? 0 : s.souls;
    }

    /** Suma (o resta, sin bajar de 0) almas a un jugador. */
    void addSouls(UUID u, int n) {
        St s = st(u);
        s.souls = Math.max(0, s.souls + n);
    }

    String hudLine(Player p) {
        St s = states.get(p.getUniqueId());
        if (s == null || (s.souls <= 0 && !open)) return "";
        return "<dark_aqua>☾" + s.souls + "</dark_aqua>";
    }

    // ------------------------------------------------------------------ ciclo de la sala

    void start(DungeonBuilder.Room r, long tick) {
        open = true;
        endTick = tick + Math.max(10, cfg.i("merchant.seconds")) * 20L;
        spawnSign(r);
        world.playSound(r.center(world), Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 1.5f, 0.5f);
        for (Player p : inst.aliveOnline()) {
            St s = st(p.getUniqueId());
            s.soulSold = false;
            s.bloodSold = false;
            s.lifeBought = false;
            s.pactDone = false;
            s.heals = 0;
            s.ready = false;
            List<Relic> stock = inst.relics().rollFor(p.getUniqueId(), 2);
            s.soulRelic = stock.size() > 0 ? stock.get(0) : null;
            s.bloodRelic = stock.size() > 1 ? stock.get(1) : null;
            p.sendMessage(Text.mm(DungeonManager.PREFIX + "<dark_aqua>El Mercader Fantasma te espera.</dark_aqua> ")
                    .append(Text.mm("<green><bold>[Abrir tienda]</bold></green>")
                            .clickEvent(ClickEvent.runCommand("/dungeon shop"))));
            openMenu(p);
        }
    }

    /** @return true cuando se acabó el tiempo o todos los vivos pulsaron «Listo». */
    boolean finished(long tick) {
        if (!open) return false;
        List<Player> al = inst.aliveOnline();
        if (al.isEmpty()) return false;
        boolean all = true;
        for (Player p : al) {
            St s = states.get(p.getUniqueId());
            if (s == null || !s.ready) {
                all = false;
                break;
            }
        }
        return all || tick >= endTick;
    }

    String hint(long tick) {
        int ready = 0;
        List<Player> al = inst.aliveOnline();
        for (Player p : al) {
            St s = states.get(p.getUniqueId());
            if (s != null && s.ready) ready++;
        }
        return "Mercader: " + Math.max(0, (endTick - tick) / 20) + "s · listos " + ready + "/" + al.size()
                + " · /dungeon shop";
    }

    void ambient(DungeonBuilder.Room r, long tick) {
        if (tick % 10 != 0) return;
        double x = r.cx() + 0.5;
        double z = r.cz + 0.5;
        world.spawnParticle(Particle.SOUL, x, r.floorY + 1.5, z, 3, 0.8, 0.8, 0.8, 0.01);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, x, r.floorY + 1.0, z, 2, 0.4, 0.3, 0.4, 0.005);
    }

    /** Cierra la tienda y los menús abiertos (la sala se da por superada justo después). */
    void finish() {
        if (!open) return;
        open = false;
        removeSign();
        for (Player p : inst.onlineMembers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
        }
        inst.broadcast("<dark_aqua>El Mercader Fantasma se desvanece entre la niebla...</dark_aqua>");
    }

    private void spawnSign(DungeonBuilder.Room r) {
        removeSign();
        Location at = new Location(world, r.cx() + 0.5, r.floorY + 3.2, r.cz + 0.5);
        sign = world.spawn(at, TextDisplay.class, td -> {
            td.text(Text.mm("<gradient:#7de8ff:#6b6bff><bold>☾ MERCADER FANTASMA ☾</bold></gradient>\n"
                    + "<gray>Almas o sangre. Tú eliges.</gray>"));
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(false);
            td.setShadowed(true);
            td.setDefaultBackground(false);
            td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            td.setPersistent(false);
            td.setViewRange(1.0f);
            td.setTransformationMatrix(new Matrix4f().scale(1.3f));
        });
    }

    private void removeSign() {
        if (sign != null && sign.isValid()) sign.remove();
        sign = null;
    }

    // ------------------------------------------------------------------ precios

    private int soulPrice(Relic r) {
        return switch (r.tier) {
            case COMMON -> cfg.i("merchant.price-common");
            case RARE -> cfg.i("merchant.price-rare");
            case CURSED -> cfg.i("merchant.price-cursed");
            case LEGENDARY -> cfg.i("merchant.price-legendary");
        };
    }

    private int bloodCost() {
        return Math.max(1, cfg.i("merchant.blood-relic-lives"));
    }

    // ------------------------------------------------------------------ menú

    boolean openMenu(Player p) {
        DungeonInstance.PState ps = inst.pstate(p.getUniqueId());
        if (!open || ps == null || ps.eliminated) return false;
        St s = st(p.getUniqueId());
        Menu menu = new Menu();
        Inventory inv = Bukkit.createInventory(menu, SIZE,
                Text.mm("<gradient:#7de8ff:#6b6bff><bold>☾ Mercader Fantasma ☾</bold></gradient>"));
        menu.inv = inv;
        render(inv, s, ps);
        p.openInventory(inv);
        return true;
    }

    private void render(Inventory inv, St s, DungeonInstance.PState ps) {
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.space());
        filler.setItemMeta(fm);
        for (int i = 0; i < SIZE; i++) inv.setItem(i, filler);

        int cap = inst.maxLives() + ps.bonusLives;
        inv.setItem(SLOT_INFO, item(Material.SOUL_LANTERN, "<dark_aqua><bold>Tu bolsa</bold></dark_aqua>", List.of(
                "<aqua>☾ Almas: <white>" + s.souls,
                "<red>❤ Vidas: <white>" + ps.lives + "<gray>/" + cap,
                "",
                "<dark_gray>Las almas se ganan matando enemigos."), false));

        // reliquia por almas
        if (s.soulRelic == null || s.soulSold) {
            inv.setItem(SLOT_SOUL_RELIC, soldOut());
        } else {
            int price = soulPrice(s.soulRelic);
            inv.setItem(SLOT_SOUL_RELIC, relicOffer(s.soulRelic, "<aqua>☾ " + price + " almas", s.souls >= price));
        }
        // reliquia por sangre
        if (s.bloodRelic == null || s.bloodSold) {
            inv.setItem(SLOT_BLOOD_RELIC, soldOut());
        } else {
            boolean ok = ps.lives > bloodCost();
            inv.setItem(SLOT_BLOOD_RELIC, relicOffer(s.bloodRelic,
                    "<red>❤ " + bloodCost() + (bloodCost() == 1 ? " vida" : " vidas") + " <dark_gray>(precio de sangre)", ok));
        }
        // poción espectral
        int healCost = cfg.i("merchant.heal-cost");
        int maxHeals = cfg.i("merchant.max-heals");
        boolean healLeft = s.heals < maxHeals;
        inv.setItem(SLOT_HEAL, item(Material.POTION, "<green><bold>Esencia Espectral</bold></green>", List.of(
                "<gray>Recuperas <green>" + String.format(java.util.Locale.ROOT, "%.1f", cfg.d("merchant.heal-amount") / 2.0)
                        + " ❤</green> de salud.",
                "",
                "<aqua>☾ " + healCost + " almas",
                "<dark_gray>Compradas: " + s.heals + "/" + maxHeals,
                "",
                healLeft ? (s.souls >= healCost ? "<green>▸ Clic para comprar" : "<red>✖ Almas insuficientes")
                        : "<red>✖ Agotado"), false));
        // vida prestada
        int lifeCost = cfg.i("merchant.life-cost");
        inv.setItem(SLOT_LIFE, item(Material.TOTEM_OF_UNDYING, "<gold><bold>Vida Prestada</bold></gold>", List.of(
                "<gray>Recuperas <red>1 vida</red> (sin pasar del máximo).",
                "",
                "<aqua>☾ " + lifeCost + " almas",
                "",
                s.lifeBought ? "<red>✖ Solo una por visita"
                        : ps.lives >= cap ? "<red>✖ Ya tienes todas las vidas"
                        : s.souls >= lifeCost ? "<green>▸ Clic para comprar" : "<red>✖ Almas insuficientes"), false));
        // pacto de sangre
        int blood = cfg.i("merchant.pact-souls");
        inv.setItem(SLOT_PACT, item(Material.REDSTONE, "<dark_red><bold>Pacto de Sangre</bold></dark_red>", List.of(
                "<gray>Entregas <red>1 vida</red> y recibes",
                "<aqua>☾ " + blood + " almas</aqua><gray>.",
                "",
                s.pactDone ? "<red>✖ Solo un pacto por visita"
                        : ps.lives <= 1 ? "<red>✖ No puedes entregar tu última vida"
                        : "<green>▸ Clic para pactar"), false));
        // listo
        inv.setItem(SLOT_READY, item(s.ready ? Material.LIME_DYE : Material.GRAY_DYE,
                s.ready ? "<green><bold>Listo ✔</bold></green>" : "<yellow><bold>Estoy listo</bold></yellow>", List.of(
                        "<gray>Cuando todos estén listos, la sala se abre.",
                        "<gray>Clic para " + (s.ready ? "cancelar" : "confirmar") + "."), false));
    }

    private ItemStack relicOffer(Relic r, String priceLine, boolean affordable) {
        List<String> lore = new ArrayList<>();
        lore.add(r.tier.color + r.tier.label + " <dark_gray>· Reliquia</dark_gray>");
        lore.add("");
        for (String line : RelicSystem.wrap(r.description, 34)) lore.add("<gray>" + line);
        lore.add("");
        lore.add(priceLine);
        lore.add("");
        lore.add(affordable ? "<green>▸ Clic para comprar" : "<red>✖ No puedes pagarlo");
        return item(r.icon, r.tier.color + "<bold>" + r.title + "</bold>", lore, affordable);
    }

    private ItemStack soldOut() {
        return item(Material.BARRIER, "<dark_gray><bold>Agotado</bold></dark_gray>",
                List.of("<gray>El Mercader ya no tiene esto."), false);
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

    // ------------------------------------------------------------------ compras

    void click(Player p, int slot) {
        if (!open) return;
        UUID u = p.getUniqueId();
        DungeonInstance.PState ps = inst.pstate(u);
        St s = states.get(u);
        if (ps == null || s == null || ps.eliminated) return;
        switch (slot) {
            case SLOT_SOUL_RELIC -> buySoulRelic(p, s);
            case SLOT_BLOOD_RELIC -> buyBloodRelic(p, s, ps);
            case SLOT_HEAL -> buyHeal(p, s);
            case SLOT_LIFE -> buyLife(p, s, ps);
            case SLOT_PACT -> pact(p, s, ps);
            case SLOT_READY -> {
                s.ready = !s.ready;
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, s.ready ? 1.4f : 0.9f);
            }
            default -> {
                return;
            }
        }
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof Menu) render(top, s, ps);
    }

    private void buySoulRelic(Player p, St s) {
        if (s.soulRelic == null || s.soulSold) return;
        int price = soulPrice(s.soulRelic);
        if (s.souls < price) {
            deny(p, "No tienes suficientes almas.");
            return;
        }
        s.souls -= price;
        s.soulSold = true;
        paid(p);
        inst.relics().grant(p.getUniqueId(), s.soulRelic);
    }

    private void buyBloodRelic(Player p, St s, DungeonInstance.PState ps) {
        if (s.bloodRelic == null || s.bloodSold) return;
        if (ps.lives <= bloodCost()) {
            deny(p, "No puedes pagar con tu última vida.");
            return;
        }
        ps.lives -= bloodCost();
        s.bloodSold = true;
        bloodFx(p);
        inst.relics().grant(p.getUniqueId(), s.bloodRelic);
    }

    private void buyHeal(Player p, St s) {
        int cost = cfg.i("merchant.heal-cost");
        if (s.heals >= cfg.i("merchant.max-heals")) {
            deny(p, "El Mercader no tiene más esencias.");
            return;
        }
        AttributeInstance mh = p.getAttribute(Attribute.MAX_HEALTH);
        double max = mh == null ? 20.0 : mh.getValue();
        if (p.getHealth() >= max - 0.1) {
            deny(p, "Ya tienes la salud llena.");
            return;
        }
        if (s.souls < cost) {
            deny(p, "No tienes suficientes almas.");
            return;
        }
        s.souls -= cost;
        s.heals++;
        paid(p);
        inst.healPlayer(p, cfg.d("merchant.heal-amount"));
        world.spawnParticle(Particle.HEART, p.getLocation().add(0, 1.8, 0), 6, 0.3, 0.2, 0.3, 0);
    }

    private void buyLife(Player p, St s, DungeonInstance.PState ps) {
        int cost = cfg.i("merchant.life-cost");
        int cap = inst.maxLives() + ps.bonusLives;
        if (s.lifeBought) {
            deny(p, "Solo puedes comprar una vida por visita.");
            return;
        }
        if (ps.lives >= cap) {
            deny(p, "Ya tienes todas tus vidas.");
            return;
        }
        if (s.souls < cost) {
            deny(p, "No tienes suficientes almas.");
            return;
        }
        s.souls -= cost;
        s.lifeBought = true;
        ps.lives++;
        paid(p);
        p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 0.5f, 1.6f);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1.2, 0), 20, 0.4, 0.7, 0.4, 0.3);
    }

    private void pact(Player p, St s, DungeonInstance.PState ps) {
        if (s.pactDone) {
            deny(p, "Solo un pacto por visita.");
            return;
        }
        if (ps.lives <= 1) {
            deny(p, "No puedes entregar tu última vida.");
            return;
        }
        ps.lives--;
        s.pactDone = true;
        s.souls += cfg.i("merchant.pact-souls");
        bloodFx(p);
    }

    private void paid(Player p) {
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 0.7f);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.3f);
    }

    private void bloodFx(Player p) {
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_HURT, 0.6f, 1.5f);
        world.spawnParticle(Particle.DAMAGE_INDICATOR, p.getLocation().add(0, 1.4, 0), 10, 0.3, 0.4, 0.3, 0.1);
        inst.fx().text(p.getLocation().add(0, 2.3, 0), "<dark_red><bold>-1 ❤</bold></dark_red>", 1.2f);
    }

    private void deny(Player p, String msg) {
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
        p.sendMessage(Text.mm(DungeonManager.PREFIX + "<red>" + msg + "</red>"));
    }
}
