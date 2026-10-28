package me.custommobs.dungeon;

import me.custommobs.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Secretos de la dungeon (los coloca {@link DungeonBuilder}): una <b>palanca oculta</b> que derriba una pared agrietada
 * y abre una <b>cámara secreta</b> con cofre, <b>cofres escondidos</b> en rincones y vestíbulos (a veces son mímicos) y
 * <b>fuentes de almas</b> que curan. Cada jugador abre cada cofre y fuente una sola vez. Un brillo sutil delata los secretos
 * a quien pasa cerca.
 */
final class SecretSystem {

    private final DungeonInstance inst;
    private final DungeonConfig cfg;
    private final World world;
    private final DungeonBuilder builder;
    private final Set<String> opened = new HashSet<>();
    private final Map<String, Set<UUID>> claimed = new HashMap<>();

    SecretSystem(DungeonInstance inst, DungeonConfig cfg, World world, DungeonBuilder builder) {
        this.inst = inst;
        this.cfg = cfg;
        this.world = world;
        this.builder = builder;
    }

    private DungeonBuilder.Secret at(Block b) {
        for (DungeonBuilder.Secret s : builder.secrets()) {
            if (s.x == b.getX() && s.y == b.getY() && s.z == b.getZ()) return s;
        }
        return null;
    }

    boolean isSecret(Block b) {
        return at(b) != null;
    }

    /** Se llama al reiniciar la partida: paredes selladas, palancas bajadas y cofres otra vez cerrados. */
    void resetAll() {
        for (DungeonBuilder.Secret s : builder.secrets()) {
            if (s.kind != DungeonBuilder.Secret.Kind.LEVER || !opened.contains(s.id)) continue;
            for (int[] p : s.plug) world.getBlockAt(p[0], p[1], p[2]).setType(Material.CRACKED_DEEPSLATE_BRICKS, false);
            Block lv = world.getBlockAt(s.x, s.y, s.z);
            if (lv.getBlockData() instanceof org.bukkit.block.data.type.Switch sw) {
                sw.setPowered(false);
                lv.setBlockData(sw, false);
            }
        }
        opened.clear();
        claimed.clear();
    }

    // ------------------------------------------------------------------ interacción

    /** @return true si el clic era sobre un secreto (se cancela el comportamiento vanilla). */
    boolean interact(Player p, Block b) {
        DungeonBuilder.Secret s = at(b);
        if (s == null) return false;
        DungeonInstance.PState ps = inst.pstate(p.getUniqueId());
        if (ps == null || ps.eliminated) return true;
        switch (s.kind) {
            case LEVER -> pull(p, s, b);
            case CHEST -> openChest(p, s, b);
            case FOUNTAIN -> drink(p, s, b);
        }
        return true;
    }

    private void pull(Player p, DungeonBuilder.Secret s, Block lever) {
        if (!opened.add(s.id)) return;
        if (lever.getBlockData() instanceof org.bukkit.block.data.type.Switch sw) {
            sw.setPowered(true);
            lever.setBlockData(sw, false);
        }
        world.playSound(lever.getLocation(), Sound.BLOCK_LEVER_CLICK, 1f, 0.8f);
        for (int[] pb : s.plug) {
            Location l = new Location(world, pb[0] + 0.5, pb[1] + 0.5, pb[2] + 0.5);
            world.spawnParticle(Particle.BLOCK, l, 14, 0.4, 0.4, 0.4, 0.05, Material.DEEPSLATE_BRICKS.createBlockData());
            world.getBlockAt(pb[0], pb[1], pb[2]).setType(Material.AIR, false);
        }
        if (!s.plug.isEmpty()) {
            int[] mid = s.plug.get(s.plug.size() / 2);
            Location c = new Location(world, mid[0] + 0.5, mid[1] + 0.5, mid[2] + 0.5);
            world.playSound(c, Sound.BLOCK_PISTON_EXTEND, 1.4f, 0.5f);
            world.playSound(c, Sound.ENTITY_IRON_GOLEM_DAMAGE, 1.2f, 0.5f);
            world.playSound(c, Sound.BLOCK_DEEPSLATE_BRICKS_BREAK, 1.6f, 0.6f);
        }
        inst.broadcast("<yellow>Una pared se desmorona...</yellow> <gray>¡Hay una <white>cámara secreta</white>!</gray>");
    }

    private void openChest(Player p, DungeonBuilder.Secret s, Block chest) {
        Set<UUID> got = claimed.computeIfAbsent(s.id + "@" + s.x + "," + s.z, k -> new HashSet<>());
        if (got.contains(p.getUniqueId())) {
            p.sendActionBar(Text.mm("<gray>Ya has vaciado este cofre.</gray>"));
            return;
        }
        got.add(p.getUniqueId());
        Location at = chest.getLocation().add(0.5, 1.0, 0.5);
        world.playSound(at, Sound.BLOCK_CHEST_OPEN, 1f, 0.9f);
        world.spawnParticle(Particle.ENCHANT, at, 24, 0.4, 0.4, 0.4, 0.6);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (rnd.nextDouble() < cfg.d("secrets.mimic-chance")) {
            // ¡era un mímico!
            world.playSound(at, Sound.ENTITY_RAVAGER_ROAR, 1f, 1.4f);
            world.spawnParticle(Particle.LARGE_SMOKE, at, 20, 0.4, 0.4, 0.4, 0.05);
            inst.broadcast("<red><bold>¡Era un mímico!</bold></red>");
            int n = 2 + inst.groupSize() / 2;
            for (int i = 0; i < n; i++) {
                Location l = inst.randomSpot(inst.roomAt(chest.getLocation()));
                if (l == null) l = at.clone().add(rnd.nextDouble(-2, 2), 0, rnd.nextDouble(-2, 2));
                inst.spawnObjectiveMob(l, i == 0);
            }
        }
        reward(p, s.big);
    }

