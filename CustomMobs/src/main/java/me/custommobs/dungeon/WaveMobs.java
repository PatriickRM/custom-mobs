package me.custommobs.dungeon;

import me.custommobs.Text;
import me.custommobs.model.MobVisuals;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Stray;
import org.bukkit.entity.Vindicator;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.Husk;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Fábrica de los enemigos normales y élite de las salas de oleadas. */
public final class WaveMobs {

    private enum Arch {
        CENIZO(Zombie.class, "Cenizo", "cenizo", 28, 5, 0.24, 0),
        CALCINADO(Skeleton.class, "Arquero Calcinado", "calcinado", 22, 4, 0.25, 0),
        MARCHITO(Husk.class, "Marchito", "marchito", 36, 6, 0.25, 1),
        BRUTO(Vindicator.class, "Bruto Forjado", "bruto", 42, 9, 0.27, 1),
        ESPECTRO(WitherSkeleton.class, "Espectro", "espectro", 48, 9, 0.27, 2),
        ACECHADOR(Stray.class, "Sacerdote de Almas", "sacerdote", 32, 4, 0.26, 2),
        BESTIA(Ravager.class, "Bestia de Ceniza", "bestia", 95, 12, 0.30, 3);

        final Class<? extends LivingEntity> type;
        final String name;
        final String model;
        final double hp;
        final double atk;
        final double speed;
        final int minTier;

        Arch(Class<? extends LivingEntity> type, String name, String model, double hp, double atk, double speed, int minTier) {
            this.type = type;
            this.name = name;
            this.model = model;
            this.hp = hp;
            this.atk = atk;
            this.speed = speed;
            this.minTier = minTier;
        }
    }

    private WaveMobs() {}

    /** Tier 0..3 según cuántas salas de oleadas se llevan superadas. */
    public static int tierFor(int gameRoomOrdinal) {
        return Math.max(0, Math.min(3, gameRoomOrdinal / 2));
    }

    public static LivingEntity spawn(DungeonInstance inst, Location loc, int tier, boolean elite,
                                     double hpMul, double dmgMul) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Arch> pool = new ArrayList<>();
        for (Arch a : Arch.values()) {
            if (a.minTier <= tier) {
                pool.add(a);
                if (a.minTier == tier) pool.add(a); // los del tier actual pesan más
            }
        }
        Arch arch = pool.get(rnd.nextInt(pool.size()));
        World w = loc.getWorld();

        w.spawnParticle(org.bukkit.Particle.SOUL_FIRE_FLAME, loc.clone().add(0, 1, 0), 18, 0.3, 0.6, 0.3, 0.04);
        w.playSound(loc, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 0.5f);

        LivingEntity e = w.spawn(loc, arch.type);
        if (e instanceof Ageable ag) ag.setAdult();
        if (e instanceof Zombie z) z.setBaby(false);

        double hp = arch.hp * hpMul * (elite ? inst.cfg().d("waves.elite-health-multiplier") : 1.0);
        attr(e, Attribute.MAX_HEALTH, hp);
        e.setHealth(hp);
        attr(e, Attribute.ATTACK_DAMAGE, arch.atk * dmgMul * (elite ? 1.3 : 1.0));
        attr(e, Attribute.MOVEMENT_SPEED, arch.speed * (elite ? 1.08 : 1.0));
        attr(e, Attribute.FOLLOW_RANGE, 48.0);
        if (arch != Arch.BESTIA) attr(e, Attribute.KNOCKBACK_RESISTANCE, elite ? 0.5 : 0.1);
        if (elite) attr(e, Attribute.SCALE, 1.25);
        else if (arch == Arch.BESTIA) attr(e, Attribute.SCALE, 0.9);

        e.setRemoveWhenFarAway(false);
        e.setCanPickupItems(false);
        e.setPersistent(false);
        MobVisuals vis = inst.plugin().visuals();
        boolean modeled = vis != null && vis.has(arch.model);
        if (modeled) {
            equipModeled(e, arch, tier + (elite ? 1 : 0), vis);
        } else {
            equip(e, arch, tier + (elite ? 1 : 0));
        }

