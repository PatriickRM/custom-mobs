package me.custommobs.dungeon;

import me.custommobs.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Objetivos de las salas de oleadas: en vez de solo matar, a veces hay que <b>destruir cristales de invocación</b>
 * repartidos por la arena (cada uno invoca enemigos sin parar) o <b>capturar faros</b> manteniéndote sobre ellos
 * mientras te atacan. Se colocan en los anclajes que el constructor marca en cada arena ({@code Room.anchors}).
 */
final class ObjectiveSystem {

    enum Kind { CRYSTALS, BEACONS, CORE, RUNES, CHAMPION }

    private static final class Crystal {
        EnderCrystal ent;
        TextDisplay label;
        double hp;
        double max;
        long nextPulse;
        boolean dead;
        Location at;
    }

    private static final class Beacon {
        Location at;
        BlockDisplay beam;
        TextDisplay label;
        double progress;
        boolean done;
    }

    private static final class Rune {
        Location at;
        BlockDisplay pillar;
        TextDisplay label;
        int idx;
        boolean done;
    }

    private final DungeonInstance inst;
    private final DungeonConfig cfg;
    private final World world;
    private Kind kind;
    private boolean active;
    private long t;
    private long nextSpawn;
    private boolean finishing;
    private long finishAt;
    private final List<Crystal> crystals = new ArrayList<>();
    private final List<Beacon> beacons = new ArrayList<>();
    private final List<Rune> runes = new ArrayList<>();
    private Crystal core;
    private long coreEnd;
    private int runeNext;
    private int runeHold;
    private org.bukkit.entity.LivingEntity champion;
    private long nextBlink;
    private List<int[]> anchorsCache = new ArrayList<>();

    ObjectiveSystem(DungeonInstance inst, DungeonConfig cfg, World world) {
        this.inst = inst;
        this.cfg = cfg;
        this.world = world;
    }

    boolean enabled() {
        return cfg.b("objectives.enabled");
    }

    boolean active() {
        return active;
    }

    // ------------------------------------------------------------------ inicio / fin

    /** Decide al azar si la sala de oleadas tiene un objetivo especial. @return true si lo hay. */
    boolean begin(DungeonBuilder.Room r) {
        clear();
        if (!enabled() || r.anchors.size() < 3) return false;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (rnd.nextDouble() >= cfg.d("objectives.chance")) return false;
        kind = Kind.values()[rnd.nextInt(Kind.values().length)];
        List<int[]> pool = new ArrayList<>(r.anchors);
        Collections.shuffle(pool, rnd);
        active = true;
        finishing = false;
        t = 0;
        nextSpawn = 60;
        int group = inst.groupSize();
        if (kind == Kind.CRYSTALS) {
            int n = Math.min(pool.size(), Math.min(5, 3 + (group - 1) / 2));
            double hp = cfg.d("objectives.crystal-health") * (1 + 0.3 * (group - 1)) * inst.heat().hpMul();
            for (int i = 0; i < n; i++) {
                int[] a = pool.get(i);
                Crystal c = new Crystal();
                c.at = new Location(world, a[0] + 0.5, r.floorY + 2, a[1] + 0.5);
                c.hp = hp;
                c.max = hp;
                c.nextPulse = 80 + i * 30L;
                c.ent = world.spawn(c.at, EnderCrystal.class, e -> {
                    e.setShowingBottom(false);
                    e.setPersistent(false);
                });
                inst.manager().indexMob(c.ent.getUniqueId(), inst);
                c.label = label(c.at.clone().add(0, 2.4, 0), "");
                crystals.add(c);
                updateLabel(c);
            }
            inst.titleAll("<light_purple><bold>CRISTALES DE INVOCACIÓN</bold></light_purple>",
                    "<gray>Destruye los <white>" + n + "</white> cristales antes de que os desborden</gray>");
        } else if (kind == Kind.CORE) {
            beginCore(r);
        } else if (kind == Kind.RUNES) {
            beginRunes(r, pool);
        } else if (kind == Kind.CHAMPION) {
            beginChampion(r, pool);
        } else {
            int n = Math.min(3, pool.size());
            for (int i = 0; i < n; i++) {
                int[] a = pool.get(i);
                Beacon b = new Beacon();
                b.at = new Location(world, a[0] + 0.5, r.floorY + 1, a[1] + 0.5);
                b.beam = world.spawn(b.at, BlockDisplay.class, d -> {
                    d.setBlock(Material.WHITE_STAINED_GLASS.createBlockData());
                    d.setPersistent(false);
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setViewRange(2.0f);
                    d.setTransformationMatrix(new Matrix4f().translate(-0.25f, 0f, -0.25f).scale(0.5f, 7f, 0.5f));
                });
                b.label = label(b.at.clone().add(0, 7.6, 0), "");
                beacons.add(b);
            }
            inst.titleAll("<aqua><bold>CAPTURA LOS FAROS</bold></aqua>",
                    "<gray>Quédate sobre cada faro hasta completarlo. ¡Os atacarán!</gray>");
        }
        inst.soundAll(Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.7f);
        return true;
    }

