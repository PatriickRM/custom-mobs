package me.custommobs.model;

import me.custommobs.Text;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dibuja a los enemigos con modelos 3D del resource pack: el mob real queda invisible y cada pieza del modelo
 * es un ItemDisplay que se mueve y se anima cada 2 ticks.
 */
public final class MobVisuals implements Listener {

    private static final int PERIOD = 2;
    private static final int DEATH_TICKS = 14;

    private final JavaPlugin plugin;
    private final ModelDefs defs;
    private final Map<UUID, Visual> visuals = new ConcurrentHashMap<>();
    private BukkitTask task;
    private long clock;

    private final boolean enabled;
    private final String packUrl;
    private final String packSha1;
    private final boolean packRequired;

    public MobVisuals(JavaPlugin plugin) {
        this.plugin = plugin;
        this.defs = ModelDefs.load(plugin);
        this.enabled = plugin.getConfig().getBoolean("models.enabled", true) && defs.size() > 0;
        this.packUrl = plugin.getConfig().getString("models.resource-pack-url", "");
        this.packSha1 = plugin.getConfig().getString("models.sha1", "");
        this.packRequired = plugin.getConfig().getBoolean("models.required", true);
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, PERIOD);
        plugin.getLogger().info("Modelos de mobs: " + (enabled ? defs.size() + " cargados" : "desactivados"));
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (Visual v : visuals.values()) v.removeDisplays();
        visuals.clear();
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean has(String modelId) {
        return enabled && defs.get(modelId) != null;
    }

    /** Hace invisible al mob real y le monta el modelo. */
    public void attach(LivingEntity e, String modelId, boolean elite) {
        ModelDefs.Def def = defs.get(modelId);
        if (!enabled || def == null) return;
        e.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
        Visual v = new Visual(e, def, elite);
        v.build();
        visuals.put(e.getUniqueId(), v);
    }

    /** Fuerza el gesto de ataque (por ejemplo al telegrafiar un golpe). */
    public void attack(UUID id, int ticks) {
        Visual v = visuals.get(id);
        if (v != null) v.atkTimer = Math.max(v.atkTimer, ticks);
    }

