package me.custommobs.dungeon;

import me.custommobs.CustomMobsPlugin;
import me.custommobs.Text;
import me.custommobs.boss.BossManager;
import me.custommobs.boss.VorthakBoss;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;

/** Una partida de la dungeon: construye la arena, lleva las salas, las vidas y el puntaje. */
public final class DungeonInstance {

    public enum State { BUILDING, RUNNING, COMPLETED, ENDING }

    private enum RoomState { LOCKED, READY, ACTIVE, CLEARED }

    static final class PState {
        String name;
        int lives;
        boolean eliminated;
        boolean completed;
        int deaths;
        int kills;
        double damage;
        Location returnLoc;
        GameMode prevMode = GameMode.SURVIVAL;
        long offlineSince = -1;
        final Map<Blessing, Integer> blessings = new EnumMap<>(Blessing.class);
        List<Blessing> offer;
        int bonusLives;
        int rerolls;

        int stacks(Blessing b) {
            return blessings.getOrDefault(b, 0);
        }
    }

    private static final class Tomb {
        UUID owner;
        String ownerName;
        Location loc;
        TextDisplay display;
        int progress;
        int medic;
    }

    // ------------------------------------------------------------------ campos

    private final CustomMobsPlugin plugin;
    private final DungeonManager manager;
    private final DungeonConfig cfg;
    private final BossManager bosses;
    private final int id;
    private final int slot;
    private final World world;
    private final DungeonBuilder builder;
    private final List<DungeonBuilder.Room> rooms;
    private final RoomState[] roomStates;
    private final Map<UUID, PState> members = new LinkedHashMap<>();
    private final Modifier modifier;
    private final Random rnd = new Random();
    private final int maxLives;
    private final int oz;
    private final int zBand;
    private final List<MiniBoss.Kind> miniOrder = new ArrayList<>(Arrays.asList(MiniBoss.Kind.values()));
    private final BossBar bar;

    private State state = State.BUILDING;
    private BukkitTask task;
    private long tick;
    private long runStart;
    /** Instante en que cayó Vorthak: congela el reloj de la run. */
    private long runEnd;
    private long nextAmbient;
    /** Botín personal de cada jugador (se recoge en el cofre de recompensas, nunca cae al suelo). */
    private final Map<UUID, List<ItemStack>> rewards = new LinkedHashMap<>();
    private final Map<UUID, Inventory> rewardInvs = new HashMap<>();
    private Location rewardChest;
    private long completedTick;
    private long emptySince = -1;
    private int active;
    private long startEnd;
    private long enterSince = -1;
    private String hint = "";
    private boolean wiping;
    private int restarts;
    private int revivesUsed;
    private int miniIdx;

    // sala en curso
    private final Set<UUID> mobs = new HashSet<>();
    private int wave;
    private int totalWaves;
    private int pendingSpawns;
    private long nextWaveTick;
    private long trapStart;
    private boolean[] trapOk = new boolean[3];
    private int[] trapProg = new int[3];
    private int trapDone;
    private long trapNext;
    // salas nuevas
    private double altarCharge;
    private int altarTotal;
    private long altarSpawn;
    private long altarHaz;
    private int altarMilestone;
    private boolean[] vaultOpen = new boolean[4];
    private int[] vaultProg = new int[4];
    private int vaultOpened;
    private boolean[] keyOk = new boolean[4];
    private int[] keyProg = new int[4];
    private int keysDone;
    private double sweepAng;
    private int sweepDir = 1;
    private long sweepFlip;
    private boolean sweepWarned;
    private long sweepHaz;
    private final Map<UUID, Long> sweepHit = new java.util.HashMap<>();
    private final BlessingSystem bless;
    private final RelicSystem relics;
    private final MerchantSystem merchant;
    private final EventSystem events;
    private final CombatFx fx;
    private final RoomAmbience ambience;
    private final Puzzles puzzles;
    // camino: cada paso es una sala o una elección entre dos (índices físicos de sala)
    private final List<int[]> steps = new ArrayList<>();
    private final int[] stepOfRoom;
    private int curStep;
    private int voteStep = -1;
    private int voteFrom;
    private long voteEnd;
    private final Map<UUID, Integer> votes = new LinkedHashMap<>();
    private long miniSpawnTick;
    private MiniBoss mini;
    private long restEnd;
    private long vorthakSpawnTick;
    private UUID vorthakId;
    private boolean vorthakDead;
    private long vorthakMissing;
    private long arenaNext;
    private int arenaPhase = -1;
    private int finalScore;
    private String finalRank = "";

    private final List<Tomb> tombs = new ArrayList<>();
    private final List<Hazard> hazards = new ArrayList<>();