    /** Retira entidades y estado del objetivo (al superar la sala, reiniciar o cerrar). */
    void clear() {
        for (Crystal c : crystals) {
            if (c.ent != null) {
                inst.manager().unindexMob(c.ent.getUniqueId());
                if (c.ent.isValid()) c.ent.remove();
            }
            if (c.label != null && c.label.isValid()) c.label.remove();
        }
        for (Beacon b : beacons) {
            if (b.beam != null && b.beam.isValid()) b.beam.remove();
            if (b.label != null && b.label.isValid()) b.label.remove();
        }
        crystals.clear();
        beacons.clear();
        if (core != null) {
            if (core.ent != null) {
                inst.manager().unindexMob(core.ent.getUniqueId());
                if (core.ent.isValid()) core.ent.remove();
            }
            if (core.label != null && core.label.isValid()) core.label.remove();
            core = null;
        }
        for (Rune ru : runes) {
            if (ru.pillar != null && ru.pillar.isValid()) ru.pillar.remove();
            if (ru.label != null && ru.label.isValid()) ru.label.remove();
        }
        runes.clear();
        if (champion != null && champion.isValid()) champion.setGlowing(false);
        champion = null;
        active = false;
        finishing = false;
        kind = null;
    }

    private TextDisplay label(Location at, String mm) {
        return world.spawn(at, TextDisplay.class, td -> {
            td.text(Text.mm(mm));
            td.setBillboard(Display.Billboard.CENTER);
            td.setShadowed(true);
            td.setDefaultBackground(false);
            td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            td.setPersistent(false);
            td.setViewRange(2.0f);
        });
    }

    // ------------------------------------------------------------------ bucle (cada 2 ticks)

    void tick(DungeonBuilder.Room r) {
        if (!active) return;
        t += 2;
        if (finishing) {
            if (t >= finishAt) {
                clear();
                inst.objectiveComplete();
            }
            return;
        }
        switch (kind) {
            case CRYSTALS -> tickCrystals(r);
            case CORE -> tickCore(r);
            case RUNES -> tickRunes(r);
            case CHAMPION -> tickChampion(r);
            default -> tickBeacons(r);
        }
    }

    private int cap() {
        return Math.max(6, Math.min(cfg.i("waves.max-alive"), 8 + 2 * inst.groupSize()));
    }

