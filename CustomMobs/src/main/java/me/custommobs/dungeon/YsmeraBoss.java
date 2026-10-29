package me.custommobs.dungeon;

import me.custommobs.CustomMobsPlugin;
import me.custommobs.Text;
import me.custommobs.boss.BossItems;
import me.custommobs.model.MobVisuals;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.PointedDripstone;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntConsumer;

/**
 * Ysmera, Reina de Escarcha: jefe final con modelo 3D propio y tres fases.
 *
 * <ul>
 *   <li><b>Fase I · Reina Gélida</b>: carámbanos telegrafiados, onda helada (hay que saltar), lanzas de hielo,
 *       salto glacial, invocaciones y el Agarre Helado (un aliado te descongela estando cerca).</li>
 *   <li><b>Transición · Escudo de Cristal</b>: cuatro cristales la protegen; hay que romperlos mientras ella invoca.
 *       Al caer el último queda aturdida y recibe más daño.</li>
 *   <li><b>Fase II · Tormenta</b>: ventiscas con medidor de frío (las hogueras son el refugio), lanzas en cadena.</li>
 *   <li><b>Transición · Cero Absoluto</b>: el suelo se congela desde el centro y solo se sobrevive junto a las
 *       hogueras; después queda exhausta.</li>
 *   <li><b>Fase III · Cero Absoluto</b>: suelo resbaladizo, todo más rápido y, al final, una muerte cinemática.</li>
 * </ul>
 */
final class YsmeraBoss implements DungeonBoss {

    static final String ID = "ysmera";

    // ------------------------------------------------------------------ geometría compartida con el constructor

    /** Centros (x, z) de los altares de los cristales de la transición. */
    static int[][] crystalSpots(DungeonBuilder.Room r) {
        int cx = r.cx();
        return new int[][]{{cx + 11, r.cz}, {cx - 11, r.cz}, {cx, r.cz + 11}, {cx, r.cz - 11}};
    }

    /** Centros (x, z) de las hogueras: zonas cálidas contra el frío. */
    static int[][] warmSpots(DungeonBuilder.Room r) {
        int cx = r.cx();
        return new int[][]{{cx + 9, r.cz + 9}, {cx - 9, r.cz + 9}, {cx + 9, r.cz - 9}, {cx - 9, r.cz - 9}};
    }

    private static final double WARM_RADIUS = 4.6;

    // ------------------------------------------------------------------ fases y habilidades

    private enum Phase {
        ONE("<gray>Fase I</gray> <dark_gray>·</dark_gray> <aqua>Reina Gélida</aqua>", BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10,
                10, 0.27, 1.00, 1.00),
        TWO("<gray>Fase II</gray> <dark_gray>·</dark_gray> <light_purple>Tormenta</light_purple>", BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_12,
                13, 0.30, 0.82, 1.15),
        THREE("<gray>Fase III</gray> <dark_gray>·</dark_gray> <white><bold>Cero Absoluto</bold></white>", BossBar.Color.WHITE, BossBar.Overlay.NOTCHED_20,
                16, 0.33, 0.62, 1.30);

        final String title;
        final BossBar.Color color;
        final BossBar.Overlay overlay;
        final double attack, speed, cd, dmg;

        Phase(String title, BossBar.Color color, BossBar.Overlay overlay, double attack, double speed, double cd, double dmg) {
            this.title = title;
            this.color = color;
            this.overlay = overlay;
            this.attack = attack;
            this.speed = speed;
            this.cd = cd;
            this.dmg = dmg;
        }
    }

    private enum Ab {
        ICICLES(1, 150), NOVA(1, 250), BOLTS(1, 170), SUMMON(1, 520), BLINK(1, 280), GRASP(1, 560),
        SPEARS(2, 260), COMET(3, 330);

        final int minPhase;
        final int cd;

        Ab(int minPhase, int cd) {
            this.minPhase = minPhase;
            this.cd = cd;
        }
    }

    // ------------------------------------------------------------------ piezas auxiliares

    private static final class Crystal {
        EnderCrystal ent;
        Location at;
        double hp, max;
        long nextBolt;
        boolean dead;
    }

    private static final class Bolt {
        BlockDisplay d;
        Location pos;
        Vector vel;
        int life = 24;
        double dmg;
    }

    private static final class Prison {
        UUID who;
        BlockDisplay cube;
        long start;
        int progress;       // 0..60
        boolean done;
    }

    // ------------------------------------------------------------------ estado

    private final DungeonInstance inst;
    private final CustomMobsPlugin plugin;
    private final DungeonConfig cfg;
    private final World world;
    private final DungeonBuilder.Room room;
    private final MobVisuals vis;
    private final Husk ent;
    private final Location center;
    private final int floorY;
    private final double maxHp;
    private final BossBar bar;
    private final Cinematic cine;
    private final Map<Ab, Long> cooldowns = new EnumMap<>(Ab.class);
    private final List<Crystal> crystals = new ArrayList<>();
    private final List<Bolt> bolts = new ArrayList<>();
    private final List<Prison> prisons = new ArrayList<>();
    private final Map<UUID, Double> cold = new HashMap<>();
    private final Map<UUID, Long> coldHurt = new HashMap<>();
    private final List<Location> warm = new ArrayList<>();
    private final List<BlockDisplay> temp = new ArrayList<>();   // decoración temporal (carámbanos, lanzas...)

    private Phase phase = Phase.ONE;
    private boolean transitioning;
    private boolean invulnerable;
    private boolean dying;
    private boolean dead;
    private boolean gone;
    private boolean intro = true;
    private boolean shielded;
    private boolean absolute;           // canalizando el Cero Absoluto
    private boolean coldOn;
    private boolean frenzy;
    private long now;
    private long busyUntil;
    private long rootUntil;
    private long vulnUntil;
    private long nextSurge;
    private long surgeUntil;
    private long shieldStart;
    private long absoluteEnd;
    private long absoluteTotal;
    private long nextAdds;
    private long nextVoice;
    private long lastRetarget;
    private Ab last;
    private String hint = "Derrota a Ysmera";
    private int surgeDir;

    private YsmeraBoss(DungeonInstance inst, DungeonBuilder.Room room, Husk ent, double maxHp) {
        this.inst = inst;
        this.plugin = inst.plugin();
        this.cfg = inst.cfg();
        this.world = inst.world();
        this.room = room;
        this.vis = plugin.visuals();
        this.ent = ent;
        this.maxHp = maxHp;
        this.center = new Location(world, room.cx() + 0.5, room.floorY + 1, room.cz + 0.5);
        this.floorY = room.floorY;
        this.bar = BossBar.bossBar(Component.text("Ysmera"), 1f, phase.color, phase.overlay);
        this.cine = new Cinematic(plugin);
        for (int[] w : warmSpots(room)) warm.add(new Location(world, w[0] + 0.5, room.floorY + 1, w[1] + 0.5));
    }

    // ------------------------------------------------------------------ creación

    static YsmeraBoss spawn(DungeonInstance inst, DungeonBuilder.Room r) {
        DungeonConfig cfg = inst.cfg();
        World w = inst.world();
        Location at = new Location(w, r.cx() + 0.5, r.floorY + 1, r.cz + 0.5);
        double base = Math.min(1024.0, Math.max(100.0, cfg.d("ysmera.health")));
        Husk h = w.spawn(at, Husk.class, e -> {
            e.customName(Text.mm("<gradient:#9fe0f5:#ffffff><bold>Ysmera</bold></gradient>"));
            e.setCustomNameVisible(false);
            e.setPersistent(true);
            e.setRemoveWhenFarAway(false);
            e.setCanPickupItems(false);
            e.setSilent(true);
            e.setAdult();
            attr(e, Attribute.MAX_HEALTH, base);
            e.setHealth(base);
            attr(e, Attribute.KNOCKBACK_RESISTANCE, 1.0);
            attr(e, Attribute.FOLLOW_RANGE, 48.0);
            attr(e, Attribute.SCALE, 1.5);
            attr(e, Attribute.ATTACK_DAMAGE, Phase.ONE.attack);
            attr(e, Attribute.MOVEMENT_SPEED, Phase.ONE.speed);
        });
        if (h.getEquipment() != null) h.getEquipment().clear();
        YsmeraBoss b = new YsmeraBoss(inst, r, h, base);
        inst.manager().indexMob(h.getUniqueId(), inst);
        b.model();
        b.intro();
        return b;
    }

    private static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance ai = e.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    private void model() {
        if (vis == null || !vis.enabled()) return;
        String m = phase == Phase.ONE ? "ysmera" : phase == Phase.TWO ? "ysmera2" : "ysmera3";
        vis.attach(ent, m, false);
    }

    private void applyStats() {
        attr(ent, Attribute.ATTACK_DAMAGE, phase.attack * inst.heat().dmgMul());
        attr(ent, Attribute.MOVEMENT_SPEED, phase.speed);
    }

    // ------------------------------------------------------------------ interfaz DungeonBoss

    @Override
    public LivingEntity entity() {
        return ent;
    }

    @Override
    public boolean owns(Entity e) {
        if (e.getUniqueId().equals(ent.getUniqueId())) return true;
        for (Crystal c : crystals) if (c.ent != null && c.ent.getUniqueId().equals(e.getUniqueId())) return true;
        return false;
    }

    @Override
    public boolean finished() {
        return gone || dead;
    }

    @Override
    public String hint() {
        return hint;
    }

