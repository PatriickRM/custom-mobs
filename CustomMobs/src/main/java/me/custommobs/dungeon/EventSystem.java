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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Salas de Evento: un texto, tres decisiones y consecuencias (riesgo y recompensa). Cada jugador decide por sí mismo.
 *
 * <p>Los eventos son DATOS (ver {@link #EVENTS}): para añadir uno basta con copiar un bloque {@code event(...)}.
 * Cada opción tiene un coste opcional (brasas / vidas), una probabilidad de éxito y la lista de efectos de
 * éxito y de fallo.</p>
 */
final class EventSystem {

    // ------------------------------------------------------------------ datos

    enum Fx { SOULS, HEAL, HURT, LIFE, RELIC, CURSED_RELIC, AMBUSH }

    record Effect(Fx type, int n) { }

    /** @param chance 100 = seguro; si no, % de que salgan los efectos de {@code win} (si no, los de {@code lose}). */
    record Choice(String label, String desc, int costSouls, int costLives, int chance,
                  List<Effect> win, List<Effect> lose) { }

    record Event(String title, Material icon, String text, List<Choice> choices) { }

    private static Effect fx(Fx t, int n) {
        return new Effect(t, n);
    }

    private static Effect fx(Fx t) {
        return new Effect(t, 0);
    }

    /** Opción segura (sin azar). */
    private static Choice sure(String label, String desc, int costSouls, int costLives, Effect... win) {
        return new Choice(label, desc, costSouls, costLives, 100, List.of(win), List.of());
    }

    /** Opción con azar. */
    private static Choice gamble(String label, String desc, int chance, List<Effect> win, List<Effect> lose) {
        return new Choice(label, desc, 0, 0, chance, win, lose);
    }

    /** Opción de salida: no pasa nada. */
    private static Choice leave(String label) {
        return sure(label, "No ocurre nada. A veces es lo más sensato.", 0, 0);
    }

    private static final List<Event> EVENTS = List.of(
            new Event("Altar Sangrante", Material.REDSTONE_BLOCK,
                    "Un altar de piedra negra rezuma sangre fresca. Una voz promete poder a quien pague su precio.",
                    List.of(
                            sure("Ofrecer tu sangre", "Pierdes 1 vida y recibes una reliquia al azar.",
                                    0, 1, fx(Fx.RELIC)),
                            gamble("Beber del cuenco", "70%: te curas 3 corazones. 30%: pierdes 2 corazones de salud.",
                                    70, List.of(fx(Fx.HEAL, 6)), List.of(fx(Fx.HURT, 4))),
                            leave("Marcharte"))),
            new Event("Fuente Maldita", Material.PRISMARINE_CRYSTALS,
                    "Una fuente de agua negra burbujea en el centro de la sala. Algo brilla en el fondo.",
                    List.of(
                            gamble("Beber", "60%: ganas 1 vida. 40%: recibes una reliquia maldita.",
                                    60, List.of(fx(Fx.LIFE, 1)), List.of(fx(Fx.CURSED_RELIC))),
                            sure("Llenar un frasco", "Ganas 12 brasas, pero el agua te quema (-2 corazones de salud).",
                                    0, 0, fx(Fx.SOULS, 12), fx(Fx.HURT, 4)),
                            leave("Marcharte"))),
            new Event("Cofre Trampa", Material.CHEST,
                    "Un cofre cubierto de runas reposa solo en mitad de la sala. Demasiado fácil.",
                    List.of(
                            gamble("Forzar la cerradura", "50%: reliquia al azar. 50%: emboscada.",
                                    50, List.of(fx(Fx.RELIC)), List.of(fx(Fx.AMBUSH, 4))),
                            sure("Abrirlo con cuidado", "Ganas 10 brasas, pero un dardo te hiere (-1 corazón de salud).",
                                    0, 0, fx(Fx.SOULS, 10), fx(Fx.HURT, 2)),
                            leave("Ignorarlo"))),
            new Event("Susurro del Vacío", Material.ENDER_EYE,
                    "Una voz sin boca te habla desde las grietas. Conoce tus miedos y promete saciarlos.",
                    List.of(
                            sure("Escuchar", "Recibes una reliquia maldita y 20 brasas.",
                                    0, 0, fx(Fx.CURSED_RELIC), fx(Fx.SOULS, 20)),
                            gamble("Responderle", "50%: ganas 30 brasas. 50%: emboscada.",
                                    50, List.of(fx(Fx.SOULS, 30)), List.of(fx(Fx.AMBUSH, 3))),
                            leave("Taparte los oídos"))),
            new Event("Mendigo Espectral", Material.SOUL_LANTERN,
                    "Un espectro harapiento extiende la mano. Sus ojos parecen contar tus brasas.",
                    List.of(
                            sure("Darle 10 brasas", "Te recompensa con una reliquia al azar.",
                                    10, 0, fx(Fx.RELIC)),
                            gamble("Robarle", "60%: ganas 15 brasas. 40%: emboscada.",
                                    60, List.of(fx(Fx.SOULS, 15)), List.of(fx(Fx.AMBUSH, 4))),
                            leave("Ignorarlo"))));

    // ------------------------------------------------------------------ menú

    /** Menú de decisión (el holder permite reconocer el inventario en el listener). */
    static final class Menu implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int[][] SLOTS = {{13}, {11, 15}, {11, 13, 15}};
    private static final int INFO_SLOT = 4;

    private final DungeonInstance inst;
    private final DungeonConfig cfg;
    private final World world;
    private final Random rnd = new Random();
    private final Set<Integer> used = new HashSet<>();
    private final Map<UUID, Boolean> done = new HashMap<>();
    private Event cur;
    private boolean open;
    private long endTick;
    private TextDisplay sign;

    EventSystem(DungeonInstance inst, DungeonConfig cfg, World world) {
        this.inst = inst;
        this.cfg = cfg;
        this.world = world;
    }

    void resetAll() {
        used.clear();
        done.clear();
        cur = null;
        open = false;
        removeSign();
    }

    // ------------------------------------------------------------------ ciclo de la sala

    void start(DungeonBuilder.Room r, long tick) {
        if (used.size() >= EVENTS.size()) used.clear();
        int idx;
        do {
            idx = rnd.nextInt(EVENTS.size());
        } while (used.contains(idx));
        used.add(idx);
        cur = EVENTS.get(idx);
        done.clear();
        open = true;
        endTick = tick + Math.max(10, cfg.i("event.seconds")) * 20L;
        spawnSign(r);
        world.playSound(r.center(world), Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.2f, 0.6f);
        for (Player p : inst.aliveOnline()) {
            done.put(p.getUniqueId(), false);
            p.sendMessage(Text.mm(DungeonManager.PREFIX + "<light_purple>Un evento te aguarda.</light_purple> ")
                    .append(Text.mm("<green><bold>[Decidir]</bold></green>")
                            .clickEvent(ClickEvent.runCommand("/dungeon event"))));
            openMenu(p);
        }
    }

    private boolean allDone() {
        List<Player> al = inst.aliveOnline();
        if (al.isEmpty()) return false;
        for (Player p : al) if (!done.getOrDefault(p.getUniqueId(), false)) return false;
        return true;
    }

    /** Todos decidieron (o se acabó el tiempo: los indecisos se marchan sin más). */
    boolean finished(long tick) {
        if (!open) return false;
        if (tick >= endTick) {
            for (Player p : inst.aliveOnline()) done.put(p.getUniqueId(), true);
        }
        return allDone();
    }

    String hint(long tick, boolean busy) {
        int n = 0;
        List<Player> al = inst.aliveOnline();
        for (Player p : al) if (done.getOrDefault(p.getUniqueId(), false)) n++;
        if (busy) return "¡Acaba con los enemigos!";
        return "Evento: " + Math.max(0, (endTick - tick) / 20) + "s · decididos " + n + "/" + al.size()
                + " · /dungeon event";
    }

    void ambient(DungeonBuilder.Room r, long tick) {
        if (tick % 10 != 0) return;
        double x = r.cx() + 0.5;
        double z = r.cz + 0.5;
        world.spawnParticle(Particle.ENCHANT, x, r.floorY + 2.0, z, 8, 1.0, 0.8, 1.0, 0.6);
        world.spawnParticle(Particle.DUST, x, r.floorY + 1.5, z, 3, 0.6, 0.5, 0.6, 0,
                new Particle.DustOptions(Color.fromRGB(160, 30, 60), 1.2f));
    }

    void finish() {
        if (!open) return;
        open = false;
        removeSign();
        for (Player p : inst.onlineMembers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
        }
    }

    private void spawnSign(DungeonBuilder.Room r) {
        removeSign();
        Location at = new Location(world, r.cx() + 0.5, r.floorY + 3.4, r.cz + 0.5);
        sign = world.spawn(at, TextDisplay.class, td -> {
            td.text(Text.mm("<gradient:#c471ed:#ff6b81><bold>❖ " + cur.title() + " ❖</bold></gradient>"));
            td.setBillboard(Display.Billboard.CENTER);
            td.setShadowed(true);
            td.setDefaultBackground(false);
            td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            td.setPersistent(false);
            td.setViewRange(1.0f);
            td.setTransformationMatrix(new Matrix4f().scale(1.4f));
        });
    }

    private void removeSign() {
        if (sign != null && sign.isValid()) sign.remove();
        sign = null;
    }

    // ------------------------------------------------------------------ menú

    boolean openMenu(Player p) {
        DungeonInstance.PState ps = inst.pstate(p.getUniqueId());
        if (!open || cur == null || ps == null || ps.eliminated) return false;
        if (done.getOrDefault(p.getUniqueId(), false)) return false;
        Menu menu = new Menu();
        Inventory inv = Bukkit.createInventory(menu, 27,
                Text.mm("<gradient:#c471ed:#ff6b81><bold>❖ " + cur.title() + " ❖</bold></gradient>"));
        menu.inv = inv;
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.space());
        filler.setItemMeta(fm);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        List<String> story = new ArrayList<>();
        for (String line : RelicSystem.wrap(cur.text(), 34)) story.add("<gray>" + line);
        inv.setItem(INFO_SLOT, item(cur.icon(), "<light_purple><bold>" + cur.title() + "</bold></light_purple>", story, false));

        int[] slots = SLOTS[cur.choices().size() - 1];
        for (int i = 0; i < cur.choices().size(); i++) {
            inv.setItem(slots[i], choiceItem(cur.choices().get(i), p, ps));
        }
        p.openInventory(inv);
        return true;
    }

    private boolean canPay(Choice c, Player p, DungeonInstance.PState ps) {
        if (c.costSouls() > 0 && inst.merchant().souls(p.getUniqueId()) < c.costSouls()) return false;
        return c.costLives() <= 0 || ps.lives > c.costLives();
    }

    private ItemStack choiceItem(Choice c, Player p, DungeonInstance.PState ps) {
        boolean ok = canPay(c, p, ps);
        List<String> lore = new ArrayList<>();
        for (String line : RelicSystem.wrap(c.desc(), 34)) lore.add("<gray>" + line);
        if (c.costSouls() > 0 || c.costLives() > 0) {
            lore.add("");
            StringBuilder sb = new StringBuilder("<white>Coste: ");
            if (c.costSouls() > 0) sb.append("<gold>◆ ").append(c.costSouls()).append(" brasas</gold> ");
            if (c.costLives() > 0) sb.append("<red>❤ ").append(c.costLives()).append(c.costLives() == 1 ? " vida" : " vidas").append("</red>");
            lore.add(sb.toString());
        }
        lore.add("");
        lore.add(ok ? "<green>▸ Clic para elegir" : "<red>✖ No puedes pagarlo");
        Material m = c.costLives() > 0 ? Material.REDSTONE
                : c.chance() < 100 ? Material.AMETHYST_SHARD
                : c.costSouls() > 0 ? Material.SOUL_LANTERN
                : Material.PAPER;
        return item(m, (ok ? "<yellow><bold>" : "<dark_gray><bold>") + c.label() + "</bold>", lore, ok);
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

    // ------------------------------------------------------------------ decisión

    void click(Player p, int slot) {
        if (!open || cur == null) return;
        UUID u = p.getUniqueId();
        if (done.getOrDefault(u, true)) return;
        DungeonInstance.PState ps = inst.pstate(u);
        if (ps == null || ps.eliminated) return;
        int[] slots = SLOTS[cur.choices().size() - 1];
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != slot) continue;
            Choice c = cur.choices().get(i);
            if (!canPay(c, p, ps)) {
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 0.9f);
                return;
            }
            resolve(p, ps, c);
            return;
        }
    }

    private void resolve(Player p, DungeonInstance.PState ps, Choice c) {
        UUID u = p.getUniqueId();
        done.put(u, true);
        if (c.costSouls() > 0) inst.merchant().addSouls(u, -c.costSouls());
        if (c.costLives() > 0) ps.lives = Math.max(1, ps.lives - c.costLives());
        boolean win = c.chance() >= 100 || rnd.nextInt(100) < c.chance();
        List<String> results = new ArrayList<>();
        for (Effect e : win ? c.win() : c.lose()) results.add(apply(p, ps, e));
        String tag = c.chance() >= 100 ? "" : win ? " <green>(¡suerte!)</green>" : " <red>(mala suerte)</red>";
        String res = results.isEmpty() ? "<gray>nada</gray>" : String.join("<dark_gray>, </dark_gray>", results);
        inst.broadcast("<white>" + ps.name + "</white> <gray>eligió</gray> <yellow>" + c.label() + "</yellow>" + tag
                + "<gray>:</gray> " + res);
        p.playSound(p.getLocation(), win ? Sound.BLOCK_AMETHYST_BLOCK_CHIME : Sound.ENTITY_WITHER_HURT,
                0.8f, win ? 1.3f : 1.4f);
        inst.later(1, () -> {
            if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
        });
    }

    /** Aplica un efecto y devuelve su descripción (MiniMessage) para el mensaje del grupo. */
    private String apply(Player p, DungeonInstance.PState ps, Effect e) {
        UUID u = p.getUniqueId();
        switch (e.type()) {
            case SOULS -> {
                inst.merchant().addSouls(u, e.n());
                return "<gold>" + (e.n() >= 0 ? "+" : "") + e.n() + " brasas</gold>";
            }
            case HEAL -> {
                inst.healPlayer(p, e.n());
                world.spawnParticle(Particle.HEART, p.getLocation().add(0, 1.8, 0), 6, 0.3, 0.2, 0.3, 0);
                return "<green>curación</green>";
            }
            case HURT -> {
                p.setHealth(Math.max(1.0, p.getHealth() - e.n()));
                world.spawnParticle(Particle.DAMAGE_INDICATOR, p.getLocation().add(0, 1.4, 0), 8, 0.3, 0.4, 0.3, 0.1);
                return "<red>-" + String.format(java.util.Locale.ROOT, "%.1f", e.n() / 2.0) + " ❤ de salud</red>";
            }
            case LIFE -> {
                int cap = inst.maxLives() + ps.bonusLives;
                int before = ps.lives;
                ps.lives = Math.max(1, Math.min(cap, ps.lives + e.n()));
                if (ps.lives == before) return "<gray>(vidas sin cambios)</gray>";
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1.2, 0), 20, 0.4, 0.7, 0.4, 0.3);
                return ps.lives > before ? "<green>+1 vida</green>" : "<dark_red>-1 vida</dark_red>";
            }
            case RELIC -> {
                List<Relic> roll = inst.relics().rollFor(u, 1);
                if (roll.isEmpty()) {
                    inst.merchant().addSouls(u, 15);
                    return "<gold>+15 brasas</gold> <dark_gray>(ya tienes todas las reliquias)</dark_gray>";
                }
                inst.relics().grant(u, roll.get(0));
                return roll.get(0).tier.color + roll.get(0).title + "</" + roll.get(0).tier.color.substring(1);
            }
            case CURSED_RELIC -> {
                Relic r = inst.relics().rollCursed(u);
                if (r == null) {
                    inst.merchant().addSouls(u, 15);
                    return "<gold>+15 brasas</gold> <dark_gray>(sin malditas disponibles)</dark_gray>";
                }
                inst.relics().grant(u, r);
                return "<dark_red>" + r.title + "</dark_red>";
            }
            case AMBUSH -> {
                inst.titleAll("<red><bold>¡EMBOSCADA!</bold></red>", "<gray>Algo salió mal...</gray>");
                inst.queueAmbush(e.n() + 2 * inst.groupSize());
                return "<red>¡emboscada!</red>";
            }
        }
        return "";
    }
}
