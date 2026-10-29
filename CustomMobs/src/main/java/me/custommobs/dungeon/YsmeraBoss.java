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
        POOLS(1, 300), SPEARS(2, 260), VORTEX(2, 400), BEAM(2, 360), FRACTURE(2, 380), COMET(3, 330);

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

    private static final class Pool {
        Location at;
        long start, end;
        double r;
        BlockDisplay disc;
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
    private final List<Pool> pools = new ArrayList<>();
    private final boolean[] hearthLit = {true, true, true, true};
    private final int[] relight = new int[4];
    private long nextFreeze;
    private long fightStart;
    private long enrageNext;
    private boolean enraged;
    private int musicStep;

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
        attr(ent, Attribute.ATTACK_DAMAGE, phase.attack * inst.heat().dmgMul() * cfg.d("ysmera.damage-mul"));
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
        for (Pool pl : pools) if (pl.disc != null && pl.disc.isValid()) pl.disc.remove();
        pools.clear();
        for (int i = 0; i < hearthLit.length; i++) setHearth(i, true);
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
        return phase.dmg * inst.heat().dmgMul() * cfg.d("ysmera.damage-mul") * (enraged ? 1.4 : 1.0);
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
        for (int i = 0; i < warm.size(); i++) {
            if (!hearthLit[i]) continue;
            Location w = warm.get(i);
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
        poolsTick();
        hearthTick();
        coldTick();
        music();
        enrageTick();
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
        mul *= cfg.d("ysmera.damage-taken-mul");
        if (now < vulnUntil) mul *= 1.35;
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
        sound(c, Sound.AMBIENT_CAVE, 2f, 0.5f);
        sound(c, Sound.ENTITY_PLAYER_HURT_FREEZE, 2f, 0.5f);
        if (vis != null) {
            vis.tempo(id(), 0.5f);
            vis.pose(id(), "head", 28, 0, 0);
            wings(-12);
            arms(25, 10);
        }
        lift(0f);
        // plano 1: grúa sobre la sala (0-75) · plano 2: la reina se eleva (75-140) · plano 3: primer plano del rostro (140-212)
        cine.play(ps, t -> {
            if (t < 75) return center.clone().add(0, 5, 0);
            if (t < 140) return ent.getLocation().add(0, 3.6 + Math.min(1.0, (t - 75) / 40.0), 0);
            return ent.getLocation().add(0, 5.5, 0);
        }, 212, (t, total, f, start) -> {
            double a0 = Math.atan2(start.getZ() - center.getZ(), start.getX() - center.getX());
            double a, r, y;
            if (t < 75) {
                double u = Cinematic.ease(t / 75.0);
                a = a0 + u * 0.6;
                r = Cinematic.lerp(16, 12, u);
                y = Cinematic.lerp(10, 6.5, u);
            } else if (t < 140) {
                double u = Cinematic.ease((t - 75) / 65.0);
                a = a0 + 0.6 + u * 0.7;
                r = Cinematic.lerp(11, 7.5, u);
                y = Cinematic.lerp(1.4, 4.2, u);
            } else {
                double u = Cinematic.ease((t - 140) / 72.0);
                a = a0 + 1.3 - u * 0.3;
                r = Cinematic.lerp(6.6, 4.6, u);
                y = Cinematic.lerp(5.4, 5.9, u);
            }
            return new Location(world, center.getX() + Math.cos(a) * r, center.getY() + y, center.getZ() + Math.sin(a) * r);
        }, 150, 205, null);
        // la sala despierta: columnas de luz en los altares y la nieve empieza a girar
        seq(4, 18, i -> {
            for (int[] sp : crystalSpots(room)) {
                Location at = new Location(world, sp[0] + 0.5, floorY + 1, sp[1] + 0.5);
                world.spawnParticle(Particle.END_ROD, at.getX(), at.getY() + i * 0.45, at.getZ(), 2, 0.1, 0.05, 0.1, 0.01);
            }
            for (Location w : warm) ringDust(w, WARM_RADIUS, 24, Color.fromRGB(255, 170, 70), 0.9f);
            world.spawnParticle(Particle.SNOWFLAKE, center.getX(), center.getY() + 4, center.getZ(), 30, 12, 3, 12, 0.02);
        }, null);
        after(10, () -> sayNow("Silencio..."));
        after(30, () -> {
            sound(center, Sound.BLOCK_BEACON_ACTIVATE, 2f, 0.5f);
            wings(10);
            if (vis != null) vis.tempo(id(), 1f);
        });
        after(44, () -> lift(2.4f));
        after(75, () -> {
            inst.titleAll("<gradient:#9fe0f5:#ffffff><bold>YSMERA</bold></gradient>", "<gray>La Reina de Escarcha</gray>");
            sound(ent.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.6f, 1.8f);
            sayNow("Otro grupo de valientes... otra tumba de hielo.");
        });
        after(95, () -> {
            arms(-130, 45);
            wings(40);
            if (vis != null) {
                vis.pulse(id(), 1.12f);
                vis.tempo(id(), 2f);
                vis.shake(id(), 2f);
            }
            sound(ent.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.5f);
            sound(ent.getLocation(), Sound.BLOCK_GLASS_BREAK, 2f, 0.6f);
            ring(ent.getLocation(), 3, 40, Particle.SNOWFLAKE);
            iceBurst(ent.getLocation().add(0, 2, 0), 60, 2.5);
        });
        after(112, () -> {
            for (int[] sp : crystalSpots(room)) {
                Location at = new Location(world, sp[0] + 0.5, floorY + 1, sp[1] + 0.5);
                world.strikeLightningEffect(at);
                iceBurst(at.clone().add(0, 1, 0), 40, 0.8);
            }
            sayNow("Cada invierno alguien viene a desafiarme. Ninguno ha visto la primavera.");
        });
        seq(4, 22, i -> {
            Location b = ent.getLocation();
            ring(b, 1.0 + i * 0.7, 36, Particle.SNOWFLAKE);
            if (i % 2 == 0) ring(b, 0.5 + i * 0.6, 24, Particle.END_ROD);
        }, null);
        after(140, () -> {
            sound(ent.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 2f, 0.5f);
            Location eyes = ent.getLocation().add(0, 5.9, 0);
            world.spawnParticle(Particle.END_ROD, eyes, 40, 0.4, 0.3, 0.4, 0.15);
            if (vis != null) vis.shake(id(), 0.8f);
        });
        after(165, () -> {
            if (vis != null) {
                vis.pulse(id(), 1.22f);
                vis.shake(id(), 6f);
            }
            sound(ent.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, 0.4f);
            sound(ent.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 3f, 0.6f);
            world.strikeLightningEffect(ent.getLocation());
            seq(2, 12, i -> {
                ring(ent.getLocation(), 2 + i * 1.7, 70, Particle.SNOWFLAKE);
                ring(ent.getLocation(), 2 + i * 1.7, 40, Particle.CLOUD);
            }, null);
        });
        after(188, () -> sayNow("Arrodillaos."));
        after(205, () -> {
            relax();
            lift(0f);
        });
        after(214, () -> {
            intro = false;
            invulnerable = false;
            fightStart = now;
            enrageNext = now + cfg.i("ysmera.enrage-seconds") * 20L;
            busy(40);
            for (Ab a : Ab.values()) cooldowns.put(a, now + 40 + ThreadLocalRandom.current().nextInt(50));
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
            if (a == Ab.SUMMON && inst.mobCount() >= 5 + group) continue;
            if (a == Ab.NOVA && nearestPlayer(ent.getLocation()) != null
                    && nearestPlayer(ent.getLocation()).getLocation().distanceSquared(ent.getLocation()) > 20 * 20) continue;
            pool.add(a);
        }
        if (pool.isEmpty()) return;
        Ab a = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        if (a == last && pool.size() > 1) a = pool.get((pool.indexOf(a) + 1) % pool.size());
        last = a;
        cooldowns.put(a, now + (long) (a.cd * phase.cd * cfg.d("ysmera.cooldown-mul") * (frenzy || enraged ? 0.7 : 1.0)));
        busy(16);
        switch (a) {
            case ICICLES -> icicles();
            case NOVA -> nova();
            case BOLTS -> boltVolley(phaseNum() == 1 ? 5 : phaseNum() == 2 ? 7 : 9, phaseNum() == 1 ? 12 : 9);
            case SUMMON -> summon();
            case BLINK -> blink();
            case GRASP -> grasp();
            case SPEARS -> spears();
            case COMET -> comet();
            case POOLS -> pools();
            case VORTEX -> vortex();
            case BEAM -> beam();
            case FRACTURE -> fracture();
        }
        // combo: a veces encadena un segundo ataque ligero mientras el primero aún se resuelve
        double chance = phase == Phase.ONE ? 0.18 : phase == Phase.TWO ? 0.42 : 0.68;
        if (a != Ab.BLINK && a != Ab.VORTEX && a != Ab.BEAM && a != Ab.NOVA && ThreadLocalRandom.current().nextDouble() < chance) {
            after(22, this::comboNext);
        }
    }

    private void comboNext() {
        if (!alive() || !calm() || intro) return;
        Ab[] opts = phase == Phase.ONE ? new Ab[]{Ab.BOLTS, Ab.ICICLES}
                : new Ab[]{Ab.BOLTS, Ab.ICICLES, Ab.POOLS, Ab.SPEARS};
        Ab b = opts[ThreadLocalRandom.current().nextInt(opts.length)];
        if (b == last) return;
        switch (b) {
            case BOLTS -> boltVolley(phaseNum() == 1 ? 3 : 5, 14);
            case ICICLES -> icicles();
            case POOLS -> pools();
            case SPEARS -> spears();
            default -> { }
        }
    }

    // ------------------------------------------------------------------ habilidades

    private void icicles() {
        sayNow(randomOf("¡Que llueva escarcha!", "Sentid cómo se agrieta el cielo.", "Cada copo es una condena."));
        arms(-100, 25);
        wings(20);
        busy(56);
        int delay = phase == Phase.ONE ? 36 : phase == Phase.TWO ? 30 : 24;
        List<Location> spots = new ArrayList<>();
        for (Player p : alivePlayers()) spots.add(p.getLocation().clone());
        for (int i = 0; i < 2 + phaseNum() * 2; i++) {
            Location l = inst.randomSpot(room);
            if (l != null) spots.add(l);
        }
        for (int i = 0; i < Math.min(13, spots.size()); i++) icicleAt(spots.get(i), delay);
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
        int rings = phaseNum() + 1;
        int total = 36 + rings * 16;
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
        after(32, () -> inst.titleAll("", "<aqua><bold>¡SALTA!</bold></aqua>"));
        for (int k = 0; k < rings; k++) {
            final double speed = 1.25 + 0.12 * k;
            after(34 + k * 16, () -> expandRing(c.clone(), speed));
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
        int cap = 8 + inst.groupSize();
        int n = Math.min(3 + inst.groupSize() / 2 + (phaseNum() - 1), 8);
        n = Math.min(n, Math.max(0, cap - inst.mobCount()));
        for (int i = 0; i < n; i++) {
            Location l = inst.randomSpot(room);
            if (l == null) continue;
            final boolean elite = phaseNum() >= 2 && i == 0;
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
        int lines = phase == Phase.TWO ? 2 : 4;
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
        cine.play(inst.onlineMembers(), t -> center.clone().add(0, 5.6, 0), 96,
                Cinematic.orbit(12, 7, 1.5, 5.5, Math.PI * 0.55), 0, 46, null);
        seq(3, 20, i -> {
            ring(center, 1.5 + i * 1.0, 40, Particle.SNOWFLAKE);
            ring(center, 1.0 + i * 0.85, 30, Particle.END_ROD);
        }, null);
        after(50, () -> {
            double hp = cfg.d("ysmera.crystal-health") * (1 + 0.45 * (inst.groupSize() - 1)) * inst.heat().hpMul();
            Location beam = new Location(world, center.getBlockX(), floorY + 4, center.getBlockZ());
            for (int[] sp : crystalSpots(room)) {
                Crystal c = new Crystal();
                c.at = new Location(world, sp[0] + 0.5, floorY + 2, sp[1] + 0.5);
                c.hp = hp;
                c.max = hp;
                c.nextBolt = now + 150 + ThreadLocalRandom.current().nextInt(80);
                c.ent = world.spawn(c.at, EnderCrystal.class, e -> {
                    e.setShowingBottom(false);
                    e.setPersistent(false);
                    e.setBeamTarget(beam);
                });
                inst.manager().indexMob(c.ent.getUniqueId(), inst);
                crystals.add(c);
                world.strikeLightningEffect(c.at);
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
            shieldStart = now + 46;
            nextAdds = now + 150;
            nextShieldBolt = now + 140;
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
        int cap = 8 + inst.groupSize();
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
        cine.play(inst.onlineMembers(), t -> ent.getLocation().add(0, t < 20 ? 4.0 : 2.0, 0), 72,
                Cinematic.orbit(9, 4.8, 6.0, 1.8, -Math.PI * 0.4), 0, 30, null);
        after(76, () -> {
            transitioning = false;
            invulnerable = false;
            vulnUntil = now + 170;
            root(170);
            busyUntil = now + 190;
            if (vis != null) vis.shake(id(), 0f);
            inst.titleAll("<red><bold>¡AL SUELO!</bold></red>", "<gray>Ysmera está aturdida: <white>más daño recibido</white></gray>");
            sound(ent.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.8f);
        });
        after(76 + 180, () -> {
            if (dying || transitioning || absolute) return;
            relax();
            coldOn = true;
            nextSurge = now + 260;
            nextFreeze = now + 900;
            sayNow("Esto... apenas empieza. Que el frío os devore.");
            sound(ent.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.7f);
            ring(ent.getLocation(), 4, 50, Particle.SNOWFLAKE);
            inst.titleAll("<light_purple><bold>VENTISCA</bold></light_purple>", "<gray>Acércate a las <gold>hogueras</gold> para no congelarte</gray>");
            for (Ab a : Ab.values()) cooldowns.put(a, now + 40 + ThreadLocalRandom.current().nextInt(50));
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
        clearThreats();
        for (int i = 0; i < hearthLit.length; i++) setHearth(i, true);
        busyUntil = Long.MAX_VALUE / 2;
        sayNow("Contemplad el final de todo calor...");
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
        cine.play(inst.onlineMembers(), t -> center.clone().add(0, 5.8, 0), 84,
                Cinematic.orbit(13, 6.5, 1.0, 7.0, Math.PI * 0.75), 20, 80, null);
        seq(3, 26, i -> {
            ring(center, 2 + i * 0.7, 40, Particle.SNOWFLAKE);
            world.spawnParticle(Particle.END_ROD, center.getX(), center.getY() + 5, center.getZ(), 8, 1.4, 1.4, 1.4, 0.06);
        }, null);
        after(40, () -> inst.titleAll("<white><bold>CERO ABSOLUTO</bold></white>",
                "<gray>¡Refugiaos en las <gold>hogueras</gold> antes de que todo se congele!</gray>"));
        after(86, () -> {
            sayNow("¡CERO ABSOLUTO!");
            absolute = true;
            transitioning = false;     // el combate sigue (los jugadores se mueven), pero ella es inmune
            absoluteTotal = 260;
            absoluteEnd = now + absoluteTotal;
            seq(2, 60, i -> freezeRing(i * 0.33, (i + 1) * 0.33 + 0.4), null);
            nextShieldBolt = now + 60;
        });
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
                inst.hazardHit(p, 16.0 * inst.heat().dmgMul());
                chill(p, 200, 100, 2);
                Vector kb = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                if (kb.lengthSquared() > 0.01) p.setVelocity(kb.normalize().multiply(1.2).setY(0.5));
            }
        }
        cold.clear();
        cine.play(inst.onlineMembers(), t -> ent.getLocation().add(0, t < 30 ? 4.5 : 3.0, 0), 100,
                Cinematic.orbit(10, 5.5, 2.0, 4.2, Math.PI * 0.5), 0, 40, null);
        after(30, () -> {
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
            iceBurst(ent.getLocation().add(0, 3, 0), 120, 2.4);
            sound(ent.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, 0.4f);
            sound(ent.getLocation(), Sound.BLOCK_GLASS_BREAK, 3f, 0.3f);
            sayNow("Si he de caer... ¡caeréis conmigo!");
        });
        after(104, () -> {
            transitioning = false;
            invulnerable = false;
            vulnUntil = now + 200;
            root(200);
            busyUntil = now + 220;
            if (vis != null) vis.shake(id(), 0f);
            inst.titleAll("<white><bold>FASE III</bold></white>", "<gray>El suelo está helado: <white>cuidado al moverte</white></gray>");
        });
        after(104 + 210, () -> {
            if (dying || transitioning || absolute) return;
            relax();
            coldOn = true;
            nextSurge = now + 200;
            nextFreeze = now + 500;
            sound(ent.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 0.6f);
            for (Ab a : Ab.values()) cooldowns.put(a, now + 30 + ThreadLocalRandom.current().nextInt(40));
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
        for (int i = 0; i < hearthLit.length; i++) setHearth(i, true);
        sayNow("El invierno... jamás... termina...");
        inst.titleAll("<white><bold>YSMERA CAE</bold></white>", "<gray>El hielo se resquebraja...</gray>");
        // plano 1: la reina colgando (0-90) · plano 2: primer plano del pecho agrietado (90-170) · plano 3: se aleja mientras estalla (170-258)
        cine.play(inst.onlineMembers(), t -> ent.getLocation().add(0, t < 90 ? 3.4 : t < 170 ? 3.9 : 3.4, 0), 258, (t, total, f, start) -> {
            double a0 = Math.atan2(start.getZ() - center.getZ(), start.getX() - center.getX());
            double a, r, y;
            if (t < 90) {
                double u = Cinematic.ease(t / 90.0);
                a = a0 - u * 0.5;
                r = Cinematic.lerp(12, 8, u);
                y = Cinematic.lerp(2.0, 4.0, u);
            } else if (t < 170) {
                double u = Cinematic.ease((t - 90) / 80.0);
                a = a0 - 0.5 - u * 0.3;
                r = Cinematic.lerp(6.0, 4.0, u);
                y = Cinematic.lerp(3.8, 4.6, u);
            } else {
                double u = Cinematic.ease((t - 170) / 88.0);
                a = a0 - 0.8 - u * 0.5;
                r = Cinematic.lerp(4.6, 13.5, u);
                y = Cinematic.lerp(4.4, 8.5, u);
            }
            return new Location(world, center.getX() + Math.cos(a) * r, center.getY() + y, center.getZ() + Math.sin(a) * r);
        }, 120, 250, null);
        lift(1.8f);
        arms(35, 12);
        wings(-30);
        if (vis != null) {
            vis.pose(id(), "head", 32, 0, 0);
            vis.tempo(id(), 0.5f);
            vis.shake(id(), 1.5f);
        }
        sound(ent.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 2f, 0.6f);
        seq(4, 62, i -> {
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
        after(95, () -> {
            if (vis != null) {
                vis.tempo(id(), 0.02f);
                vis.shake(id(), 0.8f);
            }
            sound(ent.getLocation(), Sound.BLOCK_POWDER_SNOW_BREAK, 2f, 0.5f);
            iceBurst(ent.getLocation().add(0, 3, 0), 90, 1.6);
            sayNow("Tanto frío... tanta soledad...");
        });
        after(130, () -> {
            if (vis != null) vis.shake(id(), 5f);
            sound(ent.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 2f, 0.5f);
        });
        after(170, () -> sayNow("Gracias... por romper... mi invierno."));
        seq(3, 26, i -> {
            Location l = ent.getLocation().add(0, 2.5, 0);
            sound(l, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.5f + i * 0.04f);
            world.spawnParticle(Particle.END_ROD, l, 8, 0.8, 1.6, 0.8, 0.1);
        }, null);
        after(200, () -> {
            if (vis != null) vis.shake(id(), 7f);
            sound(ent.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 2.5f, 0.5f);
        });
        after(236, () -> {
            if (vis != null) {
                vis.pulse(id(), 1.32f);
                vis.shake(id(), 9f);
            }
            sound(ent.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 2f, 1.6f);
        });
        after(250, () -> {
            Location l = ent.getLocation().add(0, 3, 0);
            if (vis != null) vis.drop(id());
            world.spawnParticle(Particle.EXPLOSION_EMITTER, l, 3, 1, 1, 1, 0);
            iceBurst(l, 280, 3.4);
            sound(l, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.6f);
            sound(l, Sound.BLOCK_GLASS_BREAK, 3f, 0.3f);
            sound(l, Sound.ENTITY_ENDER_DRAGON_DEATH, 2f, 1.4f);
            world.strikeLightningEffect(l);
            seq(2, 16, i -> {
                ring(l, 1 + i * 1.5, 70, Particle.SNOWFLAKE);
                ring(l, 1 + i * 1.5, 40, Particle.END_ROD);
            }, null);
        });
        after(264, () -> {
            dying = false;
            invulnerable = false;
            transitioning = false;
            ent.setHealth(0.0);
        });
    }


    // ------------------------------------------------------------------ mecánicas añadidas: charcos, vórtice, rayo, fractura, hogueras, enrage

    /** Charcos de escarcha: zonas que ralentizan y congelan durante un buen rato; obligan a moverse. */
    private void pools() {
        sayNow(randomOf("El suelo mismo os traicionará.", "Pisad con cuidado.", "Aquí no hay refugio."));
        arms(-60, 40);
        busy(44);
        List<Location> spots = new ArrayList<>();
        for (Player p : alivePlayers()) spots.add(p.getLocation().clone());
        for (int i = 0; i < phaseNum(); i++) {
            Location l = inst.randomSpot(room);
            if (l != null) spots.add(l);
        }
        for (int i = 0; i < Math.min(8, spots.size()); i++) {
            Pool pl = new Pool();
            pl.at = new Location(world, spots.get(i).getX(), groundY() + 0.02, spots.get(i).getZ());
            pl.r = 2.6;
            pl.start = now + 30;
            pl.end = now + 30 + 260 + phaseNum() * 40L;
            pl.disc = world.spawn(pl.at, BlockDisplay.class, b -> {
                b.setBlock(Material.BLUE_ICE.createBlockData());
                b.setPersistent(false);
                b.setBrightness(new Display.Brightness(12, 12));
                b.setViewRange(2.5f);
                b.setTransformationMatrix(new Matrix4f().translate(-0.05f, 0, -0.05f).scale(0.1f, 0.04f, 0.1f));
            });
            temp.add(pl.disc);
            pools.add(pl);
            Pool ref = pl;
            after(30, () -> {
                if (ref.disc == null || !ref.disc.isValid()) return;
                ref.disc.setInterpolationDelay(0);
                ref.disc.setInterpolationDuration(8);
                float d = (float) (ref.r * 2);
                ref.disc.setTransformationMatrix(new Matrix4f().translate(-d / 2, 0, -d / 2).scale(d, 0.04f, d));
                world.playSound(ref.at, Sound.BLOCK_POWDER_SNOW_PLACE, 1.4f, 0.5f);
            });
        }
        after(46, () -> {
            if (calm()) relax();
        });
    }

    private void poolsTick() {
        if (pools.isEmpty()) return;
        List<Pool> done = new ArrayList<>();
        for (Pool pl : pools) {
            if (now >= pl.end) {
                done.add(pl);
                continue;
            }
            if (now < pl.start) {
                ringDust(pl.at, pl.r, 16, Color.fromRGB(255, 120, 120), 0.9f);
                continue;
            }
            if (now % 6 == 0) {
                ringDust(pl.at, pl.r, 20, Color.fromRGB(170, 230, 255), 1.0f);
                world.spawnParticle(Particle.SNOWFLAKE, pl.at.getX(), pl.at.getY() + 0.3, pl.at.getZ(), 4, pl.r * 0.5, 0.1, pl.r * 0.5, 0.01);
            }
            for (Player p : alivePlayers()) {
                Location l = p.getLocation();
                if (Math.hypot(l.getX() - pl.at.getX(), l.getZ() - pl.at.getZ()) > pl.r || !onGround(p)) continue;
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 2, false, false, true));
                p.setFreezeTicks(Math.min(p.getMaxFreezeTicks() + 40, p.getFreezeTicks() + 6));
                if (now % 20 == 0) hit(p, 2.5);
            }
        }
        for (Pool pl : done) {
            if (pl.disc != null && pl.disc.isValid()) {
                iceBurst(pl.at.clone().add(0, 0.2, 0), 14, pl.r * 0.5);
                pl.disc.remove();
            }
            temp.remove(pl.disc);
            pools.remove(pl);
        }
    }

    /** Vórtice: succiona a todos hacia ella y detona; sobrevive quien se aleje del centro (o se agarre a lo lejos). */
    private void vortex() {
        sayNow(randomOf("¡Venid a mí!", "No hay escapatoria del invierno.", "Acercaos... y congelaos."));
        arms(-150, 20);
        wings(-12);
        lift(1.6f);
        busy(130);
        root(120);
        Location c = ent.getLocation().clone();
        c.setY(groundY());
        if (vis != null) {
            vis.tempo(id(), 3f);
            vis.shake(id(), 2f);
        }
        inst.titleAll("", "<light_purple><bold>¡SUCCIÓN! Aléjate del centro</bold></light_purple>");
        seq(2, 50, i -> {
            double r = 14 - i * 0.22;
            for (int k = 0; k < 3; k++) {
                double a = i * 0.45 + k * Math.PI * 2 / 3;
                for (int q = 0; q < 6; q++) {
                    double rr = r * (1 - q / 7.0);
                    double aa = a + q * 0.35;
                    world.spawnParticle(Particle.SNOWFLAKE, c.getX() + Math.cos(aa) * rr, c.getY() + 0.5 + q * 0.2,
                            c.getZ() + Math.sin(aa) * rr, 1, 0, 0, 0, 0);
                }
            }
            if (i % 6 == 0) sound(c, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.4f, 0.5f + i * 0.015f);
            for (Player p : alivePlayers()) {
                Location pl = p.getLocation();
                Vector to = c.toVector().subtract(pl.toVector()).setY(0);
                double d = to.length();
                if (d < 1.6 || d > 24) continue;
                p.setVelocity(p.getVelocity().add(to.normalize().multiply(0.085 + 0.003 * i)));
            }
        }, null);
        after(104, () -> {
            sound(c, Sound.ENTITY_GENERIC_EXPLODE, 2.5f, 0.5f);
            sound(c, Sound.ENTITY_WARDEN_SONIC_BOOM, 2.5f, 0.6f);
            world.spawnParticle(Particle.EXPLOSION_EMITTER, c.getX(), c.getY() + 1.5, c.getZ(), 2, 1, 0.5, 1, 0);
            iceBurst(c.clone().add(0, 1.5, 0), 160, 3.5);
            seq(2, 8, i -> ring(c, 1.5 + i * 1.0, 50, Particle.SNOWFLAKE), null);
            for (Player p : alivePlayers()) {
                Location pl = p.getLocation();
                double d = Math.hypot(pl.getX() - c.getX(), pl.getZ() - c.getZ());
                if (d <= 6.5) {
                    hit(p, 15);
                    chill(p, 160, 80, 2);
                    Vector kb = new Vector(pl.getX() - c.getX(), 0, pl.getZ() - c.getZ());
                    if (kb.lengthSquared() < 0.01) kb = new Vector(1, 0, 0);
                    p.setVelocity(kb.normalize().multiply(1.3).setY(0.6));
                }
            }
            if (vis != null) vis.shake(id(), 0f);
        });
        after(126, () -> {
            if (calm()) {
                relax();
                lift(0f);
            }
        });
    }

    /** Rayo giratorio a ras de suelo: hay que saltarlo. */
    private void beam() {
        sayNow(randomOf("¡Saltad, ratones!", "Mi mirada corta el aire.", "Cada giro... es una sentencia."));
        arms(-90, 70);
        wings(30);
        busy(150);
        root(140);
        Location c = ent.getLocation().clone();
        c.setY(groundY());
        double dir0 = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double sign = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        int sweeps = phaseNum() == 2 ? 1 : 2;
        int total = 40 * sweeps + 30;
        double radius = 18;
        inst.titleAll("", "<red><bold>¡SALTA EL RAYO!</bold></red>");
        // telegrafía la dirección inicial
        seq(3, 10, i -> line(c.clone().add(0, 0.2, 0), c.clone().add(Math.cos(dir0) * radius, 0.2, Math.sin(dir0) * radius),
                Color.fromRGB(255, 110, 110), 36), null);
        java.util.Map<UUID, Long> lastHit = new HashMap<>();
        after(30, () -> {
            sound(c, Sound.BLOCK_BEACON_POWER_SELECT, 2f, 0.5f);
            seq(2, total / 2, i -> {
                double ang = dir0 + sign * i * (Math.PI * 2 / 20.0);
                for (int k = 1; k <= 24; k++) {
                    double d = k * radius / 24.0;
                    world.spawnParticle(Particle.END_ROD, c.getX() + Math.cos(ang) * d, c.getY() + 0.35, c.getZ() + Math.sin(ang) * d, 1, 0, 0, 0, 0);
                    if (k % 3 == 0) world.spawnParticle(Particle.SNOWFLAKE, c.getX() + Math.cos(ang) * d, c.getY() + 0.5,
                            c.getZ() + Math.sin(ang) * d, 1, 0.05, 0.1, 0.05, 0);
                }
                if (i % 4 == 0) sound(c, Sound.ENTITY_GUARDIAN_ATTACK, 1.2f, 0.5f + i * 0.01f);
                for (Player p : alivePlayers()) {
                    Location pl = p.getLocation();
                    double dx = pl.getX() - c.getX();
                    double dz = pl.getZ() - c.getZ();
                    double d = Math.hypot(dx, dz);
                    if (d < 1.5 || d > radius + 1) continue;
                    double pa = Math.atan2(dz, dx);
                    double diff = Math.abs(Math.atan2(Math.sin(pa - ang), Math.cos(pa - ang)));
                    if (diff * d < 1.3 && pl.getY() - groundY() < 0.9 && now - lastHit.getOrDefault(p.getUniqueId(), -100L) > 30) {
                        lastHit.put(p.getUniqueId(), now);
                        hit(p, 12);
                        chill(p, 120, 60, 1);
                        p.setVelocity(new Vector(-Math.sin(ang) * sign, 0.5, Math.cos(ang) * sign).multiply(0.9));
                    }
                }
            }, null);
        });
        after(total + 36, () -> {
            if (calm()) relax();
        });
    }

    /** Fractura: grietas radiales que revientan en lanzas de hielo. Hay que quedarse entre dos grietas. */
    private void fracture() {
        sayNow(randomOf("El hielo se parte... y vosotros con él.", "¡Que se abra la tierra!"));
        arms(-120, 30);
        wings(24);
        lift(1.2f);
        busy(90);
        root(70);
        Location c = ent.getLocation().clone();
        c.setY(groundY());
        int cracks = 4 + phaseNum();
        double base = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        inst.titleAll("", "<aqua><bold>¡GRIETAS! Colócate entre ellas</bold></aqua>");
        for (int k = 0; k < cracks; k++) {
            double ang = base + k * Math.PI * 2 / cracks;
            for (int i = 0; i < 7; i++) {
                double d = 3 + i * 2.2;
                Location s = new Location(world, c.getX() + Math.cos(ang) * d, groundY(), c.getZ() + Math.sin(ang) * d);
                if (Math.abs(s.getX() - center.getX()) > 20 || Math.abs(s.getZ() - center.getZ()) > 20) continue;
                spearAt(s, 34 + i * 3);
            }
            seq(3, 10, i -> line(c.clone().add(0, 0.15, 0), c.clone().add(Math.cos(ang) * 18, 0.15, Math.sin(ang) * 18),
                    Color.fromRGB(150, 225, 255), 30), null);
        }
        after(36, () -> sound(c, Sound.ENTITY_WARDEN_SONIC_BOOM, 2f, 0.7f));
        after(80, () -> {
            if (calm()) {
                relax();
                lift(0f);
            }
        });
    }

    // ---- hogueras que se apagan

    private void setHearth(int i, boolean lit) {
        if (i < 0 || i >= warm.size()) return;
        hearthLit[i] = lit;
        org.bukkit.block.Block b = warm.get(i).getBlock();
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Campfire cf) {
            cf.setLit(lit);
            b.setBlockData(cf, false);
        }
    }

    private void hearthTick() {
        if (!coldOn || absolute || transitioning || dying) return;
        int lit = 0;
        for (boolean h : hearthLit) if (h) lit++;
        int minLit = phase == Phase.TWO ? 3 : 2;
        if (now >= nextFreeze && lit > minLit) {
            List<Integer> on = new ArrayList<>();
            for (int i = 0; i < hearthLit.length; i++) if (hearthLit[i]) on.add(i);
            int i = on.get(ThreadLocalRandom.current().nextInt(on.size()));
            setHearth(i, false);
            relight[i] = 300;
            nextFreeze = now + (phase == Phase.TWO ? 700 : 480);
            Location w = warm.get(i);
            sound(w, Sound.BLOCK_FIRE_EXTINGUISH, 2f, 0.6f);
            iceBurst(w.clone().add(0, 1, 0), 40, 1.2);
            inst.titleAll("", "<gray>Una <gold>hoguera</gold> se ha apagado: ¡quédate en ella para reavivarla!</gray>");
        }
        for (int i = 0; i < hearthLit.length; i++) {
            if (hearthLit[i]) continue;
            Location w = warm.get(i);
            boolean near = false;
            for (Player p : alivePlayers()) {
                if (Math.hypot(p.getLocation().getX() - w.getX(), p.getLocation().getZ() - w.getZ()) <= WARM_RADIUS) near = true;
            }
            relight[i] -= near ? 6 : 2;
            if (now % 10 == 0) ringDust(w, WARM_RADIUS, 20, Color.fromRGB(120, 160, 200), 0.9f);
            if (relight[i] <= 0) {
                setHearth(i, true);
                sound(w, Sound.ITEM_FIRECHARGE_USE, 1.5f, 0.8f);
                world.spawnParticle(Particle.FLAME, w.getX(), w.getY() + 0.5, w.getZ(), 30, 0.5, 0.4, 0.5, 0.05);
            }
        }
    }

    // ---- ambientación y enrage

    private void music() {
        if (intro || dying) return;
        int period = phase == Phase.THREE ? 16 : phase == Phase.TWO ? 24 : 40;
        if (now % period == 0) {
            sound(center, Sound.ENTITY_WARDEN_HEARTBEAT, 1.4f, phase == Phase.THREE ? 1.4f : 1.0f);
        }
        if (now % 200 == 0) {
            musicStep++;
            sound(center, Sound.AMBIENT_CAVE, 1.2f, 0.5f + (musicStep % 3) * 0.15f);
        }
    }

    private void enrageTick() {
        if (enraged || enrageNext <= 0 || intro || dying || transitioning || now < enrageNext) return;
        enraged = true;
        sayNow("¡Se acabó el juego! ¡TODO se congelará!");
        inst.titleAll("<dark_red><bold>ENRAGE</bold></dark_red>", "<gray>Ysmera pierde la paciencia: <red>más rápida y más letal</red></gray>");
        sound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, 0.5f);
        if (vis != null) {
            vis.pulse(id(), 1.2f);
            vis.tempo(id(), 1.5f);
        }
        seq(100, 60, i -> {
            if (!alive() || dying) return;
            for (Player p : alivePlayers()) icicleAt(p.getLocation().clone(), 30);
        }, null);
    }
}