    @Override
    public List<ItemStack> rollLoot() {
        List<ItemStack> out = new ArrayList<>(cfg.rollLoot("ysmera.loot"));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        ItemStack heart = new ItemStack(Material.NETHER_STAR);
        ItemMeta hm = heart.getItemMeta();
        hm.displayName(Text.item("<gradient:#9fe0f5:#ffffff><bold>Corazón de Escarcha</bold></gradient>"));
        hm.lore(List.of(Text.item("<gray>Aún late, helado, en el pecho de Ysmera.</gray>"),
                Text.item("<dark_aqua>Trofeo de la Reina de Escarcha</dark_aqua>")));
        hm.setEnchantmentGlintOverride(true);
        heart.setItemMeta(hm);
        out.add(heart);
        out.add(new ItemStack(Material.PRISMARINE_CRYSTALS, r.nextInt(6, 13)));
        out.add(new ItemStack(Material.BLUE_ICE, r.nextInt(8, 17)));
        if (r.nextDouble() < cfg.d("ysmera.blade-chance")) {
            ItemStack blade = new ItemStack(Material.NETHERITE_SWORD);
            ItemMeta bm = blade.getItemMeta();
            bm.displayName(Text.item("<gradient:#9fe0f5:#e4faff><bold>Filo de Escarcha</bold></gradient>"));
            bm.lore(List.of(Text.item("<gray>Forjado con el último invierno.</gray>")));
            bm.addEnchant(Enchantment.SHARPNESS, 6, true);
            bm.addEnchant(Enchantment.LOOTING, 3, true);
            bm.addEnchant(Enchantment.UNBREAKING, 4, true);
            blade.setItemMeta(bm);
            out.add(blade);
        }
        try {
            out.addAll(BossItems.rollBooks(plugin));
        } catch (Throwable t) {
            // sin libros si la API cambia
        }
        return out;
    }

    @Override
    public void onDeath(EntityDeathEvent e) {
        dead = true;
        e.getDrops().clear();
        e.setDroppedExp(cfg.i("ysmera.xp"));
        cleanup();
    }

    @Override
    public void discard() {
        gone = true;
        cleanup();
        if (vis != null) vis.drop(ent.getUniqueId());
        inst.manager().unindexMob(ent.getUniqueId());
        if (ent.isValid()) ent.remove();
    }

    /** Retira todo lo temporal (barra, cristales, prisiones, proyectiles, cámara). */
    private void cleanup() {
        cine.stop();
        for (Player p : inst.onlineMembers()) {
            p.hideBossBar(bar);
            p.setFreezeTicks(0);
            p.removePotionEffect(PotionEffectType.SLOWNESS);
            p.removePotionEffect(PotionEffectType.JUMP_BOOST);
        }
        for (Crystal c : crystals) removeCrystal(c);
        crystals.clear();
        for (Bolt b : bolts) if (b.d != null && b.d.isValid()) b.d.remove();
        bolts.clear();
        for (Prison p : prisons) if (p.cube != null && p.cube.isValid()) p.cube.remove();
        prisons.clear();
        for (BlockDisplay d : temp) if (d.isValid()) d.remove();
        temp.clear();
        cold.clear();
    }

    private void removeCrystal(Crystal c) {
        if (c.ent != null) {
            inst.manager().unindexMob(c.ent.getUniqueId());
            if (c.ent.isValid()) c.ent.remove();
        }
    }

    // ------------------------------------------------------------------ utilidades

    private boolean alive() {
        return !gone && !dead && ent.isValid();
    }