    private void tickCrystals(DungeonBuilder.Room r) {
        int left = 0;
        double speed = 1.0;
        int total = crystals.size();
        int dead = 0;
        for (Crystal c : crystals) if (c.dead) dead++;
        if (dead * 2 >= total) speed = 0.7;      // con la mitad destruida, los supervivientes invocan más rápido
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Crystal c : crystals) {
            if (c.dead) continue;
            left++;
            if (c.ent == null || !c.ent.isValid()) {
                destroy(c, false);
                continue;
            }
            if (t % 10 == 0) {
                world.spawnParticle(Particle.PORTAL, c.at.clone().add(0, 0.5, 0), 14, 0.7, 1.0, 0.7, 0.4);
                if (rnd.nextInt(3) == 0) world.spawnParticle(Particle.DUST, c.at.clone().add(0, 1.5, 0), 6, 0.5, 0.7, 0.5, 0,
                        new Particle.DustOptions(Color.fromRGB(190, 80, 255), 1.1f));
            }
            if (t >= c.nextPulse) {
                c.nextPulse = t + (long) (cfg.d("objectives.crystal-pulse-seconds") * 20 * speed);
                pulse(c, r);
            }
        }
        inst.hint("Cristales: " + left + "/" + total + " · enemigos: " + inst.mobCount());
        if (left == 0 && !finishing) finish("<green><bold>¡CRISTALES DESTRUIDOS!</bold></green>", "<gray>Los enemigos restantes se desvanecen</gray>");
    }

    private void pulse(Crystal c, DungeonBuilder.Room r) {
        world.spawnParticle(Particle.REVERSE_PORTAL, c.at, 40, 0.6, 0.8, 0.6, 0.3);
        world.playSound(c.at, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0f, 0.8f);
        if (inst.mobCount() >= cap()) return;
        int n = 2 + (ThreadLocalRandom.current().nextInt(3) == 0 ? 1 : 0);
        for (int i = 0; i < n; i++) {
            Location l = spotNear(c.at, r);
            if (l != null) inst.spawnObjectiveMob(l, false);
        }
    }

    /** Un sitio libre cerca del cristal (a 2-4 bloques). */
    private Location spotNear(Location around, DungeonBuilder.Room r) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 12; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double d = 2.5 + rnd.nextDouble() * 2.0;
            Location l = new Location(world, around.getX() + Math.cos(a) * d, r.floorY + 1, around.getZ() + Math.sin(a) * d);
            if (l.getBlock().isPassable() && l.clone().add(0, 1, 0).getBlock().isPassable()
                    && !l.clone().add(0, -1, 0).getBlock().isPassable()) return l;
        }
        return inst.randomSpot(r);
    }

    private void updateLabel(Crystal c) {
        if (c.label == null || !c.label.isValid()) return;
        int bars = 10;
        int filled = (int) Math.ceil(Math.max(0, c.hp) / c.max * bars);
        StringBuilder sb = new StringBuilder("<light_purple><bold>❖ Cristal</bold></light_purple> <dark_gray>[</dark_gray><red>");
        for (int i = 0; i < bars; i++) {
            if (i == filled) sb.append("</red><dark_gray>");
            sb.append("▮");
        }
        sb.append(filled >= bars ? "</red>" : "</dark_gray>").append("<dark_gray>]</dark_gray>");
        c.label.text(Text.mm(sb.toString()));
    }

    /** Daño a un cristal: se cancela la explosión vanilla y se descuenta de su vida propia. */
    boolean onDamaged(EntityDamageEvent e) {
        if (active && kind == Kind.CORE && core != null && core.ent != null && core.ent.getUniqueId().equals(e.getEntity().getUniqueId())) {
            e.setCancelled(true);       // el núcleo solo lo dañan los enemigos que lo rodean (ver tickCore)
            return true;
        }
        if (!active || kind != Kind.CRYSTALS) return false;
        Entity ent = e.getEntity();
        for (Crystal c : crystals) {
            if (c.dead || c.ent == null || !c.ent.getUniqueId().equals(ent.getUniqueId())) continue;
            e.setCancelled(true);
            double dmg = Math.max(0.0, e.getFinalDamage());
            if (e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent be) {
                Player p = be.getDamager() instanceof Player pl ? pl
                        : be.getDamager() instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Player sp ? sp : null;
                if (p == null) return true;      // los enemigos no dañan a los cristales
                inst.addDamage(p, dmg);
            } else {
                return true;
            }
            c.hp -= Math.max(1.0, dmg);
            world.spawnParticle(Particle.ENCHANTED_HIT, c.at.clone().add(0, 1, 0), 10, 0.4, 0.5, 0.4, 0.3);
            world.playSound(c.at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1f, 0.8f);
            if (c.hp <= 0) destroy(c, true);
            else updateLabel(c);
            return true;
        }
        return false;
    }

    private void destroy(Crystal c, boolean announce) {
        if (c.dead) return;
        c.dead = true;
        world.spawnParticle(Particle.EXPLOSION_EMITTER, c.at.clone().add(0, 1, 0), 1);
        world.spawnParticle(Particle.END_ROD, c.at.clone().add(0, 1, 0), 40, 0.6, 0.8, 0.6, 0.2);
        world.playSound(c.at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.2f, 0.6f);
        world.playSound(c.at, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.4f);
        if (c.ent != null) {
            inst.manager().unindexMob(c.ent.getUniqueId());
            if (c.ent.isValid()) c.ent.remove();
        }
        if (c.label != null && c.label.isValid()) c.label.remove();
        if (announce) {
            int left = 0;
            for (Crystal o : crystals) if (!o.dead) left++;
            if (left > 0) inst.broadcast("<light_purple>Cristal destruido.</light_purple> <gray>Quedan <white>" + left + "</white>.</gray>");
        }
    }

    // ------------------------------------------------------------------ faros

    private void tickBeacons(DungeonBuilder.Room r) {
        double radius = cfg.d("objectives.beacon-radius");
        double perCall = 100.0 / Math.max(3.0, cfg.d("objectives.beacon-seconds")) / 10.0;   // se llama 10 veces por segundo
        int done = 0;
        List<Player> alive = inst.aliveOnline();
        for (Beacon b : beacons) {
            if (b.done) {
                done++;
                continue;
            }
            int inside = 0;
            for (Player p : alive) {
                if (p.getWorld().equals(world) && flat(p.getLocation(), b.at) <= radius) inside++;
            }
            int enemies = 0;
            for (Entity e : world.getNearbyEntities(b.at, radius, 3, radius)) {
                if (e instanceof Player || !(e instanceof org.bukkit.entity.LivingEntity)) continue;
                if (inst.isProcTarget(e)) enemies++;
            }
            boolean contested = enemies >= 4;
            if (inside > 0 && !contested) {
                b.progress += perCall * (1.0 + 0.6 * Math.min(3, inside - 1));
            } else if (inside == 0) {
                b.progress -= perCall * 0.35;
            }
            b.progress = Math.max(0, Math.min(100, b.progress));
            if (t % 10 == 0) drawBeacon(b, radius, inside, contested);
            if (b.progress >= 100) {
                b.done = true;
                done++;
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, b.at.clone().add(0, 2, 0), 50, 0.8, 1.2, 0.8, 0.4);
                world.playSound(b.at, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 1.2f);
                if (b.beam != null && b.beam.isValid()) b.beam.setBlock(Material.LIME_STAINED_GLASS.createBlockData());
                if (b.label != null && b.label.isValid()) b.label.text(Text.mm("<green><bold>✔ Faro capturado</bold></green>"));
                inst.broadcast("<aqua>Faro capturado.</aqua> <gray>(" + done + "/" + beacons.size() + ")</gray>");
            }
        }
        // refuerzos continuos mientras haya faros por capturar
        if (t >= nextSpawn) {
            nextSpawn = t + (long) (cfg.d("objectives.beacon-spawn-seconds") * 20);
            if (inst.mobCount() < cap()) {
                int n = 2 + inst.groupSize() / 2;
                boolean elite = done == beacons.size() - 1 && ThreadLocalRandom.current().nextInt(3) == 0;
                for (int i = 0; i < n; i++) {
                    Location l = inst.randomSpot(r);
                    if (l != null) inst.spawnObjectiveMob(l, elite && i == 0);
                }
            }
        }
        inst.hint("Faros: " + done + "/" + beacons.size() + " · enemigos: " + inst.mobCount());
        if (done >= beacons.size()) finish("<green><bold>¡FAROS CAPTURADOS!</bold></green>", "<gray>Los enemigos restantes se desvanecen</gray>");
    }

    private static double flat(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void drawBeacon(Beacon b, double radius, int inside, boolean contested) {
        Color col = contested ? Color.fromRGB(255, 70, 70) : inside > 0 ? Color.fromRGB(70, 220, 255) : Color.fromRGB(235, 235, 235);
        Particle.DustOptions opt = new Particle.DustOptions(col, 1.3f);
        for (int i = 0; i < 28; i++) {
            double a = Math.PI * 2 * i / 28;
            world.spawnParticle(Particle.DUST, b.at.getX() + Math.cos(a) * radius, b.at.getY() + 0.15,
                    b.at.getZ() + Math.sin(a) * radius, 1, 0, 0, 0, 0, opt);
        }
        if (b.beam != null && b.beam.isValid()) {
            b.beam.setBlock((contested ? Material.RED_STAINED_GLASS : inside > 0 ? Material.LIGHT_BLUE_STAINED_GLASS
                    : Material.WHITE_STAINED_GLASS).createBlockData());
        }
        if (b.label != null && b.label.isValid()) {
            String tag = contested ? "<red>¡Disputado!</red>" : inside > 0 ? "<aqua>Capturando</aqua>" : "<gray>Sin capturar</gray>";
            b.label.text(Text.mm("<white><bold>" + (int) b.progress + "%</bold></white> " + tag));
        }
    }


    // ------------------------------------------------------------------ defender el núcleo

    private void beginCore(DungeonBuilder.Room r) {
        int group = inst.groupSize();
        double hp = cfg.d("objectives.core-health") * (1 + 0.25 * (group - 1)) * inst.heat().hpMul();
        core = new Crystal();
        core.at = new Location(world, r.cx() + 0.5, r.floorY + 2, r.cz + 0.5);
        core.hp = hp;
        core.max = hp;
        core.ent = world.spawn(core.at, EnderCrystal.class, e -> {
            e.setShowingBottom(true);
            e.setPersistent(false);
        });
        inst.manager().indexMob(core.ent.getUniqueId(), inst);
        core.label = label(core.at.clone().add(0, 2.4, 0), "");
        coreEnd = Math.max(10, cfg.i("objectives.core-seconds")) * 20L;
        nextSpawn = 40;
        inst.titleAll("<aqua><bold>DEFIENDE EL NÚCLEO</bold></aqua>",
                "<gray>Resiste <white>" + cfg.i("objectives.core-seconds") + "s</white> y no dejes que lo destruyan</gray>");
    }

    private void tickCore(DungeonBuilder.Room r) {
        if (core == null || core.ent == null || !core.ent.isValid()) {
            failCore();
            return;
        }
        // los enemigos cercanos lo desgastan; los jugadores cerca lo reparan lentamente
        int hitters = 0;
        for (Entity e : world.getNearbyEntities(core.at, 3.2, 3, 3.2)) {
            if (e instanceof Player || !(e instanceof org.bukkit.entity.LivingEntity)) continue;
            if (inst.isProcTarget(e)) hitters++;
        }
        int guards = 0;
        for (Player p : inst.aliveOnline()) if (p.getWorld().equals(world) && flat(p.getLocation(), core.at) <= 5) guards++;
        core.hp -= hitters * 0.9 * (1.0 + 0.1 * inst.heat().dmgMul());
        if (hitters == 0 && guards > 0) core.hp = Math.min(core.max, core.hp + 0.5);
        if (t % 10 == 0 && hitters > 0) {
            world.spawnParticle(Particle.ENCHANTED_HIT, core.at.clone().add(0, 1, 0), 12, 0.5, 0.6, 0.5, 0.3);
            world.playSound(core.at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1f, 0.7f);
        }
        if (t % 10 == 0) {
            world.spawnParticle(Particle.END_ROD, core.at.clone().add(0, 1.5, 0), 6, 0.4, 0.8, 0.4, 0.05);
            long left = Math.max(0, (coreEnd - t) / 20);
            int pct = (int) Math.max(0, Math.round(core.hp / core.max * 100));
            if (core.label != null && core.label.isValid()) {
                core.label.text(Text.mm("<aqua><bold>❖ Núcleo</bold></aqua> <white>" + pct + "%</white> <dark_gray>·</dark_gray> <yellow>" + left + "s</yellow>"));
            }
        }
        if (core.hp <= 0) {
            failCore();
            return;
        }
        if (t >= nextSpawn) {
            nextSpawn = t + (long) (cfg.d("objectives.beacon-spawn-seconds") * 20 * 0.7);
            if (inst.mobCount() < cap() + 2) {
                int n = 2 + inst.groupSize() / 2;
                for (int i = 0; i < n; i++) {
                    Location l = inst.randomSpot(r);
                    if (l != null) {
                        org.bukkit.entity.LivingEntity m = inst.spawnObjectiveMob(l, false);
                        if (m instanceof org.bukkit.entity.Mob mob && core.ent.isValid()) {
                            org.bukkit.Bukkit.getScheduler().runTaskLater(inst.plugin(), () -> {
                                if (mob.isValid()) mob.getPathfinder().moveTo(core.at);
                            }, 5L);
                        }
                    }
                }
            }
        }
        inst.hint("Núcleo: " + (int) Math.max(0, core.hp) + " PV · " + Math.max(0, (coreEnd - t) / 20) + "s");
        if (t >= coreEnd) finish("<green><bold>¡NÚCLEO DEFENDIDO!</bold></green>", "<gray>Los enemigos restantes se desvanecen</gray>");
    }

    private void failCore() {
        if (finishing) return;
        if (core != null) {
            world.spawnParticle(Particle.EXPLOSION_EMITTER, core.at, 3, 1, 1, 1, 0);
            world.playSound(core.at, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.6f);
            for (Player p : inst.aliveOnline()) {
                if (p.getWorld().equals(world) && flat(p.getLocation(), core.at) <= 9) inst.hazardHit(p, 8.0 * inst.heat().dmgMul());
            }
        }
        finish("<red><bold>¡NÚCLEO DESTRUIDO!</bold></red>", "<gray>La explosión os hiere... pero la sala sigue</gray>");
    }

    // ------------------------------------------------------------------ runas en orden

    private void beginRunes(DungeonBuilder.Room r, List<int[]> pool) {
        int n = Math.min(4, pool.size());
        for (int i = 0; i < n; i++) {
            int[] a = pool.get(i);
            Rune ru = new Rune();
            ru.idx = i + 1;
            ru.at = new Location(world, a[0] + 0.5, r.floorY + 1, a[1] + 0.5);
            ru.pillar = world.spawn(ru.at, BlockDisplay.class, d -> {
                d.setBlock(Material.PURPLE_STAINED_GLASS.createBlockData());
                d.setPersistent(false);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(2.0f);
                d.setTransformationMatrix(new Matrix4f().translate(-0.3f, 0f, -0.3f).scale(0.6f, 4.5f, 0.6f));
            });
            ru.label = label(ru.at.clone().add(0, 5.0, 0), "<light_purple><bold>" + ru.idx + "</bold></light_purple>");
            runes.add(ru);
        }
        runeNext = 0;
        runeHold = 0;
        nextSpawn = 60;
        inst.titleAll("<light_purple><bold>RUNAS EN ORDEN</bold></light_purple>",
                "<gray>Actívalas en orden <white>1 → " + n + "</white>. Si fallas, se reinician</gray>");
    }

    private void resetRunes(String why) {
        runeNext = 0;
        runeHold = 0;
        for (Rune ru : runes) {
            ru.done = false;
            if (ru.pillar != null && ru.pillar.isValid()) ru.pillar.setBlock(Material.PURPLE_STAINED_GLASS.createBlockData());
        }
        inst.broadcast("<red>" + why + "</red> <gray>Las runas se reinician.</gray>");
        inst.soundAll(Sound.BLOCK_BEACON_DEACTIVATE, 1f, 0.8f);
    }

    private void tickRunes(DungeonBuilder.Room r) {
        if (runes.isEmpty()) {
            finish("<green><bold>¡SALA SUPERADA!</bold></green>", "");
            return;
        }
        double holdNeed = cfg.d("objectives.rune-hold-seconds") * 20;
        Rune target = runeNext < runes.size() ? runes.get(runeNext) : null;
        List<Player> alive = inst.aliveOnline();
        boolean onTarget = false;
        for (Rune ru : runes) {
            if (ru.done) continue;
            boolean stand = false;
            for (Player p : alive) if (p.getWorld().equals(world) && flat(p.getLocation(), ru.at) <= 2.0) stand = true;
            if (stand && ru != target) {
                // pisar una runa fuera de orden: castigo
                resetRunes("¡Orden equivocado!");
                for (int i = 0; i < 2 + inst.groupSize() / 2; i++) {
                    Location l = inst.randomSpot(r);
                    if (l != null) inst.spawnObjectiveMob(l, false);
                }
                return;
            }
            if (stand && ru == target) onTarget = true;
        }
        if (t % 10 == 0) {
            for (Rune ru : runes) {
                Color col = ru.done ? Color.fromRGB(80, 255, 120) : ru == target ? Color.fromRGB(255, 220, 80) : Color.fromRGB(190, 80, 255);
                Particle.DustOptions o = new Particle.DustOptions(col, 1.3f);
                for (int i = 0; i < 20; i++) {
                    double a = Math.PI * 2 * i / 20;
                    world.spawnParticle(Particle.DUST, ru.at.getX() + Math.cos(a) * 2.0, ru.at.getY() + 0.15, ru.at.getZ() + Math.sin(a) * 2.0, 1, 0, 0, 0, 0, o);
                }
            }
        }
        if (target != null) {
            if (onTarget) runeHold += 2;
            else runeHold = Math.max(0, runeHold - 3);
            if (t % 10 == 0 && onTarget) world.playSound(target.at, Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 0.6f + runeNext * 0.2f);
            if (runeHold >= holdNeed) {
                target.done = true;
                runeHold = 0;
                runeNext++;
                if (target.pillar != null && target.pillar.isValid()) target.pillar.setBlock(Material.LIME_STAINED_GLASS.createBlockData());
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, target.at.clone().add(0, 2, 0), 40, 0.6, 1.0, 0.6, 0.3);
                world.playSound(target.at, Sound.BLOCK_BEACON_POWER_SELECT, 1.2f, 0.8f + runeNext * 0.15f);
            }
        }
        if (t >= nextSpawn) {
            nextSpawn = t + (long) (cfg.d("objectives.beacon-spawn-seconds") * 20 * 0.9);
            if (inst.mobCount() < cap()) {
                int n = 2 + inst.groupSize() / 2;
                for (int i = 0; i < n; i++) {
                    Location l = inst.randomSpot(r);
                    if (l != null) inst.spawnObjectiveMob(l, false);
                }
            }
        }
        inst.hint("Runas: " + runeNext + "/" + runes.size() + " (en orden) · enemigos: " + inst.mobCount());
        if (runeNext >= runes.size()) finish("<green><bold>¡RUNAS ACTIVADAS!</bold></green>", "<gray>Los enemigos restantes se desvanecen</gray>");
    }

    // ------------------------------------------------------------------ campeón fugitivo

    private void beginChampion(DungeonBuilder.Room r, List<int[]> pool) {
        anchorsCache = new ArrayList<>(pool);
        Location l = inst.randomSpot(r);
        if (l == null) l = new Location(world, r.cx() + 0.5, r.floorY + 1, r.cz + 0.5);
        champion = inst.spawnObjectiveMob(l, true);
        if (champion != null) {
            champion.setGlowing(true);
            var hpAttr = champion.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (hpAttr != null) {
                double hp = Math.min(1000.0, hpAttr.getBaseValue() * cfg.d("objectives.champion-health-mul"));
                hpAttr.setBaseValue(hp);
                champion.setHealth(hp);
            }
        }
        nextBlink = 120;
        nextSpawn = 100;
        inst.titleAll("<red><bold>CAZA AL CAMPEÓN</bold></red>",
                "<gray>Un campeón brillante huye y llama refuerzos: <white>¡acábalo!</white></gray>");
    }

    private void tickChampion(DungeonBuilder.Room r) {
        if (champion == null || !champion.isValid() || champion.isDead()) {
            finish("<green><bold>¡CAMPEÓN ABATIDO!</bold></green>", "<gray>Los enemigos restantes se desvanecen</gray>");
            return;
        }
        if (t % 6 == 0) world.spawnParticle(Particle.END_ROD, champion.getLocation().add(0, 1.2, 0), 3, 0.3, 0.6, 0.3, 0.02);
        if (t >= nextBlink) {
            nextBlink = t + (long) (cfg.d("objectives.champion-blink-seconds") * 20);
            Location from = champion.getLocation();
            world.spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.8);
            world.playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.7f);
            Location to = null;
            if (!anchorsCache.isEmpty()) {
                int[] a = anchorsCache.get(ThreadLocalRandom.current().nextInt(anchorsCache.size()));
                to = new Location(world, a[0] + 0.5, r.floorY + 1, a[1] + 0.5);
            }
            if (to == null) to = inst.randomSpot(r);
            if (to != null) {
                champion.teleport(to);
                world.spawnParticle(Particle.PORTAL, to.clone().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.8);
                world.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 1.2f);
                for (int i = 0; i < 2 && inst.mobCount() < cap() + 2; i++) {
                    Location l = spotNear(to, r);
                    if (l != null) inst.spawnObjectiveMob(l, false);
                }
                inst.broadcast("<red>¡El campeón se ha teletransportado e invoca refuerzos!</red>");
            }
        }
        inst.hint("Caza al campeón brillante · enemigos: " + inst.mobCount());
    }

    // ------------------------------------------------------------------ final

    private void finish(String title, String sub) {
        finishing = true;
        finishAt = t + 40;
        inst.titleAll(title, sub);
        inst.soundAll(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.1f);
        inst.purgeWaveMobs();
    }
}
