package me.custommobs.dungeon;

import me.custommobs.Sfx;
import me.custommobs.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Evoker;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mini jefes de la dungeon, cada uno con una mecánica distinta:
 * <ul>
 *   <li><b>Sombra Veloz</b>: muy rápida; embestidas telegrafiadas, parpadeos a la espalda y remolino.</li>
 *   <li><b>Guardián de Brasas</b>: escudo que absorbe daño y se rompe (aturdido y vulnerable); pisotón y carga.</li>
 *   <li><b>Nigromante de Almas</b>: invocador; casi inmune mientras vivan sus súbditos; lluvia de almas.</li>
 * </ul>
 */
public final class MiniBoss {

    public enum Kind {
        SOMBRA("Sombra Veloz", "sombra", "<gradient:#7f7fd5:#c471ed>", BossBar.Color.PURPLE),
        GUARDIAN("Guardián de Brasas", "guardian", "<gradient:#ff8c00:#ff3d00>", BossBar.Color.RED),
        NIGROMANTE("Nigromante de Almas", "nigromante", "<gradient:#4dd0e1:#00838f>", BossBar.Color.BLUE);

        public final String title;
        final String key;
        final String gradient;
        final BossBar.Color color;

        Kind(String title, String key, String gradient, BossBar.Color color) {
            this.title = title;
            this.key = key;
            this.gradient = gradient;
            this.color = color;
        }
    }

    private final DungeonInstance inst;
    private final Kind kind;
    private final LivingEntity entity;
    private final BossBar bar;
    private final double maxHp;
    private final double dmg;
    private final Set<UUID> minions = new HashSet<>();

    private boolean dead;
    private boolean fast;
    private long busyUntil;
    private long nextAbility;
    private long nextSummon;
    private long nextRain;
    private long nextBlink;
    private long invalidSince = -1;

    // guardián
    private double shieldMax;
    private double shield;
    private long brokenUntil;

    private MiniBoss(DungeonInstance inst, Kind kind, LivingEntity entity, double maxHp, double dmg) {
        this.inst = inst;
        this.kind = kind;
        this.entity = entity;
        this.maxHp = maxHp;
        this.dmg = dmg;
        this.bar = BossBar.bossBar(Component.text(kind.title), 1f, kind.color, BossBar.Overlay.NOTCHED_10);
    }

    // ------------------------------------------------------------------ creación