    private void after(int ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!gone) r.run();
        }, Math.max(1, ticks));
    }

    private void seq(int period, int times, IntConsumer step, Runnable done) {
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (gone) {
                    cancel();
                    return;
                }
                if (i >= times) {
                    cancel();
                    if (done != null) done.run();
                    return;
                }
                step.accept(i++);
            }
        }.runTaskTimer(plugin, 0L, period);
    }

    private List<Player> alivePlayers() {
        List<Player> out = new ArrayList<>();
        for (Player p : inst.aliveOnline()) {
            if (p.getWorld().equals(world)) out.add(p);
        }
        return out;
    }

    private Player randomPlayer() {
        List<Player> al = alivePlayers();
        return al.isEmpty() ? null : al.get(ThreadLocalRandom.current().nextInt(al.size()));
    }

    private Player nearestPlayer(Location from) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : alivePlayers()) {
            double d = p.getLocation().distanceSquared(from);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private double groundY() {
        return floorY + 1.0;
    }

    private boolean onGround(Player p) {
        return p.getLocation().getY() - groundY() < 0.6;
    }

    private double dmgScale() {
        return phase.dmg * inst.heat().dmgMul();
    }

    private void hit(Player p, double base) {
        inst.hazardHit(p, base * dmgScale());
    }

    private void chill(Player p, int freezeTicks, int slowTicks, int slowLevel) {
        p.setFreezeTicks(Math.min(p.getMaxFreezeTicks() + 40, p.getFreezeTicks() + freezeTicks));
        if (slowTicks > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks, slowLevel, false, true, true));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT_FREEZE, 1f, 1f);
    }

    private boolean isWarm(Location l) {
        for (Location w : warm) {
            double dx = w.getX() - l.getX();
            double dz = w.getZ() - l.getZ();
            if (dx * dx + dz * dz <= WARM_RADIUS * WARM_RADIUS) return true;
        }
        return false;
    }

    private void say(String line) {
        if (now < nextVoice) return;
        nextVoice = now + 60;
        inst.broadcast("<dark_gray>[</dark_gray><aqua>Ysmera</aqua><dark_gray>]</dark_gray> <white><italic>" + line + "</italic></white>");
    }

    private void sayNow(String line) {
        nextVoice = 0;
        say(line);
    }

    private void sound(Location l, Sound s, float vol, float pitch) {
        world.playSound(l, s, vol, pitch);
    }

    private void ring(Location c, double r, int points, Particle p) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            world.spawnParticle(p, c.getX() + Math.cos(a) * r, c.getY() + 0.12, c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    private void ringDust(Location c, double r, int points, Color col, float size) {
        Particle.DustOptions o = new Particle.DustOptions(col, size);
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            world.spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * r, c.getY() + 0.15, c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0, o);
        }
    }

    private void iceBurst(Location l, int n, double spread) {
        world.spawnParticle(Particle.BLOCK, l, n, spread, spread * 0.8, spread, 0.1, Material.BLUE_ICE.createBlockData());
        world.spawnParticle(Particle.SNOWFLAKE, l, n / 2, spread, spread, spread, 0.12);
        world.spawnParticle(Particle.END_ROD, l, Math.max(2, n / 6), spread, spread, spread, 0.15);
    }

    private void line(Location a, Location b, Color col, int steps) {
        Vector d = b.toVector().subtract(a.toVector());
        Particle.DustOptions o = new Particle.DustOptions(col, 1.1f);
        for (int i = 0; i <= steps; i++) {
            Vector p = a.toVector().add(d.clone().multiply(i / (double) steps));
            world.spawnParticle(Particle.DUST, p.getX(), p.getY(), p.getZ(), 1, 0, 0, 0, 0, o);
        }
    }

    // ---- gestos (poses dirigidas del modelo)

    private void arms(float rx, float spreadZ) {
        if (vis == null) return;
        vis.pose(id(), "armL", rx, 0, -spreadZ);
        vis.pose(id(), "armR", rx, 0, spreadZ);
    }

    private void wings(float rz) {
        if (vis == null) return;
        vis.pose(id(), "wingL", 0, 0, rz);
        vis.pose(id(), "wingR", 0, 0, -rz);
    }

    private void relax() {
        if (vis != null) {
            vis.clearPose(id());
            vis.tempo(id(), 1f);
            vis.shake(id(), 0f);
            vis.pulse(id(), 1f);
        }
    }

    private void lift(float blocks) {
        if (vis != null) vis.lift(id(), blocks);
    }

    private void root(int ticks) {
        rootUntil = Math.max(rootUntil, now + ticks);
    }

    private void busy(int ticks) {
        busyUntil = Math.max(busyUntil, now + ticks);
    }

    private Matrix4f spike(float sx, float sy, float sz, float fall) {
        return new Matrix4f().translate(-0.5f * sx, -fall, -0.5f * sz).scale(sx, sy, sz);
    }

    private BlockDisplay dripstone(Location at, boolean down, float sx, float sy, float sz) {
        PointedDripstone pd = (PointedDripstone) Material.POINTED_DRIPSTONE.createBlockData();
        pd.setVerticalDirection(down ? BlockFace.DOWN : BlockFace.UP);
        pd.setThickness(PointedDripstone.Thickness.FRUSTUM);
        BlockDisplay d = world.spawn(at, BlockDisplay.class, b -> {
            b.setBlock(pd);
            b.setPersistent(false);
            b.setBrightness(new Display.Brightness(12, 12));
            b.setViewRange(2.5f);
            b.setInterpolationDelay(0);
            b.setInterpolationDuration(6);
            b.setTransformationMatrix(spike(sx, sy, sz, 0f));
        });
        temp.add(d);
        return d;
    }

    private void drop(BlockDisplay d) {
        if (d != null && d.isValid()) d.remove();
        temp.remove(d);
    }

    private int phaseNum() {
        return phase.ordinal() + 1;
    }

    // ------------------------------------------------------------------ bucle principal

    @Override
    public void tick(long t) {
        now = t;
        if (gone || dead) return;
        if (!ent.isValid()) return;
        ent.setFireTicks(0);
        bars();
        ambient();
        keepInside();
        boolean still = intro || transitioning || dying || shielded || absolute || now < rootUntil;
        ent.setAI(!still);
        if (still) {
            ent.setVelocity(new Vector(0, Math.min(0, ent.getVelocity().getY()), 0));
        } else if (now - lastRetarget >= 20) {
            lastRetarget = now;
            Player n = nearestPlayer(ent.getLocation());
            if (n != null) ent.setTarget(n);
        }
        boltsTick();
        prisonTick();
        crystalsTick();
        coldTick();
        if (shielded) {
            int left = 0;
            for (Crystal c : crystals) if (!c.dead) left++;
            hint = "Destruye los cristales (" + left + ")";
            shieldTick();
        } else if (absolute) {
            hint = "¡Refúgiate junto a una hoguera!";
            if (now >= absoluteEnd) finishAbsolute();
        } else {
            hint = "Derrota a Ysmera";
        }
        if (phase == Phase.THREE && !frenzy && !dying && !transitioning && ent.getHealth() < maxHp * 0.2) {
            frenzy = true;
            sayNow("¡Basta! ¡Que todo se congele!");
            inst.titleAll("<white><bold>FRENESÍ GLACIAL</bold></white>", "<gray>Ysmera ataca sin descanso</gray>");
            sound(ent.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.6f);
            if (vis != null) {
                vis.pulse(id(), 1.15f);
                after(10, () -> {
                    if (vis != null) vis.pulse(id(), 1f);
                });
            }
        }
        if (!intro && !transitioning && !dying && !shielded && !absolute && now >= busyUntil) chooseAbility();
    }

    private void keepInside() {
        if (now % 20 != 0) return;
        Location l = ent.getLocation();
        if (Math.hypot(l.getX() - center.getX(), l.getZ() - center.getZ()) > 23 || l.getY() < floorY - 2) {
            ent.teleport(center);
        }
    }

    private void bars() {
        float prog;
        String name;
        BossBar.Color col = phase.color;
        BossBar.Overlay ov = phase.overlay;
        if (shielded) {
            double tot = 0, mx = 0;
            for (Crystal c : crystals) {
                tot += Math.max(0, c.hp);
                mx += c.max;
            }
            prog = mx > 0 ? (float) (tot / mx) : 0f;
            name = "<aqua><bold>Ysmera</bold></aqua> <dark_gray>·</dark_gray> <light_purple><bold>Escudo de Cristal</bold></light_purple>";
            col = BossBar.Color.PURPLE;
            ov = BossBar.Overlay.NOTCHED_6;
        } else if (absolute) {
            prog = (float) Math.max(0, (absoluteEnd - now) / (double) Math.max(1, absoluteTotal));
            name = "<white><bold>CERO ABSOLUTO</bold></white> <dark_gray>·</dark_gray> <gold>refúgiate en una hoguera</gold> <dark_gray>·</dark_gray> <aqua>"
                    + Math.max(0, (absoluteEnd - now) / 20) + "s</aqua>";
            col = BossBar.Color.WHITE;
            ov = BossBar.Overlay.PROGRESS;
        } else {
            prog = (float) (ent.getHealth() / maxHp);
            boolean vuln = now < vulnUntil;
            name = "<gradient:#9fe0f5:#ffffff><bold>Ysmera</bold></gradient> <dark_gray>·</dark_gray> " + phase.title
                    + (vuln ? " <dark_gray>·</dark_gray> <red><bold>¡VULNERABLE!</bold></red>" : "");
            if (vuln) col = BossBar.Color.RED;
        }
        bar.progress(Math.max(0f, Math.min(1f, prog)));
        bar.name(Text.mm(name));
        bar.color(col);
        bar.overlay(ov);
        for (Player p : inst.onlineMembers()) p.showBossBar(bar);
    }

    private void ambient() {
        Location l = ent.getLocation();
        world.spawnParticle(Particle.SNOWFLAKE, l.getX(), l.getY() + 2.4, l.getZ(), phase == Phase.THREE ? 5 : 3, 1.3, 1.6, 1.3, 0.01);
        if (now % 6 == 0) {
            world.spawnParticle(Particle.END_ROD, l.getX(), l.getY() + 5.2, l.getZ(), 2, 0.4, 0.3, 0.4, 0.01);
            if (phase != Phase.ONE) world.spawnParticle(Particle.ELECTRIC_SPARK, l.getX(), l.getY() + 3, l.getZ(), 3, 1.6, 1.8, 1.6, 0.05);
        }
        if (phase != Phase.ONE || absolute || transitioning) {
            for (Location w : warm) {
                if (now % 4 == 0) {
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, w.getX(), w.getY() + 0.5, w.getZ(), 2, 0.25, 0.4, 0.25, 0.01);
                }
                if (now % 10 == 0) ringDust(w, WARM_RADIUS, 26, Color.fromRGB(255, 170, 70), 0.9f);
            }
        }
    }

    // ------------------------------------------------------------------ daño recibido

    @Override
    public void onDamage(EntityDamageEvent e) {
        Entity en = e.getEntity();
        for (Crystal c : crystals) {
            if (c.dead || c.ent == null || !c.ent.getUniqueId().equals(en.getUniqueId())) continue;
            e.setCancelled(true);
            if (!(e instanceof EntityDamageByEntityEvent be)) return;
            Player p = be.getDamager() instanceof Player pl ? pl
                    : be.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player sp ? sp : null;
            if (p == null) return;
            double dmg = Math.max(1.0, e.getFinalDamage());
            inst.addDamage(p, dmg);
            c.hp -= dmg;
            world.spawnParticle(Particle.ENCHANTED_HIT, c.at.clone().add(0, 1, 0), 12, 0.5, 0.6, 0.5, 0.3);
            world.playSound(c.at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 0.6f);
            if (c.hp <= 0) destroyCrystal(c);
            return;
        }
        if (!en.getUniqueId().equals(ent.getUniqueId())) return;
        switch (e.getCause()) {
            case FIRE, FIRE_TICK, LAVA, DROWNING, FREEZE, SUFFOCATION, POISON, WITHER, HOT_FLOOR, CONTACT -> {
                e.setCancelled(true);
                return;
            }
            default -> { }
        }
        if (invulnerable || dying || transitioning || intro || shielded || absolute) {
            e.setCancelled(true);
            Location l = ent.getLocation().add(0, 2.5, 0);
            world.spawnParticle(Particle.ENCHANTED_HIT, l, 6, 0.8, 1.0, 0.8, 0.2);
            if (now % 10 == 0) world.playSound(l, Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.8f, 0.5f);
            return;
        }
        double n = inst.groupSize();
        double mul = 1.0 / ((1 + cfg.d("ysmera.health-per-extra-player") * (n - 1)) * inst.heat().hpMul());
        if (now < vulnUntil) mul *= 1.6;
        double real = e.getFinalDamage() * mul;
        double hp = ent.getHealth();
        double floorHp = phase == Phase.ONE ? maxHp * 0.66 : phase == Phase.TWO ? maxHp * 0.33 : 1.0;
        if (hp - real <= floorHp) {
            e.setCancelled(true);
            double dealt = Math.max(0, hp - floorHp);
            ent.setHealth(Math.max(1.0, floorHp));
            if (e instanceof EntityDamageByEntityEvent be) {
                Player p = be.getDamager() instanceof Player pl ? pl
                        : be.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player sp ? sp : null;
                if (p != null) inst.addDamage(p, dealt);
            }
            if (phase == Phase.ONE) beginShield();
            else if (phase == Phase.TWO) beginAbsolute();
            else beginDeath();
            return;
        }
        e.setDamage(e.getDamage() * mul);
    }

    // ------------------------------------------------------------------ entrada (cinemática)

    private void intro() {
        intro = true;
        invulnerable = true;
        ent.setAI(false);
        List<Player> ps = inst.onlineMembers();
        Location c = ent.getLocation();
        inst.titleAll("<gradient:#9fe0f5:#ffffff><bold>YSMERA</bold></gradient>", "<gray>La Reina de Escarcha despierta</gray>");
        sound(c, Sound.ENTITY_WITHER_SPAWN, 1.5f, 1.8f);
        sound(c, Sound.AMBIENT_CAVE, 1.5f, 0.5f);
        world.strikeLightningEffect(c);
        if (vis != null) {
            vis.tempo(id(), 2f);
            vis.pose(id(), "head", -18, 0, 0);
            wings(34);
            arms(-20, 40);
        }
        lift(0f);
        cine.play(ps, () -> ent.getLocation().add(0, 3.4, 0), 120, Cinematic.orbit(15, 7.5, 1.0, 3.8, Math.PI * 0.55), 60, 100, null);
        after(8, () -> lift(2.2f));
        after(30, () -> sayNow("Otro grupo de valientes... otra tumba de hielo."));
        after(60, () -> {
            arms(-130, 45);
            wings(40);
            if (vis != null) {
                vis.pulse(id(), 1.12f);
                vis.shake(id(), 2.5f);
            }
            sound(ent.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.5f);
            sound(ent.getLocation(), Sound.BLOCK_GLASS_BREAK, 2f, 0.6f);
            ring(ent.getLocation(), 3, 40, Particle.SNOWFLAKE);
            iceBurst(ent.getLocation().add(0, 2, 0), 60, 2.5);
        });
        seq(4, 20, i -> {
            Location b = ent.getLocation();
            ring(b, 1.0 + i * 0.7, 36, Particle.SNOWFLAKE);
            ring(b, 0.5 + i * 0.6, 24, Particle.END_ROD);
        }, null);
        after(100, () -> sayNow("Acercaos. Os daré un invierno eterno."));
        after(122, () -> {
            relax();
            lift(0f);
            intro = false;
            invulnerable = false;
            busy(50);
            for (Ab a : Ab.values()) cooldowns.put(a, now + 60 + ThreadLocalRandom.current().nextInt(60));
        });
    }

    // ------------------------------------------------------------------ selección de habilidad

    private void chooseAbility() {
        List<Ab> pool = new ArrayList<>();
        int group = inst.groupSize();
        for (Ab a : Ab.values()) {
            if (a.minPhase > phaseNum()) continue;
            if (cooldowns.getOrDefault(a, 0L) > now) continue;
            if (a == last && pool.size() > 0) continue;
            if (a == Ab.GRASP && alivePlayers().size() < 2) continue;
            if (a == Ab.SUMMON && inst.mobCount() >= 4 + group) continue;
            if (a == Ab.NOVA && nearestPlayer(ent.getLocation()) != null
                    && nearestPlayer(ent.getLocation()).getLocation().distanceSquared(ent.getLocation()) > 20 * 20) continue;
            pool.add(a);
        }
        if (pool.isEmpty()) return;
        Ab a = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        if (a == last && pool.size() > 1) a = pool.get((pool.indexOf(a) + 1) % pool.size());
        last = a;
        cooldowns.put(a, now + (long) (a.cd * phase.cd * (frenzy ? 0.7 : 1.0)));
        busy(30);
        switch (a) {
            case ICICLES -> icicles();
            case NOVA -> nova();
            case BOLTS -> boltVolley(phaseNum() == 1 ? 3 : phaseNum() == 2 ? 5 : 7, phaseNum() == 1 ? 12 : 9);
            case SUMMON -> summon();
            case BLINK -> blink();
            case GRASP -> grasp();
            case SPEARS -> spears();
            case COMET -> comet();
        }
    }

    // ------------------------------------------------------------------ habilidades

    private void icicles() {
        sayNow(randomOf("¡Que llueva escarcha!", "Sentid cómo se agrieta el cielo.", "Cada copo es una condena."));
        arms(-100, 25);
        wings(20);
        busy(56);
        int delay = phase == Phase.ONE ? 44 : phase == Phase.TWO ? 38 : 32;
        List<Location> spots = new ArrayList<>();
        for (Player p : alivePlayers()) spots.add(p.getLocation().clone());
        for (int i = 0; i < 1 + phaseNum(); i++) {
            Location l = inst.randomSpot(room);
            if (l != null) spots.add(l);
        }
        for (int i = 0; i < Math.min(9, spots.size()); i++) icicleAt(spots.get(i), delay);
        after(delay + 8, () -> {
            if (calm()) relax();
        });
    }

    private void icicleAt(Location s, int delay) {
        double gy = groundY();
        Location base = new Location(world, s.getX(), gy, s.getZ());
        float sy = 3.4f;
        double ceil = floorY + room.height;
        Location top = new Location(world, s.getX(), ceil - sy + 0.3, s.getZ());
        BlockDisplay d = dripstone(top, true, 1.7f, sy, 1.7f);
        float fall = (float) (top.getY() - gy);
        int steps = Math.max(1, delay / 4);
        seq(4, steps, i -> {
            double k = 1.0 - i / (double) steps;
            ringDust(base, 2.3, 18, Color.fromRGB(110, 200, 255), 1.0f);
            ringDust(base, Math.max(0.3, 2.3 * k), 12, Color.WHITE, 0.8f);
            if (i % 3 == 0) world.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 0.6f + (float) (1 - k));
        }, null);
        after(delay - 8, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(8);
            d.setTransformationMatrix(spike(1.7f, sy, 1.7f, fall));
        });
        after(delay, () -> {
            for (Player p : alivePlayers()) {
                Location pl = p.getLocation();
                if (Math.hypot(pl.getX() - base.getX(), pl.getZ() - base.getZ()) <= 2.3 && Math.abs(pl.getY() - gy) < 4) {
                    hit(p, 9);
                    chill(p, 80, 50, 1);
                }
            }
            iceBurst(base.clone().add(0, 0.4, 0), 40, 1.1);
            ring(base, 2.3, 30, Particle.SNOWFLAKE);
            world.playSound(base, Sound.BLOCK_GLASS_BREAK, 1.4f, 0.7f);
            world.playSound(base, Sound.BLOCK_POWDER_SNOW_BREAK, 1.2f, 0.6f);
        });
        after(delay + 10, () -> drop(d));
    }

    private void nova() {
        int rings = phaseNum();
        int total = 40 + rings * 18;
        busy(total + 30);
        root(total + 10);
        sayNow(randomOf("¡Arrodillaos ante el invierno!", "Que el suelo mismo os rechace.", "¡Ni un paso más!"));
        arms(-125, 35);
        wings(26);
        lift(1.4f);
        if (vis != null) {
            vis.pulse(id(), 1.1f);
            vis.tempo(id(), 2.5f);
        }
        Location c = ent.getLocation().clone();
        c.setY(groundY());
        seq(2, 18, i -> {
            ring(c, 1.0 + i * 0.22, 30, Particle.SNOWFLAKE);
            ringDust(c, 4.0, 22, Color.fromRGB(150, 220, 255), 1.1f);
            world.spawnParticle(Particle.END_ROD, c.getX(), c.getY() + 0.3, c.getZ(), 3, 1.5, 0.2, 1.5, 0.02);
            if (i % 5 == 0) world.playSound(c, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.4f, 0.5f + i * 0.04f);
        }, null);
        after(36, () -> inst.titleAll("", "<aqua><bold>¡SALTA!</bold></aqua>"));
        for (int k = 0; k < rings; k++) {
            final double speed = 1.15 + 0.1 * k;
            after(38 + k * 18, () -> expandRing(c.clone(), speed));
        }
        after(total, () -> {
            if (calm()) {
                relax();
                lift(0f);
            }
        });
    }

    private void expandRing(Location c, double speed) {
        world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.5f);
        world.playSound(c, Sound.BLOCK_GLASS_BREAK, 2f, 0.5f);
        java.util.Set<UUID> hitSet = new java.util.HashSet<>();
        seq(2, 20, i -> {
            double r = 2.0 + (i + 1) * speed;
            int pts = (int) Math.min(120, 24 + r * 7);
            ring(c, r, pts, Particle.SNOWFLAKE);
            ring(c, r, pts / 2, Particle.CLOUD);
            Particle.DustOptions o = new Particle.DustOptions(Color.fromRGB(190, 235, 255), 1.6f);
            for (int q = 0; q < pts / 2; q++) {
                double a = Math.PI * 2 * q / (pts / 2.0);
                world.spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * r, c.getY() + 0.5, c.getZ() + Math.sin(a) * r, 1, 0, 0.2, 0, 0, o);
            }
            for (Player p : alivePlayers()) {
                Location pl = p.getLocation();
                double d = Math.hypot(pl.getX() - c.getX(), pl.getZ() - c.getZ());
                if (Math.abs(d - r) < 1.5 && onGround(p) && hitSet.add(p.getUniqueId())) {
                    hit(p, 10);
                    chill(p, 100, 60, 1);
                    Vector kb = new Vector(pl.getX() - c.getX(), 0, pl.getZ() - c.getZ());
                    if (kb.lengthSquared() > 0.001) p.setVelocity(kb.normalize().multiply(0.9).setY(0.45));
                }
            }
        }, null);
    }

    private void boltVolley(int n, double fanStep) {
        Player t = randomPlayer();
        if (t == null) return;
        sayNow(randomOf("Corred, si podéis.", "Lanzas de escarcha, a mi voluntad.", "No hay donde esconderse."));
        if (calm()) {
            arms(-75, 12);
            wings(14);
        }
        busy(40 + n * 3);
        root(24);
        Location from = ent.getLocation().add(0, 3.3, 0);
        Location aim = t.getLocation().add(0, 1.0, 0);
        for (int i = 0; i < 4; i++) {
            after(i * 4, () -> line(from, aim, Color.fromRGB(150, 225, 255), 18));
        }
        after(16, () -> {
            for (int i = 0; i < n; i++) {
                final int k = i;
                after(k * 3, () -> {
                    Vector dir = aim.toVector().subtract(from.toVector()).normalize();
                    double ang = Math.toRadians((k - (n - 1) / 2.0) * fanStep);
                    double cs = Math.cos(ang), sn = Math.sin(ang);
                    Vector v = new Vector(dir.getX() * cs - dir.getZ() * sn, dir.getY(), dir.getX() * sn + dir.getZ() * cs).normalize();
                    fireBolt(from.clone(), v);
                });
            }
            after(n * 3 + 8, () -> {
                if (calm()) relax();
            });
        });
    }

    private void fireBolt(Location from, Vector dir) {
        Bolt b = new Bolt();
        b.pos = from.clone();
        b.pos.setDirection(dir);
        b.vel = dir.clone().multiply(1.7);
        b.dmg = 8;
        BlockDisplay d = world.spawn(b.pos, BlockDisplay.class, bd -> {
            bd.setBlock(Material.BLUE_ICE.createBlockData());
            bd.setPersistent(false);
            bd.setBrightness(new Display.Brightness(15, 15));
            bd.setViewRange(2.5f);
            bd.setTeleportDuration(2);
            bd.setTransformationMatrix(new Matrix4f().translate(-0.22f, -0.22f, -0.8f).scale(0.44f, 0.44f, 1.6f));
        });
        b.d = d;
        bolts.add(b);
        world.playSound(from, Sound.ENTITY_BLAZE_SHOOT, 1.2f, 1.7f);
        world.playSound(from, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.6f);
    }

    private void boltsTick() {
        Iterator<Bolt> it = bolts.iterator();
        while (it.hasNext()) {
            Bolt b = it.next();
            b.pos.add(b.vel);
            b.life--;
            boolean boom = b.life <= 0 || !b.pos.getBlock().isPassable();
            if (!boom) {
                for (Player p : alivePlayers()) {
                    if (p.getLocation().add(0, 1.0, 0).distanceSquared(b.pos) <= 1.3 * 1.3) {
                        hit(p, b.dmg);
                        chill(p, 80, 50, 1);
                        boom = true;
                        break;
                    }
                }
            }
            if (boom) {
                iceBurst(b.pos, 24, 0.5);
                world.playSound(b.pos, Sound.BLOCK_GLASS_BREAK, 1f, 1.3f);
                if (b.d != null && b.d.isValid()) b.d.remove();
                it.remove();
                continue;
            }
            if (b.d != null && b.d.isValid()) b.d.teleport(b.pos);
            world.spawnParticle(Particle.SNOWFLAKE, b.pos, 2, 0.1, 0.1, 0.1, 0.01);
        }
    }

    private void summon() {
        sayNow(randomOf("Levantaos, centinelas del frío.", "El invierno tiene hijos.", "Servidme."));
        arms(-100, 20);
        wings(22);
        busy(80);
        root(50);
        int cap = 6 + inst.groupSize();
        int n = Math.min(2 + inst.groupSize() / 2 + (phaseNum() - 1), 6);
        n = Math.min(n, Math.max(0, cap - inst.mobCount()));
        for (int i = 0; i < n; i++) {
            Location l = inst.randomSpot(room);
            if (l == null) continue;
            final boolean elite = phase == Phase.THREE && i == 0;
            seq(4, 8, k -> {
                ring(l, 1.6, 16, Particle.SNOWFLAKE);
                world.spawnParticle(Particle.END_ROD, l.getX(), l.getY() + 0.3 + k * 0.2, l.getZ(), 2, 0.3, 0.1, 0.3, 0.02);
            }, () -> {
                iceBurst(l.clone().add(0, 1, 0), 40, 0.8);
                world.playSound(l, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.6f);
                inst.spawnObjectiveMob(l, elite);
            });
        }
        after(60, () -> {
            if (calm()) relax();
        });
    }

    private void blink() {
        Player t = randomPlayer();
        if (t == null) return;
        busy(96);
        root(96);
        Location old = ent.getLocation();
        iceBurst(old.clone().add(0, 2, 0), 50, 1.2);
        world.playSound(old, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.6f);
        if (vis != null) {
            vis.pulse(id(), 0.05f);
            vis.tempo(id(), 3f);
        }
        after(10, () -> {
            if (!alive() || dying || transitioning) return;
            Location pl = t.getLocation();
            Vector back = pl.getDirection().setY(0);
            if (back.lengthSquared() < 0.01) back = new Vector(1, 0, 0);
            back.normalize().multiply(-3.4);
            Location dest = pl.clone().add(back);
            dest.setY(groundY());
            if (!dest.getBlock().isPassable() || !dest.clone().add(0, 1, 0).getBlock().isPassable()) dest = center.clone();
            dest.setDirection(pl.toVector().subtract(dest.toVector()).setY(0));
            ent.teleport(dest);
            if (vis != null) {
                vis.pulse(id(), 1.15f);
                after(6, () -> {
                    if (vis != null) vis.pulse(id(), 1f);
                });
            }
            iceBurst(dest.clone().add(0, 2, 0), 60, 1.4);
            world.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 1.3f);
            world.playSound(dest, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.8f);
            arms(-95, 28);
        });
        after(16, () -> {
            for (int i = 0; i < 3; i++) {
                after(i * 4, () -> {
                    Location f = ent.getLocation();
                    Vector fw = f.getDirection().setY(0).normalize();
                    Particle.DustOptions o = new Particle.DustOptions(Color.fromRGB(255, 120, 120), 1.2f);
                    for (double a = -60; a <= 60; a += 6) {
                        Vector v = fw.clone().rotateAroundY(Math.toRadians(a));
                        for (double r = 1.5; r <= 6.5; r += 1.0) {
                            world.spawnParticle(Particle.DUST, f.getX() + v.getX() * r, groundY() + 0.15, f.getZ() + v.getZ() * r, 1, 0, 0, 0, 0, o);
                        }
                    }
                });
            }
        });
        after(32, () -> {
            if (!alive() || dying) return;
            if (vis != null) vis.attack(id(), 10);
            arms(-30, 20);
            Location f = ent.getLocation();
            Vector fw = f.getDirection().setY(0).normalize();
            world.playSound(f, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 2f, 0.5f);
            world.playSound(f, Sound.BLOCK_GLASS_BREAK, 1.5f, 0.5f);
            for (double a = -60; a <= 60; a += 12) {
                Vector v = fw.clone().rotateAroundY(Math.toRadians(a));
                for (double r = 1.5; r <= 6.5; r += 1.3) {
                    world.spawnParticle(Particle.SWEEP_ATTACK, f.getX() + v.getX() * r, f.getY() + 1.2, f.getZ() + v.getZ() * r, 1, 0, 0, 0, 0);
                    world.spawnParticle(Particle.SNOWFLAKE, f.getX() + v.getX() * r, f.getY() + 1.2, f.getZ() + v.getZ() * r, 4, 0.2, 0.3, 0.2, 0.05);
                }
            }
            for (Player p : alivePlayers()) {
                Vector to = p.getLocation().toVector().subtract(f.toVector()).setY(0);
                double dist = to.length();
                if (dist < 0.01 || dist > 6.8) continue;
                if (Math.toDegrees(fw.angle(to.clone().normalize())) <= 62) {
                    hit(p, 11);
                    chill(p, 60, 40, 1);
                    p.setVelocity(to.normalize().multiply(1.0).setY(0.4));
                }
            }
        });
        after(60, () -> {
            if (calm()) relax();
        });
    }

    private void grasp() {
        Player t = randomPlayer();
        if (t == null) return;
        busy(70);
        root(30);
        sayNow(randomOf("Quédate quieto... para siempre.", "Una estatua más para mi jardín.", "Duerme en el hielo."));
        arms(-90, 15);
        titleTo(t, "<aqua><bold>❄ AGARRE HELADO</bold></aqua>", "<gray>Un aliado debe acercarse para descongelarte</gray>");
        for (int i = 0; i < 5; i++) {
            after(i * 4, () -> line(ent.getLocation().add(0, 3.0, 0), t.getLocation().add(0, 1, 0), Color.fromRGB(110, 210, 255), 24));
        }
        after(22, () -> {
            if (!t.isOnline() || t.isDead() || !alivePlayers().contains(t)) return;
            Prison pr = new Prison();
            pr.who = t.getUniqueId();
            pr.start = now;
            Location at = t.getLocation();
            pr.cube = world.spawn(at, BlockDisplay.class, d -> {
                d.setBlock(Material.ICE.createBlockData());
                d.setPersistent(false);
                d.setBrightness(new Display.Brightness(13, 13));
                d.setViewRange(2.5f);
                d.setTeleportDuration(1);
                d.setTransformationMatrix(new Matrix4f().translate(-0.8f, -0.05f, -0.8f).scale(1.6f, 2.5f, 1.6f));
            });
            prisons.add(pr);
            iceBurst(at.clone().add(0, 1, 0), 50, 0.7);
            world.playSound(at, Sound.BLOCK_GLASS_BREAK, 1.5f, 0.5f);
            world.playSound(at, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.5f, 0.6f);
            inst.broadcast("<aqua>❄ " + t.getName() + " está congelado.</aqua> <gray>¡Acércate para descongelarlo!</gray>");
            relax();
        });
    }

    private void prisonTick() {
        Iterator<Prison> it = prisons.iterator();
        while (it.hasNext()) {
            Prison pr = it.next();
            Player v = Bukkit.getPlayer(pr.who);
            if (v == null || !v.isOnline() || v.isDead() || !alivePlayers().contains(v)) {
                releasePrison(pr, v, false);
                it.remove();
                continue;
            }
            v.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 6, 7, false, false, false));
            v.setFreezeTicks(v.getMaxFreezeTicks() + 20);
            if (pr.cube != null && pr.cube.isValid()) pr.cube.teleport(v.getLocation());
            int allies = 0;
            for (Player p : alivePlayers()) {
                if (!p.equals(v) && p.getLocation().distanceSquared(v.getLocation()) <= 3.4 * 3.4) allies++;
            }
            if (allies > 0) {
                pr.progress += Math.min(3, allies) * 2;
                world.spawnParticle(Particle.FLAME, v.getLocation().add(0, 1, 0), 3, 0.5, 0.6, 0.5, 0.01);
            } else if (pr.progress > 0 && now % 10 == 0) {
                pr.progress--;
            }
            if (now % 4 == 0) world.spawnParticle(Particle.SNOWFLAKE, v.getLocation().add(0, 1, 0), 4, 0.5, 0.8, 0.5, 0.02);
            int pct = Math.min(100, pr.progress * 100 / 60);
            if (now % 6 == 0) {
                String bar = "█".repeat(pct / 10) + "<dark_gray>" + "█".repeat(10 - pct / 10) + "</dark_gray>";
                String msg = "<aqua>❄ Descongelando</aqua> <white>" + bar + "</white> <gray>" + pct + "%</gray>";
                v.sendActionBar(Text.mm(msg));
                for (Player p : alivePlayers()) {
                    if (!p.equals(v) && p.getLocation().distanceSquared(v.getLocation()) <= 8 * 8) p.sendActionBar(Text.mm(msg));
                }
            }
            if (pr.progress >= 60) {
                releasePrison(pr, v, true);
                it.remove();
            } else if (now - pr.start > 200) {
                hit(v, 8);
                releasePrison(pr, v, false);
                it.remove();
            }
        }
    }

    private void releasePrison(Prison pr, Player v, boolean thawed) {
        if (pr.cube != null && pr.cube.isValid()) {
            iceBurst(pr.cube.getLocation().add(0, 1, 0), 50, 0.8);
            pr.cube.remove();
        }
        if (v != null && v.isOnline()) {
            v.removePotionEffect(PotionEffectType.SLOWNESS);
            v.setFreezeTicks(0);
            world.playSound(v.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.4f, 0.9f);
            if (thawed) {
                v.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60, 1, false, false, false));
                inst.broadcast("<gold>🔥 " + v.getName() + " ha sido descongelado.</gold>");
            }
        }
    }

    private void spears() {
        Player t = randomPlayer();
        if (t == null) return;
        sayNow(randomOf("El hielo os atravesará.", "Lanzas de invierno, alzaos.", "Mirad cómo crece el suelo."));
        arms(-95, 15);
        wings(20);
        busy(90);
        root(30);
        int lines = phase == Phase.TWO ? 1 : 3;
        Location from = ent.getLocation().clone();
        Vector base = t.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (base.lengthSquared() < 0.01) base = new Vector(1, 0, 0);
        base.normalize();
        for (int l = 0; l < lines; l++) {
            Vector dir = base.clone().rotateAroundY(Math.toRadians((l - (lines - 1) / 2.0) * 30));
            for (int i = 1; i <= 8; i++) {
                Location s = from.clone().add(dir.clone().multiply(2.6 * i));
                s.setY(groundY());
                if (Math.hypot(s.getX() - center.getX(), s.getZ() - center.getZ()) > 19) continue;
                spearAt(s, 20 + i * 3);
            }
        }
        after(50, () -> {
            if (calm()) relax();
        });
    }

    private void spearAt(Location s, int delay) {
        int steps = Math.max(1, delay / 4);
        seq(4, steps, i -> {
            ringDust(s, 1.5, 12, Color.fromRGB(150, 225, 255), 1.0f);
            if (i == steps - 1) world.spawnParticle(Particle.BLOCK, s.getX(), s.getY() + 0.1, s.getZ(), 12, 0.6, 0.1, 0.6, 0.1,
                    Material.BLUE_ICE.createBlockData());
        }, null);
        after(delay, () -> {
            BlockDisplay d = dripstone(s, false, 1.5f, 0.2f, 1.5f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!d.isValid()) return;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(3);
                d.setTransformationMatrix(spike(1.5f, 3.4f, 1.5f, 0f));
            }, 1L);
            world.playSound(s, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.5f, 0.6f);
            iceBurst(s.clone().add(0, 0.5, 0), 20, 0.7);
            for (Player p : alivePlayers()) {
                Location pl = p.getLocation();
                if (Math.hypot(pl.getX() - s.getX(), pl.getZ() - s.getZ()) <= 1.7 && Math.abs(pl.getY() - s.getY()) < 3) {
                    hit(p, 10);
                    chill(p, 60, 40, 1);
                    p.setVelocity(new Vector(0, 0.75, 0));
                }
            }
            after(28, () -> {
                iceBurst(s.clone().add(0, 1.5, 0), 20, 0.7);
                drop(d);
            });
        });
    }

    private void comet() {
        Player t = randomPlayer();
        if (t == null) return;
        sayNow(randomOf("¡Que caiga el cielo!", "Mi cometa no falla.", "Contemplad el fin."));
        arms(-140, 30);
        wings(40);
        lift(1.8f);
        busy(100);
        root(60);
        Location ground = t.getLocation().clone();
        ground.setY(groundY());
        double ceil = floorY + room.height;
        float size = 3.4f;
        Location top = new Location(world, ground.getX(), ceil - size, ground.getZ());
        BlockDisplay d = world.spawn(top, BlockDisplay.class, bd -> {
            bd.setBlock(Material.PACKED_ICE.createBlockData());
            bd.setPersistent(false);
            bd.setBrightness(new Display.Brightness(15, 15));
            bd.setViewRange(3f);
            bd.setInterpolationDelay(0);
            bd.setInterpolationDuration(1);
            bd.setTransformationMatrix(new Matrix4f().translate(-size / 2, 0, -size / 2).scale(size));
        });
        temp.add(d);
        float fall = (float) (top.getY() - groundY());
        seq(3, 16, i -> {
            double k = 1.0 - i / 16.0;
            ringDust(ground, 5.5, 30, Color.fromRGB(255, 110, 110), 1.2f);
            ringDust(ground, Math.max(0.5, 5.5 * k), 18, Color.WHITE, 1.0f);
            if (i % 3 == 0) world.playSound(ground, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 0.5f + i * 0.05f);
        }, null);
        after(38, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(10);
            d.setTransformationMatrix(new Matrix4f().translate(-size / 2, -fall, -size / 2).scale(size));
        });
        after(50, () -> {
            drop(d);
            world.playSound(ground, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.6f);
            world.playSound(ground, Sound.BLOCK_GLASS_BREAK, 2f, 0.4f);
            world.spawnParticle(Particle.EXPLOSION_EMITTER, ground.getX(), ground.getY() + 0.6, ground.getZ(), 2, 0.5, 0.2, 0.5, 0);
            iceBurst(ground.clone().add(0, 1, 0), 140, 3.2);
            for (Player p : alivePlayers()) {
                Location pl = p.getLocation();
                double dd = Math.hypot(pl.getX() - ground.getX(), pl.getZ() - ground.getZ());
                if (dd <= 5.5 && onGround(p)) {
                    hit(p, 17);
                    chill(p, 140, 80, 2);
                    Vector kb = new Vector(pl.getX() - ground.getX(), 0, pl.getZ() - ground.getZ());
                    if (kb.lengthSquared() > 0.01) p.setVelocity(kb.normalize().multiply(1.1).setY(0.6));
                }
            }
            expandRing(ground.clone(), 1.0);
        });
        after(80, () -> {
            if (calm()) {
                relax();
                lift(0f);
            }
        });
    }

    private static String randomOf(String... s) {
        return s[ThreadLocalRandom.current().nextInt(s.length)];
    }

    // ------------------------------------------------------------------ transición I → II: escudo de cristal

    private boolean calm() {
        return !transitioning && !dying && !shielded && !absolute;
    }

    private void clearThreats() {
        for (Bolt b : bolts) if (b.d != null && b.d.isValid()) b.d.remove();
        bolts.clear();
        for (Prison p : new ArrayList<>(prisons)) releasePrison(p, Bukkit.getPlayer(p.who), false);
        prisons.clear();
        for (BlockDisplay d : temp) if (d.isValid()) d.remove();
        temp.clear();
    }

    private void titleTo(Player p, String title, String sub) {
        p.showTitle(net.kyori.adventure.title.Title.title(Text.mm(title), Text.mm(sub),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(1800),
                        java.time.Duration.ofMillis(400))));
    }

    private long nextShieldBolt;

    private void beginShield() {
        transitioning = true;
        invulnerable = true;
        clearThreats();
        busyUntil = Long.MAX_VALUE / 2;
        sayNow("¡Basta de juegos! Cristales míos... ¡despertad!");
        inst.titleAll("<light_purple><bold>ESCUDO DE CRISTAL</bold></light_purple>", "<gray>Destruye los cuatro cristales</gray>");
        Location old = ent.getLocation();
        iceBurst(old.clone().add(0, 2, 0), 70, 1.5);
        ent.teleport(center);
        phase = Phase.TWO;
        applyStats();
        model();
        arms(-135, 40);
        wings(42);
        lift(3.4f);
        if (vis != null) {
            vis.pulse(id(), 1.15f);
            vis.shake(id(), 3f);
            vis.tempo(id(), 2.5f);
        }
        sound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.5f);
        sound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.6f);
        world.strikeLightningEffect(center);
        seq(3, 16, i -> {
            ring(center, 1.5 + i * 1.1, 40, Particle.SNOWFLAKE);
            ring(center, 1.0 + i * 0.9, 30, Particle.END_ROD);
        }, null);
        after(50, () -> {
            double hp = cfg.d("ysmera.crystal-health") * (1 + 0.45 * (inst.groupSize() - 1)) * inst.heat().hpMul();
            Location beam = new Location(world, center.getBlockX(), floorY + 4, center.getBlockZ());
            for (int[] s : crystalSpots(room)) {
                Crystal c = new Crystal();
                c.at = new Location(world, s[0] + 0.5, floorY + 2, s[1] + 0.5);
                c.hp = hp;
                c.max = hp;
                c.nextBolt = now + 100 + ThreadLocalRandom.current().nextInt(80);
                c.ent = world.spawn(c.at, EnderCrystal.class, e -> {
                    e.setShowingBottom(false);
                    e.setPersistent(false);
                    e.setBeamTarget(beam);
                });
                inst.manager().indexMob(c.ent.getUniqueId(), inst);
                crystals.add(c);
                iceBurst(c.at.clone().add(0, 1, 0), 40, 0.8);
                world.playSound(c.at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.5f, 0.5f);
            }
            if (vis != null) {
                vis.shake(id(), 0f);
                vis.tempo(id(), 1.4f);
                vis.pulse(id(), 1f);
            }
            shielded = true;
            transitioning = false;
            busyUntil = 0;
            shieldStart = now;
            nextAdds = now + 120;
            nextShieldBolt = now + 100;
        });
    }

    private void shieldTick() {
        if (now >= nextAdds) {
            nextAdds = now + 280;
            spawnAdds(Math.min(4, 2 + inst.groupSize() / 3));
        }
        if (now >= nextShieldBolt) {
            nextShieldBolt = now + 150;
            boltVolley(3, 14);
        }
        if (now - shieldStart > 1800 && now % 60 == 0) {
            for (Player p : alivePlayers()) {
                inst.hazardHit(p, 3.0);
                chill(p, 60, 0, 0);
            }
            if (now % 120 == 0) inst.broadcast("<aqua>La escarcha crece... ¡rompe los cristales!</aqua>");
        }
    }

    private void spawnAdds(int n) {
        int cap = 6 + inst.groupSize();
        n = Math.min(n, Math.max(0, cap - inst.mobCount()));
        for (int i = 0; i < n; i++) {
            Location l = inst.randomSpot(room);
            if (l == null) continue;
            iceBurst(l.clone().add(0, 1, 0), 40, 0.8);
            world.playSound(l, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.6f);
            inst.spawnObjectiveMob(l, false);
        }
    }

    private void crystalsTick() {
        if (crystals.isEmpty()) return;
        Location beam = new Location(world, center.getBlockX(), floorY + 4, center.getBlockZ());
        for (Crystal c : crystals) {
            if (c.dead || c.ent == null || !c.ent.isValid()) continue;
            c.ent.setBeamTarget(beam);
            if (now % 4 == 0) {
                world.spawnParticle(Particle.END_ROD, c.at.clone().add(0, 1, 0), 2, 0.5, 0.8, 0.5, 0.02);
                Location up = c.at.clone().add(0, 1.5, 0);
                line(up, ent.getLocation().add(0, 3.5, 0), Color.fromRGB(170, 140, 255), 14);
            }
            if (shielded && now >= c.nextBolt) {
                c.nextBolt = now + 150 + ThreadLocalRandom.current().nextInt(60);
                crystalBolt(c);
            }
        }
    }

    private void crystalBolt(Crystal c) {
        Player t = randomPlayer();
        if (t == null) return;
        Location spot = t.getLocation().clone();
        spot.setY(groundY());
        Location src = c.at.clone().add(0, 1.2, 0);
        world.playSound(src, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, 1.4f);
        for (int i = 0; i < 5; i++) {
            after(i * 4, () -> {
                ringDust(spot, 2.0, 16, Color.fromRGB(190, 150, 255), 1.1f);
                line(src, spot.clone().add(0, 0.2, 0), Color.fromRGB(180, 140, 255), 20);
            });
        }
        after(22, () -> {
            line(src, spot.clone().add(0, 0.3, 0), Color.WHITE, 30);
            iceBurst(spot.clone().add(0, 0.6, 0), 40, 1.0);
            world.playSound(spot, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.9f, 1.8f);
            for (Player p : alivePlayers()) {
                Location pl = p.getLocation();
                if (Math.hypot(pl.getX() - spot.getX(), pl.getZ() - spot.getZ()) <= 2.1 && Math.abs(pl.getY() - spot.getY()) < 3) {
                    hit(p, 8);
                    chill(p, 80, 50, 1);
                }
            }
        });
    }

    private void destroyCrystal(Crystal c) {
        if (c.dead) return;
        c.dead = true;
        Location at = c.at.clone().add(0, 1, 0);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        iceBurst(at, 80, 1.2);
        world.playSound(at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 2f, 0.5f);
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.4f);
        removeCrystal(c);
        for (Player p : alivePlayers()) {
            Location pl = p.getLocation();
            if (pl.distanceSquared(at) <= 4.5 * 4.5) {
                hit(p, 6);
                Vector kb = pl.toVector().subtract(at.toVector()).setY(0);
                if (kb.lengthSquared() > 0.01) p.setVelocity(kb.normalize().multiply(0.9).setY(0.4));
            }
        }
        int left = 0;
        for (Crystal o : crystals) if (!o.dead) left++;
        if (left > 0) {
            inst.broadcast("<light_purple>Cristal destruido.</light_purple> <gray>Quedan <white>" + left + "</white>.</gray>");
            if (vis != null) {
                vis.shake(id(), 4f);
                after(8, () -> {
                    if (vis != null) vis.shake(id(), 0f);
                });
            }
            sayNow(randomOf("¡Mis cristales!", "¡Insolentes!", "No... ¡no podéis!"));
        } else {
            shieldBroken();
        }
    }

    private void shieldBroken() {
        shielded = false;
        transitioning = true;
        invulnerable = true;
        clearThreats();
        sayNow("¡NO! ¡Mi poder...!");
        Location l = ent.getLocation();
        world.strikeLightningEffect(center);
        sound(l, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.5f);
        sound(l, Sound.BLOCK_GLASS_BREAK, 2f, 0.4f);
        iceBurst(l.clone().add(0, 3, 0), 120, 2.2);
        lift(0f);
        arms(28, 8);
        wings(-24);
        if (vis != null) {
            vis.pose(id(), "head", 38, 0, 0);
            vis.shake(id(), 3.5f);
            vis.tempo(id(), 0.4f);
        }
        after(30, () -> {
            transitioning = false;
            invulnerable = false;
            vulnUntil = now + 220;
            root(220);
            busyUntil = now + 240;
            if (vis != null) vis.shake(id(), 0f);
            inst.titleAll("<red><bold>¡AL SUELO!</bold></red>", "<gray>Ysmera está aturdida: <white>daño x1.6</white></gray>");
            sound(ent.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.8f);
        });
        after(250, () -> {
            if (dying || transitioning || absolute) return;
            relax();
            coldOn = true;
            nextSurge = now + 300;
            sayNow("Esto... apenas empieza. Que el frío os devore.");
            sound(ent.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.7f);
            ring(ent.getLocation(), 4, 50, Particle.SNOWFLAKE);
            inst.titleAll("<light_purple><bold>VENTISCA</bold></light_purple>", "<gray>Acércate a las <gold>hogueras</gold> para no congelarte</gray>");
            for (Ab a : Ab.values()) cooldowns.put(a, now + 60 + ThreadLocalRandom.current().nextInt(60));
        });
    }

    // ------------------------------------------------------------------ frío y ventiscas

    private void coldTick() {
        if (!coldOn && !absolute) {
            if (!cold.isEmpty()) {
                for (UUID u : cold.keySet()) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null && p.isOnline() && !isPrisoner(p)) p.setFreezeTicks(0);
                }
                cold.clear();
            }
            return;
        }
        boolean surge = now < surgeUntil;
        if (coldOn && !absolute && now >= nextSurge && !surge && !transitioning && !dying) {
            surgeUntil = now + 160;
            nextSurge = now + (phase == Phase.TWO ? 560 : 400);
            surgeDir = ThreadLocalRandom.current().nextInt(4);
            surge = true;
            inst.titleAll("<white><bold>¡VENTISCA!</bold></white>", "<gray>Refúgiate junto a las <gold>hogueras</gold></gray>");
            sound(center, Sound.ENTITY_PLAYER_HURT_FREEZE, 2f, 0.5f);
            sound(center, Sound.AMBIENT_CAVE, 2f, 0.5f);
        }
        Vector wind = new Vector(surgeDir == 0 ? 1 : surgeDir == 1 ? -1 : 0, 0, surgeDir == 2 ? 1 : surgeDir == 3 ? -1 : 0);
        if (surge || absolute) {
            for (int i = 0; i < 12; i++) {
                double x = center.getX() + ThreadLocalRandom.current().nextDouble(-19, 19);
                double z = center.getZ() + ThreadLocalRandom.current().nextDouble(-19, 19);
                double y = groundY() + ThreadLocalRandom.current().nextDouble(0.3, 4.5);
                world.spawnParticle(Particle.SNOWFLAKE, x, y, z, 0, wind.getX(), 0.02, wind.getZ(), 0.9);
            }
        }
        List<Player> ps = alivePlayers();
        for (Player p : ps) {
            if (isPrisoner(p)) continue;
            UUID u = p.getUniqueId();
            double c = cold.getOrDefault(u, 0.0);
            boolean w = isWarm(p.getLocation());
            double perSec = absolute ? 11.0 : (phase == Phase.TWO ? 1.6 : 2.6) * (surge ? 3.4 : 1.0);
            if (w) c = Math.max(0, c - 3.0);
            else c = Math.min(100, c + perSec * 0.1);
            cold.put(u, c);
            p.setFreezeTicks((int) (c / 100.0 * p.getMaxFreezeTicks()));
            if (surge && !absolute && !w) p.setVelocity(p.getVelocity().add(wind.clone().multiply(0.045)));
            if (c >= 100 && now - coldHurt.getOrDefault(u, 0L) >= 20) {
                coldHurt.put(u, now);
                inst.hazardHit(p, 3.0 * inst.heat().dmgMul());
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 0, false, false, false));
            }
            if (now % 10 == 0) {
                int n = (int) Math.round(c / 10.0);
                String col = c < 50 ? "<aqua>" : c < 80 ? "<yellow>" : "<red>";
                p.sendActionBar(Text.mm((w ? "<gold>🔥 A salvo</gold> " : "<aqua>❄ Frío</aqua> ") + col + "█".repeat(n)
                        + "</" + col.substring(1) + "<dark_gray>" + "█".repeat(10 - n) + "</dark_gray>"));
            }
        }
    }

    private boolean isPrisoner(Player p) {
        for (Prison pr : prisons) if (pr.who.equals(p.getUniqueId())) return true;
        return false;
    }

    // ------------------------------------------------------------------ transición II → III: Cero Absoluto

    private void beginAbsolute() {
        transitioning = true;
        invulnerable = true;
        coldOn = false;
        absolute = true;
        clearThreats();
        absoluteTotal = 260;
        absoluteEnd = now + absoluteTotal;
        busyUntil = Long.MAX_VALUE / 2;
        sayNow("Contemplad el final de todo calor... ¡CERO ABSOLUTO!");
        inst.titleAll("<white><bold>CERO ABSOLUTO</bold></white>", "<gray>¡Refugiaos en las <gold>hogueras</gold> antes de que todo se congele!</gray>");
        Location old = ent.getLocation();
        iceBurst(old.clone().add(0, 2, 0), 60, 1.4);
        ent.teleport(center);
        arms(-140, 45);
        wings(46);
        lift(3.6f);
        if (vis != null) {
            vis.pulse(id(), 1.2f);
            vis.shake(id(), 2f);
            vis.tempo(id(), 3.5f);
        }
        sound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 2.5f, 0.4f);
        sound(center, Sound.ENTITY_WARDEN_SONIC_CHARGE, 2.5f, 0.5f);
        world.strikeLightningEffect(center);
        transitioning = false;     // el combate continúa (los jugadores pueden moverse), pero ella es inmune
        seq(2, 60, i -> freezeRing(i * 0.33, (i + 1) * 0.33 + 0.4), null);
        seq(2, 40, i -> {
            ring(center, 2 + i * 0.5, 40, Particle.SNOWFLAKE);
            world.spawnParticle(Particle.END_ROD, center.getX(), center.getY() + 3.5, center.getZ(), 5, 1.2, 1.2, 1.2, 0.05);
        }, null);
        nextShieldBolt = now + 80;
    }

    /** Congela el suelo de la arena en una corona (BLUE_ICE: resbaladizo y con escarcha). */
    private void freezeRing(double r0, double r1) {
        int cx = room.cx(), cz = room.cz;
        int R = (int) Math.ceil(r1) + 1;
        for (int x = cx - R; x <= cx + R; x++) {
            for (int z = cz - R; z <= cz + R; z++) {
                if (x < room.x0 || x > room.x1 || z < room.z0 || z > room.z1) continue;
                double d = Math.hypot(x - cx, z - cz);
                if (d < r0 || d >= r1) continue;
                org.bukkit.block.Block b = world.getBlockAt(x, floorY, z);
                Material m = b.getType();
                if (!m.isSolid() || m == Material.BLUE_ICE || m == Material.BARRIER || m == Material.BEDROCK) continue;
                inst.setBlock(x, floorY, z, Material.BLUE_ICE);
                if (((x + z) & 3) == 0) {
                    world.spawnParticle(Particle.SNOWFLAKE, x + 0.5, floorY + 1.1, z + 0.5, 2, 0.3, 0.05, 0.3, 0.02);
                }
            }
        }
        if (nextShieldBolt > 0 && absolute && now >= nextShieldBolt) {
            nextShieldBolt = now + 90;
            boltVolley(1 + inst.groupSize() / 2, 10);
        }
    }

    private void finishAbsolute() {
        absolute = false;
        transitioning = true;
        invulnerable = true;
        clearThreats();
        Location c = center.clone();
        world.strikeLightningEffect(c);
        sound(c, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.5f);
        sound(c, Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 0.7f);
        sound(c, Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, 0.5f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, c.getX(), c.getY() + 3, c.getZ(), 4, 1.5, 1, 1.5, 0);
        iceBurst(c.clone().add(0, 3, 0), 200, 3.5);
        seq(2, 16, i -> {
            ring(c, 2 + i * 1.4, 60, Particle.SNOWFLAKE);
            ring(c, 2 + i * 1.4, 40, Particle.CLOUD);
        }, null);
        for (Player p : alivePlayers()) {
            if (isWarm(p.getLocation())) {
                p.sendMessage(Text.mm("<gold>🔥 La hoguera te ha protegido del Cero Absoluto.</gold>"));
                p.setFreezeTicks(0);
            } else {
                inst.hazardHit(p, 13.0 * inst.heat().dmgMul());
                chill(p, 200, 100, 2);
                Vector kb = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                if (kb.lengthSquared() > 0.01) p.setVelocity(kb.normalize().multiply(1.2).setY(0.5));
            }
        }
        cold.clear();
        phase = Phase.THREE;
        applyStats();
        model();
        lift(0f);
        arms(28, 8);
        wings(-26);
        if (vis != null) {
            vis.pose(id(), "head", 40, 0, 0);
            vis.shake(id(), 4f);
            vis.tempo(id(), 0.4f);
        }
        inst.titleAll("<white><bold>FASE III</bold></white>", "<gray>El suelo está helado: <white>cuidado al moverte</white> · daño x1.6 a Ysmera</gray>");
        vulnUntil = now + 260;
        root(260);
        busyUntil = now + 280;
        after(24, () -> {
            transitioning = false;
            invulnerable = false;
            if (vis != null) vis.shake(id(), 0f);
        });
        after(262, () -> {
            if (dying || transitioning || absolute) return;
            relax();
            coldOn = true;
            nextSurge = now + 240;
            sayNow("Si he de caer... ¡caeréis conmigo!");
            sound(ent.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.6f);
            for (Ab a : Ab.values()) cooldowns.put(a, now + 50 + ThreadLocalRandom.current().nextInt(50));
        });
    }

    // ------------------------------------------------------------------ muerte cinemática

    private void beginDeath() {
        dying = true;
        invulnerable = true;
        transitioning = true;
        coldOn = false;
        absolute = false;
        shielded = false;
        clearThreats();
        inst.purgeWaveMobs();
        ent.setTarget(null);
        sayNow("El invierno... jamás... termina...");
        inst.titleAll("<white><bold>YSMERA CAE</bold></white>", "<gray>El hielo se resquebraja...</gray>");
        cine.play(inst.onlineMembers(), () -> ent.getLocation().add(0, 3.0, 0), 172,
                Cinematic.orbit(12, 5.5, 2.4, 4.2, -Math.PI * 0.6), 112, 168, null);
        lift(1.8f);
        arms(35, 12);
        wings(-30);
        if (vis != null) {
            vis.pose(id(), "head", 32, 0, 0);
            vis.tempo(id(), 0.5f);
            vis.shake(id(), 1.5f);
        }
        sound(ent.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 2f, 0.6f);
        seq(4, 40, i -> {
            Location l = ent.getLocation();
            world.spawnParticle(Particle.SNOWFLAKE, l.getX(), l.getY() + 3, l.getZ(), 6, 1.2, 1.8, 1.2, 0.05);
            if (i % 3 == 0) world.spawnParticle(Particle.BLOCK, l.getX(), l.getY() + 2.5, l.getZ(), 10, 0.8, 1.6, 0.8, 0.05,
                    Material.BLUE_ICE.createBlockData());
        }, null);
        after(30, () -> {
            sound(ent.getLocation(), Sound.BLOCK_GLASS_BREAK, 2f, 0.5f);
            if (vis != null) vis.shake(id(), 3f);
        });
        after(60, () -> {
            sayNow("Mi reino... se derrite...");
            if (vis != null) vis.pulse(id(), 1.06f);
            sound(ent.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.5f);
        });
        after(90, () -> {
            if (vis != null) {
                vis.tempo(id(), 0.02f);
                vis.shake(id(), 0.8f);
            }
            sound(ent.getLocation(), Sound.BLOCK_POWDER_SNOW_BREAK, 2f, 0.5f);
            iceBurst(ent.getLocation().add(0, 3, 0), 90, 1.6);
        });
        after(120, () -> {
            if (vis != null) vis.shake(id(), 5f);
            sound(ent.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 2f, 0.5f);
        });
        seq(3, 18, i -> {
            Location l = ent.getLocation().add(0, 2.5, 0);
            sound(l, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.5f + i * 0.05f);
            world.spawnParticle(Particle.END_ROD, l, 8, 0.8, 1.6, 0.8, 0.1);
        }, null);
        after(160, () -> {
            if (vis != null) {
                vis.pulse(id(), 1.3f);
                vis.shake(id(), 8f);
            }
            sound(ent.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 2.5f, 0.5f);
        });
        after(170, () -> {
            Location l = ent.getLocation().add(0, 3, 0);
            if (vis != null) vis.drop(id());
            world.spawnParticle(Particle.EXPLOSION_EMITTER, l, 3, 1, 1, 1, 0);
            iceBurst(l, 260, 3.2);
            sound(l, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.6f);
            sound(l, Sound.BLOCK_GLASS_BREAK, 3f, 0.3f);
            sound(l, Sound.ENTITY_ENDER_DRAGON_DEATH, 2f, 1.4f);
            seq(2, 14, i -> {
                ring(l, 1 + i * 1.6, 70, Particle.SNOWFLAKE);
                ring(l, 1 + i * 1.6, 40, Particle.END_ROD);
            }, null);
        });
        after(176, () -> {
            dying = false;
            invulnerable = false;
            transitioning = false;
            ent.setHealth(0.0);
        });
    }
}