    private void reward(Player p, boolean big) {
        UUID u = p.getUniqueId();
        List<ItemStack> loot = cfg.rollLoot("secrets.loot");
        if (big) loot.addAll(cfg.rollLoot("secrets.loot"));
        for (ItemStack it : loot) {
            for (ItemStack left : p.getInventory().addItem(it).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        int souls = cfg.i(big ? "secrets.souls-big" : "secrets.souls-small");
        if (souls > 0) inst.merchant().addSouls(u, souls);
        StringBuilder sb = new StringBuilder("<gold>Botín:</gold> <white>" + loot.size() + " objeto(s)</white>");
        if (souls > 0) sb.append(" <gray>·</gray> <gold>◆ +" + souls + "</gold>");
        if (ThreadLocalRandom.current().nextDouble() < cfg.d(big ? "secrets.relic-chance-big" : "secrets.relic-chance-small")) {
            List<Relic> r = inst.relics().rollFor(u, 1);
            if (!r.isEmpty()) {
                inst.relics().grant(u, r.get(0), false);
                sb.append(" <gray>·</gray> ").append(r.get(0).tier.color).append("<bold>").append(r.get(0).title).append("</bold>");
                p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 0.5f, 1.4f);
            }
        }
        p.sendMessage(Text.mm(DungeonManager.PREFIX + sb));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
    }

    private void drink(Player p, DungeonBuilder.Secret s, Block fountain) {
        Set<UUID> got = claimed.computeIfAbsent(s.id, k -> new HashSet<>());
        if (got.contains(p.getUniqueId())) {
            p.sendActionBar(Text.mm("<gray>La fuente ya no te da más.</gray>"));
            return;
        }
        got.add(p.getUniqueId());
        double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                ? p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() : 20.0;
        p.setHealth(Math.min(max, p.getHealth() + max * 0.6));
        p.setFoodLevel(20);
        for (PotionEffectType t : new PotionEffectType[]{PotionEffectType.POISON, PotionEffectType.WITHER, PotionEffectType.SLOWNESS,
                PotionEffectType.WEAKNESS, PotionEffectType.BLINDNESS}) {
            p.removePotionEffect(t);
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, cfg.i("secrets.fountain-regen-seconds") * 20, 1, false, true));
        p.setFreezeTicks(0);
        Location at = fountain.getLocation().add(0.5, 1.2, 0.5);
        world.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.6f);
        world.playSound(at, Sound.ENTITY_PLAYER_SPLASH, 0.8f, 1.2f);
        world.spawnParticle(Particle.HEART, p.getLocation().add(0, 1.8, 0), 6, 0.4, 0.3, 0.4, 0);
        world.spawnParticle(Particle.SOUL, at, 20, 0.3, 0.6, 0.3, 0.03);
        p.sendMessage(Text.mm(DungeonManager.PREFIX + "<aqua>La fuente de almas te cura.</aqua>"));
    }

    // ------------------------------------------------------------------ pistas

    /** Brillo sutil sobre las paredes agrietadas, palancas y cofres que aún no se han usado, para quien pasa cerca. */
    void tick(long tick) {
        if (tick % 40 != 0) return;
        for (Player p : inst.aliveOnline()) {
            Location pl = p.getLocation();
            for (DungeonBuilder.Secret s : builder.secrets()) {
                if (Math.abs(s.x - pl.getX()) > 14 || Math.abs(s.z - pl.getZ()) > 14) continue;
                if (s.kind == DungeonBuilder.Secret.Kind.LEVER) {
                    if (opened.contains(s.id) || s.plug.isEmpty()) continue;
                    int[] m = s.plug.get(s.plug.size() / 2);
                    p.spawnParticle(Particle.DUST, m[0] + 0.5, m[1] + 0.5, m[2] + 0.5, 3, 0.8, 0.8, 0.3, 0,
                            new Particle.DustOptions(org.bukkit.Color.fromRGB(200, 200, 210), 0.8f));
                } else if (s.kind == DungeonBuilder.Secret.Kind.CHEST) {
                    Set<UUID> got = claimed.get(s.id + "@" + s.x + "," + s.z);
                    if (got != null && got.contains(p.getUniqueId())) continue;
                    p.spawnParticle(Particle.END_ROD, s.x + 0.5, s.y + 1.1, s.z + 0.5, 1, 0.2, 0.1, 0.2, 0.01);
                }
            }
        }
    }
}