    public static MiniBoss spawn(DungeonInstance inst, Kind kind, Location loc) {
        DungeonConfig cfg = inst.cfg();
        World w = loc.getWorld();
        // Minecraft limita la vida máxima a 1024: sin el tope, setHealth lanza IllegalArgumentException
        // (pasaba con el Guardián de Brasas a partir de 3 jugadores).
        double hp = Math.min(1024.0, cfg.d("miniboss." + kind.key + ".health")
                * (1 + cfg.d("scaling.vorthak-health-per-extra-player") * 0.8 * (inst.groupSize() - 1))
                * inst.heat().hpMul());
        double dmg = cfg.d("miniboss." + kind.key + ".damage") * (1 + 0.05 * Math.max(0, inst.roomOrdinal() - 1))
                * inst.heat().dmgMul();

        LivingEntity e = switch (kind) {
            case SOMBRA -> w.spawn(loc, WitherSkeleton.class);
            case GUARDIAN -> w.spawn(loc, Husk.class);
            case NIGROMANTE -> w.spawn(loc, Evoker.class);
        };

        attr(e, Attribute.MAX_HEALTH, hp);
        e.setHealth(hp);
        attr(e, Attribute.ATTACK_DAMAGE, dmg);
        attr(e, Attribute.KNOCKBACK_RESISTANCE, 1.0);
        attr(e, Attribute.FOLLOW_RANGE, 48.0);
        switch (kind) {
            case SOMBRA -> {
                attr(e, Attribute.MOVEMENT_SPEED, 0.36);
                attr(e, Attribute.SCALE, 1.3);
            }
            case GUARDIAN -> {
                attr(e, Attribute.MOVEMENT_SPEED, 0.22);
                attr(e, Attribute.SCALE, 1.6);
            }
            case NIGROMANTE -> {
                attr(e, Attribute.MOVEMENT_SPEED, 0.30);
                attr(e, Attribute.SCALE, 1.4);
            }
        }
        e.customName(Text.mm(kind.gradient + "<bold>" + kind.title + "</bold></gradient>"));
        e.setCustomNameVisible(false);
        e.setRemoveWhenFarAway(false);
        e.setPersistent(false);
        e.setCanPickupItems(false);
        e.setGlowing(true);

        EntityEquipment eq = e.getEquipment();
        if (eq != null) {
            switch (kind) {
                case SOMBRA -> {
                    eq.setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
                    eq.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
                }
                case GUARDIAN -> {
                    eq.setItemInMainHand(new ItemStack(Material.NETHERITE_AXE));
                    eq.setItemInOffHand(new ItemStack(Material.SHIELD));
                    eq.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
                    eq.setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
                    eq.setLeggings(new ItemStack(Material.NETHERITE_LEGGINGS));
                    eq.setBoots(new ItemStack(Material.NETHERITE_BOOTS));
                }
                default -> { }
            }
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setBootsDropChance(0f);
            eq.setItemInMainHandDropChance(0f);
            eq.setItemInOffHandDropChance(0f);
        }

        // modelo 3D (resource pack): el mob real queda invisible y sin voz; su protección pasa a atributos
        me.custommobs.model.MobVisuals vis = inst.plugin().visuals();
        if (vis != null && vis.has(kind.key)) {
            e.setSilent(true);
            e.setGlowing(false);
            if (eq != null) {
                eq.setHelmet(null);
                eq.setChestplate(null);
                eq.setLeggings(null);
                eq.setBoots(null);
                eq.setItemInOffHand(null);
                if (kind != Kind.NIGROMANTE) {
                    eq.setItemInMainHand(vis.invisibleItem(kind == Kind.GUARDIAN ? Material.NETHERITE_AXE : Material.NETHERITE_SWORD));
                }
            }
            if (kind == Kind.GUARDIAN) {
                attr(e, Attribute.ARMOR, 20.0);
                attr(e, Attribute.ARMOR_TOUGHNESS, 12.0);
            } else if (kind == Kind.SOMBRA) {
                attr(e, Attribute.ARMOR, 3.0);
                attr(e, Attribute.ARMOR_TOUGHNESS, 3.0);
            }
            vis.attach(e, kind.key, false);
        }

        MiniBoss mb = new MiniBoss(inst, kind, e, hp, dmg);
        if (kind == Kind.GUARDIAN) {
            mb.shieldMax = hp * inst.cfg().d("miniboss.guardian-shield-percent");
            mb.shield = mb.shieldMax;
        }
        inst.registerMob(e, false);
        long t = inst.tick();
        mb.busyUntil = t + 40;
        mb.nextAbility = t + 60;
        mb.nextSummon = t + 60;
        mb.nextRain = t + 160;
        mb.nextBlink = t + 120;

        w.strikeLightningEffect(loc);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(0, 1, 0), 80, 1, 1.5, 1, 0.1);
        w.playSound(loc, Sound.ENTITY_WITHER_SPAWN, 1.5f, 0.8f);
        return mb;
    }

    private static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance ai = e.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    // ------------------------------------------------------------------ acceso

    public LivingEntity entity() {
        return entity;
    }

    public Kind kind() {
        return kind;
    }

    public BossBar bar() {
        return bar;
    }

    public boolean owns(UUID id) {
        return entity.getUniqueId().equals(id);
    }

    public boolean isMinion(UUID id) {
        return minions.contains(id);
    }

    // ------------------------------------------------------------------ daño (escudo / súbditos)

    /** Llamado antes de aplicar el daño al jefe (prioridad alta en el listener). */
    public void onDamage(EntityDamageEvent e) {
        long t = inst.tick();
        if (!(kind == Kind.GUARDIAN && shield > 0 && t >= brokenUntil)) hurtVoice(t);
        Location at = entity.getLocation().add(0, entity.getHeight() * 0.6, 0);
        World w = at.getWorld();
        if (kind == Kind.GUARDIAN) {
            if (t < brokenUntil) {
                e.setDamage(e.getDamage() * 1.5);
                return;
            }
            if (shield > 0) {
                shield -= e.getDamage();
                e.setCancelled(true);
                w.playSound(at, Sound.ITEM_SHIELD_BLOCK, 1f, 0.8f);
                w.spawnParticle(Particle.CRIT, at, 10, 0.4, 0.5, 0.4, 0.2);
                if (shield <= 0) breakShield(t);
            }
        } else if (kind == Kind.NIGROMANTE && !minions.isEmpty()) {
            e.setDamage(e.getDamage() * 0.15);
            w.spawnParticle(Particle.SOUL, at, 6, 0.4, 0.5, 0.4, 0.02);
            w.playSound(at, Sound.ITEM_SHIELD_BLOCK, 0.6f, 1.4f);
        }
    }

    private void breakShield(long t) {
        shield = 0;
        brokenUntil = t + 120;
        busyUntil = t + 120;
        entity.setAI(false);
        Location at = entity.getLocation();
        at.getWorld().playSound(at, Sound.ITEM_SHIELD_BREAK, 2f, 0.6f);
        at.getWorld().playSound(at, Sound.BLOCK_ANVIL_LAND, 1.5f, 0.5f);
        at.getWorld().spawnParticle(Particle.EXPLOSION, at.clone().add(0, 1.5, 0), 3, 0.6, 0.6, 0.6, 0);
        inst.titleAll("<yellow><bold>¡ESCUDO ROTO!</bold></yellow>", "<gray>El Guardián está aturdido: +50% de daño</gray>");
    }

    // ------------------------------------------------------------------ bucle

    public void tick() {
        if (dead) return;
        long t = inst.tick();
        if (!entity.isValid()) {
            if (invalidSince < 0) invalidSince = t;
            else if (t - invalidSince > 40) inst.onMiniGone(this);
            return;
        }
        updateBar(t);
        ambient(t);

        Player target = inst.nearestAlive(entity.getLocation());
        if (target == null) return;
        if (entity instanceof Mob m) {
            LivingEntity cur = m.getTarget();
            if (cur == null || !cur.isValid() || cur.isDead()) m.setTarget(target);
        }

        double frac = entity.getHealth() / maxHp;
        switch (kind) {
            case SOMBRA -> {
                if (!fast && frac < 0.5) {
                    fast = true;
                    attr(entity, Attribute.MOVEMENT_SPEED, 0.43);
                    inst.titleAll("<light_purple><bold>FRENESÍ</bold></light_purple>", "<gray>La Sombra se acelera...</gray>");
                }
                if (t < busyUntil || t < nextAbility) return;
                double d = entity.getLocation().distance(target.getLocation());
                int r = ThreadLocalRandom.current().nextInt(100);
                if (d > 9 && r < 55) dash(target, 1.7, 1.1, 18);
                else if (r < 80) blink(target);
                else spin();
                nextAbility = t + (fast ? 40 : 70) + ThreadLocalRandom.current().nextInt(30);
            }
            case GUARDIAN -> {
                if (brokenUntil > 0 && t >= brokenUntil) {
                    brokenUntil = 0;
                    shield = shieldMax;
                    entity.setAI(true);
                    entity.getWorld().playSound(entity.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 0.7f);
                    inst.broadcast("<aqua>🛡 El Guardián recompone su escudo.</aqua>");
                }
                if (t < busyUntil || t < nextAbility) return;
                double d = entity.getLocation().distance(target.getLocation());
                int r = ThreadLocalRandom.current().nextInt(100);
                if (d > 8 && r < 65) dash(target, 1.35, 1.4, 22);
                else slam();
                nextAbility = t + 90 + ThreadLocalRandom.current().nextInt(40);
            }
            case NIGROMANTE -> {
                pruneMinions();
                if (t < busyUntil) return;
                double d = entity.getLocation().distance(target.getLocation());
                if (t >= nextBlink && d < 5) {
                    blinkAway();
                    nextBlink = t + 160;
                } else if (t >= nextSummon && minions.size() < 3 + inst.groupSize()) {
                    summon();
                    nextSummon = t + 300;
                } else if (t >= nextRain) {
                    soulRain();
                    nextRain = t + (frac < 0.5 ? 140 : 200);
                }
            }
        }
    }

    private void updateBar(long t) {
        bar.progress((float) Math.max(0.0, Math.min(1.0, entity.getHealth() / maxHp)));
        String status = "";
        if (kind == Kind.GUARDIAN) {
            if (t < brokenUntil) status = " <yellow>✦ Aturdido</yellow>";
            else if (shield > 0) status = " <aqua>🛡 " + (int) Math.ceil(shield / shieldMax * 100) + "%</aqua>";
        } else if (kind == Kind.NIGROMANTE && !minions.isEmpty()) {
            status = " <aqua>⛨ Súbditos: " + minions.size() + "</aqua>";
        }
        bar.name(Text.mm("<dark_gray>☠</dark_gray> " + kind.gradient + "<bold>" + kind.title.toUpperCase()
                + "</bold></gradient> <dark_gray>[</dark_gray><white>" + (int) Math.ceil(entity.getHealth())
                + "</white><gray>/</gray><white>" + (int) maxHp + "</white><dark_gray>]</dark_gray>" + status));
    }

    // ---- voz propia (con modelo 3D el mob real va en silencio)
    private long nextVoice;
    private long lastHurtFx;
    private Location lastStep;
    private double stepAcc;

    private String[] voiceKeys() {
        return switch (kind) {
            case SOMBRA -> new String[]{"entity.vex.ambient", "entity.vex.hurt", "entity.vex.death", "0.55"};
            case GUARDIAN -> new String[]{"entity.ravager.ambient", "entity.iron_golem.hurt", "entity.iron_golem.death", "0.6"};
            case NIGROMANTE -> new String[]{"entity.evoker.ambient", "entity.evoker.hurt", "entity.evoker.death", "0.7"};
        };
    }

    private void voice(long t) {
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        String[] k = voiceKeys();
        Location l = entity.getLocation();
        if (t >= nextVoice) {
            Sfx.play(l, k[0], 1.4f, Float.parseFloat(k[3]) + (float) rnd.nextDouble(-0.08, 0.08));
            if (kind == Kind.NIGROMANTE) Sfx.play(l, "particle.soul_escape", 1.0f, 0.8f);
            nextVoice = t + 110 + rnd.nextInt(140);
        }
        if (kind == Kind.GUARDIAN) {   // pasos pesados cada ~2 bloques
            if (lastStep != null && lastStep.getWorld() != null && lastStep.getWorld().equals(l.getWorld())) {
                stepAcc += Math.hypot(l.getX() - lastStep.getX(), l.getZ() - lastStep.getZ());
                if (stepAcc > 2.0) {
                    stepAcc = 0;
                    Sfx.play(l, "entity.iron_golem.step", 1.5f, 0.55f);
                    Sfx.play(l, "block.anvil.land", 0.25f, 0.5f);
                }
            }
            lastStep = l.clone();
        }
    }

    private void hurtVoice(long t) {
        if (t - lastHurtFx < 8) return;
        lastHurtFx = t;
        String[] k = voiceKeys();
        Sfx.play(entity.getLocation(), k[1], 1.3f, Float.parseFloat(k[3]) + 0.1f);
    }

    private void ambient(long t) {
        voice(t);
        if (t % 6 != 0) return;
        World w = entity.getWorld();
        Location c = entity.getLocation().add(0, entity.getHeight() * 0.5, 0);
        switch (kind) {
            case SOMBRA -> w.spawnParticle(Particle.SOUL_FIRE_FLAME, c, 4, 0.4, 0.8, 0.4, 0.01);
            case GUARDIAN -> {
                w.spawnParticle(Particle.FLAME, c, 4, 0.5, 0.9, 0.5, 0.01);
                if (shield > 0) w.spawnParticle(Particle.END_ROD, c, 3, 0.7, 0.9, 0.7, 0.01);
            }
            case NIGROMANTE -> w.spawnParticle(Particle.SOUL, c, 4, 0.5, 0.9, 0.5, 0.02);
        }
    }

    // ------------------------------------------------------------------ utilidades

    private void ring(Location c, double r, int points, Particle p) {
        World w = c.getWorld();
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            w.spawnParticle(p, c.getX() + Math.cos(a) * r, c.getY() + 0.15, c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    private void knock(Player p, Location from, double h, double v) {
        Vector d = p.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (d.lengthSquared() < 1.0E-4) d = new Vector(1, 0, 0);
        d.normalize().multiply(h).setY(v);
        p.setVelocity(d);
    }

    // ------------------------------------------------------------------ SOMBRA / GUARDIÁN

    /** Embestida: línea roja telegrafiada y carga en línea recta. */
    private void dash(Player target, double speed, double dmgMul, int telegraph) {
        busyUntil = inst.tick() + telegraph + 30;
        Location from = entity.getLocation();
        Vector dir = target.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(1, 0, 0);
        final Vector d = dir.normalize();
        entity.setAI(false);
        from.getWorld().playSound(from, Sound.ENTITY_RAVAGER_ROAR, 1.4f, 0.8f);
        inst.repeat(2, telegraph / 2, i -> {
            Location base = entity.getLocation();
            for (double k = 1; k <= 14; k += 1.0) {
                base.getWorld().spawnParticle(Particle.DUST, base.getX() + d.getX() * k, base.getY() + 0.2,
                        base.getZ() + d.getZ() * k, 1, 0, 0, 0, 0, new Particle.DustOptions(org.bukkit.Color.RED, 1.4f));
            }
        }, () -> {
            entity.setAI(true);
            entity.setVelocity(d.clone().multiply(speed).setY(0.2));
            Set<UUID> hit = new HashSet<>();
            inst.repeat(2, 10, i -> {
                Location l = entity.getLocation();
                l.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, l.clone().add(0, 1, 0), 6, 0.4, 0.5, 0.4, 0.02);
                for (Player p : inst.aliveOnline()) {
                    if (p.getLocation().distanceSquared(l) <= 5.5 && hit.add(p.getUniqueId())) {
                        p.damage(dmg * dmgMul, entity);
                        knock(p, l, 1.0, 0.5);
                    }
                }
            }, null);
        });
    }

    /** Parpadeo a la espalda del objetivo y tajo. */
    private void blink(Player target) {
        Vector back = target.getLocation().getDirection().setY(0);
        if (back.lengthSquared() < 1.0E-4) back = new Vector(0, 0, 1);
        back.normalize().multiply(-2.2);
        Location dest = target.getLocation().add(back);
        if (!free(dest)) {
            dest = target.getLocation().add(back.clone().multiply(-1));
            if (!free(dest)) return;
        }
        busyUntil = inst.tick() + 30;
        Location from = entity.getLocation();
        World w = from.getWorld();
        w.spawnParticle(Particle.PORTAL, from.clone().add(0, 1.5, 0), 60, 0.6, 1.2, 0.6, 0.5);
        w.playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1.4f, 0.6f);
        dest.setDirection(target.getLocation().toVector().subtract(dest.toVector()));
        entity.teleport(dest);
        w.spawnParticle(Particle.PORTAL, dest.clone().add(0, 1.5, 0), 60, 0.6, 1.2, 0.6, 0.5);
        inst.later(8, () -> {
            if (!entity.isValid() || !target.isOnline() || target.isDead()) return;
            if (target.getLocation().distanceSquared(entity.getLocation()) <= 20) {
                target.damage(dmg, entity);
                target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0));
                entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, 0.6f);
                entity.getWorld().spawnParticle(Particle.SWEEP_ATTACK, target.getLocation().add(0, 1, 0), 3, 0.5, 0.3, 0.5, 0);
            }
        });
    }

    private boolean free(Location l) {
        return l.getBlock().isPassable() && l.clone().add(0, 1, 0).getBlock().isPassable()
                && l.clone().add(0, 2, 0).getBlock().isPassable()
                && !l.clone().add(0, -1, 0).getBlock().isPassable();
    }

    /** Remolino: anillo telegrafiado alrededor del jefe. */
    private void spin() {
        busyUntil = inst.tick() + 40;
        entity.setAI(false);
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_WITHER_SHOOT, 1.5f, 0.6f);
        inst.repeat(2, 7, i -> ring(entity.getLocation(), 4.2, 28, Particle.SOUL_FIRE_FLAME), () -> {
            Location c = entity.getLocation();
            entity.setAI(true);
            c.getWorld().spawnParticle(Particle.SWEEP_ATTACK, c.clone().add(0, 1, 0), 12, 2.5, 0.3, 2.5, 0);
            c.getWorld().playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.6f, 0.5f);
            for (Player p : inst.aliveOnline()) {
                if (p.getLocation().distanceSquared(c) <= 4.6 * 4.6 && Math.abs(p.getLocation().getY() - c.getY()) < 3) {
                    p.damage(dmg * 1.1, entity);
                    knock(p, c, 1.0, 0.5);
                }
            }
        });
    }

    /** Pisotón del Guardián. */
    private void slam() {
        busyUntil = inst.tick() + 50;
        entity.setAI(false);
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.6f, 0.6f);
        inst.repeat(2, 10, i -> ring(entity.getLocation(), 5.5, 30, Particle.FLAME), () -> {
            Location c = entity.getLocation();
            entity.setAI(true);
            World w = c.getWorld();
            w.spawnParticle(Particle.EXPLOSION, c, 4, 2, 0.2, 2, 0);
            w.spawnParticle(Particle.FLAME, c, 60, 2.5, 0.2, 2.5, 0.08);
            w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.7f);
            for (Player p : inst.aliveOnline()) {
                if (p.getLocation().distanceSquared(c) <= 6 * 6 && Math.abs(p.getLocation().getY() - c.getY()) < 3) {
                    p.damage(dmg * 1.2, entity);
                    knock(p, c, 1.0, 0.7);
                    p.setFireTicks(60);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
                }
            }
        });
    }

    // ------------------------------------------------------------------ NIGROMANTE

    private void pruneMinions() {
        Iterator<UUID> it = minions.iterator();
        while (it.hasNext()) {
            org.bukkit.entity.Entity m = org.bukkit.Bukkit.getEntity(it.next());
            if (m == null || !m.isValid()) it.remove();
        }
    }

    private void summon() {
        busyUntil = inst.tick() + 40;
        Location c = entity.getLocation();
        World w = c.getWorld();
        w.playSound(c, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.6f, 0.7f);
        int n = Math.min(2 + inst.groupSize(), 3 + inst.groupSize() - minions.size());
        double hp = 24 * (1 + 0.25 * (inst.groupSize() - 1));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / Math.max(1, n);
            Location spot = c.clone().add(Math.cos(a) * 4, 0, Math.sin(a) * 4);
            if (!spot.getBlock().isPassable()) spot = c.clone();
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, spot.clone().add(0, 1, 0), 25, 0.3, 0.6, 0.3, 0.05);
            Skeleton sk = w.spawn(spot, Skeleton.class);
            attr(sk, Attribute.MAX_HEALTH, hp);
            sk.setHealth(hp);
            sk.customName(Text.mm("<aqua>Alma Condenada</aqua>"));
            sk.setCustomNameVisible(true);
            sk.setRemoveWhenFarAway(false);
            sk.setPersistent(false);
            sk.setCanPickupItems(false);
            EntityEquipment eq = sk.getEquipment();
            if (eq != null) {
                eq.setItemInMainHand(new ItemStack(Material.BOW));
                eq.setHelmet(new ItemStack(Material.IRON_HELMET));
                eq.setHelmetDropChance(0f);
                eq.setItemInMainHandDropChance(0f);
            }
            inst.registerMob(sk, false);
            Player t = inst.nearestAlive(spot);
            if (t != null) sk.setTarget(t);
            minions.add(sk.getUniqueId());
        }
        inst.broadcast("<aqua>El Nigromante invoca súbditos: ¡mátalos para dañarlo!</aqua>");
    }

    private void soulRain() {
        busyUntil = inst.tick() + 30;
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 1.6f, 0.6f);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Player p : inst.aliveOnline()) {
            Location base = p.getLocation().clone();
            inst.hazard(base, 34, 2.4, dmg * 1.3);
            inst.hazard(base.clone().add(rnd.nextDouble(-4, 4), 0, rnd.nextDouble(-4, 4)), 40, 2.4, dmg * 1.3);
        }
    }

    private void blinkAway() {
        Location dest = inst.randomSpot(inst.currentRoom());
        if (dest == null) return;
        Location from = entity.getLocation();
        busyUntil = inst.tick() + 20;
        from.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, 1.5, 0), 70, 0.6, 1.2, 0.6, 0.5);
        from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1.4f, 0.5f);
        entity.teleport(dest);
        dest.getWorld().spawnParticle(Particle.PORTAL, dest.clone().add(0, 1.5, 0), 70, 0.6, 1.2, 0.6, 0.5);
    }

    // ------------------------------------------------------------------ fin

    public void onDeath() {
        dead = true;
        Sfx.play(entity.getLocation(), voiceKeys()[2], 1.6f, Float.parseFloat(voiceKeys()[3]));
        Sfx.play(entity.getLocation(), "entity.wither.death", 0.8f, 1.2f);
        removeMinions();
    }

    public void remove() {
        dead = true;
        removeMinions();
        if (entity.isValid()) entity.remove();
    }

    private void removeMinions() {
        for (UUID id : minions) {
            org.bukkit.entity.Entity m = org.bukkit.Bukkit.getEntity(id);
            if (m != null) {
                m.getWorld().spawnParticle(Particle.SOUL, m.getLocation().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0.05);
                m.remove();
            }
        }
        minions.clear();
    }
}
