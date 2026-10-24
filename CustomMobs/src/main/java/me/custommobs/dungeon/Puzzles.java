package me.custommobs.dungeon;

import me.custommobs.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Acertijos de la sala PUZZLE. Cada partida sale uno distinto (sin repetir el anterior):
 *
 * <ul>
 *   <li><b>Los Vigilantes</b>: recoge 4 fragmentos mientras unas estatuas solo se mueven cuando nadie las mira.</li>
 *   <li><b>Portadores de Almas</b>: lleva cada orbe a su pedestal de color mientras los enemigos van a por ti.
 *       Si te hieren, sueltas el orbe.</li>
 *   <li><b>Resonancia</b>: rompe a golpes los cristales que se iluminan antes de que se acabe el tiempo.</li>
 * </ul>
 *
 * Reutiliza las 4 placas de la sala (norte, este, sur, oeste) como puntos de anclaje.
 */
final class Puzzles {

    enum Kind { VIGILANTES, PORTADORES, RESONANCIA }

    private static final String[] COLOR_NAME = {"Rojo", "Azul", "Verde", "Amarillo"};
    private static final String[] COLOR_TAG = {"<red>", "<blue>", "<green>", "<yellow>"};
    private static final Material[] PEDESTAL = {Material.RED_CONCRETE, Material.BLUE_CONCRETE,
            Material.LIME_CONCRETE, Material.YELLOW_CONCRETE};
    private static final Material[] GLASS = {Material.RED_STAINED_GLASS, Material.BLUE_STAINED_GLASS,
            Material.LIME_STAINED_GLASS, Material.YELLOW_STAINED_GLASS};
    private static final Particle.DustOptions[] DUST = {
            new Particle.DustOptions(Color.fromRGB(255, 50, 50), 1.4f),
            new Particle.DustOptions(Color.fromRGB(70, 110, 255), 1.4f),
            new Particle.DustOptions(Color.fromRGB(70, 230, 90), 1.4f),
            new Particle.DustOptions(Color.fromRGB(255, 220, 50), 1.4f)};

    private final DungeonInstance inst;
    private DungeonBuilder.Room room;
    private Kind kind;
    private Kind last;
    private final List<ArmorStand> stands = new ArrayList<>();

    // --- vigilantes
    private final boolean[] got = new boolean[4];
    private int gotN;
    private final ArmorStand[] frag = new ArmorStand[4];
    private final List<ArmorStand> statues = new ArrayList<>();
    private final Map<UUID, Long> stunned = new HashMap<>();
    private long lastStepFx;

    // --- portadores
    private final int[] orbState = new int[4];              // 0 libre, 1 llevado, 2 entregado
    private final ArmorStand[] orbEnt = new ArmorStand[4];
    private final Map<UUID, Integer> carrying = new HashMap<>();
    private final Map<UUID, Double> lastHp = new HashMap<>();
    private final Map<UUID, Long> lockout = new HashMap<>();
    private final Map<UUID, Long> wrongCd = new HashMap<>();
    private int delivered;
    private long nextSpawn;

    // --- resonancia
    private final ArmorStand[] crystal = new ArmorStand[4];
    private final boolean[] lit = new boolean[4];
    private final int[] hits = new int[4];
    private final Map<UUID, Long> hitCd = new HashMap<>();
    private int round;
    private int rounds;
    private int need;
    private int windowTicks;
    private long windowEnd;
    private long nextPulse;
    private long nextRound;

    Puzzles(DungeonInstance inst) {
        this.inst = inst;
    }

    Kind kind() {
        return kind;
    }

