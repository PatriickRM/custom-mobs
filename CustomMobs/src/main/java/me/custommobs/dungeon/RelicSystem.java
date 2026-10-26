package me.custommobs.dungeon;

import me.custommobs.CustomMobsPlugin;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Reliquias de una partida: objetos pasivos que se ofrecen entre salas (alternando con las bendiciones).
 *
 * <p>Es un sistema independiente de {@link BlessingSystem}: se engancha a los mismos eventos de daño y muerte
 * a través de {@link DungeonListener}. Igual que las bendiciones, el daño "extra" (rayos, ondas...) nunca
 * alcanza a Vorthak ni a sus heraldos.</p>
 */
final class RelicSystem {

    /** Menú de elección (el holder permite reconocer el inventario en el listener). */
    static final class Menu implements InventoryHolder {
        private final DungeonInstance inst;
        Inventory inv;

        Menu(DungeonInstance inst) {
            this.inst = inst;
        }

        DungeonInstance instance() {
            return inst;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int[][] SLOTS = {{13}, {11, 15}, {11, 13, 15}};
    private static final int INFO_SLOT = 4;
    private static final Particle.DustOptions VOID_DUST = new Particle.DustOptions(Color.fromRGB(150, 70, 255), 1.2f);

    /** Estado de cada jugador (se pierde al terminar la partida). */
    private static final class St {
        final EnumSet<Relic> owned = EnumSet.noneOf(Relic.class);
        List<Relic> offer;
        int souls;
        int kills;
        int drum;
        long drumUntil;
        long aegisNext;
        /** Enemigos que ya recibieron el golpe del Sello del Cazador. */
        final Set<UUID> hunted = new HashSet<>();
    }

    private final CustomMobsPlugin plugin;
    private final DungeonInstance inst;
    private final DungeonConfig cfg;
    private final World world;
    private final Random rnd = new Random();
    private final Map<UUID, St> states = new HashMap<>();
    /** > 0 mientras se aplica daño causado por una reliquia (evita bucles de efectos). */
    private int inProc;

    RelicSystem(CustomMobsPlugin plugin, DungeonInstance inst, DungeonConfig cfg, World world) {
        this.plugin = plugin;
        this.inst = inst;
        this.cfg = cfg;
        this.world = world;
    }

    private St st(UUID u) {
        return states.computeIfAbsent(u, k -> new St());
    }

    void resetAll() {
        states.clear();
        inProc = 0;
    }

    boolean enabled() {
        return cfg.b("relics.enabled");
    }

    // ------------------------------------------------------------------ bucle

    void tick(long t) {
        if (t % 20 != 0 || inst.state() != DungeonInstance.State.RUNNING) return;
        for (Player p : inst.aliveOnline()) {
            St s = states.get(p.getUniqueId());
            if (s == null || s.owned.isEmpty()) continue;
            if (s.owned.contains(Relic.ASH_AEGIS) && t >= s.aegisNext && p.getAbsorptionAmount() <= 0.01) {
                s.aegisNext = t + 300;
                p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 20, 0, false, true, true));
                Location at = p.getLocation().add(0, 1.0, 0);
                world.spawnParticle(Particle.FLAME, at, 14, 0.4, 0.6, 0.4, 0.02);
                world.spawnParticle(Particle.SOUL, at, 6, 0.4, 0.6, 0.4, 0.02);
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.4f, 1.8f);
            }
            if (s.owned.contains(Relic.SOUL_LANTERN) && s.souls > 0) {
                world.spawnParticle(Particle.SOUL, p.getLocation().add(0, 1.0, 0), Math.min(4, 1 + s.souls / 4),
                        0.5, 0.6, 0.5, 0.01);
            }
        }
    }

    // ------------------------------------------------------------------ daño recibido

    /** @return true si el golpe se anuló del todo (el listener debe dejar de procesar el evento). */
    boolean onHurt(Player p, EntityDamageEvent e) {
        St s = states.get(p.getUniqueId());
        if (s == null || s.owned.isEmpty() || inst.state() != DungeonInstance.State.RUNNING) return false;
        DungeonInstance.PState ps = inst.pstate(p.getUniqueId());
        if (ps == null || ps.eliminated) return false;
        DamageCause c = e.getCause();
        if (c == DamageCause.VOID || c == DamageCause.KILL || c == DamageCause.SUICIDE
                || c == DamageCause.WORLD_BORDER) {
            return false;
        }
        boolean fromEnemy = e instanceof EntityDamageByEntityEvent;
        if (fromEnemy && s.owned.contains(Relic.MIRROR_RING) && rnd.nextDouble() < 0.12) {
            e.setCancelled(true);
            Location at = p.getLocation().add(0, 1.0, 0);
            world.spawnParticle(Particle.END_ROD, at, 18, 0.4, 0.7, 0.4, 0.05);
            world.spawnParticle(Particle.ENCHANTED_HIT, at, 12, 0.4, 0.7, 0.4, 0.2);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.7f);
            inst.fx().text(p.getLocation().add(0, 2.3, 0), "<light_purple><bold>¡ANULADO!</bold></light_purple>", 1.1f);
            return true;
        }
        if (s.owned.contains(Relic.GLASS_FANG)) e.setDamage(e.getDamage() * 1.25);
        if (fromEnemy && s.owned.contains(Relic.SOUL_LANTERN) && s.souls > 0) s.souls /= 2;
        return false;
    }

    // ------------------------------------------------------------------ daño infligido

    void modifyDealt(Player p, LivingEntity target, EntityDamageByEntityEvent e) {
        if (inProc > 0) return;
        St s = states.get(p.getUniqueId());
        if (s == null || s.owned.isEmpty()) return;
        DungeonInstance.PState ps = inst.pstate(p.getUniqueId());
        if (ps == null || ps.eliminated) return;
        double bonus = 0;
        if (s.owned.contains(Relic.GLASS_FANG)) bonus += 0.35;
        if (s.owned.contains(Relic.SOUL_LANTERN)) bonus += 0.025 * s.souls;
        double max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                ? target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() : 20.0;
        double frac = max <= 0 ? 1.0 : target.getHealth() / max;
        Location at = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        if (s.owned.contains(Relic.EXECUTIONER_MASK) && frac < 0.30) {
            bonus += 0.80;
            world.spawnParticle(Particle.CRIT, at, 12, 0.3, 0.4, 0.3, 0.3);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 6, 0.2, 0.3, 0.2, 0.03);
        }
        if (s.owned.contains(Relic.HUNTER_SEAL) && frac >= 0.90 && s.hunted.add(target.getUniqueId())) {
            bonus += 0.60;
            if (s.hunted.size() > 400) s.hunted.clear();
            world.spawnParticle(Particle.CRIT, at, 14, 0.3, 0.4, 0.3, 0.4);
            world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 0.8f);
        }
        if (bonus != 0) e.setDamage(e.getDamage() * (1.0 + bonus));
    }

    void afterDealt(Player p, LivingEntity target, double dealt) {
        if (inProc > 0 || dealt < 1.0) return;
        St s = states.get(p.getUniqueId());
        if (s == null || !s.owned.contains(Relic.VOID_NEEDLE)) return;
        if (rnd.nextDouble() >= 0.18) return;
        LivingEntity best = null;
        double bd = 49.0;
        for (Entity en : target.getNearbyEntities(7, 4, 7)) {
            if (en == target || !(en instanceof LivingEntity)) continue;
            LivingEntity le = (LivingEntity) en;
            if (!inst.isProcTarget(le)) continue;
            double d = le.getLocation().distanceSquared(target.getLocation());
            if (d < bd) {
                bd = d;
                best = le;
            }
        }
        if (best == null) return;
        Location a = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        Location b = best.getLocation().add(0, best.getHeight() * 0.6, 0);
        line(a, b);
        world.spawnParticle(Particle.REVERSE_PORTAL, b, 20, 0.3, 0.5, 0.3, 0.2);
        world.playSound(b, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.6f);
        deal(p, best, Math.max(3.0, dealt * 0.6));
    }

    // ------------------------------------------------------------------ muertes

    void onKill(LivingEntity mob, Player killer, boolean elite) {
        St s = states.get(killer.getUniqueId());
        if (s == null || s.owned.isEmpty()) return;
        long t = inst.tick();
        Location at = mob.getLocation().add(0, mob.getHeight() * 0.5, 0);
        boolean chained = inProc > 0;

        if (s.owned.contains(Relic.SOUL_LANTERN) && s.souls < 12) {
            s.souls++;
            world.spawnParticle(Particle.SOUL, at, 6, 0.3, 0.4, 0.3, 0.04);
        }
        if (s.owned.contains(Relic.CRIMSON_CHALICE)) {
            double heal = elite ? 3.0 : 1.0;
            inst.healPlayer(killer, heal);
            world.spawnParticle(Particle.HEART, killer.getLocation().add(0, 2.0, 0), elite ? 4 : 2, 0.25, 0.1, 0.25, 0);
            inst.fx().text(killer.getLocation().add(0, 2.4, 0),
                    "<green>+" + String.format(Locale.ROOT, "%.1f", heal / 2.0) + " ❤</green>", 0.9f);
        }
        if (s.owned.contains(Relic.WAR_DRUM)) {
            s.drum = t <= s.drumUntil ? Math.min(4, s.drum + 1) : 1;
            s.drumUntil = t + 100;
            killer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, s.drum - 1, false, false, true));
            killer.playSound(killer.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.7f, 0.6f + 0.2f * s.drum);
        }
        if (chained) return;

        if (s.owned.contains(Relic.EMBER_HEART)) {
            for (Entity en : world.getNearbyEntities(at, 4, 2, 4)) {
                if (!(en instanceof LivingEntity)) continue;
                LivingEntity le = (LivingEntity) en;
                if (le == mob || !inst.isProcTarget(le)) continue;
                le.setFireTicks(120);
            }
            world.spawnParticle(Particle.FLAME, at, 28, 0.5, 0.5, 0.5, 0.09);
            world.spawnParticle(Particle.LARGE_SMOKE, at, 6, 0.4, 0.4, 0.4, 0.02);
            world.playSound(at, Sound.ITEM_FIRECHARGE_USE, 0.7f, 1.2f);
        }
        if (s.owned.contains(Relic.SOUL_REAPER)) {
            s.kills++;
            if (s.kills % 8 == 0) reap(killer, at);
        }
    }

    /** Onda de almas del Segador: daña y empuja a los enemigos cercanos. */
    private void reap(Player killer, Location at) {
        world.playSound(at, Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 0.7f);
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.4f);
        for (int ring = 1; ring <= 4; ring++) {
            double rad = ring * 1.5;
            for (int deg = 0; deg < 360; deg += 15) {
                double ang = Math.toRadians(deg);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, at.getX() + Math.cos(ang) * rad, at.getY() - 0.4,
                        at.getZ() + Math.sin(ang) * rad, 1, 0.05, 0.05, 0.05, 0.01);
            }
        }
        inst.fx().text(at.clone().add(0, 1.2, 0), "<dark_aqua><bold>☠ ONDA DE ALMAS ☠</bold></dark_aqua>", 1.4f);
        for (Entity en : world.getNearbyEntities(at, 6, 3, 6)) {
            if (!(en instanceof LivingEntity)) continue;
            LivingEntity le = (LivingEntity) en;
            if (!inst.isProcTarget(le)) continue;
            deal(killer, le, 8.0);
            if (!le.isValid() || le.isDead()) continue;
            Vector dir = le.getLocation().toVector().subtract(at.toVector());
            dir.setY(0);
            if (dir.lengthSquared() < 0.01) dir = new Vector(0, 0, 1);
            dir.normalize().multiply(0.9);
            dir.setY(0.35);
            le.setVelocity(dir);
        }
    }

    // ------------------------------------------------------------------ utilidades de daño y efectos

    /** Daño causado por una reliquia (sin disparar otras reliquias). */
    private void deal(Player p, LivingEntity target, double dmg) {
        if (dmg <= 0 || !target.isValid() || target.isDead()) return;
        inProc++;
        try {
            target.setNoDamageTicks(0);
            target.damage(dmg, p);
        } finally {
            inProc--;
        }
    }

    private void line(Location a, Location b) {
        Vector v = b.toVector().subtract(a.toVector());
        double len = v.length();
        if (len < 0.1) return;
        v.normalize();
        for (double d = 0; d < len; d += 0.4) {
            Location l = a.clone().add(v.clone().multiply(d));
            world.spawnParticle(Particle.DUST, l.getX(), l.getY(), l.getZ(), 1, 0, 0, 0, 0, VOID_DUST);
        }
    }

    // ------------------------------------------------------------------ oferta y menú

    boolean hasPending() {
        for (Map.Entry<UUID, St> en : states.entrySet()) {
            if (en.getValue().offer == null) continue;
            DungeonInstance.PState ps = inst.pstate(en.getKey());
            if (ps != null && !ps.eliminated) return true;
        }
        return false;
    }

    /** Si alguien no eligió a tiempo, se le concede una al azar. */
    void autoPickPending() {
        for (UUID u : new ArrayList<>(states.keySet())) {
            St s = states.get(u);
            if (s != null && s.offer != null) choose(u, rnd.nextInt(s.offer.size()), true);
        }
    }

    /** Ofrece una reliquia a cada jugador vivo. */
    void offer() {
        if (!enabled() || inst.state() != DungeonInstance.State.RUNNING) return;
        int n = Math.max(1, Math.min(3, cfg.i("relics.choices")));
        for (UUID u : new ArrayList<>(inst.memberStates().keySet())) {
            DungeonInstance.PState ps = inst.pstate(u);
            if (ps == null || ps.eliminated) continue;
            St s = st(u);
            if (s.offer != null) choose(u, rnd.nextInt(s.offer.size()), true);
            List<Relic> roll = roll(s, n);
            if (roll.isEmpty()) continue;
            s.offer = roll;
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 0.8f);
            p.sendMessage(Text.mm(DungeonManager.PREFIX + "<light_purple>¡Reliquia disponible!</light_purple> ")
                    .append(Text.mm("<green><bold>[Elegir]</bold></green>")
                            .clickEvent(ClickEvent.runCommand("/dungeon relic"))));
            if (!p.isDead()) openMenu(p);
        }
    }

    private List<Relic> roll(St s, int n) {
        List<Relic> pool = new ArrayList<>();
        for (Relic r : Relic.values()) if (!s.owned.contains(r)) pool.add(r);
        List<Relic> out = new ArrayList<>();
        while (out.size() < n && !pool.isEmpty()) {
            int total = 0;
            for (Relic r : pool) total += r.tier.weight;
            int roll = rnd.nextInt(total);
            Relic pick = pool.get(0);
            for (Relic r : pool) {
                roll -= r.tier.weight;
                if (roll < 0) {
                    pick = r;
                    break;
                }
            }
            pool.remove(pick);
            out.add(pick);
        }
        return out;
    }

    boolean openMenu(Player p) {
        St s = states.get(p.getUniqueId());
        DungeonInstance.PState ps = inst.pstate(p.getUniqueId());
        if (s == null || ps == null || ps.eliminated || s.offer == null || s.offer.isEmpty()) return false;
        Menu menu = new Menu(inst);
        Inventory inv = Bukkit.createInventory(menu, 27,
                Text.mm("<gradient:#c471ed:#7f7fd5><bold>❖ Elige una reliquia ❖</bold></gradient>"));
        menu.inv = inv;
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.space());
        filler.setItemMeta(fm);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        int[] slots = SLOTS[s.offer.size() - 1];
        for (int i = 0; i < s.offer.size(); i++) {
            Relic r = s.offer.get(i);
            inv.setItem(slots[i], relicItem(r));
            ItemStack pane = new ItemStack(r.tier.pane);
            ItemMeta pm = pane.getItemMeta();
            pm.displayName(Text.item(r.tier.color + r.tier.label));
            pane.setItemMeta(pm);
            inv.setItem(slots[i] - 9, pane);
            inv.setItem(slots[i] + 9, pane);
        }
        inv.setItem(INFO_SLOT, infoItem(s));
        p.openInventory(inv);
        return true;
    }

    private ItemStack relicItem(Relic r) {
        ItemStack it = new ItemStack(r.icon);
        ItemMeta m = it.getItemMeta();
        m.displayName(Text.item(r.tier.color + "<bold>" + r.title + "</bold>"));
        List<Component> lore = new ArrayList<>();
        lore.add(Text.item(r.tier.color + r.tier.label + " <dark_gray>· Reliquia</dark_gray>"));
        lore.add(Component.empty());
        for (String line : wrap(r.description, 34)) lore.add(Text.item("<gray>" + line));
        lore.add(Component.empty());
        lore.add(Text.item("<green>▸ Clic para elegir"));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private ItemStack infoItem(St s) {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta m = it.getItemMeta();
        m.displayName(Text.item("<light_purple><bold>Tus reliquias</bold></light_purple>"));
        List<Component> lore = new ArrayList<>();
        if (s.owned.isEmpty()) {
            lore.add(Text.item("<gray>Todavía no tienes ninguna."));
        } else {
            for (Relic r : s.owned) lore.add(Text.item(r.tier.color + "• " + r.title));
        }
        lore.add(Component.empty());
        lore.add(Text.item("<dark_gray>Si no eliges, se te dará una al azar"));
        lore.add(Text.item("<dark_gray>al entrar en la siguiente sala."));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : text.split(" ")) {
            if (cur.length() > 0 && cur.length() + 1 + w.length() > width) {
                lines.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    void click(Player p, int slot) {
        St s = states.get(p.getUniqueId());
        if (s == null || s.offer == null || s.offer.isEmpty()) return;
        int[] slots = SLOTS[s.offer.size() - 1];
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                choose(p.getUniqueId(), i, false);
                return;
            }
        }
    }

    void choose(UUID u, int idx, boolean auto) {
        St s = states.get(u);
        if (s == null || s.offer == null || idx < 0 || idx >= s.offer.size()) return;
        Relic r = s.offer.get(idx);
        s.offer = null;
        s.owned.add(r);
        DungeonInstance.PState ps = inst.pstate(u);
        if (r == Relic.PHOENIX_FEATHER && ps != null) {
            ps.bonusLives++;
            ps.lives++;
        }
        if (r == Relic.ASH_AEGIS) s.aegisNext = 0;
        String name = ps != null ? ps.name : "Alguien";
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 0.4f, 1.5f);
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1.2, 0), 25, 0.4, 0.7, 0.4, 0.3);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                    p.closeInventory();
                }
            });
        }
        inst.broadcast("<white>" + name + "</white> <gray>obtuvo la reliquia</gray> " + r.tier.color + "<bold>"
                + r.title + "</bold>" + (auto ? " <dark_gray>(al azar)</dark_gray>" : ""));
    }

    // ------------------------------------------------------------------ HUD

    String hudLine(Player p) {
        St s = states.get(p.getUniqueId());
        if (s == null) return "";
        List<String> parts = new ArrayList<>();
        if (!s.owned.isEmpty()) parts.add("<light_purple>❖" + s.owned.size() + "</light_purple>");
        if (s.owned.contains(Relic.SOUL_LANTERN)) parts.add("<aqua>✦" + s.souls + "</aqua>");
        if (s.owned.contains(Relic.SOUL_REAPER)) parts.add("<dark_aqua>☠" + (s.kills % 8) + "/8</dark_aqua>");
        if (s.owned.contains(Relic.WAR_DRUM) && inst.tick() <= s.drumUntil) parts.add("<yellow>♪x" + s.drum + "</yellow>");
        if (s.offer != null) parts.add("<green>/dungeon relic</green>");
        return String.join(" <dark_gray>·</dark_gray> ", parts);
    }
}
