package me.custommobs.dungeon;

import me.custommobs.CustomMobsPlugin;
import me.custommobs.model.MobVisuals;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntFunction;

/**
 * Cámara de cine reutilizable para los jefes: durante unos segundos los jugadores vuelan por una trayectoria
 * mirando a un punto de interés, con franjas negras (si el resource pack está activo) y protegidos del daño.
 * Al terminar cada jugador vuelve exactamente a donde estaba.
 */
final class Cinematic {

    /** Trayectoria: posición de la cámara en el tick {@code t} (de 0 a total) respecto a un punto de foco. */
    interface Path {
        Location at(double t, double total, Location focus, Location start);
    }

    private record Cam(Location start, boolean allowFlight, boolean flying, ItemStack helmet, boolean hadHelmetSwap) {
    }

    private final CustomMobsPlugin plugin;
    private final Map<UUID, Cam> cams = new HashMap<>();
    private BukkitRunnable task;

    Cinematic(CustomMobsPlugin plugin) {
        this.plugin = plugin;
    }

    boolean running() {
        return task != null;
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static double ease(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /** Órbita alrededor del foco: el radio y la altura van de (r0,h0) a (r1,h1) mientras gira {@code sweep} radianes. */
    static Path orbit(double r0, double r1, double h0, double h1, double sweep) {
        return (t, total, focus, start) -> {
            double u = ease(t / total);
            double a0 = Math.atan2(start.getZ() - focus.getZ(), start.getX() - focus.getX());
            double a = a0 + u * sweep;
            double r = lerp(r0, r1, u);
            return new Location(focus.getWorld(), focus.getX() + Math.cos(a) * r, focus.getY() + lerp(h0, h1, u),
                    focus.getZ() + Math.sin(a) * r);
        };
    }

    private ItemStack letterboxHelmet() {
        ItemStack it = new ItemStack(org.bukkit.Material.LEATHER_HELMET);
        ItemMeta m = it.getItemMeta();
        NamespacedKey empty = NamespacedKey.fromString("custommobs:empty");
        if (empty != null) m.setItemModel(empty);
        org.bukkit.inventory.meta.components.EquippableComponent eq = m.getEquippable();
        eq.setSlot(org.bukkit.inventory.EquipmentSlot.HEAD);
        NamespacedKey inv = NamespacedKey.fromString("custommobs:invisible");
        if (inv != null) eq.setModel(inv);
        NamespacedKey overlay = NamespacedKey.fromString("custommobs:misc/letterbox");
        if (overlay != null) eq.setCameraOverlay(overlay);
        m.setEquippable(eq);
        m.setMaxStackSize(1);
        it.setItemMeta(m);
        return it;
    }

    /**
     * Reproduce la cinemática. {@code focus} se evalúa cada tick con el número de tick (el jefe se mueve y puede cambiar de plano). {@code shake} añade temblor de
     * cámara entre los ticks {@code shakeFrom} y {@code shakeTo}.
     */
    void play(List<Player> players, IntFunction<Location> focus, int ticks, Path path, int shakeFrom, int shakeTo, Runnable done) {
        stop();
        MobVisuals pk = plugin.visuals();
        boolean bars = plugin.getConfig().getBoolean("vorthak.letterbox", true) && pk != null && pk.enabled();
        for (Player p : players) {
            boolean swapped = false;
            ItemStack old = null;
            boolean af = p.getAllowFlight();
            old = p.getInventory().getHelmet();
            if (bars) {
                try {
                    p.getInventory().setHelmet(letterboxHelmet());
                    swapped = true;
                } catch (Throwable t) {
                    swapped = false;
                }
            }
            cams.put(p.getUniqueId(), new Cam(p.getLocation().clone(), af, p.isFlying(), old, swapped));
            p.setAllowFlight(true);
            p.setFlying(true);
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks + 40, 4, false, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, ticks + 10, 0, false, false, false));
        }
        task = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                Location f = focus.apply(t);
                if (t >= ticks || cams.isEmpty() || f == null || f.getWorld() == null) {
                    finish();
                    if (done != null) done.run();
                    return;
                }
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (Map.Entry<UUID, Cam> en : cams.entrySet()) {
                    Player p = Bukkit.getPlayer(en.getKey());
                    Cam cam = en.getValue();
                    if (p == null || !p.isOnline() || cam == null || cam.start().getWorld() == null
                            || !cam.start().getWorld().equals(f.getWorld())) continue;
                    Location pos = path.at(t, ticks, f, cam.start());
                    Vector d = f.toVector().subtract(pos.toVector().add(new Vector(0, 1.62, 0)));
                    float yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
                    float pitch = (float) -Math.toDegrees(Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ())));
                    if (t >= shakeFrom && t <= shakeTo) {
                        yaw += (float) rnd.nextDouble(-1.6, 1.6);
                        pitch += (float) rnd.nextDouble(-1.1, 1.1);
                    }
                    pos.setYaw(yaw);
                    pos.setPitch(Math.max(-85f, Math.min(85f, pitch)));
                    p.teleport(pos);
                    p.setFallDistance(0f);
                }
                t++;
            }
        };
        task.runTaskTimer(plugin, 0L, 1L);
    }

    /** Termina la cinemática y devuelve a cada jugador a su sitio. */
    void finish() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Map.Entry<UUID, Cam> en : new ArrayList<>(cams.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            Cam cam = en.getValue();
            if (p == null || !p.isOnline() || cam == null) continue;
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
            if (cam.hadHelmetSwap()) p.getInventory().setHelmet(cam.helmet());
            p.setFlying(cam.flying());
            p.setAllowFlight(cam.allowFlight());
            if (cam.start().getWorld() != null) p.teleport(cam.start());
            p.setFallDistance(0f);
        }
        cams.clear();
    }

    void stop() {
        if (task != null || !cams.isEmpty()) finish();
    }
}