        if (elite) {
            e.customName(Text.mm("<gold>★ Élite</gold> <white>" + arch.name + "</white>"));
            e.setCustomNameVisible(true);
            if (!modeled) e.setGlowing(true);   // con modelo, el brillo lo llevan las piezas
        }
        if (inst.modifier() == Modifier.FRENZY) {
            e.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 60 * 20, 0, false, false));
        }

        inst.registerMob(e, elite);
        if (modeled) vis.attach(e, arch.model, elite);
        if (inst.plugin().abilities() != null) inst.plugin().abilities().register(e, arch.model, inst);

        if (e instanceof Mob m) {
            Player t = inst.nearestAlive(loc);
            if (t != null) m.setTarget(t);
        }
        return e;
    }

    private static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance inst = e.getAttribute(a);
        if (inst != null) inst.setBaseValue(v);
    }

    private static ItemStack leather(Material m) {
        ItemStack it = new ItemStack(m);
        LeatherArmorMeta meta = (LeatherArmorMeta) it.getItemMeta();
        meta.setColor(Color.fromRGB(38, 36, 40));
        it.setItemMeta(meta);
        return it;
    }

    /** Con modelo 3D: sin armadura visible (se compensa con el atributo de armadura) y arco invisible si dispara. */
    private static void equipModeled(LivingEntity e, Arch arch, int tier, MobVisuals vis) {
        int t = Math.max(0, Math.min(4, tier));
        attr(e, Attribute.ARMOR, new double[]{3, 6, 10, 15, 18}[t]);
        EntityEquipment eq = e.getEquipment();
        if (eq == null) return;
        if (arch == Arch.CALCINADO || arch == Arch.ACECHADOR) eq.setItemInMainHand(vis.invisibleItem(Material.BOW));
        eq.setItemInMainHandDropChance(0f);
        eq.setItemInOffHandDropChance(0f);
        eq.setHelmetDropChance(0f);
        eq.setChestplateDropChance(0f);
        eq.setLeggingsDropChance(0f);
        eq.setBootsDropChance(0f);
    }

    private static void equip(LivingEntity e, Arch arch, int tier) {
        EntityEquipment eq = e.getEquipment();
        if (eq == null || arch == Arch.BESTIA) return;
        int t = Math.max(0, Math.min(4, tier));
        if (t == 0) {
            eq.setHelmet(leather(Material.LEATHER_HELMET));
            eq.setChestplate(leather(Material.LEATHER_CHESTPLATE));
        } else if (t == 1) {
            eq.setHelmet(new ItemStack(Material.CHAINMAIL_HELMET));
            eq.setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.CHAINMAIL_LEGGINGS));
        } else if (t == 2) {
            eq.setHelmet(new ItemStack(Material.IRON_HELMET));
            eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
            eq.setBoots(new ItemStack(Material.IRON_BOOTS));
        } else {
            eq.setHelmet(new ItemStack(Material.DIAMOND_HELMET));
            eq.setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
            eq.setBoots(new ItemStack(Material.DIAMOND_BOOTS));
        }
        switch (arch) {
            case CALCINADO, ACECHADOR -> eq.setItemInMainHand(new ItemStack(Material.BOW));
            case BRUTO -> eq.setItemInMainHand(new ItemStack(t >= 3 ? Material.DIAMOND_AXE : Material.IRON_AXE));
            case ESPECTRO -> eq.setItemInMainHand(new ItemStack(t >= 3 ? Material.DIAMOND_SWORD : Material.IRON_SWORD));
            default -> eq.setItemInMainHand(new ItemStack(t >= 3 ? Material.IRON_SWORD
                    : t >= 1 ? Material.STONE_SWORD : Material.WOODEN_SWORD));
        }
        eq.setHelmetDropChance(0f);
        eq.setChestplateDropChance(0f);
        eq.setLeggingsDropChance(0f);
        eq.setBootsDropChance(0f);
        eq.setItemInMainHandDropChance(0f);
        eq.setItemInOffHandDropChance(0f);
    }
}