    /** Ítem sin modelo para que el esqueleto conserve su arco sin que se vea. */
    public ItemStack invisibleItem(org.bukkit.Material m) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        NamespacedKey k = NamespacedKey.fromString("custommobs:empty");
        if (k != null) meta.setItemModel(k);
        it.setItemMeta(meta);
        return it;
    }

    // ------------------------------------------------------------------ resource pack

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled || packUrl.isBlank()) return;
        Player p = e.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.setResourcePack(packUrl, packSha1.isBlank() ? null : packSha1, packRequired,
                    Text.mm("<gold>CustomMobs</gold> <gray>necesita su resource pack (enemigos de ceniza e infierno).</gray>"));
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        Visual v = visuals.get(e.getEntity().getUniqueId());
        if (v != null) v.deathTicks = 0;
    }

    // ------------------------------------------------------------------ bucle

    private void tick() {
        clock += PERIOD;
        Iterator<Visual> it = visuals.values().iterator();
        while (it.hasNext()) {
            Visual v = it.next();
            try {
                if (!v.update(clock)) {
                    v.removeDisplays();
                    it.remove();
                }
            } catch (Exception ex) {
                v.removeDisplays();
                it.remove();
                plugin.getLogger().warning("Modelo descartado: " + ex);
            }
        }
    }

    // ------------------------------------------------------------------ un mob visible

    private final class Visual {
        final LivingEntity ent;
        final ModelDefs.Def def;
        final boolean elite;
        final ItemDisplay[] disp;
        final Matrix4f[] world;
        Location last;
        Location lastDisplayLoc;
        float walkPhase;
        double lastBodyYaw;
        float walkFactor;
        int atkTimer;
        int atkCooldown;
        int deathTicks = -1;
        final float seed = (float) (Math.random() * 6.28);

        Visual(LivingEntity ent, ModelDefs.Def def, boolean elite) {
            this.ent = ent;
            this.def = def;
            this.elite = elite;
            this.disp = new ItemDisplay[def.nodes.size()];
            this.world = new Matrix4f[def.nodes.size()];
            this.last = ent.getLocation();
            this.lastBodyYaw = ent.getBodyYaw();
        }

        void build() {
            Location loc = ent.getLocation();
            for (int i = 0; i < def.nodes.size(); i++) {
                ModelDefs.Node n = def.nodes.get(i);
                world[i] = new Matrix4f();
                if (n.model == null) continue;
                ItemStack item = new ItemStack(org.bukkit.Material.PAPER);
                ItemMeta meta = item.getItemMeta();
                NamespacedKey k = NamespacedKey.fromString("custommobs:" + n.model);
                if (k != null) meta.setItemModel(k);
                item.setItemMeta(meta);
                final boolean glow = n.glow;
                ItemDisplay d = loc.getWorld().spawn(loc, ItemDisplay.class, id -> {
                    id.setItemStack(item);
                    id.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    id.setPersistent(false);
                    id.setInterpolationDelay(0);
                    id.setInterpolationDuration(PERIOD);
                    id.setTeleportDuration(PERIOD);
                    id.setDisplayWidth(4f);
                    id.setDisplayHeight(4f);
                    id.setViewRange(1.5f);
                    id.setBillboard(Display.Billboard.FIXED);
                    if (glow) id.setBrightness(new Display.Brightness(15, 15));
                    if (elite) {
                        id.setGlowing(true);
                        id.setGlowColorOverride(Color.fromRGB(255, 120, 30));
                    }
                });
                disp[i] = d;
            }
            lastDisplayLoc = loc.clone();
            update(clock);
        }

        void removeDisplays() {
            for (ItemDisplay d : disp) if (d != null && d.isValid()) d.remove();
        }

        /** @return false cuando el modelo debe retirarse. */
        boolean update(long now) {
            if (deathTicks < 0 && (!ent.isValid() || ent.isDead())) {
                if (ent.isDead()) deathTicks = 0;      // murió sin pasar por el evento
                else return false;                      // retirado (limpieza de sala)
            }
            if (deathTicks > DEATH_TICKS) return false;

            Location cur = ent.isValid() ? ent.getLocation() : last;
            double dx = cur.getX() - last.getX();
            double dz = cur.getZ() - last.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            last = cur.clone();

            float target = deathTicks >= 0 ? 0f : (float) Math.min(1.0, (dist / PERIOD) / 0.08);
            walkFactor += (target - walkFactor) * 0.5f;
            walkPhase += (float) (dist * 3.2);

            float time = (now + seed * 10f) * 0.05f;           // segundos
            double bodyYaw = ent.isValid() ? ent.getBodyYaw() : lastBodyYaw;
            lastBodyYaw = bodyYaw;
            float headYawDiff = 0f;
            float headPitch = 0f;
            if (ent.isValid()) {
                headYawDiff = wrap(cur.getYaw() - (float) bodyYaw);
                headPitch = cur.getPitch();
            }
            headYawDiff = clamp(headYawDiff, -70f, 70f);
            headPitch = clamp(headPitch, -45f, 45f);

            // ataque
            if (atkCooldown > 0) atkCooldown -= PERIOD;
            if (atkTimer > 0) atkTimer -= PERIOD;
            float atkAmt = 0f;
            if (deathTicks < 0 && ent instanceof Mob m) {
                LivingEntity t = m.getTarget();
                if (ent.isHandRaised()) {
                    atkAmt = 1f;
                } else if (t != null && atkCooldown <= 0
                        && t.getLocation().distanceSquared(cur) < 3.6 * 3.6 * scaleAttr() * scaleAttr()) {
                    atkTimer = 8;
                    atkCooldown = 16;
                }
            }
            if (atkTimer > 0) atkAmt = Math.max(atkAmt, (float) Math.sin(Math.PI * (1.0 - atkTimer / 8.0)));

            // retroceso al recibir daño
            float hurt = 0f;
            if (deathTicks < 0 && ent.isValid()) {
                int nd = ent.getNoDamageTicks();
                int max = Math.max(1, ent.getMaximumNoDamageTicks());
                if (nd > max / 2) hurt = Math.min(1f, (nd - max / 2f) / (max / 2f));
            }

            float s = def.scale * scaleAttr();
            Matrix4f root = new Matrix4f().rotateY((float) -Math.toRadians(bodyYaw));
            if (hurt > 0f) root.rotateX((float) Math.toRadians(-8 * hurt));
            if (deathTicks >= 0) {
                float p = deathTicks / (float) DEATH_TICKS;
                root.rotateX((float) Math.toRadians(-90 * Math.min(1f, p * 1.4f)));
                float shrink = p > 0.7f ? 1f - (p - 0.7f) / 0.3f * 0.9f : 1f;
                root.translate(0, -0.05f * p, 0);
                s *= shrink;
                if (deathTicks % 4 == 0) {
                    cur.getWorld().spawnParticle(Particle.ASH, cur.clone().add(0, 0.8 * s, 0), 14, 0.4, 0.5, 0.4, 0.02);
                    cur.getWorld().spawnParticle(Particle.SMOKE, cur.clone().add(0, 0.5, 0), 4, 0.3, 0.3, 0.3, 0.01);
                }
                deathTicks += PERIOD;
            }
            root.scale(s);

            for (int i = 0; i < def.nodes.size(); i++) {
                ModelDefs.Node n = def.nodes.get(i);
                float rx = n.rx, ry = n.ry, rz = n.rz;
                if (n.walkAmp != 0f) rx += (float) Math.sin(walkPhase + Math.toRadians(n.walkPhase)) * n.walkAmp * walkFactor;
                if (n.idleAmp != 0f) {
                    float a = (float) Math.sin(time * n.idleSpeed * 3.0 + Math.toRadians(n.idlePhase)) * n.idleAmp;
                    switch (n.idleAxis) {
                        case 'y' -> ry += a;
                        case 'z' -> rz += a;
                        default -> rx += a;
                    }
                }
                if (n.atk != 0f) rx -= n.atk * atkAmt;
                if (n.look) {
                    ry -= headYawDiff;
                    rx += headPitch;
                }
                float tx = n.px, ty = n.py, tz = n.pz;
                float ox = 0, oy = 0, oz = 0;
                if (n.parent >= 0) {
                    ModelDefs.Node p = def.nodes.get(n.parent);
                    tx -= p.px; ty -= p.py; tz -= p.pz;
                }
                if (n.orbitR != 0f) {
                    double ang = Math.toRadians(n.orbitPhase + n.orbitSpeed * time);
                    ox = (float) (Math.cos(ang) * n.orbitR);
                    oz = (float) (Math.sin(ang) * n.orbitR);
                }
                if (n.hoverAmp != 0f) oy = (float) Math.sin(time * n.hoverSpeed * 2.0) * n.hoverAmp;

                Matrix4f m = new Matrix4f(n.parent >= 0 ? world[n.parent] : root);
                m.translate((tx + ox) / 16f, (ty + oy) / 16f, (tz + oz) / 16f);
                m.rotateX((float) Math.toRadians(rx)).rotateY((float) Math.toRadians(ry)).rotateZ((float) Math.toRadians(rz));
                if (n.spinAxis != 0 && n.spinSpeed != 0f) {
                    float a = (float) Math.toRadians(n.spinSpeed * time);
                    switch (n.spinAxis) {
                        case 'x' -> m.rotateX(a);
                        case 'z' -> m.rotateZ(a);
                        default -> m.rotateY(a);
                    }
                }
                world[i].set(m);
                ItemDisplay d = disp[i];
                if (d == null) continue;
                if (!d.isValid()) return false;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(PERIOD);
                d.setTransformationMatrix(m);
            }

            Location dl = new Location(cur.getWorld(), cur.getX(), cur.getY(), cur.getZ(), 0f, 0f);
            if (lastDisplayLoc == null || lastDisplayLoc.distanceSquared(dl) > 1.0e-4) {
                for (ItemDisplay d : disp) if (d != null) d.teleport(dl);
                lastDisplayLoc = dl;
            }
            return true;
        }

        float scaleAttr() {
            AttributeInstance a = ent.getAttribute(Attribute.SCALE);
            return a == null ? 1f : (float) a.getValue();
        }
    }

    private static float wrap(float a) {
        while (a > 180f) a -= 360f;
        while (a < -180f) a += 360f;
        return a;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