    public DungeonInstance(CustomMobsPlugin plugin, DungeonManager manager, int id, int slot,
                           List<Player> players, Modifier modifier) {
        this.plugin = plugin;
        this.manager = manager;
        this.cfg = manager.cfg();
        this.bosses = manager.bosses();
        this.id = id;
        this.slot = slot;
        this.world = manager.world();
        this.modifier = modifier;
        this.oz = slot * cfg.i("instance-spacing");

        List<RoomType> types = new ArrayList<>();
        types.add(RoomType.START);
        steps.add(new int[]{0});
        for (List<RoomType> opt : cfg.steps()) {
            int[] idx = new int[opt.size()];
            for (int k = 0; k < opt.size(); k++) {
                idx[k] = types.size();
                types.add(opt.get(k));
            }
            steps.add(idx);
        }
        this.stepOfRoom = new int[types.size()];
        for (int st = 0; st < steps.size(); st++) {
            for (int ri : steps.get(st)) stepOfRoom[ri] = st;
        }
        this.builder = new DungeonBuilder(plugin, world, cfg, types, 16, cfg.i("build-y"), oz, System.nanoTime());
        this.rooms = builder.rooms();
        int maxHalf = 0;
        for (DungeonBuilder.Room r : rooms) maxHalf = Math.max(maxHalf, r.half());
        this.zBand = maxHalf + 6;
        this.roomStates = new RoomState[rooms.size()];
        Arrays.fill(roomStates, RoomState.LOCKED);
        Collections.shuffle(miniOrder);

        this.bless = new BlessingSystem(plugin, this, cfg, world, id);
        this.relics = new RelicSystem(plugin, this, cfg, world);
        this.merchant = new MerchantSystem(this, cfg, world);
        this.events = new EventSystem(this, cfg, world);
        this.fx = new CombatFx(plugin, cfg, world);
        this.ambience = new RoomAmbience(plugin, this, cfg, world);
        this.puzzles = new Puzzles(this);
        this.maxLives = cfg.i("players.lives") + (modifier == Modifier.BLESSED ? 1 : 0);
        for (Player p : players) {
            PState s = new PState();
            s.name = p.getName();
            s.lives = maxLives;
            s.rerolls = cfg.i("blessings.rerolls");
            s.returnLoc = manager.returnPoint(p);
            s.prevMode = p.getGameMode();
            members.put(p.getUniqueId(), s);
        }
        this.bar = BossBar.bossBar(Component.text("Dungeon"), 0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
    }

    // ------------------------------------------------------------------ acceso

    public int id() {
        return id;
    }

    public int slot() {
        return slot;
    }

    public State state() {
        return state;
    }

    public DungeonConfig cfg() {
        return cfg;
    }

    public CustomMobsPlugin plugin() {
        return plugin;
    }

    // acceso para Puzzles (mismo paquete)

    World world() {
        return world;
    }

    Random rnd() {
        return rnd;
    }

    void setHint(String h) {
        hint = h;
    }

    void puzzleCleared() {
        clearRoom(active);
    }

    /** Daño de zona de peligro (atraviesa armadura) a un jugador. */
    void hazardHit(Player p, double dmg) {
        p.damage(dmg, HAZARD_SRC);
    }

    /** Pone enemigos en cola para que aparezcan en la sala actual. */
    void queueMobs(int n) {
        pendingSpawns += Math.max(0, n);
    }

    void pumpMobs(DungeonBuilder.Room r) {
        if (pendingSpawns > 0) spawnPending(r);
        if (tick % 10 == 0) cleanMobs();
    }

    int liveMobs() {
        return mobs.size() + pendingSpawns;
    }

    /** Las salas sin oleadas no usan 'wave'; así los enemigos no reciben el bonus de última oleada. */
    void mobMode() {
        wave = 1;
        totalWaves = 99;
    }

    void killAllMobs() {
        killMobs();
    }

    void unregisterMob(Entity e) {
        manager.unindexMob(e.getUniqueId());
    }

    public Modifier modifier() {
        return modifier;
    }

    public long tick() {
        return tick;
    }

    public boolean isMember(UUID u) {
        return members.containsKey(u);
    }

    public int groupSize() {
        return Math.max(1, members.size());
    }

    /** Número de sala jugada (cuenta pasos, no salas físicas: una elección no suma dos). */
    public int roomOrdinal() {
        return Math.max(1, curStep);
    }

    public DungeonBuilder.Room currentRoom() {
        return rooms.get(Math.max(0, Math.min(active, rooms.size() - 1)));
    }

    public Location checkpoint() {
        DungeonBuilder.Room r = currentRoom();
        return active == 0 ? r.center(world) : r.entrance(world);
    }

    public long elapsedSeconds() {
        if (runStart == 0) return 0;
        long end = runEnd > 0 ? runEnd : System.currentTimeMillis();
        return Math.max(0, (end - runStart) / 1000);
    }

    public String describe() {
        return "#" + id + " " + state + " sala " + curStep + "/" + (steps.size() - 1) + " jugadores=" + members.size();
    }

    public List<String> memberNames() {
        List<String> out = new ArrayList<>();
        for (PState s : members.values()) out.add(s.name);
        return out;
    }

    // ------------------------------------------------------------------ utilidades

    public void later(int ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (state != State.ENDING) r.run();
        }, ticks);
    }

    public void repeat(int period, int times, IntConsumer step, Runnable done) {
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (state == State.ENDING) {
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

    public List<Player> onlineMembers() {
        List<Player> out = new ArrayList<>();
        for (UUID u : members.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) out.add(p);
        }
        return out;
    }

    /** Jugadores vivos (no eliminados), conectados y en el mundo de la dungeon. */
    public List<Player> aliveOnline() {
        List<Player> out = new ArrayList<>();
        for (Map.Entry<UUID, PState> en : members.entrySet()) {
            if (en.getValue().eliminated) continue;
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null && p.isOnline() && !p.isDead() && p.getWorld().equals(world)
                    && p.getGameMode() != GameMode.SPECTATOR) {
                out.add(p);
            }
        }
        return out;
    }

    public Player nearestAlive(Location loc) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : aliveOnline()) {
            double d = p.getLocation().distanceSquared(loc);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    public void broadcast(String mm) {
        Component c = Text.mm(DungeonManager.PREFIX + mm);
        for (Player p : onlineMembers()) p.sendMessage(c);
    }

    public void titleAll(String title, String subtitle) {
        Title t = Title.title(Text.mm(title), Text.mm(subtitle),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(600)));
        for (Player p : onlineMembers()) p.showTitle(t);
        eventSound(title);
    }

    /** Sonido de ambiente/aviso según el evento anunciado (se deduce del título para cubrir todas las salas). */
    private void eventSound(String title) {
        String s = title.toUpperCase(java.util.Locale.ROOT);
        float[] v = {1f, 1f};
        String key;
        if (s.contains("DERROTADO") || s.contains("SUPERADA") || s.contains("RELIQUIA PURA")) {
            key = "ui.toast.challenge_complete";
        } else if (s.contains("DERROTA")) {
            key = "entity.wither.death";
            v[1] = 0.7f;
        } else if (s.contains("OLEADA")) {
            key = "event.raid.horn";
            v[0] = 2.5f;
            v[1] = 1.3f;
        } else if (s.contains("MINI JEFE") || s.contains("SOMBRA") || s.contains("GUARDI") || s.contains("NIGROMANTE")) {
            key = "entity.wither.spawn";
            v[1] = 0.6f;
        } else if (s.contains("VORTHAK")) {
            key = "entity.ender_dragon.growl";
            v[1] = 0.55f;
        } else if (s.contains("TRAMPA") || s.contains("EMBOSCADA") || s.contains("MALDICI")) {
            key = "entity.warden.nearby_close";
        } else if (s.contains("CAMINO")) {
            key = "block.bell.use";
            v[1] = 0.8f;
        } else if (s.contains("ALTAR") || s.contains("RELIQUIAS") || s.contains("FARO")) {
            key = "block.beacon.activate";
            v[1] = 0.7f;
        } else if (s.contains("MERCADER")) {
            key = "block.amethyst_block.chime";
            v[1] = 0.6f;
        } else if (s.contains("EVENTO")) {
            key = "block.end_portal_frame.fill";
            v[1] = 0.7f;
        } else if (s.contains("DESCANSO")) {
            key = "block.respawn_anchor.charge";
            v[1] = 1.2f;
        } else if (s.trim().matches("<[^>]*>+\\s*<bold>\\s*\\d+.*") || s.matches(".*<BOLD>\\d+</BOLD>.*")) {
            key = "block.note_block.hat";   // cuenta atrás
            v[1] = 1.4f;
        } else {
            return;
        }
        for (Player p : onlineMembers()) {
            p.playSound(p.getLocation(), key, org.bukkit.SoundCategory.MASTER, v[0], v[1]);
        }
    }

    /** Ambiente de la dungeon: ecos lejanos, viento de almas y lava cada ~10-25 s. */
    private void ambientSounds() {
        if (tick < nextAmbient || !cfg.b("sounds.ambient")) return;
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        nextAmbient = tick + 200 + rnd.nextInt(300);
        String[] pool = {"ambient.cave", "ambient.soul_sand_valley.mood", "ambient.nether_wastes.additions", "ambient.basalt_deltas.additions",
                "block.lava.ambient", "entity.wither.ambient", "block.portal.ambient", "ambient.crimson_forest.additions"};
        String key = pool[rnd.nextInt(pool.length)];
        for (Player p : onlineMembers()) {
            Location l = p.getLocation().add(rnd.nextDouble(-14, 14), rnd.nextDouble(0, 6), rnd.nextDouble(-14, 14));
            float base = (float) cfg.d("sounds.ambient-volume");
            float vol = key.equals("entity.wither.ambient") ? base * 0.35f : base;
            p.playSound(l, key, org.bukkit.SoundCategory.AMBIENT, vol, 0.7f + (float) rnd.nextDouble(0.5));
        }
    }

    void soundAll(Sound s, float vol, float pitch) {
        for (Player p : onlineMembers()) p.playSound(p.getLocation(), s, vol, pitch);
    }

    void setBlock(int x, int y, int z, Material m) {
        world.getBlockAt(x, y, z).setType(m, false);
    }

    private static String clock(long secs) {
        return String.format("%02d:%02d", secs / 60, secs % 60);
    }

    // ------------------------------------------------------------------ marca de enemigos

    public void registerMob(LivingEntity e, boolean elite) {
        e.getPersistentDataContainer().set(manager.mobKey, PersistentDataType.STRING, String.valueOf(id));
        if (elite) e.getPersistentDataContainer().set(manager.eliteKey, PersistentDataType.BYTE, (byte) 1);
        manager.indexMob(e.getUniqueId(), this);
    }

    /** Punto aleatorio libre dentro de la sala (lejos de los jugadores si es posible). */
    public Location randomSpot(DungeonBuilder.Room r) {
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 14; i++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                double rad = r.half() * (0.3 + 0.55 * rnd.nextDouble());
                int x = (int) Math.floor(r.cx() + Math.cos(a) * rad);
                int z = (int) Math.floor(r.cz + Math.sin(a) * rad);
                Location l = new Location(world, x + 0.5, r.floorY + 1, z + 0.5);
                if (!l.getBlock().isPassable() || !l.clone().add(0, 1, 0).getBlock().isPassable()
                        || l.clone().add(0, -1, 0).getBlock().isPassable()) continue;
                if (pass == 0) {
                    boolean near = false;
                    for (Player p : aliveOnline()) {
                        if (p.getLocation().distanceSquared(l) < 25) {
                            near = true;
                            break;
                        }
                    }
                    if (near) continue;
                }
                return l;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ ciclo de vida

    public void begin() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick0, 2L, 2L);
        builder.build(this::onBuilt);
    }

    private void onBuilt() {
        if (state != State.BUILDING) return;
        state = State.RUNNING;
        runStart = System.currentTimeMillis();
        roomStates[0] = RoomState.ACTIVE;
        active = 0;
        startEnd = tick + cfg.i("players.start-countdown-seconds") * 20L;
        for (UUID u : new ArrayList<>(members.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) enter(p);
        }
    }

    private void enter(Player p) {
        PState s = members.get(p.getUniqueId());
        if (s == null) return;
        int idx = new ArrayList<>(members.keySet()).indexOf(p.getUniqueId());
        Location start = rooms.get(0).center(world).add((idx % 2 == 0 ? -1.5 : 1.5), 0, (idx / 2 - 0.5) * 2);
        start.setYaw(-90f);
        p.setGameMode(GameMode.ADVENTURE);
        p.teleport(start);
        AttributeInstance mh = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(mh == null ? 20.0 : mh.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.showBossBar(bar);
        p.sendMessage(Text.mm("<dark_gray>━━━━━━━━ <gradient:#ff4d4d:#ffb347><bold>DUNGEON DE VORTHAK</bold></gradient> <dark_gray>━━━━━━━━"));
        p.sendMessage(Text.mm("<gray>Vidas: <red>" + maxLives + " ❤</red>  <dark_gray>·</dark_gray>  Salas: <white>"
                + (steps.size() - 1) + "</white>  <dark_gray>·</dark_gray>  Equipo: <white>" + members.size() + "</white>"));
        p.sendMessage(Text.mm("<gray>Si un compañero cae sin vidas, mantén <white>SHIFT</white> sobre su tumba para revivirlo."));
        if (modifier != null) {
            p.sendMessage(Text.mm("<gray>Modificador del día: <light_purple><bold>" + modifier.title + "</bold></light_purple> <dark_gray>—</dark_gray> "
                    + modifier.description));
        }
    }

    public void rejoin(Player p) {
        PState s = members.get(p.getUniqueId());
        if (s == null) return;
        s.offlineSince = -1;
        if (state == State.BUILDING) return;
        p.setGameMode(s.eliminated ? GameMode.SPECTATOR : GameMode.ADVENTURE);
        p.teleport(respawnLocation(p));
        p.showBossBar(bar);
        if (mini != null) p.showBossBar(mini.bar());
        p.sendMessage(Text.mm(DungeonManager.PREFIX + "<green>Has vuelto a la dungeon.</green>"));
    }

    public void playerQuit(Player p) {
        PState s = members.get(p.getUniqueId());
        if (s != null) s.offlineSince = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ bucle

    private void tick0() {
        tick += 2;
        if (state == State.ENDING) return;
        if (tick % 20 == 0) offlineCheck();
        if (state == State.ENDING) return;
        if (state == State.BUILDING) {
            if (tick % 10 == 0) {
                int pct = (int) Math.round(builder.progress() * 100);
                for (Player p : onlineMembers()) {
                    p.sendActionBar(Text.mm("<gray>⚒ Preparando la dungeon... <white>" + pct + "%</white>"));
                }
            }
            return;
        }
        updateTombs();
        hazardTick();
        bless.tick(tick);
        relics.tick(tick);
        ambience.tick(tick);
        if (tick % 40 == 0) keepSpectators();
        if (state == State.RUNNING) {
            ambientSounds();
            runRooms();
            if (mini != null) mini.tick();
        } else if (state == State.COMPLETED) {
            completedTick();
        }
        if (tick % 20 == 0) hud();
    }

    private void offlineCheck() {
        long now = System.currentTimeMillis();
        long grace = cfg.i("players.reconnect-seconds") * 1000L;
        for (UUID u : new ArrayList<>(members.keySet())) {
            PState s = members.get(u);
            if (s == null) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                s.offlineSince = -1;
                continue;
            }
            if (s.offlineSince < 0) s.offlineSince = now;
            else if (now - s.offlineSince > grace) abandon(u);
        }
        if (members.isEmpty()) {
            close();
            return;
        }
        if (onlineMembers().isEmpty()) {
            if (emptySince < 0) emptySince = now;
            else if (now - emptySince > grace) close();
        } else {
            emptySince = -1;
        }
    }

    /** Un jugador desconectado no volvió a tiempo: se le saca de la partida. */
    private void abandon(UUID u) {
        PState s = members.remove(u);
        if (s == null) return;
        manager.unmapPlayer(u);
        manager.addPending(u, s.returnLoc, s.prevMode);
        manager.applyCooldown(u, cfg.i("cooldown.fail-minutes"));
        removeTombOf(u);
        broadcast("<gray>" + s.name + " abandonó la dungeon.</gray>");
        checkWipe();
    }

    // ------------------------------------------------------------------ salas

    private void runRooms() {
        if (roomStates[0] == RoomState.ACTIVE) {
            long left = startEnd - tick;
            if (left <= 0) {
                titleAll("<gradient:#ff4d4d:#ffb347><bold>¡ADELANTE!</bold></gradient>", "<gray>Las puertas se abren</gray>");
                soundAll(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1.2f);
                clearRoom(0);
            } else if (left % 20 == 0) {
                long sec = left / 20;
                hint = "Comienza en " + sec + "s";
                if (sec <= 5) {
                    titleAll("<yellow><bold>" + sec + "</bold></yellow>", "<gray>Prepárate...</gray>");
                    soundAll(Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f + (5 - sec) * 0.1f);
                }
            }
            return;
        }
        checkEntry();
        voteTick();
        if (active <= 0 || roomStates[active] != RoomState.ACTIVE) return;
        DungeonBuilder.Room r = rooms.get(active);
        switch (r.type) {
            case WAVES -> wavesTick(r);
            case TRAP -> trapTick(r);
            case PUZZLE -> puzzles.tick(r);
            case ALTAR -> altarTick(r);
            case VAULT -> vaultTick(r);
            case MERCHANT -> merchantTick(r);
            case EVENT -> eventTick(r);
            case SWEEP -> sweepTick(r);
            case MINIBOSS -> miniTick(r);
            case REST -> restTick(r);
            case VORTHAK -> vorthakTick(r);
            default -> { }
        }
    }

    /** Detecta cuándo el grupo entra a la siguiente sala y la activa. */
    private void checkEntry() {
        for (int i = 1; i < rooms.size(); i++) {
            if (roomStates[i] != RoomState.READY) continue;
            DungeonBuilder.Room r = rooms.get(i);
            List<Player> alive = aliveOnline();
            if (alive.isEmpty()) return;
            int inside = 0;
            for (Player p : alive) if (r.contains(p.getLocation())) inside++;
            if (inside == 0) {
                enterSince = -1;
                return;
            }
            if (enterSince < 0) enterSince = tick;
            if (inside == alive.size() || tick - enterSince >= 200) {
                for (Player p : alive) {
                    if (!r.contains(p.getLocation())) p.teleport(r.entrance(world));
                }
                enterSince = -1;
                activateRoom(i);
            }
            return;
        }
    }

    private void activateRoom(int i) {
        roomStates[i] = RoomState.ACTIVE;
        active = i;
        curStep = stepOfRoom[i];
        DungeonBuilder.Room r = rooms.get(i);
        setGate(i, false, false);
        mobs.clear();
        pendingSpawns = 0;
        wave = 0;
        nextWaveTick = 0;
        hint = "";
        soundAll(Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.5f);
        autoPickPending();
        ambience.enter(r);
        switch (r.type) {
            case WAVES -> {
                totalWaves = cfg.i("waves.count");
                nextWaveTick = tick + 50;
                titleAll("<gold><bold>SALA " + curStep + "</bold></gold>", "<gray>Sobrevive a las oleadas</gray>");
            }
            case TRAP -> {
                trapStart = tick;
                trapOk = new boolean[r.pads.size()];
                trapProg = new int[r.pads.size()];
                trapDone = 0;
                trapNext = tick + 60;
                for (int k = 0; k < r.pads.size(); k++) setTrapPad(r, k, false);
                titleAll("<red><bold>SALA TRAMPA</bold></red>",
                        "<gray>Activa las <white>" + r.pads.size() + " balizas</white> quedándote encima. ¡El suelo ataca!</gray>");
            }
            case PUZZLE -> puzzles.start(r);
            case ALTAR -> startAltar(r);
            case VAULT -> startVault(r);
            case MERCHANT -> {
                titleAll("<dark_aqua><bold>MERCADER FANTASMA</bold></dark_aqua>",
                        "<gray>Paga con almas... o con sangre</gray>");
                merchant.start(r, tick);
            }
            case EVENT -> {
                titleAll("<light_purple><bold>EVENTO</bold></light_purple>", "<gray>Cada uno elige su destino</gray>");
                wave = 1;
                totalWaves = 99;
                events.start(r, tick);
            }
            case SWEEP -> startSweep(r);
            case MINIBOSS -> {
                miniSpawnTick = tick + 60;
                titleAll("<dark_red><bold>MINI JEFE</bold></dark_red>", "<gray>Algo despierta en la sala...</gray>");
            }
            case REST -> startRest(r);
            case VORTHAK -> {
                vorthakSpawnTick = tick + 80;
                titleAll("<gradient:#ff4d4d:#ffb347><bold>VORTHAK</bold></gradient>", "<gray>No hay vuelta atrás.</gray>");
                soundAll(Sound.ENTITY_WITHER_SPAWN, 0.8f, 0.6f);
            }
            default -> { }
        }
    }

    private void clearRoom(int i) {
        roomStates[i] = RoomState.CLEARED;
        mobs.clear();
        pendingSpawns = 0;
        hint = "";
        ambience.clear();
        DungeonBuilder.Room r = rooms.get(i);
        if (r.type == RoomType.VORTHAK) return;
        if (i > 0) {
            titleAll("<green><bold>SALA SUPERADA</bold></green>", "<gray>Las puertas se abren</gray>");
            soundAll(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
            setGate(i, true, false);
            boolean miniReward = r.type == RoomType.MINIBOSS && cfg.b("blessings.enabled")
                    && cfg.b("blessings.miniboss-reward");
            if (blessingsDue(i) || miniReward) offerBlessings(r.type == RoomType.MINIBOSS);
            if (relicsDue(i)) relics.offer();
        }
        advanceFrom(i);
    }

    // ------------------------------------------------------------------ camino (elección entre salas)

    /** Prepara lo que viene tras limpiar la sala 'i': la siguiente sala o una votación entre dos. */
    private void advanceFrom(int i) {
        int next = stepOfRoom[i] + 1;
        if (next >= steps.size()) return;
        int[] opts = steps.get(next);
        if (opts.length == 1) openRoom(i, opts[0]);
        else startVote(i, next, opts);
    }

    /** Abre la sala 'to' tras limpiar 'from': a pie si es la contigua, o con un portal si se saltó alguna. */
    private void openRoom(int from, int to) {
        roomStates[to] = RoomState.READY;
        setGate(to, true, false);
        if (to != from + 1) portal(to);
    }

    private void portal(int to) {
        DungeonBuilder.Room r = rooms.get(to);
        titleAll("<light_purple><bold>EL CAMINO SE ABRE</bold></light_purple>", "<gray>" + r.type.title + "</gray>");
        soundAll(Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.6f);
        for (Player p : aliveOnline()) {
            world.spawnParticle(Particle.REVERSE_PORTAL, p.getLocation().add(0, 1, 0), 60, 0.5, 1, 0.5, 0.3);
        }
        later(40, () -> {
            if (state != State.RUNNING || roomStates[to] != RoomState.READY) return;
            for (Player p : aliveOnline()) {
                p.teleport(r.entrance(world));
                world.spawnParticle(Particle.REVERSE_PORTAL, p.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.2);
            }
            soundAll(Sound.ENTITY_ENDERMAN_TELEPORT, 0.9f, 0.7f);
        });
    }

    private void startVote(int from, int step, int[] opts) {
        voteStep = step;
        voteFrom = from;
        votes.clear();
        voteEnd = tick + Math.max(8, cfg.i("path.vote-seconds")) * 20L;
        titleAll("<gold><bold>ELIGE EL CAMINO</bold></gold>", "<gray>Votad en el chat: gana la mayoría</gray>");
        soundAll(Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.3f);
        for (Player p : onlineMembers()) {
            p.sendMessage(Text.mm("<dark_gray>━━━━━━━━ <gradient:#ffd86b:#ff9a3c><bold>ELIGE EL CAMINO</bold></gradient> <dark_gray>━━━━━━━━"));
            for (int k = 0; k < opts.length; k++) {
                RoomType t = rooms.get(opts[k]).type;
                p.sendMessage(Text.mm("<green><bold>[" + (k + 1) + "] " + t.title + "</bold></green>")
                        .clickEvent(ClickEvent.runCommand("/dungeon path " + (k + 1)))
                        .append(Text.mm(" <gray>" + t.blurb + "</gray>")));
            }
            p.sendMessage(Text.mm("<gray>Haz clic en una opción o usa <white>/dungeon path <número></white>.</gray>"));
        }
    }

    /** Voto de un jugador. @return mensaje de error, o null si se registró. */
    public String vote(Player p, int option) {
        if (voteStep < 0) return "No hay ninguna votación de camino ahora mismo.";
        PState s = members.get(p.getUniqueId());
        if (s == null || s.eliminated) return "Solo los jugadores vivos pueden votar.";
        int[] opts = steps.get(voteStep);
        if (option < 1 || option > opts.length) return "Opción inválida (1-" + opts.length + ").";
        votes.put(p.getUniqueId(), option - 1);
        broadcast("<white>" + s.name + "</white> <gray>vota por</gray> <green>" + rooms.get(opts[option - 1]).type.title
                + "</green> <dark_gray>(" + votes.size() + "/" + Math.max(1, aliveOnline().size()) + ")</dark_gray>");
        return null;
    }

    private void voteTick() {
        if (voteStep < 0) return;
        if (state != State.RUNNING) return;
        votes.keySet().removeIf(u -> {
            PState s = members.get(u);
            return s == null || s.eliminated;
        });
        int[] opts = steps.get(voteStep);
        int[] count = new int[opts.length];
        for (int v : votes.values()) count[v]++;
        List<Player> al = aliveOnline();
        boolean allIn = !al.isEmpty() && votes.size() >= al.size();
        boolean pending = bless.hasPending() || relics.hasPending();
        boolean timeUp = tick >= voteEnd;
        StringBuilder sb = new StringBuilder("Camino: ");
        for (int k = 0; k < opts.length; k++) {
            sb.append(rooms.get(opts[k]).type.title).append(" ").append(count[k]).append("  ");
        }
        sb.append(timeUp ? "· elige tu bendición" : "· " + Math.max(0, (voteEnd - tick) / 20) + "s");
        hint = sb.toString();
        if ((allIn && !pending) || (timeUp && (!pending || tick >= voteEnd + 300))) resolveVote(opts, count);
    }

    private void resolveVote(int[] opts, int[] count) {
        int max = -1;
        List<Integer> best = new ArrayList<>();
        for (int k = 0; k < count.length; k++) {
            if (count[k] > max) {
                max = count[k];
                best.clear();
                best.add(k);
            } else if (count[k] == max) {
                best.add(k);
            }
        }
        int pick = best.get(rnd.nextInt(best.size()));
        int to = opts[pick];
        int from = voteFrom;
        voteStep = -1;
        votes.clear();
        hint = "";
        broadcast("<gold>Camino elegido:</gold> <white>" + rooms.get(to).type.title + "</white>");
        openRoom(from, to);
    }

    private void setGate(int i, boolean open, boolean quiet) {
        if (i <= 0 || i >= rooms.size()) return;
        DungeonBuilder.Room r = rooms.get(i);
        for (int[] b : r.gateBlocks()) {
            Block bl = world.getBlockAt(b[0], b[1], b[2]);
            if (open) {
                if (!quiet) world.spawnParticle(Particle.SMOKE, b[0] + 0.5, b[1] + 0.5, b[2] + 0.5, 4, 0.3, 0.3, 0.3, 0.01);
                bl.setType(Material.AIR, false);
            } else {
                bl.setType(Material.IRON_BARS, true);
            }
        }
        if (!quiet) {
            world.playSound(new Location(world, r.westWall + 0.5, r.floorY + 2, r.cz + 0.5),
                    open ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_IRON_DOOR_CLOSE, 2f, 0.6f);
            Location gl = new Location(world, r.westWall + 0.5, r.floorY + 2, r.cz + 0.5);
            world.playSound(gl, open ? "block.chain.break" : "block.chain.place", 1.6f, 0.6f);
            world.playSound(gl, open ? "block.piston.extend" : "block.anvil.land", 1.0f, open ? 0.5f : 0.7f);
        }
    }

    // ------------------------------------------------------------------ oleadas

    private void wavesTick(DungeonBuilder.Room r) {
        if (nextWaveTick > 0 && tick >= nextWaveTick) {
            nextWaveTick = 0;
            startWave();
        }
        if (pendingSpawns > 0) spawnPending(r);
        if (tick % 10 != 0) return;
        mobs.removeIf(u -> {
            Entity e = Bukkit.getEntity(u);
            return e == null || !e.isValid() || e.isDead();
        });
        hint = "Oleada " + Math.max(1, wave) + "/" + totalWaves + " · enemigos: " + (mobs.size() + pendingSpawns);
        if (nextWaveTick == 0 && pendingSpawns == 0 && mobs.isEmpty() && wave > 0) {
            if (wave < totalWaves) {
                nextWaveTick = tick + cfg.i("waves.delay-seconds") * 20L;
                broadcast("<green>Oleada " + wave + " superada.</green> <gray>Prepárate...</gray>");
                soundAll(Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            } else {
                clearRoom(active);
            }
        }
    }

    private void startWave() {
        wave++;
        int n = cfg.i("waves.base-mobs") + cfg.i("waves.extra-per-wave") * (wave - 1)
                + cfg.i("waves.extra-per-player") * (groupSize() - 1)
                + cfg.i("waves.extra-per-room") * (roomOrdinal() - 1);
        if (modifier == Modifier.HORDE) n = (int) Math.ceil(n * 1.4);
        pendingSpawns = Math.min(n, cfg.i("waves.max-alive"));
        titleAll("<gold><bold>OLEADA " + wave + "/" + totalWaves + "</bold></gold>",
                wave == totalWaves ? "<red>¡Última oleada!</red>" : "<gray>" + pendingSpawns + " enemigos</gray>");
        soundAll(Sound.ENTITY_WITHER_AMBIENT, 0.7f, 1.2f);
    }

    private void spawnPending(DungeonBuilder.Room r) {
        int ordinal = roomOrdinal();
        int tier = Math.min(3, WaveMobs.tierFor(ordinal) + (wave == totalWaves ? 1 : 0));
        double hpMul = (1 + cfg.d("scaling.health-per-extra-player") * (groupSize() - 1))
                * (1 + cfg.d("scaling.health-per-room") * (ordinal - 1));
        double dmgMul = 1 + cfg.d("scaling.damage-per-room") * (ordinal - 1);
        double eliteChance = cfg.d("waves.elite-chance") * (modifier == Modifier.ELITE ? 4 : 1);
        int n = Math.min(pendingSpawns, 3);
        for (int i = 0; i < n; i++) {
            Location l = randomSpot(r);
            if (l == null) l = r.center(world);
            boolean elite = rnd.nextDouble() < eliteChance;
            LivingEntity e = WaveMobs.spawn(this, l, tier, elite, hpMul, dmgMul);
            mobs.add(e.getUniqueId());
            pendingSpawns--;
        }
    }

    // ------------------------------------------------------------------ zonas de peligro

    private enum HShape { CIRCLE, DONUT, RECT, CHECKER }

    private static final class Hazard {
        HShape shape = HShape.CIRCLE;
        double x, z;                      // centro (círculo / donut / rectángulo)
        double y;                         // altura a la que se camina
        double radius, inner;             // radio exterior e interior (donut)
        double minX, maxX, minZ, maxZ;    // cajón envolvente (todas las formas)
        double cell;                      // tamaño de casilla (tablero)
        int parity;                       // qué casillas del tablero atacan (0 o 1)
        long start, fireTick, removeTick;
        double damage;
        boolean fired;
        final List<Block> spikes = new ArrayList<>();

        boolean contains(double px, double pz) {
            double dx = px - x;
            double dz = pz - z;
            double d2 = dx * dx + dz * dz;
            return switch (shape) {
                case CIRCLE -> d2 <= radius * radius;
                case DONUT -> d2 <= radius * radius && d2 >= inner * inner;
                case RECT -> px >= minX && px <= maxX && pz >= minZ && pz <= maxZ;
                case CHECKER -> px >= minX && px <= maxX && pz >= minZ && pz <= maxZ
                        && ((((int) Math.floor((px - minX) / cell)) + ((int) Math.floor((pz - minZ) / cell))) & 1) == parity;
            };
        }
    }

    private static final Particle.DustOptions HAZARD_DUST = new Particle.DustOptions(Color.fromRGB(255, 40, 40), 1.4f);
    /** Daño mágico: ignora la armadura (solo lo reducen Protección, Resistencia y bendiciones). */
    private static final DamageSource HAZARD_SRC = DamageSource.builder(DamageType.MAGIC).build();

    private Hazard newHazard(HShape shape, int delay, double damage) {
        Hazard h = new Hazard();
        h.shape = shape;
        h.y = currentRoom().floorY + 1;
        h.start = tick;
        h.fireTick = tick + Math.max(4, delay);
        h.damage = damage;
        return h;
    }

    private void addHazard(Hazard h) {
        if (hazards.size() < 150) hazards.add(h);
    }

    /** Círculo telegrafiado: anillo rojo y, tras el retraso, pinchos. */
    public void hazard(Location loc, int delay, double radius, double damage) {
        Hazard h = newHazard(HShape.CIRCLE, delay, damage);
        h.x = loc.getX();
        h.z = loc.getZ();
        h.radius = radius;
        h.minX = h.x - radius;
        h.maxX = h.x + radius;
        h.minZ = h.z - radius;
        h.maxZ = h.z + radius;
        addHazard(h);
    }

    /** Donut: es seguro estar dentro del radio interior y fuera del exterior. */
    public void hazardDonut(Location loc, int delay, double inner, double outer, double damage) {
        Hazard h = newHazard(HShape.DONUT, delay, damage);
        h.x = loc.getX();
        h.z = loc.getZ();
        h.inner = inner;
        h.radius = outer;
        h.minX = h.x - outer;
        h.maxX = h.x + outer;
        h.minZ = h.z - outer;
        h.maxZ = h.z + outer;
        addHazard(h);
    }

    /** Rectángulo alineado con los ejes (líneas, muros...). Coordenadas continuas. */
    public void hazardRect(double minX, double maxX, double minZ, double maxZ, int delay, double damage) {
        Hazard h = newHazard(HShape.RECT, delay, damage);
        h.minX = minX;
        h.maxX = maxX;
        h.minZ = minZ;
        h.maxZ = maxZ;
        h.x = (minX + maxX) / 2;
        h.z = (minZ + maxZ) / 2;
        addHazard(h);
    }

    /** Tablero: la mitad de las casillas (según la paridad) atacan, la otra mitad es segura. */
    public void hazardChecker(double minX, double maxX, double minZ, double maxZ, double cell, int parity,
                              int delay, double damage) {
        Hazard h = newHazard(HShape.CHECKER, delay, damage);
        h.minX = minX;
        h.maxX = maxX;
        h.minZ = minZ;
        h.maxZ = maxZ;
        h.x = (minX + maxX) / 2;
        h.z = (minZ + maxZ) / 2;
        h.cell = Math.max(1.0, cell);
        h.parity = parity & 1;
        addHazard(h);
    }

    private void hazardTick() {
        if (hazards.isEmpty()) return;
        Iterator<Hazard> it = hazards.iterator();
        while (it.hasNext()) {
            Hazard h = it.next();
            if (!h.fired) {
                if (tick >= h.fireTick) {
                    fireHazard(h);
                } else if (tick % 4 == 0) {
                    telegraph(h);
                }
            } else if (tick >= h.removeTick) {
                for (Block b : h.spikes) {
                    if (b.getType() == Material.POINTED_DRIPSTONE) b.setType(Material.AIR, false);
                }
                it.remove();
            }
        }
    }

    private void dust(double x, double y, double z) {
        world.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, HAZARD_DUST);
    }

    private void ringDust(double cx, double cz, double r, double y, int points) {
        if (r < 0.2) return;
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            dust(cx + Math.cos(a) * r, y, cz + Math.sin(a) * r);
        }
    }

    private void outlineDust(Hazard h, double y) {
        double step = Math.max(1.0, ((h.maxX - h.minX) + (h.maxZ - h.minZ)) / 36.0);
        for (double px = h.minX; px <= h.maxX; px += step) {
            dust(px, y, h.minZ);
            dust(px, y, h.maxZ);
        }
        for (double pz = h.minZ; pz <= h.maxZ; pz += step) {
            dust(h.minX, y, pz);
            dust(h.maxX, y, pz);
        }
    }

    private void fillDust(Hazard h, double y, int count) {
        for (int i = 0, tries = 0; i < count && tries < count * 4; tries++) {
            double px = h.minX + rnd.nextDouble() * (h.maxX - h.minX);
            double pz = h.minZ + rnd.nextDouble() * (h.maxZ - h.minZ);
            if (!h.contains(px, pz)) continue;
            dust(px, y, pz);
            i++;
        }
    }

    private void telegraph(Hazard h) {
        double prog = (double) (tick - h.start) / Math.max(1, h.fireTick - h.start);
        double y = h.y + 0.1;
        switch (h.shape) {
            case CIRCLE -> {
                ringDust(h.x, h.z, h.radius, y, 20);
                ringDust(h.x, h.z, h.radius * prog, y, 20);
            }
            case DONUT -> {
                ringDust(h.x, h.z, h.radius, y, 44);
                ringDust(h.x, h.z, h.inner, y, 24);
                fillDust(h, y, (int) (10 + 24 * prog));
            }
            case RECT -> {
                outlineDust(h, y);
                fillDust(h, y, (int) (8 + 26 * prog));
            }
            case CHECKER -> fillDust(h, y, (int) (30 + 30 * prog));
        }
    }

    /** Casillas (x, z) donde aparecerán pinchos. */
    private List<int[]> spikeCells(Hazard h) {
        List<int[]> out = new ArrayList<>();
        if (h.shape == HShape.CIRCLE) {
            int bx = (int) Math.floor(h.x);
            int bz = (int) Math.floor(h.z);
            int[][] offs = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] o : offs) out.add(new int[]{bx + o[0], bz + o[1]});
            return out;
        }
        for (int x = (int) Math.floor(h.minX); x <= (int) Math.floor(h.maxX); x += 2) {
            for (int z = (int) Math.floor(h.minZ); z <= (int) Math.floor(h.maxZ); z += 2) {
                if (h.contains(x + 0.5, z + 0.5)) out.add(new int[]{x, z});
            }
        }
        if (out.size() > 60) {
            Collections.shuffle(out, rnd);
            out = new ArrayList<>(out.subList(0, 60));
        }
        return out;
    }

    private void fireHazard(Hazard h) {
        h.fired = true;
        h.removeTick = tick + 14;
        double cx = (h.minX + h.maxX) / 2;
        double cz = (h.minZ + h.maxZ) / 2;
        List<int[]> cells = spikeCells(h);
        if (h.shape == HShape.CIRCLE) {
            world.spawnParticle(Particle.CRIT, cx, h.y + 0.5, cz, 30, h.radius * 0.5, 0.3, h.radius * 0.5, 0.3);
            world.spawnParticle(Particle.SMOKE, cx, h.y + 0.3, cz, 14, h.radius * 0.5, 0.2, h.radius * 0.5, 0.02);
            Location at = new Location(world, cx, h.y, cz);
            world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.5f);
            world.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.8f, 1.8f);
        } else {
            for (int[] c : cells) {
                world.spawnParticle(Particle.CRIT, c[0] + 0.5, h.y + 0.4, c[1] + 0.5, 3, 0.3, 0.2, 0.3, 0.2);
                world.spawnParticle(Particle.SMOKE, c[0] + 0.5, h.y + 0.3, c[1] + 0.5, 2, 0.3, 0.1, 0.3, 0.02);
            }
            for (Player p : onlineMembers()) {
                p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.3f);
                p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1.6f);
            }
        }
        for (Player p : aliveOnline()) {
            Location pl = p.getLocation();
            if (Math.abs(pl.getY() - h.y) < 2.5 && h.contains(pl.getX(), pl.getZ())) {
                p.damage(h.damage, HAZARD_SRC);
                p.setVelocity(new Vector(0, 0.55, 0));
            }
        }
        for (int[] c : cells) {
            Block b = world.getBlockAt(c[0], (int) h.y, c[1]);
            if (b.getType() == Material.AIR) {
                b.setType(Material.POINTED_DRIPSTONE, false);
                h.spikes.add(b);
            }
        }
    }

    /** ¿Hay algún jugador encima de la placa? (reach = mitad del lado + margen). */
    private boolean onPad(List<Player> al, int[] pd, DungeonBuilder.Room r, double reach) {
        for (Player p : al) {
            Location l = p.getLocation();
            if (Math.abs(l.getX() - (pd[0] + 0.5)) <= reach && Math.abs(l.getZ() - (pd[1] + 0.5)) <= reach
                    && l.getY() <= r.floorY + 2.5) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ trampa

    private enum TrapPattern { CIRCLES, LINES, GAP, DONUT, CHECKER }

    private void setTrapPad(DungeonBuilder.Room r, int k, boolean done) {
        int[] pd = r.pads.get(k);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Material m = done ? Material.SEA_LANTERN
                        : (dx == 0 && dz == 0 ? Material.CRYING_OBSIDIAN : Material.POLISHED_BLACKSTONE);
                setBlock(pd[0] + dx, r.floorY, pd[1] + dz, m);
            }
        }
    }

    private void trapTick(DungeonBuilder.Room r) {
        List<Player> al = aliveOnline();
        int need = Math.max(20, cfg.i("trap.activate-seconds") * 20);
        for (int k = 0; k < r.pads.size(); k++) {
            if (trapOk[k]) continue;
            int[] pd = r.pads.get(k);
            boolean on = onPad(al, pd, r, 1.7);
            trapProg[k] = on ? Math.min(need, trapProg[k] + 2) : Math.max(0, trapProg[k] - 3);
            if (tick % 4 == 0) {
                double frac = trapProg[k] / (double) need;
                int pts = (int) Math.round(frac * 24);
                for (int i = 0; i < pts; i++) {
                    double a = Math.PI * 2 * i / 24;
                    world.spawnParticle(Particle.END_ROD, pd[0] + 0.5 + Math.cos(a) * 1.6, r.floorY + 1.2,
                            pd[1] + 0.5 + Math.sin(a) * 1.6, 1, 0, 0, 0, 0);
                }
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, pd[0] + 0.5, r.floorY + 1.2 + frac * 2, pd[1] + 0.5,
                        2, 0.2, 0.3, 0.2, 0.01);
            }
            if (trapProg[k] >= need) {
                trapOk[k] = true;
                trapDone++;
                setTrapPad(r, k, true);
                world.playSound(new Location(world, pd[0] + 0.5, r.floorY + 1, pd[1] + 0.5),
                        Sound.BLOCK_BEACON_ACTIVATE, 1.4f, 1.2f);
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, pd[0] + 0.5, r.floorY + 1.5, pd[1] + 0.5,
                        30, 0.5, 1, 0.5, 0.2);
                if (trapDone < r.pads.size()) {
                    broadcast("<aqua>Baliza activada</aqua> <gray>(" + trapDone + "/" + r.pads.size()
                            + ") — el suelo se vuelve más agresivo...</gray>");
                }
            }
        }
        if (trapDone >= r.pads.size()) {
            clearRoom(active);
            return;
        }
        if (tick >= trapNext) {
            trapPattern(r, al);
            long elapsed = tick - trapStart;
            int gap = 74 - trapDone * 14 - (int) Math.min(24, elapsed / 60);
            trapNext = tick + Math.max(30, gap);
        }
        hint = "Balizas: " + trapDone + "/" + r.pads.size();
    }

    private boolean heavyPending() {
        for (Hazard h : hazards) {
            if (!h.fired && h.shape != HShape.CIRCLE) return true;
        }
        return false;
    }

    /** Baliza en progreso (para atacarla y obligar a esquivar). */
    private int[] progressingPad(DungeonBuilder.Room r) {
        List<int[]> list = new ArrayList<>();
        for (int k = 0; k < r.pads.size(); k++) {
            if (!trapOk[k] && trapProg[k] > 0) list.add(r.pads.get(k));
        }
        return list.isEmpty() ? null : list.get(rnd.nextInt(list.size()));
    }

    private void trapPattern(DungeonBuilder.Room r, List<Player> al) {
        int roll = rnd.nextInt(100);
        TrapPattern p;
        if (trapDone == 0) {
            p = roll < 75 ? TrapPattern.CIRCLES : TrapPattern.LINES;
        } else if (trapDone == 1) {
            p = roll < 30 ? TrapPattern.CIRCLES : roll < 55 ? TrapPattern.LINES
                    : roll < 80 ? TrapPattern.GAP : TrapPattern.DONUT;
        } else {
            p = roll < 15 ? TrapPattern.CIRCLES : roll < 35 ? TrapPattern.LINES : roll < 55 ? TrapPattern.GAP
                    : roll < 78 ? TrapPattern.DONUT : TrapPattern.CHECKER;
        }
        // nunca dos patrones grandes a la vez
        if (p != TrapPattern.CIRCLES && heavyPending()) p = TrapPattern.CIRCLES;

        int tele = cfg.i("trap.telegraph-ticks");
        double dmg = cfg.d("trap.hazard-damage");
        switch (p) {
            case CIRCLES -> {
                int n = 2 + al.size() + trapDone;
                for (int i = 0; i < n; i++) {
                    Location l = null;
                    int c = rnd.nextInt(100);
                    if (c < 30) {
                        int[] pd = progressingPad(r);
                        if (pd != null) l = new Location(world, pd[0] + 0.5, r.floorY + 1, pd[1] + 0.5);
                    }
                    if (l == null && !al.isEmpty() && c < 80) l = al.get(rnd.nextInt(al.size())).getLocation();
                    if (l == null) l = randomSpot(r);
                    if (l == null) continue;
                    hazard(l, tele, 2.2, dmg);
                }
            }
            case LINES -> {
                boolean alongZ = rnd.nextBoolean();
                int count = 2 + trapDone;
                for (int i = 0; i < count; i++) {
                    double c;
                    if (!al.isEmpty()) {
                        Location pl = al.get(rnd.nextInt(al.size())).getLocation();
                        c = alongZ ? pl.getX() : pl.getZ();
                    } else {
                        c = alongZ ? r.cx() : r.cz;
                    }
                    if (i > 0) c += (rnd.nextDouble() - 0.5) * 8;
                    if (alongZ) {
                        c = Math.max(r.x0 + 1.2, Math.min(r.x1 - 0.2, c));
                        hazardRect(c - 1.2, c + 1.2, r.z0, r.z1 + 1, tele + 6, dmg);
                    } else {
                        c = Math.max(r.z0 + 1.2, Math.min(r.z1 - 0.2, c));
                        hazardRect(r.x0, r.x1 + 1, c - 1.2, c + 1.2, tele + 6, dmg);
                    }
                }
            }
            case GAP -> {
                boolean alongZ = rnd.nextBoolean();
                double gw = Math.max(3, cfg.i("trap.gap-width"));
                double dmgG = dmg * 1.15;
                Location ref = al.isEmpty() ? r.center(world) : al.get(rnd.nextInt(al.size())).getLocation();
                if (alongZ) {
                    double c = ref.getX() + (rnd.nextDouble() - 0.5) * 6;
                    c = Math.max(r.x0 + gw / 2, Math.min(r.x1 + 1 - gw / 2, c));
                    hazardRect(r.x0, c - gw / 2, r.z0, r.z1 + 1, tele + 20, dmgG);
                    hazardRect(c + gw / 2, r.x1 + 1, r.z0, r.z1 + 1, tele + 20, dmgG);
                } else {
                    double c = ref.getZ() + (rnd.nextDouble() - 0.5) * 6;
                    c = Math.max(r.z0 + gw / 2, Math.min(r.z1 + 1 - gw / 2, c));
                    hazardRect(r.x0, r.x1 + 1, r.z0, c - gw / 2, tele + 20, dmgG);
                    hazardRect(r.x0, r.x1 + 1, c + gw / 2, r.z1 + 1, tele + 20, dmgG);
                }
            }
            case DONUT -> {
                Location base = al.isEmpty() ? r.center(world) : al.get(rnd.nextInt(al.size())).getLocation();
                double x = Math.max(r.x0 + 2, Math.min(r.x1 - 1, base.getX() + (rnd.nextDouble() - 0.5) * 12));
                double z = Math.max(r.z0 + 2, Math.min(r.z1 - 1, base.getZ() + (rnd.nextDouble() - 0.5) * 12));
                hazardDonut(new Location(world, x, r.floorY + 1, z), tele + 24, 4.0, 14.0, dmg);
            }
            case CHECKER -> hazardChecker(r.x0, r.x1 + 1, r.z0, r.z1 + 1,
                    Math.max(2, cfg.i("trap.checker-cell")), rnd.nextInt(2), tele + 14, dmg);
        }
    }


    // ------------------------------------------------------------------ salas nuevas

    private void cleanMobs() {
        mobs.removeIf(u -> {
            Entity e = Bukkit.getEntity(u);
            return e == null || !e.isValid() || e.isDead();
        });
    }

    private void killMobs() {
        for (UUID u : new ArrayList<>(mobs)) {
            Entity e = Bukkit.getEntity(u);
            if (e != null && e.isValid()) {
                world.spawnParticle(Particle.SOUL, e.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.05);
                manager.unindexMob(u);
                e.remove();
            }
        }
        mobs.clear();
        pendingSpawns = 0;
    }

    private void zoneRing(double cx, double cz, double rad, double y, Particle p, int pts) {
        for (int i = 0; i < pts; i++) {
            double a = Math.PI * 2 * i / pts;
            world.spawnParticle(p, cx + Math.cos(a) * rad, y, cz + Math.sin(a) * rad, 1, 0, 0, 0, 0);
        }
    }

    // --- Altar de Almas: mantén la zona mientras llegan enemigos ---

    private void startAltar(DungeonBuilder.Room r) {
        altarCharge = 0;
        altarTotal = Math.max(200, cfg.i("altar.charge-seconds") * 20);
        altarSpawn = tick + 80;
        altarHaz = tick + 240;
        altarMilestone = 0;
        wave = 1;
        totalWaves = 99;
        titleAll("<light_purple><bold>ALTAR DE ALMAS</bold></light_purple>",
                "<gray>Permaneced en el círculo para cargarlo. ¡Defendedlo!</gray>");
    }

    private void altarTick(DungeonBuilder.Room r) {
        List<Player> al = aliveOnline();
        double rad = cfg.d("altar.radius");
        double cx = r.cx() + 0.5;
        double cz = r.cz + 0.5;
        List<Player> inZone = new ArrayList<>();
        for (Player p : al) {
            Location l = p.getLocation();
            double dx = l.getX() - cx;
            double dz = l.getZ() - cz;
            if (dx * dx + dz * dz <= rad * rad && Math.abs(l.getY() - (r.floorY + 1)) < 4) inZone.add(p);
        }
        if (!inZone.isEmpty()) {
            double mult = 1 + Math.min(1.0, (inZone.size() - 1) * 0.34);
            altarCharge = Math.min(altarTotal, altarCharge + 2 * mult);
        }
        double frac = altarCharge / altarTotal;
        if (tick % 4 == 0) {
            zoneRing(cx, cz, rad, r.floorY + 1.1, inZone.isEmpty() ? Particle.FLAME : Particle.SOUL_FIRE_FLAME, 40);
            world.spawnParticle(Particle.END_ROD, cx, r.floorY + 1.5 + frac * 3, cz, 3, 0.3, 0.3, 0.3, 0.01);
        }
        if (pendingSpawns > 0) spawnPending(r);
        if (tick % 10 == 0) cleanMobs();

        if (tick >= altarSpawn) {
            altarSpawn = tick + Math.max(60, cfg.i("altar.spawn-seconds") * 20L);
            int cap = Math.min(cfg.i("waves.max-alive"), 6 + 3 * groupSize());
            int room = cap - (mobs.size() + pendingSpawns);
            int n = Math.min(room, 2 + groupSize() + (int) (frac * 3));
            if (n > 0) pendingSpawns += n;
        }
        if (tick >= altarHaz) {
            altarHaz = tick + Math.max(80, cfg.i("altar.hazard-seconds") * 20L);
            double dmg = cfg.d("trap.hazard-damage") * 0.85;
            int tele = cfg.i("trap.telegraph-ticks");
            List<Player> targets = inZone.isEmpty() ? al : inZone;
            for (int i = 0; i < Math.min(3, targets.size()); i++) {
                hazard(targets.get(rnd.nextInt(targets.size())).getLocation(), tele, 2.3, dmg);
            }
            if (frac > 0.4 && !heavyPending()) {
                boolean alongZ = rnd.nextBoolean();
                if (alongZ) {
                    double c = cx + (rnd.nextDouble() - 0.5) * rad;
                    hazardRect(c - 1.3, c + 1.3, r.z0, r.z1 + 1, tele + 8, dmg);
                } else {
                    double c = cz + (rnd.nextDouble() - 0.5) * rad;
                    hazardRect(r.x0, r.x1 + 1, c - 1.3, c + 1.3, tele + 8, dmg);
                }
            }
            if (frac > 0.75 && !heavyPending()) {
                hazardChecker(r.x0, r.x1 + 1, r.z0, r.z1 + 1, Math.max(2, cfg.i("trap.checker-cell")),
                        rnd.nextInt(2), tele + 20, dmg);
            }
        }
        int ms = (int) (frac * 4);
        if (ms > altarMilestone && ms < 4) {
            altarMilestone = ms;
            broadcast("<light_purple>Altar al " + (ms * 25) + "%</light_purple> <gray>— los enemigos se vuelven más agresivos</gray>");
            soundAll(Sound.BLOCK_BEACON_POWER_SELECT, 1f, 0.8f + ms * 0.15f);
        }
        hint = "Altar " + (int) (frac * 100) + "% · en zona: " + inZone.size() + " · enemigos: " + (mobs.size() + pendingSpawns);
        if (inZone.isEmpty() && tick % 40 == 0) hint = "¡Volved al círculo! · Altar " + (int) (frac * 100) + "%";
        if (altarCharge >= altarTotal) {
            killMobs();
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, cx, r.floorY + 2, cz, 80, 2, 1.5, 2, 0.3);
            world.playSound(new Location(world, cx, r.floorY + 1, cz), Sound.BLOCK_BEACON_ACTIVATE, 2f, 1f);
            clearRoom(active);
        }
    }

    // --- Cámara de Reliquias: riesgo y recompensa ---

    private void setRelic(DungeonBuilder.Room r, int k, boolean open) {
        int[] pd = r.pads.get(k);
        Block b = world.getBlockAt(pd[0], r.floorY + 1, pd[1]);
        if (b.getType() != Material.CHEST) return;
        if (b.getState() instanceof org.bukkit.block.Lidded l) {
            if (open) l.open();
            else l.close();
        }
    }

    private void startVault(DungeonBuilder.Room r) {
        int n = r.pads.size();
        vaultOpen = new boolean[n];
        vaultProg = new int[n];
        vaultOpened = 0;
        wave = 1;
        totalWaves = 99;
        for (int k = 0; k < n; k++) setRelic(r, k, false);
        titleAll("<gold><bold>CÁMARA DE RELIQUIAS</bold></gold>",
                "<gray>Abre los " + n + " cofres quedándote junto a ellos... pero no son gratis</gray>");
    }

    private void vaultTick(DungeonBuilder.Room r) {
        List<Player> al = aliveOnline();
        if (pendingSpawns > 0) spawnPending(r);
        if (tick % 10 == 0) cleanMobs();
        boolean busy = !mobs.isEmpty() || pendingSpawns > 0 || heavyPending();
        int need = Math.max(20, cfg.i("vault.channel-seconds") * 20);
        for (int k = 0; k < r.pads.size(); k++) {
            if (vaultOpen[k]) continue;
            int[] pd = r.pads.get(k);
            boolean on = onPad(al, pd, r, 2.4);
            if (busy) {
                if (on && tick % 20 == 0) {
                    world.spawnParticle(Particle.SMOKE, pd[0] + 0.5, r.floorY + 2, pd[1] + 0.5, 6, 0.3, 0.3, 0.3, 0.01);
                }
                continue;
            }
            vaultProg[k] = on ? Math.min(need, vaultProg[k] + 2) : Math.max(0, vaultProg[k] - 3);
            if (tick % 4 == 0) {
                double frac = vaultProg[k] / (double) need;
                zoneRing(pd[0] + 0.5, pd[1] + 0.5, 1.6, r.floorY + 1.2, Particle.END_ROD, Math.max(1, (int) (frac * 20)));
                world.spawnParticle(Particle.ENCHANT, pd[0] + 0.5, r.floorY + 2, pd[1] + 0.5, 3, 0.4, 0.4, 0.4, 0.5);
            }
            if (vaultProg[k] >= need) openRelic(r, k);
        }
        int n = r.pads.size();
        hint = "Reliquias " + vaultOpened + "/" + n
                + (busy && vaultOpened > 0 ? " · ¡Acaba con el peligro antes de abrir otra!" : "");
        if (vaultOpened >= n && !busy) {
            int idx = active;
            clearRoom(idx);
            if (cfg.b("vault.grants-blessing") && cfg.b("blessings.enabled") && !blessingsDue(idx)) offerBlessings();
        }
    }

    private void openRelic(DungeonBuilder.Room r, int k) {
        vaultOpen[k] = true;
        vaultOpened++;
        setRelic(r, k, true);
        int[] pd = r.pads.get(k);
        Location at = new Location(world, pd[0] + 0.5, r.floorY + 1.5, pd[1] + 0.5);
        world.playSound(at, Sound.BLOCK_CHEST_OPEN, 1.5f, 0.9f);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, at, 25, 0.4, 0.6, 0.4, 0.2);
        int total = cfg.i("vault.ambush-weight") + cfg.i("vault.curse-weight") + cfg.i("vault.safe-weight");
        int roll = rnd.nextInt(Math.max(1, total));
        boolean ambush = roll < cfg.i("vault.ambush-weight");
        boolean curse = !ambush && roll < cfg.i("vault.ambush-weight") + cfg.i("vault.curse-weight");
        boolean safe = !ambush && !curse;
        int rolls = safe ? 2 : 1;
        for (int i = 0; i < rolls; i++) {
            for (ItemStack it : cfg.rollLoot("vault.loot")) {
                world.dropItemNaturally(at, it);
            }
        }
        double dmg = cfg.d("trap.hazard-damage") * 0.9;
        int tele = cfg.i("trap.telegraph-ticks");
        if (ambush) {
            titleAll("<red><bold>¡EMBOSCADA!</bold></red>", "<gray>Era un cebo...</gray>");
            soundAll(Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.4f);
            pendingSpawns += 4 + 2 * groupSize() + vaultOpened;
        } else if (curse) {
            titleAll("<dark_purple><bold>¡MALDICIÓN!</bold></dark_purple>", "<gray>El suelo se vuelve contra vosotros</gray>");
            soundAll(Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1f, 0.7f);
            hazardChecker(r.x0, r.x1 + 1, r.z0, r.z1 + 1, Math.max(2, cfg.i("trap.checker-cell")),
                    rnd.nextInt(2), tele + 20, dmg);
            for (Player p : aliveOnline()) hazard(p.getLocation(), tele + 30, 2.2, dmg);
        } else {
            titleAll("<green><bold>RELIQUIA PURA</bold></green>", "<gray>Botín doble y un respiro</gray>");
            soundAll(Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            for (Player p : aliveOnline()) healPlayer(p, 8.0);
        }
    }

    // --- Mercader Fantasma ---

    private void merchantTick(DungeonBuilder.Room r) {
        hint = merchant.hint(tick);
        merchant.ambient(r, tick);
        if (merchant.finished(tick)) {
            merchant.finish();
            clearRoom(active);
        }
    }

    // --- Sala de Evento ---

    private void eventTick(DungeonBuilder.Room r) {
        if (pendingSpawns > 0) spawnPending(r);
        if (tick % 10 == 0) cleanMobs();
        boolean busy = !mobs.isEmpty() || pendingSpawns > 0 || heavyPending();
        hint = events.hint(tick, busy);
        events.ambient(r, tick);
        if (events.finished(tick) && !busy) {
            events.finish();
            clearRoom(active);
        }
    }

    /** Encola enemigos de emboscada (los usan los eventos). */
    void queueAmbush(int n) {
        pendingSpawns += Math.max(0, n);
    }

    // --- Faro Giratorio: llaves y rayos ---

    private void startSweep(DungeonBuilder.Room r) {
        int n = r.pads.size();
        keyOk = new boolean[n];
        keyProg = new int[n];
        keysDone = 0;
        sweepAng = rnd.nextDouble() * Math.PI * 2;
        sweepDir = rnd.nextBoolean() ? 1 : -1;
        sweepFlip = tick + 260;
        sweepWarned = false;
        sweepHaz = tick + 200;
        sweepHit.clear();
        for (int k = 0; k < n; k++) setTrapPad(r, k, false);
        titleAll("<yellow><bold>FARO GIRATORIO</bold></yellow>",
                "<gray>Activa las " + n + " llaves de las esquinas. Salta los rayos.</gray>");
    }

    private void sweepTick(DungeonBuilder.Room r) {
        List<Player> al = aliveOnline();
        int n = r.pads.size();
        double cx = r.cx() + 0.5;
        double cz = r.cz + 0.5;
        int arms = keysDone < 2 ? 2 : keysDone == 2 ? 3 : 4;
        double speed = cfg.d("sweep.speed") * (1 + 0.12 * keysDone);

        if (keysDone >= 1) {
            if (!sweepWarned && tick >= sweepFlip - 30) {
                sweepWarned = true;
                world.playSound(new Location(world, cx, r.floorY + 2, cz), Sound.BLOCK_NOTE_BLOCK_BASS, 2f, 0.5f);
                zoneRing(cx, cz, 2.2, r.floorY + 1.2, Particle.FLAME, 24);
            }
            if (tick >= sweepFlip) {
                sweepDir = -sweepDir;
                sweepWarned = false;
                sweepFlip = tick + 200 - keysDone * 20L + rnd.nextInt(60);
                world.playSound(new Location(world, cx, r.floorY + 2, cz), Sound.ENTITY_BLAZE_SHOOT, 1.5f, 0.6f);
            }
        }
        sweepAng += sweepDir * speed;
        double len = r.half() * 1.45 + 1;
        double dmg = cfg.d("sweep.damage");
        double y = r.floorY + 1;
        for (int i = 0; i < arms; i++) {
            double a = sweepAng + i * Math.PI * 2 / arms;
            double ca = Math.cos(a);
            double sa = Math.sin(a);
            for (double d = 1.6; d <= len; d += 0.8) {
                world.spawnParticle(Particle.DUST, cx + ca * d, y + 0.35, cz + sa * d, 1, 0, 0, 0, 0, HAZARD_DUST);
                if (((int) (d / 0.8) & 3) == 0) {
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, cx + ca * d, y + 0.2, cz + sa * d, 1, 0, 0, 0, 0);
                }
            }
            for (Player p : al) {
                Location l = p.getLocation();
                if (l.getY() - y > 0.75) continue;
                double dx = l.getX() - cx;
                double dz = l.getZ() - cz;
                double proj = dx * ca + dz * sa;
                double perp = -dx * sa + dz * ca;
                if (proj < 1.4 || proj > len + 0.5 || Math.abs(perp) > 0.9) continue;
                if (sweepHit.getOrDefault(p.getUniqueId(), 0L) > tick) continue;
                sweepHit.put(p.getUniqueId(), tick + 30);
                p.damage(dmg, HAZARD_SRC);
                double side = perp >= 0 ? 1 : -1;
                p.setVelocity(new Vector(-sa * side * 0.8, 0.45, ca * side * 0.8));
                p.playSound(l, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.6f);
            }
        }

        int need = Math.max(20, cfg.i("sweep.key-seconds") * 20);
        for (int k = 0; k < n; k++) {
            if (keyOk[k]) continue;
            int[] pd = r.pads.get(k);
            boolean on = onPad(al, pd, r, 1.7);
            keyProg[k] = on ? Math.min(need, keyProg[k] + 2) : Math.max(0, keyProg[k] - 3);
            if (tick % 4 == 0) {
                double frac = keyProg[k] / (double) need;
                zoneRing(pd[0] + 0.5, pd[1] + 0.5, 1.6, y + 0.2, Particle.END_ROD, Math.max(1, (int) (frac * 24)));
            }
            if (keyProg[k] >= need) {
                keyOk[k] = true;
                keysDone++;
                setTrapPad(r, k, true);
                world.playSound(new Location(world, pd[0] + 0.5, y, pd[1] + 0.5), Sound.BLOCK_BEACON_ACTIVATE, 1.4f, 1.3f);
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, pd[0] + 0.5, y + 0.5, pd[1] + 0.5, 30, 0.5, 1, 0.5, 0.2);
                if (keysDone < n) broadcast("<yellow>Llave activada</yellow> <gray>(" + keysDone + "/" + n
                        + ") — el faro gira más rápido y aparecen más rayos</gray>");
            }
        }
        if (keysDone >= 1 && tick >= sweepHaz) {
            sweepHaz = tick + Math.max(80, 180 - keysDone * 25L);
            if (!al.isEmpty()) {
                hazard(al.get(rnd.nextInt(al.size())).getLocation(), cfg.i("trap.telegraph-ticks"), 2.2, dmg * 0.8);
            }
        }
        hint = "Llaves: " + keysDone + "/" + n;
        if (keysDone >= n) clearRoom(active);
    }

    // ------------------------------------------------------------------ mini jefe

    private void miniTick(DungeonBuilder.Room r) {
        if (mini == null && miniSpawnTick > 0 && tick >= miniSpawnTick) {
            miniSpawnTick = 0;
            MiniBoss.Kind kind = miniOrder.get(miniIdx++ % miniOrder.size());
            mini = MiniBoss.spawn(this, kind, r.center(world));
            for (Player p : onlineMembers()) p.showBossBar(mini.bar());
            titleAll("<dark_red><bold>" + kind.title.toUpperCase() + "</bold></dark_red>", mechanicHint(kind));
        }
        if (mini != null) hint = "Mini jefe: " + mini.kind().title;
    }

    private static String mechanicHint(MiniBoss.Kind k) {
        return switch (k) {
            case SOMBRA -> "<gray>Muy rápida: esquiva sus embestidas y parpadeos</gray>";
            case GUARDIAN -> "<gray>Rompe su escudo para dejarlo aturdido</gray>";
            case NIGROMANTE -> "<gray>Mata a sus súbditos para poder dañarlo</gray>";
        };
    }

    private void miniDone() {
        if (mini != null) {
            for (Player p : onlineMembers()) p.hideBossBar(mini.bar());
            mini = null;
        }
        if (active > 0 && rooms.get(active).type == RoomType.MINIBOSS && roomStates[active] == RoomState.ACTIVE) {
            clearRoom(active);
        }
    }

    public void onMiniGone(MiniBoss mb) {
        if (mini == mb) {
            manager.unindexMob(mb.entity().getUniqueId());
            miniDone();
        }
    }

    // ------------------------------------------------------------------ descanso

    private void startRest(DungeonBuilder.Room r) {
        restEnd = tick + cfg.i("rest.seconds") * 20L;
        titleAll("<green><bold>SALA DE DESCANSO</bold></green>", "<gray>Recupérate antes de Vorthak</gray>");
        int restore = cfg.i("rest.restore-lives");
        for (PState s : members.values()) {
            int cap = maxLives + s.bonusLives;
            if (!s.eliminated && s.lives < cap) s.lives = Math.min(cap, s.lives + restore);
        }
        if (cfg.b("rest.revive-fallen")) {
            for (UUID u : new ArrayList<>(members.keySet())) {
                PState s = members.get(u);
                if (s != null && s.eliminated) reviveAt(u, r.center(world), cfg.i("players.revive-lives"), false);
            }
        }
        if (r.chest != null) {
            Block b = world.getBlockAt(r.chest[0], r.chest[1], r.chest[2]);
            if (b.getState() instanceof org.bukkit.block.Chest chest) {
                Inventory inv = chest.getBlockInventory();
                inv.clear();
                for (int k = 0; k < members.size(); k++) {
                    for (ItemStack it : cfg.rollLoot("rest.chest-items")) inv.addItem(it);
                }
            }
        }
    }

    private void restTick(DungeonBuilder.Room r) {
        long left = restEnd - tick;
        if (left <= 0) {
            clearRoom(active);
            return;
        }
        hint = "Descanso: " + (left / 20) + "s";
        if (tick % 20 == 0) {
            for (Player p : aliveOnline()) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 1, true, false));
                p.setFoodLevel(20);
            }
            world.spawnParticle(Particle.HAPPY_VILLAGER, r.cx() + 0.5, r.floorY + 2, r.cz + 0.5, 10, 2, 1, 2, 0);
        }
    }

    // ------------------------------------------------------------------ Vorthak

    private void vorthakTick(DungeonBuilder.Room r) {
        if (vorthakId == null && vorthakSpawnTick > 0 && tick >= vorthakSpawnTick) {
            vorthakSpawnTick = 0;
            spawnVorthak(r);
        }
        if (vorthakId != null && !vorthakDead) {
            Entity v = Bukkit.getEntity(vorthakId);
            if (v == null || !v.isValid()) {
                // Respaldo: si el jefe se desregistró por morir (su onDeath corrió) pero el evento no llegó hasta aquí,
                // la dungeon se completa igualmente en vez de quedarse con el reloj en marcha o reaparecerlo.
                VorthakBoss gone = bosses.get(vorthakId);
                if (gone == null || gone.isDead()) {
                    vorthakDead = true;
                    manager.unindexMob(vorthakId);
                    plugin.getLogger().warning("[Dungeon #" + id + "] Vorthak murió sin pasar por el evento de muerte: completando por respaldo.");
                    try {
                        complete(new ArrayList<>());
                    } catch (Throwable t) {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error al completar la dungeon #" + id, t);
                    }
                    return;
                }
                vorthakMissing += 2;
                if (vorthakMissing > 100) {
                    manager.unindexMob(vorthakId);
                    bosses.unregister(vorthakId);
                    vorthakId = null;
                    vorthakMissing = 0;
                    vorthakSpawnTick = tick + 20;
                }
            } else {
                vorthakMissing = 0;
            }
            hint = "Derrota a Vorthak";
            if (v != null && v.isValid() && cfg.b("arena.enabled")) {
                VorthakBoss vb = bosses.get(vorthakId);
                if (vb != null) arenaTick(r, v, vb);
            }
        }
    }

    private static final double[] ARENA_DMG = {0.80, 0.95, 1.10, 1.30};
    private static final double[] ARENA_RATE = {1.00, 0.85, 0.70, 0.55};

    /** Mecánicas de suelo de la arena de Vorthak: se intensifican con cada fase del jefe. */
    private void arenaTick(DungeonBuilder.Room r, Entity boss, VorthakBoss vb) {
        int ph = Math.min(3, vb.phase().ordinal());
        List<Player> al = aliveOnline();
        if (al.isEmpty()) return;
        if (ph != arenaPhase) {
            if (arenaPhase >= 0) {
                broadcast("<dark_red>El suelo de la arena se vuelve inestable...</dark_red>");
                soundAll(Sound.ENTITY_WARDEN_SONIC_CHARGE, 0.8f, 0.6f);
            }
            arenaPhase = ph;
            arenaNext = tick + 80;
        }
        if (arenaNext == 0) arenaNext = tick + 200;
        if (tick < arenaNext) return;
        arenaNext = tick + Math.max(60, (long) (cfg.d("arena.interval-seconds") * 20 * ARENA_RATE[ph]));

        double dmg = cfg.d("arena.damage") * ARENA_DMG[ph];
        int tele = cfg.i("trap.telegraph-ticks") + 8;
        int roll = rnd.nextInt(100);
        int pat; // 0 círculos, 1 líneas, 2 muro con hueco, 3 donut, 4 tablero
        switch (ph) {
            case 0 -> pat = 0;
            case 1 -> pat = roll < 50 ? 0 : roll < 80 ? 1 : 2;
            case 2 -> pat = roll < 20 ? 1 : roll < 45 ? 2 : roll < 75 ? 3 : 4;
            default -> pat = roll < 15 ? 1 : roll < 40 ? 2 : roll < 65 ? 3 : 4;
        }
        if (pat != 0 && heavyPending()) pat = 0;

        switch (pat) {
            case 0 -> {
                int n = 2 + al.size() + ph;
                for (int i = 0; i < n; i++) {
                    Location l = i < al.size() ? al.get(i).getLocation() : randomSpot(r);
                    if (l != null) hazard(l, tele, 2.4, dmg);
                }
            }
            case 1 -> {
                boolean alongZ = rnd.nextBoolean();
                for (int i = 0; i < 2 + ph; i++) {
                    Location pl = al.get(rnd.nextInt(al.size())).getLocation();
                    double c = alongZ ? pl.getX() : pl.getZ();
                    if (alongZ) {
                        c = Math.max(r.x0 + 1.2, Math.min(r.x1 - 0.2, c));
                        hazardRect(c - 1.3, c + 1.3, r.z0, r.z1 + 1, tele + 6, dmg);
                    } else {
                        c = Math.max(r.z0 + 1.2, Math.min(r.z1 - 0.2, c));
                        hazardRect(r.x0, r.x1 + 1, c - 1.3, c + 1.3, tele + 6, dmg);
                    }
                }
            }
            case 2 -> {
                boolean alongZ = rnd.nextBoolean();
                double gw = Math.max(3, cfg.i("trap.gap-width") - (ph >= 3 ? 1 : 0));
                Location ref = al.get(rnd.nextInt(al.size())).getLocation();
                double g = dmg * 1.15;
                if (alongZ) {
                    double c = Math.max(r.x0 + gw / 2, Math.min(r.x1 + 1 - gw / 2, ref.getX() + (rnd.nextDouble() - 0.5) * 8));
                    hazardRect(r.x0, c - gw / 2, r.z0, r.z1 + 1, tele + 20, g);
                    hazardRect(c + gw / 2, r.x1 + 1, r.z0, r.z1 + 1, tele + 20, g);
                } else {
                    double c = Math.max(r.z0 + gw / 2, Math.min(r.z1 + 1 - gw / 2, ref.getZ() + (rnd.nextDouble() - 0.5) * 8));
                    hazardRect(r.x0, r.x1 + 1, r.z0, c - gw / 2, tele + 20, g);
                    hazardRect(r.x0, r.x1 + 1, c + gw / 2, r.z1 + 1, tele + 20, g);
                }
            }
            case 3 -> {
                // refugio: junto al jefe (peligroso) o en un punto aleatorio de la arena
                Location c = rnd.nextBoolean() ? boss.getLocation() : randomSpot(r);
                if (c == null) c = boss.getLocation();
                hazardDonut(c, tele + 24, 5.5, 60.0, dmg);
            }
            default -> hazardChecker(r.x0, r.x1 + 1, r.z0, r.z1 + 1,
                    Math.max(2, cfg.i("trap.checker-cell")), rnd.nextInt(2), tele + 14, dmg);
        }
        // Fase IV: siempre algo extra bajo los pies
        if (ph >= 3 && pat != 0) {
            for (Player p : al) hazard(p.getLocation(), tele + 24, 2.2, dmg * 0.8);
        }
    }

    private void spawnVorthak(DungeonBuilder.Room r) {
        long elapsed = elapsedSeconds();
        VorthakBoss boss = bosses.spawnVorthak(r.center(world));
        LivingEntity ve = boss.entity();
        double base = plugin.getConfig().getDouble("vorthak.health", 1000.0);
        double mul = 1 + cfg.d("scaling.vorthak-health-per-extra-player") * (groupSize() - 1);
        boolean fast = elapsed < cfg.i("scaling.fast-bonus-under-seconds");
        if (fast) mul += cfg.d("scaling.fast-bonus-vorthak-health");
        // Minecraft limita la vida máxima a 1024: sin este tope setHealth lanza
        // IllegalArgumentException y la dungeon nunca llega a registrar a Vorthak.
        double hp = Math.min(base * mul, 1024.0);
        AttributeInstance a = ve.getAttribute(Attribute.MAX_HEALTH);
        if (a != null) a.setBaseValue(hp);
        ve.setHealth(Math.min(hp, a != null ? a.getValue() : hp));
        vorthakId = ve.getUniqueId();
        arenaNext = tick + 200;
        arenaPhase = -1;
        vorthakDead = false;
        manager.indexMob(vorthakId, this);
        if (fast) {
            broadcast("<red>Vorthak percibe vuestra prisa y despierta más fuerte <gray>(+"
                    + (int) Math.round(cfg.d("scaling.fast-bonus-vorthak-health") * 100) + "% de vida)</gray></red>");
        }
    }

    private void onVorthakDeath(EntityDeathEvent e) {
        vorthakDead = true;
        e.getDrops().clear();
        plugin.getLogger().info("[Dungeon #" + id + "] Vorthak ha caído: se completa la dungeon.");
        try {
            complete(new ArrayList<>());
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error al completar la dungeon #" + id, t);
        }
    }

    // ------------------------------------------------------------------ bendiciones
    // La lógica vive en BlessingSystem; aquí solo quedan los puntos de enganche.

    /** Menú de elección (el holder permite reconocer el inventario en el listener). */
    public static final class BlessingMenu implements InventoryHolder {
        private final DungeonInstance inst;
        Inventory inv;

        BlessingMenu(DungeonInstance inst) {
            this.inst = inst;
        }

        public DungeonInstance instance() {
            return inst;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    BlessingSystem blessings() {
        return bless;
    }

    RelicSystem relics() {
        return relics;
    }

    MerchantSystem merchant() {
        return merchant;
    }

    EventSystem events() {
        return events;
    }

    int maxLives() {
        return maxLives;
    }

    CombatFx fx() {
        return fx;
    }

    /** Las reliquias se ofrecen en las salas que no ofrecen bendición (se alternan). */
    private boolean relicsDue(int clearedRoom) {
        if (!cfg.b("relics.enabled")) return false;
        int every = Math.max(1, cfg.i("relics.every"));
        int st = stepOfRoom[clearedRoom];
        return st > 0 && st % every == 1 % every && st + 1 < steps.size();
    }

    /** @return false si el jugador no tiene ninguna reliquia pendiente. */
    public boolean openRelicMenu(Player p) {
        return relics.openMenu(p);
    }

    public void chooseRelicBySlot(Player p, int slot) {
        relics.click(p, slot);
    }

    private boolean blessingsDue(int clearedRoom) {
        if (!cfg.b("blessings.enabled")) return false;
        int every = Math.max(1, cfg.i("blessings.every"));
        int st = stepOfRoom[clearedRoom];
        return st > 0 && st % every == 0 && st + 1 < steps.size();
    }

    /** Ofrece una bendición a cada jugador vivo (se abre un menú; también con /dungeon bless). */
    private void offerBlessings(boolean boss) {
        bless.offer(boss);
    }

    private void offerBlessings() {
        bless.offer(false);
    }

    /** Si alguien no eligió a tiempo, se le concede una al azar al entrar en la siguiente sala. */
    private void autoPickPending() {
        bless.autoPickPending();
        relics.autoPickPending();
    }

    /** @return false si el jugador no tiene ninguna bendición pendiente. */
    public boolean openBlessingMenu(Player p) {
        return bless.openMenu(p);
    }

    public void chooseBlessingBySlot(Player p, int slot) {
        bless.click(p, slot);
    }

    void healPlayer(Player p, double amount) {
        AttributeInstance mh = p.getAttribute(Attribute.MAX_HEALTH);
        double max = mh == null ? 20.0 : mh.getValue();
        p.setHealth(Math.max(0.1, Math.min(max, p.getHealth() + amount)));
    }

    private void clearBlessingAttributes(Player p) {
        bless.clearAttributes(p);
    }

    /** Multiplicador del daño que recibe el jugador (Piel de Obsidiana). */
    public double damageTakenMultiplier(Player p) {
        return bless.takenMultiplier(p);
    }

    // acceso para BlessingSystem (mismo paquete)

    PState pstate(UUID u) {
        return members.get(u);
    }

    Map<UUID, PState> memberStates() {
        return members;
    }

    boolean isBossEntity(Entity e) {
        return e.getUniqueId().equals(vorthakId) || bosses.heraldOwner(e) != null;
    }

    /** Enemigo normal o mini jefe de esta partida sobre el que las bendiciones pueden causar daño. */
    boolean isProcTarget(Entity e) {
        if (!(e instanceof LivingEntity) || e instanceof Player) return false;
        if (!e.isValid() || e.isDead()) return false;
        if (manager.byMob(e.getUniqueId()) != this) return false;
        if (puzzles.owns(e)) return false;
        return !isBossEntity(e);
    }

    // ------------------------------------------------------------------ eventos delegados

    public void onMobDamaged(EntityDamageEvent e) {
        if (puzzles.onDamaged(e)) return;
        if (mini != null && mini.owns(e.getEntity().getUniqueId())) mini.onDamage(e);
    }

    public void addDamage(Player p, double dmg) {
        PState s = members.get(p.getUniqueId());
        if (s != null) s.damage += dmg;
    }

    public void onMobDeath(EntityDeathEvent e) {
        UUID u = e.getEntity().getUniqueId();
        manager.unindexMob(u);
        mobs.remove(u);
        Player killer = e.getEntity().getKiller();
        if (killer != null) {
            PState s = members.get(killer.getUniqueId());
            if (s != null) s.kills++;
            if (!u.equals(vorthakId)) {
                bless.onKill(e.getEntity(), killer);
                relics.onKill(e.getEntity(), killer,
                        e.getEntity().getPersistentDataContainer().has(manager.eliteKey, PersistentDataType.BYTE));
                merchant.onKill(killer,
                        e.getEntity().getPersistentDataContainer().has(manager.eliteKey, PersistentDataType.BYTE));
            }
        }
        if (mini != null && mini.owns(u)) {
            merchant.grantAll(cfg.i("merchant.souls-miniboss"));
            mini.onDeath();
            e.getDrops().clear();
            e.getDrops().addAll(cfg.rollLoot("miniboss.loot"));
            e.setDroppedExp(cfg.i("miniboss.xp"));
            World w = e.getEntity().getWorld();
            w.strikeLightningEffect(e.getEntity().getLocation());
            titleAll("<green><bold>MINI JEFE DERROTADO</bold></green>", "<gray>" + mini.kind().title + " ha caído</gray>");
            miniDone();
            return;
        }
        if (u.equals(vorthakId)) {
            onVorthakDeath(e);
            return;
        }
        boolean elite = e.getEntity().getPersistentDataContainer().has(manager.eliteKey, PersistentDataType.BYTE);
        e.getDrops().clear();
        e.setDroppedExp(elite ? 60 : 8);
        if (elite) {
            e.getDrops().addAll(cfg.rollLoot("waves.elite-loot"));
            e.getEntity().getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, e.getEntity().getLocation().add(0, 1, 0),
                    25, 0.4, 0.8, 0.4, 0.3);
        }
    }

    // ------------------------------------------------------------------ muerte, vidas y tumbas

    public Location respawnLocation(Player p) {
        PState s = members.get(p.getUniqueId());
        if (s != null && s.eliminated) {
            for (Tomb t : tombs) if (t.owner.equals(p.getUniqueId())) return t.loc.clone().add(0, 1, 0);
            return currentRoom().center(world).add(0, 3, 0);
        }
        if (state == State.COMPLETED) return currentRoom().center(world);
        return checkpoint();
    }

    public void onPlayerDeath(Player p) {
        PState s = members.get(p.getUniqueId());
        if (s == null || state != State.RUNNING) return;
        s.deaths++;
        s.lives--;
        bless.onDeath(p);
        Location at = p.getLocation().clone();
        if (s.lives > 0) {
            broadcast("<red>☠ " + s.name + " ha caído</red> <gray>(vidas restantes: <white>" + s.lives + "</white>)</gray>");
        } else {
            s.lives = 0;
            s.eliminated = true;
            if (revivesUsed < cfg.i("players.max-revives")) {
                createTomb(p, at);
                broadcast("<dark_red>☠ " + s.name + " ha sido eliminado.</dark_red> <gray>Mantén <white>SHIFT</white> sobre su tumba para revivirlo.</gray>");
            } else {
                broadcast("<dark_red>☠ " + s.name + " ha sido eliminado</dark_red> <gray>(sin más revivires disponibles).</gray>");
            }
        }
        checkWipe();
    }

    public void afterRespawn(Player p) {
        PState s = members.get(p.getUniqueId());
        if (s == null) return;
        if (s.eliminated) {
            p.setGameMode(GameMode.SPECTATOR);
            p.sendMessage(Text.mm(DungeonManager.PREFIX + "<gray>Estás eliminado. Tus compañeros pueden revivirte; observa mientras tanto.</gray>"));
        } else {
            p.setGameMode(GameMode.ADVENTURE);
            int sec = cfg.i("players.respawn-resistance-seconds");
            if (sec > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, sec * 20, 4, true, false));
            p.sendActionBar(Text.mm("<red>Vidas restantes: <white>" + s.lives + "</white> ❤</red>"));
        }
    }

    private void createTomb(Player p, Location at) {
        Tomb t = new Tomb();
        t.owner = p.getUniqueId();
        t.ownerName = p.getName();
        t.loc = at;
        t.display = world.spawn(at.clone().add(0, 1.8, 0), TextDisplay.class, d -> {
            d.text(Text.mm("<dark_red>☠</dark_red> <white>" + p.getName() + "</white>\n<gray>Mantén SHIFT para revivir</gray>"));
            d.setBillboard(Display.Billboard.CENTER);
            d.setSeeThrough(true);
            d.setPersistent(false);
        });
        tombs.add(t);
    }

    private void removeTomb(Tomb t) {
        if (t.display != null && t.display.isValid()) t.display.remove();
    }

    private void removeTombOf(UUID u) {
        Iterator<Tomb> it = tombs.iterator();
        while (it.hasNext()) {
            Tomb t = it.next();
            if (t.owner.equals(u)) {
                removeTomb(t);
                it.remove();
            }
        }
    }

    private void updateTombs() {
        if (tombs.isEmpty()) return;
        int need = cfg.i("players.revive-seconds") * 20;
        Iterator<Tomb> it = tombs.iterator();
        while (it.hasNext()) {
            Tomb t = it.next();
            PState s = members.get(t.owner);
            if (s == null || !s.eliminated) {
                removeTomb(t);
                it.remove();
                continue;
            }
            boolean ownerOnline = Bukkit.getPlayer(t.owner) != null;
            boolean any = false;
            int medic = 0;
            if (ownerOnline) {
                for (Player p : aliveOnline()) {
                    if (p.isSneaking() && !p.getUniqueId().equals(t.owner)
                            && p.getLocation().distanceSquared(t.loc) <= 6.25) {
                        any = true;
                        medic = Math.max(medic, bless.medicLevel(p));
                        p.sendActionBar(Text.mm("<aqua>Reviviendo a <white>" + t.ownerName + "</white>... <white>"
                                + Math.min(100, (t.progress * 100) / Math.max(1, need)) + "%</white></aqua>"));
                    }
                }
            }
            t.progress = any ? t.progress + 2 + 2 * medic : Math.max(0, t.progress - 4);
            t.medic = Math.max(medic, any ? medic : t.medic);
            if (tick % 10 == 0) {
                world.spawnParticle(Particle.SOUL, t.loc.getX(), t.loc.getY() + 1, t.loc.getZ(), 4, 0.3, 0.6, 0.3, 0.02);
                if (t.display != null && t.display.isValid()) {
                    t.display.text(Text.mm("<dark_red>☠</dark_red> <white>" + t.ownerName + "</white>\n"
                            + (ownerOnline ? "<gray>Mantén SHIFT para revivir <white>" + Math.min(100, (t.progress * 100) / Math.max(1, need)) + "%</white></gray>"
                            : "<gray>(desconectado)</gray>")));
                }
            }
            if (t.progress >= need) {
                it.remove();
                removeTomb(t);
                revivesUsed++;
                reviveAt(t.owner, t.loc.clone().add(0, 0.2, 0), cfg.i("players.revive-lives"), true);
                if (t.medic > 0) {
                    Player rp = Bukkit.getPlayer(t.owner);
                    AttributeInstance rmh = rp == null ? null : rp.getAttribute(Attribute.MAX_HEALTH);
                    if (rp != null && rmh != null) rp.setHealth(Math.min(rmh.getValue(), rmh.getValue() * (0.6 + 0.2 * t.medic)));
                }
            }
        }
    }

    private void reviveAt(UUID u, Location at, int lives, boolean announce) {
        PState s = members.get(u);
        Player p = Bukkit.getPlayer(u);
        if (s == null) return;
        s.eliminated = false;
        s.lives = Math.max(1, lives);
        removeTombOf(u);
        if (p == null) return;
        p.setGameMode(GameMode.ADVENTURE);
        p.teleport(at);
        AttributeInstance mh = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth((mh == null ? 20.0 : mh.getValue()) * 0.6);
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 4, true, false));
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, at.getX(), at.getY() + 1, at.getZ(), 60, 0.5, 1, 0.5, 0.4);
        world.playSound(at, Sound.ITEM_TOTEM_USE, 1f, 1f);
        if (announce) broadcast("<green>✚ " + s.name + " ha sido revivido.</green>");
    }

    private void keepSpectators() {
        Location c = currentRoom().center(world).add(0, 3, 0);
        for (Player p : onlineMembers()) {
            PState s = members.get(p.getUniqueId());
            if (s == null || !s.eliminated || p.getGameMode() != GameMode.SPECTATOR) continue;
            if (!p.getWorld().equals(world) || p.getLocation().distanceSquared(c) > 80 * 80) p.teleport(c);
        }
    }

    // ------------------------------------------------------------------ derrota y reinicio

    private void checkWipe() {
        if (state != State.RUNNING || wiping) return;
        for (PState s : members.values()) if (!s.eliminated) return;
        wipe();
    }

    private void wipe() {
        wiping = true;
        boolean again = !cfg.s("on-wipe").equalsIgnoreCase("end") && restarts < cfg.i("max-restarts");
        soundAll(Sound.ENTITY_WITHER_DEATH, 0.8f, 0.7f);
        if (again) {
            titleAll("<dark_red><bold>DERROTA</bold></dark_red>",
                    "<gray>La dungeon se reinicia (" + (restarts + 1) + "/" + cfg.i("max-restarts") + ")...</gray>");
            later(80, this::restart);
        } else {
            titleAll("<dark_red><bold>DERROTA</bold></dark_red>", "<gray>La dungeon ha terminado.</gray>");
            for (UUID u : members.keySet()) manager.applyCooldown(u, cfg.i("cooldown.fail-minutes"));
            later(100, this::close);
        }
    }

    private void restart() {
        restarts++;
        wiping = false;
        if (mini != null) {
            for (Player p : onlineMembers()) p.hideBossBar(mini.bar());
            manager.unindexMob(mini.entity().getUniqueId());
            mini.remove();
            mini = null;
        }
        discardVorthak();
        removeAllTombs();
        restoreHazards();
        purgeEntities();
        mobs.clear();
        pendingSpawns = 0;
        wave = 0;
        nextWaveTick = 0;
        vorthakId = null;
        vorthakSpawnTick = 0;
        vorthakDead = false;
        miniSpawnTick = 0;
        Arrays.fill(roomStates, RoomState.LOCKED);
        for (int i = 1; i < rooms.size(); i++) {
            setGate(i, false, true);
            DungeonBuilder.Room r = rooms.get(i);
            if (r.type == RoomType.TRAP || r.type == RoomType.SWEEP) for (int k = 0; k < r.pads.size(); k++) setTrapPad(r, k, false);
            if (r.type == RoomType.VAULT) for (int k = 0; k < r.pads.size(); k++) setRelic(r, k, false);
        }
        puzzles.reset();
        voteStep = -1;
        votes.clear();
        roomStates[0] = RoomState.ACTIVE;
        active = 0;
        curStep = 0;
        revivesUsed = 0;
        startEnd = tick + cfg.i("players.start-countdown-seconds") * 20L;
        bless.resetAll();
        relics.resetAll();
        merchant.resetAll();
        events.resetAll();
        ambience.clear();
        for (UUID u : members.keySet()) {
            PState s = members.get(u);
            s.lives = maxLives;
            s.eliminated = false;
            s.blessings.clear();
            s.offer = null;
            s.bonusLives = 0;
            s.rerolls = cfg.i("blessings.rerolls");
            Player bp = Bukkit.getPlayer(u);
            if (bp != null) clearBlessingAttributes(bp);
        }
        for (Player p : onlineMembers()) enter(p);
    }

    private void discardVorthak() {
        if (vorthakId == null) return;
        VorthakBoss b = bosses.get(vorthakId);
        if (b != null) b.discard();
        bosses.unregister(vorthakId);
        manager.unindexMob(vorthakId);
        Entity e = Bukkit.getEntity(vorthakId);
        if (e != null && e.isValid()) e.remove();
    }

    private void removeAllTombs() {
        for (Tomb t : tombs) removeTomb(t);
        tombs.clear();
    }

    private void restoreHazards() {
        for (Hazard h : hazards) for (Block b : h.spikes) b.setType(Material.AIR, false);
        hazards.clear();
    }

    /** Elimina todas las entidades (menos jugadores) de esta instancia. */
    private void purgeEntities() {
        for (Entity e : new ArrayList<>(world.getEntities())) {
            if (e instanceof Player) continue;
            if (Math.abs(e.getLocation().getZ() - oz) <= zBand) {
                manager.unindexMob(e.getUniqueId());
                e.remove();
            }
        }
    }

    // ------------------------------------------------------------------ victoria

    private void complete(List<ItemStack> ignoredVanillaDrops) {
        runEnd = System.currentTimeMillis(); // el reloj se detiene aquí
        state = State.COMPLETED;
        completedTick = tick;
        hint = "";
        DungeonBuilder.Room r = currentRoom();
        mobs.clear();
        long secs = elapsedSeconds();

        // puntaje
        int totalDeaths = 0;
        for (PState s : members.values()) totalDeaths += s.deaths;
        int timeBonus = (int) Math.max(0, cfg.i("score.max-time-bonus") - secs * cfg.d("score.time-points-per-second"));
        int score = cfg.i("score.base") + timeBonus - totalDeaths * cfg.i("score.death-penalty")
                + (totalDeaths == 0 ? cfg.i("score.no-death-bonus") : 0);
        finalScore = Math.max(0, score);
        finalRank = finalScore >= cfg.i("score.rank-s") ? "S" : finalScore >= cfg.i("score.rank-a") ? "A"
                : finalScore >= cfg.i("score.rank-b") ? "B" : finalScore >= cfg.i("score.rank-c") ? "C" : "D";

        // los caídos reviven para poder abrir el cofre
        if (cfg.b("rewards.revive-fallen")) {
            for (UUID u : new ArrayList<>(members.keySet())) {
                PState s = members.get(u);
                if (s != null && s.eliminated) reviveAt(u, r.center(world).add(0, 0.5, 3), 1, false);
            }
        }

        // botín personal: cada jugador tira su propio loot y lo recoge en SU cofre (no cae nada al suelo)
        for (UUID u : members.keySet()) {
            List<ItemStack> mine = new ArrayList<>(VorthakBoss.rollLoot(plugin, bosses));
            mine.addAll(cfg.rollLoot("rewards.loot"));
            rewards.put(u, mine);
        }
        int cx = r.cx();
        int cy = r.floorY + 1;
        int cz = r.cz;
        world.getBlockAt(cx, cy, cz).setType(Material.ENDER_CHEST, false);
        builder.track(cx, cy, cz);
        rewardChest = new Location(world, cx + 0.5, cy, cz + 0.5);
        world.spawn(rewardChest.clone().add(0, 1.6, 0), TextDisplay.class, td -> {
            td.text(Text.mm("<gradient:#ffd86b:#ff9a3c><bold>✦ Cofre de Recompensas ✦</bold></gradient>\n<gray>Clic derecho: cada jugador ve su botín</gray>"));
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(false);
            td.setPersistent(false);
        });
        // portal de salida (decorativo + detección por cercanía)
        int px = cx;
        int pz = cz + 9;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz <= 5) setBlock(px + dx, r.floorY, pz + dz, Material.CRYING_OBSIDIAN);
            }
        }
        world.strikeLightningEffect(new Location(world, cx + 0.5, cy, cz + 0.5));

        // resumen + recompensas personales
        List<Map.Entry<UUID, PState>> ranking = new ArrayList<>(members.entrySet());
        ranking.sort((a, b) -> Double.compare(b.getValue().damage, a.getValue().damage));
        for (Player p : onlineMembers()) {
            p.showTitle(Title.title(Text.mm("<gradient:#ffd86b:#ff9a3c><bold>¡DUNGEON COMPLETADA!</bold></gradient>"),
                    Text.mm("<gray>Rango <gold><bold>" + finalRank + "</bold></gold> <dark_gray>·</dark_gray> <white>" + finalScore + "</white> pts</gray>"),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(5), Duration.ofSeconds(1))));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            p.sendMessage(Text.mm("<dark_gray>━━━━━━━━ <gradient:#ffd86b:#ff9a3c><bold>RESULTADOS</bold></gradient> <dark_gray>━━━━━━━━"));
            p.sendMessage(Text.mm("<gray>Tiempo: <aqua>" + clock(secs) + "</aqua>  <dark_gray>·</dark_gray>  Muertes: <red>" + totalDeaths
                    + "</red>  <dark_gray>·</dark_gray>  Puntaje: <white>" + finalScore + "</white> <gold>[" + finalRank + "]</gold>"));
            for (Map.Entry<UUID, PState> en : ranking) {
                PState s = en.getValue();
                p.sendMessage(Text.mm("<dark_gray>▸</dark_gray> <white>" + s.name + "</white> <gray>daño <red>" + (int) Math.round(s.damage)
                        + "</red> · bajas <white>" + s.kills + "</white> · muertes <red>" + s.deaths + "</red></gray>"));
            }
            p.sendMessage(Text.mm("<gray>Abre el <gold>Cofre de Recompensas</gold> del centro (tu botín es personal) y cruza el <light_purple>portal</light_purple> para salir (o <white>/dungeon leave</white>)."));
        }
        for (Map.Entry<UUID, PState> en : members.entrySet()) {
            PState s = en.getValue();
            s.completed = true;
            manager.applyCooldown(en.getKey(), cfg.i("cooldown.success-minutes"));
            manager.data().recordRun(en.getKey(), s.name, finalScore, (int) secs);
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) continue;
            int xp = cfg.i("rewards.xp");
            if (xp > 0) p.giveExp(xp);
            for (String cmd : cfg.list("rewards.commands")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", s.name));
            }
        }
    }

    // ------------------------------------------------------------------ cofre de recompensas personal

    /** Menú personal de recompensas (el holder lo identifica en el listener). */
    public static final class RewardMenu implements InventoryHolder {
        private final DungeonInstance inst;
        private final UUID owner;
        Inventory inv;

        RewardMenu(DungeonInstance inst, UUID owner) {
            this.inst = inst;
            this.owner = owner;
        }

        public DungeonInstance instance() {
            return inst;
        }

        public UUID owner() {
            return owner;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int REWARD_SIZE = 45;
    private static final int TAKE_ALL = REWARD_SIZE - 1;

    public boolean isRewardChest(Block b) {
        return state == State.COMPLETED && rewardChest != null && b.getType() == Material.ENDER_CHEST
                && b.getX() == rewardChest.getBlockX() && b.getY() == rewardChest.getBlockY()
                && b.getZ() == rewardChest.getBlockZ();
    }

    public void openRewards(Player p) {
        List<ItemStack> mine = rewards.get(p.getUniqueId());
        if (mine == null) {
            p.sendMessage(Text.mm(DungeonManager.PREFIX + "<gray>No tienes recompensas en esta dungeon.</gray>"));
            return;
        }
        Inventory inv = rewardInvs.get(p.getUniqueId());
        if (inv == null) {
            RewardMenu holder = new RewardMenu(this, p.getUniqueId());
            inv = Bukkit.createInventory(holder, REWARD_SIZE, Text.mm("<gradient:#ffd86b:#ff9a3c><bold>Tu recompensa</bold></gradient>"));
            holder.inv = inv;
            for (ItemStack it : mine) {
                for (ItemStack left : inv.addItem(it).values()) p.getInventory().addItem(left).values()
                        .forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
            }
            ItemStack all = new ItemStack(Material.HOPPER);
            ItemMeta m = all.getItemMeta();
            m.displayName(Text.mm("<green><bold>Recoger todo</bold></green>"));
            all.setItemMeta(m);
            inv.setItem(TAKE_ALL, all);
            rewardInvs.put(p.getUniqueId(), inv);
        }
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1f, 1f);
    }

    public void onRewardClick(Player p, int slot, boolean top) {
        if (!top || slot != TAKE_ALL) return;
        Inventory inv = rewardInvs.get(p.getUniqueId());
        if (inv == null) return;
        for (int i = 0; i < TAKE_ALL; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            inv.setItem(i, left.isEmpty() ? null : left.values().iterator().next());
        }
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1.1f);
    }

    private void completedTick() {
        DungeonBuilder.Room r = currentRoom();
        int px = r.cx();
        int pz = r.cz + 9;
        if (tick % 4 == 0) {
            world.spawnParticle(Particle.REVERSE_PORTAL, px + 0.5, r.floorY + 1.5, pz + 0.5, 12, 0.5, 1.2, 0.5, 0.2);
            for (int i = 0; i < 12; i++) {
                double a = Math.PI * 2 * i / 12 + tick * 0.1;
                world.spawnParticle(Particle.END_ROD, px + 0.5 + Math.cos(a) * 1.6, r.floorY + 1.2 + (tick % 40) / 20.0,
                        pz + 0.5 + Math.sin(a) * 1.6, 1, 0, 0, 0, 0);
            }
        }
        if (tick - completedTick > 60) {
            for (Player p : onlineMembers()) {
                Location l = p.getLocation();
                double dx = l.getX() - (px + 0.5);
                double dz = l.getZ() - (pz + 0.5);
                if (dx * dx + dz * dz <= 3.2 && Math.abs(l.getY() - (r.floorY + 1)) < 3) leave(p);
            }
        }
        long auto = cfg.i("rewards.auto-exit-seconds") * 20L;
        long left = auto - (tick - completedTick);
        if (left == 600) broadcast("<yellow>Saldréis automáticamente en 30 segundos.</yellow>");
        if (left <= 0 || members.isEmpty()) close();
    }

    // ------------------------------------------------------------------ HUD

    private void hud() {
        int total = Math.max(1, steps.size() - 1);
        int cleared = 0;
        for (int i = 1; i < rooms.size(); i++) if (roomStates[i] == RoomState.CLEARED) cleared++;
        if (state == State.COMPLETED) cleared = total;
        bar.progress((float) Math.max(0.0, Math.min(1.0, cleared / (double) total)));
        bar.name(Text.mm("<gradient:#ff4d4d:#ffb347><bold>Dungeon de Vorthak</bold></gradient> <dark_gray>·</dark_gray> <gray>Sala <white>"
                + Math.max(0, Math.min(curStep, total)) + "/" + total + "</white></gray> <dark_gray>·</dark_gray> <aqua>"
                + clock(elapsedSeconds()) + "</aqua>"
                + (modifier != null ? " <dark_gray>·</dark_gray> <light_purple>" + modifier.title + "</light_purple>" : "")));
        for (Player p : onlineMembers()) {
            PState s = members.get(p.getUniqueId());
            if (s == null) continue;
            String hearts = "<red>" + "❤".repeat(Math.max(0, s.lives)) + "</red><dark_gray>"
                    + "❤".repeat(Math.max(0, maxLives + s.bonusLives - s.lives)) + "</dark_gray>";
            String bl = bless.hudLine(p);
            String rl = relics.hudLine(p);
            String ml = merchant.hudLine(p);
            if (!ml.isEmpty()) rl = rl.isEmpty() ? ml : rl + " <dark_gray>·</dark_gray> " + ml;
            if (!rl.isEmpty()) bl = bl.isEmpty() ? rl : bl + " <dark_gray>·</dark_gray> " + rl;
            p.sendActionBar(Text.mm(hearts + (bl.isEmpty() ? "" : " <dark_gray>·</dark_gray> " + bl)
                    + (hint.isEmpty() ? "" : " <dark_gray>·</dark_gray> <gray>" + hint + "</gray>")));
        }
    }

    // ------------------------------------------------------------------ salir / cerrar

    /** Entrega el botín no recogido (ya fuera de la dungeon, en el inventario del jugador). */
    private void giveUnclaimed(Player p) {
        List<ItemStack> mine = rewards.remove(p.getUniqueId());
        Inventory inv = rewardInvs.remove(p.getUniqueId());
        List<ItemStack> pending = new ArrayList<>();
        if (inv != null) {
            for (int i = 0; i < TAKE_ALL; i++) {
                ItemStack it = inv.getItem(i);
                if (it != null && !it.getType().isAir()) pending.add(it);
            }
        } else if (mine != null && state == State.COMPLETED) {
            pending.addAll(mine);
        }
        for (ItemStack it : pending) {
            for (ItemStack left : p.getInventory().addItem(it).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        if (!pending.isEmpty()) {
            p.sendMessage(Text.mm(DungeonManager.PREFIX + "<gray>Se te entregó el botín que no habías recogido.</gray>"));
        }
    }

    private void restore(Player p, PState s) {
        p.hideBossBar(bar);
        clearBlessingAttributes(p);
        if (mini != null) p.hideBossBar(mini.bar());
        if (p.isDead()) p.spigot().respawn();
        p.setGameMode(s.prevMode);
        Location back = s.returnLoc != null ? s.returnLoc : manager.fallback();
        p.teleport(back);
        p.setFireTicks(0);
        p.removePotionEffect(PotionEffectType.RESISTANCE);
    }

    /** Un jugador sale de la dungeon (comando, portal o final). */
    public void leave(Player p) {
        PState s = members.remove(p.getUniqueId());
        if (s == null) return;
        manager.unmapPlayer(p.getUniqueId());
        removeTombOf(p.getUniqueId());
        restore(p, s);
        giveUnclaimed(p);
        if (!s.completed) {
            manager.applyCooldown(p.getUniqueId(), cfg.i("cooldown.fail-minutes"));
            p.sendMessage(Text.mm(DungeonManager.PREFIX + "<gray>Has abandonado la dungeon.</gray>"));
        } else {
            p.sendMessage(Text.mm(DungeonManager.PREFIX + "<gray>Has salido de la dungeon.</gray>"));
        }
        if (members.isEmpty()) {
            close();
        } else if (state == State.RUNNING) {
            broadcast("<gray>" + s.name + " abandonó la dungeon.</gray>");
            checkWipe();
        }
    }

    public void close() {
        shutdown(false);
    }

    /** @param sync true al apagar el servidor: no se programan tareas (el mundo se borra igualmente). */
    public void shutdown(boolean sync) {
        if (state == State.ENDING) return;
        state = State.ENDING;
        if (task != null) task.cancel();
        if (mini != null) {
            mini.remove();
            mini = null;
        }
        ambience.clear();
        discardVorthak();
        removeAllTombs();
        restoreHazards();
        for (Map.Entry<UUID, PState> en : new ArrayList<>(members.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null) {
                restore(p, en.getValue());
                giveUnclaimed(p);
            } else manager.addPending(en.getKey(), en.getValue().returnLoc, en.getValue().prevMode);
            manager.unmapPlayer(en.getKey());
        }
        members.clear();
        purgeEntities();
        manager.dropMobs(this);
        builder.cancel();
        if (sync) {
            builder.release();
            manager.instanceClosed(this);
        } else {
            builder.cleanup(() -> manager.instanceClosed(this));
        }
    }
}
