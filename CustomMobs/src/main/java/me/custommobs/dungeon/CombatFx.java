package me.custommobs.dungeon;

import me.custommobs.CustomMobsPlugin;
import me.custommobs.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TextDisplay;
import org.joml.Matrix4f;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Feedback visual del combate: números de daño flotantes (TextDisplay), críticos y avisos de texto.
 * Los textos son entidades temporales no persistentes: suben, crecen un instante y desaparecen.
 */
final class CombatFx {

    /** Máximo de textos vivos a la vez (protege al servidor en combates con muchos golpes). */
    private static final int MAX_ALIVE = 70;
    private static final int LIFE_TICKS = 18;

    private final CustomMobsPlugin plugin;
    private final DungeonConfig cfg;
    private final World world;
    private int alive;

    CombatFx(CustomMobsPlugin plugin, DungeonConfig cfg, World world) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.world = world;
    }

    /** Número de daño sobre un enemigo. */
    void damage(LivingEntity target, double dmg, boolean crit) {
        if (!cfg.b("fx.damage-numbers") || dmg < 0.5 || !target.isValid()) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = target.getLocation().add(r.nextDouble(-0.45, 0.45), target.getHeight() + 0.25,
                r.nextDouble(-0.45, 0.45));
        long shown = Math.round(dmg);
        String color;
        if (dmg >= 20) color = "<red>";
        else if (dmg >= 12) color = "<gold>";
        else if (dmg >= 6) color = "<yellow>";
        else color = "<white>";
        if (crit) {
            text(at, "<gold><bold>✦ " + shown + " ✦</bold></gold>", 1.7f);
            world.spawnParticle(Particle.CRIT, target.getLocation().add(0, target.getHeight() * 0.7, 0),
                    10, 0.3, 0.4, 0.3, 0.35);
        } else {
            text(at, color + shown + "</" + color.substring(1), 1.1f);
        }
    }

    /** Número de curación sobre un jugador. */
    void heal(Location at, double hp) {
        if (!cfg.b("fx.damage-numbers") || hp < 0.25) return;
        text(at, "<green>+" + String.format(Locale.ROOT, "%.1f", hp / 2.0) + " ❤</green>", 1.0f);
    }

    /** Texto flotante genérico (MiniMessage). */
    void text(Location at, String mm, float scale) {
        if (!cfg.b("fx.damage-numbers") || alive >= MAX_ALIVE) return;
        alive++;
        final TextDisplay d = world.spawn(at, TextDisplay.class, td -> {
            td.text(Text.mm(mm));
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.setShadowed(true);
            td.setDefaultBackground(false);
            td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            td.setPersistent(false);
            td.setViewRange(0.6f);
            td.setInterpolationDelay(0);
            td.setInterpolationDuration(LIFE_TICKS - 4);
            td.setTransformationMatrix(new Matrix4f().scale(scale * 0.45f));
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(LIFE_TICKS - 4);
            d.setTransformationMatrix(new Matrix4f().translate(0f, 0.85f, 0f).scale(scale));
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (d.isValid()) d.remove();
            alive = Math.max(0, alive - 1);
        }, LIFE_TICKS);
    }

    /** Pequeño golpe de pantalla/sonido para impactos pesados (daño alto). */
    void heavyHit(Location at, double dmg) {
        if (dmg < 14) return;
        world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.7f, 0.8f);
        world.spawnParticle(Particle.CRIT, at, 8, 0.3, 0.3, 0.3, 0.25);
    }
}