    /** ¿Esta entidad es un cristal del acertijo? (para que las bendiciones no los traten como enemigos). */
    boolean owns(Entity e) {
        for (ArmorStand c : crystal) {
            if (c != null && c.getUniqueId().equals(e.getUniqueId())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ ciclo de vida

    void start(DungeonBuilder.Room r) {
        reset();
        room = r;
        String mode = inst.cfg().s("puzzle.mode").trim().toUpperCase();
        Kind forced = null;
        for (Kind k : Kind.values()) if (k.name().equals(mode)) forced = k;
        if (forced != null) {
            kind = forced;
        } else {
            Kind[] all = Kind.values();
            Kind pick;
            int tries = 0;
            do {
                pick = all[inst.rnd().nextInt(all.length)];
                tries++;
            } while (pick == last && tries < 8);
            kind = pick;
        }
        last = kind;
        switch (kind) {
            case VIGILANTES -> startVigilantes();
            case PORTADORES -> startPortadores();
            case RESONANCIA -> startResonancia();
        }
    }

    void tick(DungeonBuilder.Room r) {
        if (kind == null || room == null) return;
        switch (kind) {
            case VIGILANTES -> tickVigilantes();
            case PORTADORES -> tickPortadores();
            case RESONANCIA -> tickResonancia();
        }
    }

    /** Quita todas las entidades del acertijo y devuelve las placas a su aspecto normal. */
    void reset() {
        for (ArmorStand a : stands) {
            if (a != null && a.isValid()) {
                inst.unregisterMob(a);
                a.remove();
            }
        }
        stands.clear();
        statues.clear();
        stunned.clear();
        carrying.clear();
        lastHp.clear();
        lockout.clear();
        wrongCd.clear();
        hitCd.clear();
        for (int k = 0; k < 4; k++) {
            got[k] = false;
            frag[k] = null;
            orbState[k] = 0;
            orbEnt[k] = null;
            crystal[k] = null;
            lit[k] = false;
            hits[k] = 0;
        }
        gotN = 0;
        delivered = 0;
        round = 0;
        if (room != null) {
            for (int k = 0; k < room.pads.size(); k++) padBase(k, Material.GILDED_BLACKSTONE, Material.BLACKSTONE);
        }
        kind = null;
    }

    private void finish() {
        DungeonBuilder.Room r = room;
        World w = inst.world();
        for (int k = 0; k < r.pads.size(); k++) {
            int[] pd = r.pads.get(k);
            padBase(k, Material.SEA_LANTERN, Material.POLISHED_BLACKSTONE);
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, pd[0] + 0.5, r.floorY + 1.5, pd[1] + 0.5, 30, 0.6, 1, 0.6, 0.2);
        }
        for (ArmorStand s : statues) {
            if (s.isValid()) w.spawnParticle(Particle.SMOKE, s.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.03);
        }
        for (ArmorStand a : stands) {
            if (a != null && a.isValid()) {
                inst.unregisterMob(a);
                a.remove();
            }
        }
        stands.clear();
        statues.clear();
        for (Player p : inst.onlineMembers()) {
            p.removePotionEffect(PotionEffectType.SLOWNESS);
            p.removePotionEffect(PotionEffectType.GLOWING);
        }
        carrying.clear();
        kind = null;
        inst.puzzleCleared();
    }

    // ------------------------------------------------------------------ utilidades

    private void padBase(int k, Material center, Material ring) {
        int[] pd = room.pads.get(k);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                inst.setBlock(pd[0] + dx, room.floorY, pd[1] + dz, dx == 0 && dz == 0 ? center : ring);
            }
        }
    }

    private ArmorStand spawnStand(Location at, Consumer<ArmorStand> init) {
        ArmorStand a = inst.world().spawn(at, ArmorStand.class, s -> {
            s.setGravity(false);
            s.setSilent(true);
            s.setPersistent(false);
            s.setCanPickupItems(false);
            init.accept(s);
        });
        stands.add(a);
        return a;
    }

    private static boolean near(Location l, double x, double z, double reach) {
        return Math.abs(l.getX() - x) <= reach && Math.abs(l.getZ() - z) <= reach;
    }

