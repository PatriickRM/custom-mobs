package me.custommobs.dungeon;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Jefe final de una dungeon. La instancia solo conoce esta interfaz: la creación, las fases, las habilidades y la
 * muerte son cosa de cada jefe. (Vorthak sigue con su propio flujo histórico en {@code DungeonInstance}; los jefes
 * nuevos implementan esto y se registran en {@link BossDef#create}.)
 */
interface DungeonBoss {

    /** Entidad principal (la que lleva la vida). */
    LivingEntity entity();

    default UUID id() {
        return entity().getUniqueId();
    }

    /** Se llama cada 2 ticks mientras la sala del jefe está activa. */
    void tick(long now);

    /** Daño recibido por el jefe o por cualquier entidad auxiliar suya (cristales...). Puede cancelar el evento. */
    void onDamage(EntityDamageEvent e);

    /** ¿Es el jefe o una entidad auxiliar suya (cristal, ilusión...)? Se excluye de bendiciones/reliquias. */
    boolean owns(Entity e);

    /** Muerte real de la entidad principal (después de su secuencia de muerte). */
    void onDeath(EntityDeathEvent e);

    /** true cuando ya no hay nada que hacer (murió o se retiró). */
    boolean finished();

    /** Texto de ayuda para el HUD de la dungeon. */
    String hint();

    /** Retira entidades, barras, tareas y efectos (reinicio, cierre de la dungeon). */
    void discard();

    /** Botín personal de cada jugador (se tira una vez por jugador). */
    List<ItemStack> rollLoot();
}