    private static Player nearest(List<Player> al, Location from) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : al) {
            if (!p.getWorld().equals(from.getWorld())) continue;
            double d = p.getLocation().distanceSquared(from);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private double hazardDamage() {
        return inst.cfg().d("trap.hazard-damage");
    }

    // ================================================================== LOS VIGILANTES

    private void startVigilantes() {
        for (int k = 0; k < 4; k++) {
            got[k] = false;
            padBase(k, Material.CRYING_OBSIDIAN, Material.POLISHED_BLACKSTONE);
            int[] pd = room.pads.get(k);
            frag[k] = spawnStand(new Location(inst.world(), pd[0] + 0.5, room.floorY + 0.6, pd[1] + 0.5), s -> {
                s.setVisible(false);
                s.setInvulnerable(true);
                s.setGlowing(true);
                if (s.getEquipment() != null) s.getEquipment().setHelmet(new ItemStack(Material.SEA_LANTERN));
            });
        }
        gotN = 0;
        int n = Math.max(1, inst.cfg().i("puzzle.statue-start")) + (inst.groupSize() >= 3 ? 1 : 0);
        for (int i = 0; i < n; i++) spawnStatue();
        inst.titleAll("<aqua><bold>ACERTIJO: LOS VIGILANTES</bold></aqua>",
                "<gray>Recoge los 4 fragmentos. Las estatuas solo se mueven si <white>nadie las mira</white></gray>");
        inst.broadcast("<gray>Cuando miras una estatua se queda <white>quieta y brilla</white>. Cubríos las espaldas unos a otros.</gray>");
    }

    private static ItemStack dyed(Material m, int r, int g, int b) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(Color.fromRGB(r, g, b));
            it.setItemMeta(meta);
        }
        return it;
    }

    private Location farCorner() {
        DungeonBuilder.Room r = room;
        int[][] cs = {{r.x0 + 2, r.z0 + 2}, {r.x1 - 2, r.z0 + 2}, {r.x0 + 2, r.z1 - 2}, {r.x1 - 2, r.z1 - 2}};
        List<Player> al = inst.aliveOnline();
        Location best = null;
        double bd = -1;
        int start = inst.rnd().nextInt(4);
        for (int i = 0; i < 4; i++) {
            int[] c = cs[(start + i) % 4];
            Location l = new Location(inst.world(), c[0] + 0.5, r.floorY + 1, c[1] + 0.5);
            double d = al.isEmpty() ? 100 : Double.MAX_VALUE;
            for (Player p : al) d = Math.min(d, p.getLocation().distanceSquared(l));
            if (d > bd) {
                bd = d;
                best = l;
            }
        }
        return best;
    }

    private void spawnStatue() {
        if (statues.size() >= Math.max(1, inst.cfg().i("puzzle.statue-max"))) return;
        Location l = farCorner();
        ArmorStand a = spawnStand(l, s -> {
            s.setArms(true);
            s.setBasePlate(false);
            s.setInvulnerable(true);
            s.setRightArmPose(new EulerAngle(-Math.PI / 2, 0, 0));
            s.setLeftArmPose(new EulerAngle(-Math.PI / 2, 0, 0));
            if (s.getEquipment() != null) {
                s.getEquipment().setHelmet(new ItemStack(Material.WITHER_SKELETON_SKULL));
                s.getEquipment().setChestplate(dyed(Material.LEATHER_CHESTPLATE, 40, 40, 48));
                s.getEquipment().setLeggings(dyed(Material.LEATHER_LEGGINGS, 40, 40, 48));
                s.getEquipment().setBoots(dyed(Material.LEATHER_BOOTS, 30, 30, 36));
            }
        });
        statues.add(a);
        stunned.put(a.getUniqueId(), inst.tick() + 50);
        inst.world().spawnParticle(Particle.SOUL_FIRE_FLAME, l.clone().add(0, 1, 0), 24, 0.3, 0.7, 0.3, 0.04);
        inst.world().playSound(l, Sound.ENTITY_WITHER_AMBIENT, 0.6f, 0.5f);
    }

    private void collect(int k, Player p) {
        got[k] = true;
        gotN++;
        World w = inst.world();
        int[] pd = room.pads.get(k);
        if (frag[k] != null && frag[k].isValid()) frag[k].remove();
        padBase(k, Material.SEA_LANTERN, Material.POLISHED_BLACKSTONE);
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, pd[0] + 0.5, room.floorY + 1.5, pd[1] + 0.5, 25, 0.4, 0.8, 0.4, 0.2);
        w.playSound(new Location(w, pd[0] + 0.5, room.floorY + 1, pd[1] + 0.5), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4f, 0.8f + 0.2f * gotN);
        if (gotN < 4) {
            inst.broadcast("<aqua>" + p.getName() + " recogió un fragmento</aqua> <gray>(" + gotN + "/4)</gray> <dark_gray>—</dark_gray> <red>un vigilante despierta</red>");
            spawnStatue();
        }
    }

    private void tickVigilantes() {
        List<Player> al = inst.aliveOnline();
        long t = inst.tick();
        World w = inst.world();
        for (int k = 0; k < 4; k++) {
            if (got[k]) continue;
            int[] pd = room.pads.get(k);
            double fx = pd[0] + 0.5;
            double fz = pd[1] + 0.5;
            if (t % 4 == 0) w.spawnParticle(Particle.END_ROD, fx, room.floorY + 2.6, fz, 2, 0.2, 0.3, 0.2, 0.01);
            for (Player p : al) {
                Location pl = p.getLocation();
                if (near(pl, fx, fz, 1.6) && Math.abs(pl.getY() - (room.floorY + 1)) < 3) {
                    collect(k, p);
                    break;
                }
            }
        }
        if (gotN >= 4) {
            finish();
            return;
        }
        double step = Math.max(0.1, inst.cfg().d("puzzle.statue-speed"));
        for (ArmorStand s : new ArrayList<>(statues)) moveStatue(s, al, t, step);
        inst.setHint("Fragmentos " + gotN + "/4 · ¡no pierdas de vista a los vigilantes!");
    }

    private void moveStatue(ArmorStand s, List<Player> al, long t, double step) {
        if (!s.isValid()) return;
        World w = inst.world();
        Long until = stunned.get(s.getUniqueId());
        if (until != null && t < until) {
            s.setGlowing(false);
            return;
        }
        Location sl = s.getLocation();
        Location eye = sl.clone().add(0, 1.6, 0);
        boolean seen = false;
        for (Player p : al) {
            if (!p.getWorld().equals(sl.getWorld())) continue;
            Location pe = p.getEyeLocation();
            Vector to = eye.toVector().subtract(pe.toVector());
            double dist = to.length();
            if (dist < 0.6) {
                seen = true;
                break;
            }
            if (dist > 45) continue;
            to.multiply(1.0 / dist);
            if (pe.getDirection().dot(to) > 0.5 && p.hasLineOfSight(s)) {
                seen = true;
                break;
            }
        }
        s.setGlowing(seen);
        if (seen) {
            if (t % 20 == 0) w.spawnParticle(Particle.SMALL_FLAME, eye, 3, 0.2, 0.3, 0.2, 0.0);
            return;
        }
        Player target = nearest(al, sl);
        if (target == null) return;
        Location tl = target.getLocation();
        double dx = tl.getX() - sl.getX();
        double dz = tl.getZ() - sl.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.3 && Math.abs(tl.getY() - sl.getY()) < 2.5) {
            strike(s, target, t);
            return;
        }
        if (len < 0.01) return;
        Location next = tryStep(sl, dx / len, dz / len, step);
        if (next == null) next = tryStep(sl, Math.signum(dx), 0, step);
        if (next == null) next = tryStep(sl, 0, Math.signum(dz), step);
        if (next == null) return;
        next.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        next.setPitch(0f);
        s.teleport(next);
        if (t - lastStepFx >= 8) {
            lastStepFx = t;
            w.playSound(next, Sound.BLOCK_STONE_STEP, 0.7f, 0.5f);
            w.spawnParticle(Particle.SMOKE, next.getX(), next.getY() + 0.1, next.getZ(), 3, 0.2, 0.05, 0.2, 0.01);
        }
    }

    private static Location tryStep(Location from, double ux, double uz, double step) {
        if (ux == 0 && uz == 0) return null;
        Location n = new Location(from.getWorld(), from.getX() + ux * step, from.getY(), from.getZ() + uz * step);
        if (!n.getBlock().isPassable() || !n.clone().add(0, 1, 0).getBlock().isPassable()) return null;
        return n;
    }

    private void strike(ArmorStand s, Player p, long t) {
        World w = inst.world();
        Location sl = s.getLocation();
        inst.hazardHit(p, hazardDamage() * 0.75);
        Vector kb = p.getLocation().toVector().subtract(sl.toVector());
        kb.setY(0);
        if (kb.lengthSquared() < 1.0E-4) kb = new Vector(1, 0, 0);
        kb.normalize();
        kb.multiply(1.1);
        kb.setY(0.45);
        p.setVelocity(kb);
        w.spawnParticle(Particle.CRIT, p.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.3);
        w.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.5f);
        p.sendActionBar(Text.mm("<red>¡Un vigilante te ha alcanzado!</red>"));
        s.teleport(farCorner());
        stunned.put(s.getUniqueId(), t + 80);
    }

    // ================================================================== PORTADORES DE ALMAS

    private void startPortadores() {
        delivered = 0;
        carrying.clear();
        lastHp.clear();
        lockout.clear();
        for (int k = 0; k < 4; k++) {
            orbState[k] = 0;
            padBase(k, PEDESTAL[k], Material.BLACKSTONE);
            Location spot = inst.randomSpot(room);
            if (spot == null) spot = room.center(inst.world());
            spawnOrb(k, spot);
        }
        inst.mobMode();
        nextSpawn = inst.tick() + 140;
        inst.titleAll("<aqua><bold>ACERTIJO: PORTADORES DE ALMAS</bold></aqua>",
                "<gray>Lleva cada orbe a su pedestal del mismo color</gray>");
        inst.broadcast("<gray>El portador va <white>más lento</white> y brilla. <red>Si le hieren, suelta el orbe.</red> ¡Protegedlo!</gray>");
    }

    private void spawnOrb(int k, Location at) {
        Location l = at.clone();
        l.setY(room.floorY + 1.0);
        orbEnt[k] = spawnStand(l, s -> {
            s.setVisible(false);
            s.setInvulnerable(true);
            s.setSmall(true);
            s.setGlowing(true);
            if (s.getEquipment() != null) s.getEquipment().setHelmet(new ItemStack(GLASS[k]));
        });
    }

    private void tickPortadores() {
        List<Player> al = inst.aliveOnline();
        long t = inst.tick();
        World w = inst.world();

        // orbes libres: brillo y recogida
        for (int k = 0; k < 4; k++) {
            if (orbState[k] != 0 || orbEnt[k] == null || !orbEnt[k].isValid()) continue;
            Location ol = orbEnt[k].getLocation();
            if (t % 4 == 0) w.spawnParticle(Particle.DUST, ol.getX(), ol.getY() + 1.0, ol.getZ(), 4, 0.15, 0.3, 0.15, 0, DUST[k]);
            for (Player p : al) {
                if (carrying.containsKey(p.getUniqueId())) continue;
                Long lock = lockout.get(p.getUniqueId());
                if (lock != null && t < lock) continue;
                Location pl = p.getLocation();
                if (near(pl, ol.getX(), ol.getZ(), 1.5) && Math.abs(pl.getY() - ol.getY()) < 3) {
                    pickup(k, p);
                    break;
                }
            }
        }

        // pedestales: un anillo del color de cada uno
        if (t % 6 == 0) {
            for (int k = 0; k < 4; k++) {
                if (orbState[k] == 2) continue;
                int[] pd = room.pads.get(k);
                w.spawnParticle(Particle.DUST, pd[0] + 0.5, room.floorY + 1.6, pd[1] + 0.5, 3, 0.4, 0.5, 0.4, 0, DUST[k]);
            }
        }

        // portadores
        for (Map.Entry<UUID, Integer> en : new ArrayList<>(carrying.entrySet())) {
            UUID u = en.getKey();
            int k = en.getValue();
            Player p = Bukkit.getPlayer(u);
            if (p == null || !al.contains(p)) {
                dropOrb(k, u, null, false);
                continue;
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 24, 0, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 24, 0, false, false));
            Location pl = p.getLocation();
            w.spawnParticle(Particle.DUST, pl.getX(), pl.getY() + 2.5, pl.getZ(), 3, 0.15, 0.1, 0.15, 0, DUST[k]);
            double hp = p.getHealth() + p.getAbsorptionAmount();
            Double prev = lastHp.get(u);
            lastHp.put(u, hp);
            if (prev != null && hp < prev - 0.4) {
                p.sendActionBar(Text.mm("<red>¡Te han herido! Sueltas el orbe " + COLOR_NAME[k].toLowerCase() + "</red>"));
                dropOrb(k, u, pl, true);
                continue;
            }
            for (int j = 0; j < room.pads.size(); j++) {
                int[] pd = room.pads.get(j);
                if (Math.abs(pl.getY() - (room.floorY + 1)) >= 3) break;
                if (j == k && near(pl, pd[0] + 0.5, pd[1] + 0.5, 1.8)) {
                    deliver(k, p, pd);
                    break;
                }
                if (j != k && orbState[j] != 2 && near(pl, pd[0] + 0.5, pd[1] + 0.5, 1.0)) {
                    wrongPedestal(p, k, j, t);
                    break;
                }
            }
        }

        // enemigos que van a por los portadores
        long every = Math.max(60, inst.cfg().i("puzzle.carrier-spawn-seconds") * 20L);
        if (t >= nextSpawn) {
            nextSpawn = t + every;
            int cap = Math.min(inst.cfg().i("waves.max-alive"), 6 + 3 * inst.groupSize());
            int free = cap - inst.liveMobs();
            int n = Math.min(free, 2 + inst.groupSize());
            if (n > 0) inst.queueMobs(n);
        }
        inst.pumpMobs(room);

        if (delivered >= 4) {
            inst.killAllMobs();
            finish();
            return;
        }
        StringBuilder sb = new StringBuilder("Orbes " + delivered + "/4");
        if (!carrying.isEmpty()) sb.append(" · llevando: ").append(carrying.size());
        sb.append(" · enemigos: ").append(inst.liveMobs());
        inst.setHint(sb.toString());
    }

    private void pickup(int k, Player p) {
        orbState[k] = 1;
        if (orbEnt[k] != null && orbEnt[k].isValid()) orbEnt[k].remove();
        stands.remove(orbEnt[k]);
        orbEnt[k] = null;
        carrying.put(p.getUniqueId(), k);
        lastHp.put(p.getUniqueId(), p.getHealth() + p.getAbsorptionAmount());
        World w = inst.world();
        w.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 1.6f);
        w.spawnParticle(Particle.DUST, p.getLocation().add(0, 1.2, 0), 20, 0.4, 0.6, 0.4, 0, DUST[k]);
        p.sendMessage(Text.mm(DungeonManager.PREFIX + "<gray>Llevas el orbe " + COLOR_TAG[k] + "<bold>" + COLOR_NAME[k].toUpperCase()
                + "</bold></gray>" + "<gray> — llévalo al pedestal " + COLOR_TAG[k] + COLOR_NAME[k].toLowerCase() + "</gray>"));
    }

    private void dropOrb(int k, UUID u, Location where, boolean lockPlayer) {
        carrying.remove(u);
        lastHp.remove(u);
        orbState[k] = 0;
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            p.removePotionEffect(PotionEffectType.SLOWNESS);
            p.removePotionEffect(PotionEffectType.GLOWING);
        }
        if (lockPlayer) lockout.put(u, inst.tick() + 80);
        Location spot = where;
        if (spot == null || !room.contains(spot)) {
            spot = inst.randomSpot(room);
            if (spot == null) spot = room.center(inst.world());
        }
        spawnOrb(k, spot);
        inst.world().playSound(spot, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.6f);
        inst.world().spawnParticle(Particle.DUST, spot.getX(), spot.getY() + 1, spot.getZ(), 20, 0.4, 0.5, 0.4, 0, DUST[k]);
    }

    private void deliver(int k, Player p, int[] pd) {
        orbState[k] = 2;
        delivered++;
        carrying.remove(p.getUniqueId());
        lastHp.remove(p.getUniqueId());
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        p.removePotionEffect(PotionEffectType.GLOWING);
        padBase(k, Material.SEA_LANTERN, PEDESTAL[k]);
        World w = inst.world();
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, pd[0] + 0.5, room.floorY + 1.5, pd[1] + 0.5, 40, 0.5, 1, 0.5, 0.3);
        w.playSound(new Location(w, pd[0] + 0.5, room.floorY + 1, pd[1] + 0.5), Sound.BLOCK_BEACON_ACTIVATE, 1.2f, 1.4f);
        inst.broadcast(COLOR_TAG[k] + "Pedestal " + COLOR_NAME[k].toLowerCase() + " activado</" + COLOR_TAG[k].substring(1)
                + " <gray>por " + p.getName() + " (" + delivered + "/4)</gray>");
    }

    private void wrongPedestal(Player p, int k, int j, long t) {
        Long cd = wrongCd.get(p.getUniqueId());
        if (cd != null && t < cd) return;
        wrongCd.put(p.getUniqueId(), t + 40);
        p.sendActionBar(Text.mm("<red>Ese es el pedestal " + COLOR_NAME[j].toLowerCase() + "</red> <gray>— tu orbe es el "
                + COLOR_TAG[k] + COLOR_NAME[k].toLowerCase() + "</gray>"));
        inst.world().playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.2f, 0.6f);
    }

    // ================================================================== RESONANCIA

    private void startResonancia() {
        rounds = Math.max(1, Math.min(8, inst.cfg().i("puzzle.crystal-rounds")));
        need = Math.max(2, inst.cfg().i("puzzle.crystal-hits"));
        round = 0;
        for (int k = 0; k < 4; k++) {
            lit[k] = false;
            hits[k] = 0;
            padBase(k, PEDESTAL[k], Material.BLACKSTONE);
            int[] pd = room.pads.get(k);
            final int idx = k;
            ArmorStand c = spawnStand(new Location(inst.world(), pd[0] + 0.5, room.floorY + 1, pd[1] + 0.5), s -> {
                s.setVisible(false);
                if (s.getEquipment() != null) s.getEquipment().setHelmet(new ItemStack(GLASS[idx]));
            });
            crystal[k] = c;
            inst.registerMob(c, false);
        }
        nextRound = inst.tick() + 80;
        inst.titleAll("<aqua><bold>ACERTIJO: RESONANCIA</bold></aqua>",
                "<gray>Rompe a golpes el cristal que <white>brilla</white> antes de que se acabe el tiempo</gray>");
        inst.broadcast("<gray>Golpear un cristal apagado te da una <red>descarga</red>. Si el tiempo se acaba, el suelo ataca.</gray>");
    }

    private void beginRound() {
        round++;
        if (round > rounds) {
            finish();
            return;
        }
        int count = (round >= 3 && inst.groupSize() >= 2) ? 2 : 1;
        List<Integer> pool = new ArrayList<>(List.of(0, 1, 2, 3));
        java.util.Collections.shuffle(pool, inst.rnd());
        for (int k = 0; k < 4; k++) {
            lit[k] = false;
            hits[k] = 0;
        }
        for (int i = 0; i < count; i++) {
            int k = pool.get(i);
            lit[k] = true;
            if (crystal[k] != null) crystal[k].setGlowing(true);
        }
        int base = Math.max(5, inst.cfg().i("puzzle.crystal-window-seconds")) * 20;
        windowTicks = Math.max(100, base - (round - 1) * 15);
        windowEnd = inst.tick() + windowTicks;
        nextPulse = inst.tick() + 70;
        inst.titleAll("<aqua><bold>RONDA " + round + "/" + rounds + "</bold></aqua>",
                "<gray>Rompe " + (count > 1 ? "los cristales iluminados" : "el cristal iluminado") + " (" + need + " golpes cada uno)</gray>");
        inst.soundAll(Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.2f);
    }

    private void tickResonancia() {
        long t = inst.tick();
        World w = inst.world();
        boolean anyLit = false;
        for (int k = 0; k < 4; k++) if (lit[k]) anyLit = true;

        if (!anyLit) {
            inst.setHint("Resonancia: prepárate...");
            if (nextRound > 0 && t >= nextRound) {
                nextRound = 0;
                beginRound();
            }
            return;
        }

        StringBuilder sb = new StringBuilder("Ronda " + round + "/" + rounds + " · ");
        for (int k = 0; k < 4; k++) {
            if (!lit[k] || crystal[k] == null) continue;
            Location cl = crystal[k].getLocation();
            if (t % 4 == 0) {
                w.spawnParticle(Particle.DUST, cl.getX(), cl.getY() + 1.6, cl.getZ(), 5, 0.3, 0.5, 0.3, 0, DUST[k]);
                w.spawnParticle(Particle.END_ROD, cl.getX(), cl.getY() + 2.4, cl.getZ(), 1, 0.1, 0.2, 0.1, 0.01);
            }
            sb.append(COLOR_NAME[k]).append(" ").append(hits[k]).append("/").append(need).append("  ");
        }
        long left = Math.max(0, windowEnd - t);
        sb.append("· ").append(left / 20).append("s");
        inst.setHint(sb.toString());

        // pulso: castiga a quien rodea los cristales
        if (t >= nextPulse) {
            nextPulse = t + 70;
            int tele = inst.cfg().i("trap.telegraph-ticks");
            for (Player p : inst.aliveOnline()) {
                for (int k = 0; k < 4; k++) {
                    if (lit[k] && crystal[k] != null && crystal[k].getLocation().distanceSquared(p.getLocation()) < 100) {
                        inst.hazard(p.getLocation(), tele, 2.2, hazardDamage() * 0.7);
                        break;
                    }
                }
            }
        }

        // tiempo agotado: el suelo ataca y se repite la ronda
        if (t >= windowEnd) {
            int tele = inst.cfg().i("trap.telegraph-ticks");
            for (Player p : inst.aliveOnline()) inst.hazard(p.getLocation(), tele, 2.6, hazardDamage());
            for (int k = 0; k < 4; k++) hits[k] = 0;
            windowEnd = t + windowTicks;
            nextPulse = t + 70;
            inst.titleAll("<red><bold>¡SE ACABÓ EL TIEMPO!</bold></red>", "<gray>Los cristales se recargan. ¡Más rápido!</gray>");
            inst.soundAll(Sound.ENTITY_WITHER_AMBIENT, 1f, 0.7f);
        }
    }

    /**
     * Golpe sobre uno de los cristales (enrutado desde DungeonInstance.onMobDamaged).
     *
     * @return true si el evento era de un cristal y ya se ha tratado
     */
    boolean onDamaged(EntityDamageEvent e) {
        if (kind != Kind.RESONANCIA) return false;
        int k = -1;
        for (int i = 0; i < 4; i++) {
            if (crystal[i] != null && crystal[i].getUniqueId().equals(e.getEntity().getUniqueId())) k = i;
        }
        if (k < 0) return false;
        e.setCancelled(true);
        if (inst.blessings().inProc()) return true;
        Player p = null;
        if (e instanceof EntityDamageByEntityEvent be) {
            if (be.getDamager() instanceof Player pl) p = pl;
            else if (be.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player pl) p = pl;
        }
        if (p == null || !inst.isMember(p.getUniqueId())) return true;
        long t = inst.tick();
        Long cd = hitCd.get(p.getUniqueId());
        if (cd != null && t < cd) return true;
        hitCd.put(p.getUniqueId(), t + 6);
        World w = inst.world();
        Location cl = crystal[k].getLocation();
        if (lit[k]) {
            hits[k]++;
            w.spawnParticle(Particle.DUST, cl.getX(), cl.getY() + 1.6, cl.getZ(), 14, 0.3, 0.5, 0.3, 0, DUST[k]);
            w.spawnParticle(Particle.CRIT, cl.getX(), cl.getY() + 1.6, cl.getZ(), 8, 0.3, 0.4, 0.3, 0.3);
            w.playSound(cl, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, 0.6f + 1.4f * hits[k] / (float) need);
            if (hits[k] >= need) shatter(k, p);
        } else {
            inst.hazardHit(p, hazardDamage() * 0.5);
            windowEnd = Math.max(t + 20, windowEnd - 20);
            w.playSound(cl, Sound.BLOCK_NOTE_BLOCK_BASS, 1.5f, 0.5f);
            w.spawnParticle(Particle.SMOKE, cl.getX(), cl.getY() + 1.5, cl.getZ(), 12, 0.3, 0.4, 0.3, 0.03);
            p.sendActionBar(Text.mm("<red>¡Ese cristal está apagado!</red> <gray>Pierdes tiempo.</gray>"));
        }
        return true;
    }

    private void shatter(int k, Player p) {
        lit[k] = false;
        World w = inst.world();
        Location cl = crystal[k].getLocation();
        if (crystal[k] != null) crystal[k].setGlowing(false);
        w.spawnParticle(Particle.EXPLOSION, cl.getX(), cl.getY() + 1.4, cl.getZ(), 1, 0, 0, 0, 0);
        w.spawnParticle(Particle.DUST, cl.getX(), cl.getY() + 1.4, cl.getZ(), 40, 0.5, 0.7, 0.5, 0, DUST[k]);
        w.playSound(cl, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f);
        w.playSound(cl, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.4f);
        padBase(k, Material.SEA_LANTERN, PEDESTAL[k]);
        boolean any = false;
        for (int i = 0; i < 4; i++) if (lit[i]) any = true;
        if (!any) {
            inst.broadcast("<aqua>Ronda " + round + "/" + rounds + " superada</aqua> <gray>por " + p.getName() + "</gray>");
            for (int i = 0; i < 4; i++) padBase(i, PEDESTAL[i], Material.BLACKSTONE);
            nextRound = inst.tick() + 60;
        }
    }
}
