package me.custommobs.dungeon;

import me.custommobs.CustomMobsPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Configuración de la dungeon (plugins/CustomMobs/dungeon.yml).
 * Si el archivo no existe se crea con los valores por defecto (con comentarios).
 * Las claves que falten en un archivo antiguo se rellenan con el valor por defecto.
 */
public final class DungeonConfig {

    private final CustomMobsPlugin plugin;
    private YamlConfiguration cfg;

    public DungeonConfig(CustomMobsPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File f = new File(plugin.getDataFolder(), "dungeon.yml");
        if (!f.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                Files.writeString(f.toPath(), DEFAULTS, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                plugin.getLogger().warning("No se pudo crear dungeon.yml: " + ex.getMessage());
            }
        }
        cfg = YamlConfiguration.loadConfiguration(f);
        cfg.setDefaults(YamlConfiguration.loadConfiguration(new StringReader(DEFAULTS)));
    }

    public int i(String path) {
        return cfg.getInt(path);
    }

    public double d(String path) {
        return cfg.getDouble(path);
    }

    public boolean b(String path) {
        return cfg.getBoolean(path);
    }

    public String s(String path) {
        return cfg.getString(path, "");
    }

    public List<String> list(String path) {
        return cfg.getStringList(path);
    }

    /**
     * Pasos de la dungeon (sin START). Cada paso es una sala o, si se escribe "A|B", una elección entre dos salas
     * (el grupo vota qué camino tomar). VORTHAK siempre queda al final y una sola vez.
     */
    public List<List<RoomType>> steps() {
        List<List<RoomType>> out = new ArrayList<>();
        for (String s : cfg.getStringList("rooms")) {
            if (s == null) continue;
            List<RoomType> opts = new ArrayList<>();
            for (String part : s.split("\\|")) {
                RoomType t = RoomType.parse(part);
                if (t == null || t == RoomType.START || t == RoomType.VORTHAK) continue;
                if (opts.size() < 2) opts.add(t);
            }
            if (!opts.isEmpty()) out.add(opts);
        }
        if (out.isEmpty()) out.add(List.of(RoomType.WAVES));
        out.add(List.of(RoomType.VORTHAK));
        return out;
    }

    public int roomSize(RoomType t) {
        int s = Math.max(9, cfg.getInt("sizes." + t.name() + ".size"));
        return s | 1; // siempre impar para que haya centro
    }

    public int roomHeight(RoomType t) {
        return Math.max(5, cfg.getInt("sizes." + t.name() + ".height"));
    }

    /**
     * Tira una lista de botín con el formato "MATERIAL:min:max:probabilidad".
     * También acepta POTION_HEAL y POTION_REGEN.
     */
    public List<ItemStack> rollLoot(String path) {
        List<ItemStack> out = new ArrayList<>();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (String line : cfg.getStringList(path)) {
            String[] p = line.split(":");
            try {
                String name = p[0].trim().toUpperCase();
                int min = p.length > 1 ? Integer.parseInt(p[1].trim()) : 1;
                int max = p.length > 2 ? Integer.parseInt(p[2].trim()) : min;
                double chance = p.length > 3 ? Double.parseDouble(p[3].trim()) : 1.0;
                if (rnd.nextDouble() >= chance) continue;
                int n = min + rnd.nextInt(Math.max(1, max - min + 1));
                if (name.equals("POTION_HEAL") || name.equals("POTION_REGEN")) {
                    PotionType type = name.equals("POTION_HEAL") ? PotionType.STRONG_HEALING : PotionType.STRONG_REGENERATION;
                    for (int k = 0; k < n; k++) {
                        ItemStack it = new ItemStack(Material.POTION);
                        PotionMeta pm = (PotionMeta) it.getItemMeta();
                        pm.setBasePotionType(type);
                        it.setItemMeta(pm);
                        out.add(it);
                    }
                    continue;
                }
                Material m = Material.matchMaterial(name);
                if (m == null || m.isAir()) {
                    plugin.getLogger().warning("dungeon.yml (" + path + "): material desconocido '" + name + "'");
                    continue;
                }
                out.add(new ItemStack(m, Math.max(1, Math.min(n, m.getMaxStackSize()))));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("dungeon.yml (" + path + "): línea inválida '" + line + "'");
            }
        }
        return out;
    }

    private static final String DEFAULTS = """
            # =====================================================================
            #  DUNGEON DE VORTHAK
            #  Se recarga con /dungeon reload (las partidas en curso no cambian).
            # =====================================================================
            enabled: true

            # Mundo vacío exclusivo (se crea al arrancar y se borra al apagar).
            world: dungeon_world
            build-y: 80
            # Bioma visual: ashen, frost, void, crimson o random (uno al azar por partida).
            biome: random

            # --- iluminación y sonido ---
            # Bloques de luz invisibles en salas y pasillos (0 = apagado). Si las salas te parecen demasiado claras, baja el nivel.
            lighting:
              enabled: true
              level: 15
            sounds:
              ambient: true          # ecos/viento de almas/lava de fondo
              ambient-volume: 0.7
            # Distancia entre instancias (bloques, en el eje Z) y máximo de partidas a la vez.
            instance-spacing: 240
            max-instances: 4
            # Bloques colocados por tick al construir/borrar (baja el valor si hay lag).
            blocks-per-tick: 2000

            lobby:
              min-players: 1
              max-players: 4
              countdown-seconds: 30
              # Exige el equipo mínimo de Vorthak (usa la sección gear: del config.yml).
              require-gear: true

            cooldown:
              success-minutes: 30
              fail-minutes: 5

            # Orden de las salas. Tipos: WAVES, TRAP, PUZZLE, MINIBOSS, REST, ALTAR, VAULT, SWEEP, MERCHANT, EVENT
            # (la sala de entrada y la de VORTHAK se añaden solas).
            # Escribe "A|B" para que el grupo ELIJA entre dos salas (voto). Ej: "ALTAR|TRAP".
            rooms: [WAVES, "ALTAR|TRAP", "VAULT|PUZZLE", "WAVES|EVENT", "SWEEP|MERCHANT", MINIBOSS, REST]

            path:
              # Segundos que tiene el grupo para votar el camino (si nadie vota, se elige al azar).
              vote-seconds: 25

            # size = lado interior (se fuerza impar), height = alto interior.
            sizes:
              START:    {size: 13, height: 8}
              WAVES:    {size: 25, height: 10}
              TRAP:     {size: 25, height: 9}
              PUZZLE:   {size: 25, height: 9}
              MINIBOSS: {size: 29, height: 13}
              REST:     {size: 15, height: 8}
              ALTAR:    {size: 29, height: 10}
              VAULT:    {size: 25, height: 9}
              SWEEP:    {size: 31, height: 8}
              MERCHANT: {size: 21, height: 9}
              EVENT:    {size: 21, height: 9}
              VORTHAK:  {size: 37, height: 20}

            players:
              lives: 3
              # Revivir compañeros eliminados: mantén SHIFT sobre su tumba.
              max-revives: 3
              revive-seconds: 5
              revive-lives: 1
              # Tiempo para volver tras desconectarse.
              reconnect-seconds: 120
              respawn-resistance-seconds: 4
              start-countdown-seconds: 10
              block-commands: true
              allowed-commands: [dungeon, msg, tell, w, r, reply, m, t]

            # Si todos mueren: restart (vuelve a empezar la dungeon) o end (se acaba).
            on-wipe: restart
            max-restarts: 2

            scaling:
              # Vida extra de los enemigos por jugador adicional (0.30 = +30%).
              health-per-extra-player: 0.30
              vorthak-health-per-extra-player: 0.60
              # Dificultad creciente por sala.
              health-per-room: 0.10
              damage-per-room: 0.05
              # Si llegan a Vorthak en menos de X segundos, empieza más fuerte.
              fast-bonus-under-seconds: 1200
              fast-bonus-vorthak-health: 0.25

            waves:
              count: 3
              base-mobs: 5
              extra-per-wave: 2
              extra-per-player: 2
              extra-per-room: 1
              max-alive: 26
              delay-seconds: 4
              elite-chance: 0.08
              elite-health-multiplier: 2.5
              # Formato: MATERIAL:min:max:probabilidad
              elite-loot:
                - "GOLDEN_APPLE:1:2:0.40"
                - "DIAMOND:1:3:0.40"
                - "NETHERITE_SCRAP:1:1:0.15"
                - "EXPERIENCE_BOTTLE:3:8:0.60"
                - "ENCHANTED_GOLDEN_APPLE:1:1:0.02"

            trap:
              # Hay que activar las balizas (quedarse encima) mientras el suelo ataca.
              # Cuantas más balizas activas, más rápidos y variados son los patrones.
              activate-seconds: 4
              # Daño de TODAS las zonas de peligro. Atraviesa la armadura:
              # solo lo reducen Protección, Resistencia y las bendiciones.
              hazard-damage: 12.0
              telegraph-ticks: 30
              # Ancho del hueco seguro del "muro con hueco" y tamaño de casilla del tablero.
              gap-width: 6
              checker-cell: 4

            puzzle:
              # AUTO = uno distinto al azar cada vez. También: VIGILANTES, PORTADORES, RESONANCIA
              #  VIGILANTES: recoge 4 fragmentos; las estatuas solo se mueven cuando NADIE las mira.
              #  PORTADORES: lleva cada orbe a su pedestal de color mientras te atacan (si te hieren, lo sueltas).
              #  RESONANCIA: rompe a golpes los cristales que se iluminan antes de que se acabe el tiempo.
              mode: AUTO
              # Vigilantes: estatuas al empezar / máximo, y velocidad (bloques cada 2 ticks).
              statue-start: 2
              statue-max: 6
              statue-speed: 0.36
              # Portadores: cada cuántos segundos llega un grupo de enemigos a por los portadores.
              carrier-spawn-seconds: 9
              # Resonancia: rondas, golpes para romper cada cristal y tiempo (segundos) de la primera ronda.
              crystal-rounds: 5
              crystal-hits: 6
              crystal-window-seconds: 9
              # Cada cuántos segundos cae un golpe de presión mientras resuelves.
              pressure-seconds: 5

            blessings:
              enabled: true
              # Se ofrece tras superar cada X salas (2 = salas 2, 4, 6...). Cada jugador elige la suya.
              every: 2
              # Opciones que se muestran (1-4).
              choices: 3
              # Relanzamientos del menú por jugador y partida (0 = desactivado).
              rerolls: 2
              # Tras derrotar al mini jefe todos reciben una bendición extra (rara o mejor).
              miniboss-reward: true

            relics:
              # Reliquias: objetos pasivos que cambian las reglas. Se ofrecen en las salas intermedias
              # (alternando con las bendiciones). Cada jugador elige la suya.
              enabled: true
              # Cada cuántas salas se ofrece una (2 = salas 1, 3, 5...).
              every: 2
              # Opciones que se muestran (1-3).
              choices: 3

            fx:
              # Números de daño flotantes sobre los enemigos.
              damage-numbers: true
              # Cristales flotantes y partículas de ambiente propias de cada sala.
              ambience: true

            altar:
              # Altar de Almas: quédate en la zona para cargarlo mientras llegan enemigos.
              # Cuantos más jugadores dentro, más rápido (hasta x2).
              charge-seconds: 45
              radius: 6.5
              # Cada cuántos segundos llega un grupo de enemigos.
              spawn-seconds: 7
              # Golpes de peligro sobre la zona (cada X segundos).
              hazard-seconds: 9

            vault:
              # Cámara de Reliquias: 4 cofres; cada uno puede ser emboscada, maldición o seguro.
              channel-seconds: 2
              ambush-weight: 55
              curse-weight: 30
              safe-weight: 15
              # Bendición extra para todos al vaciar la cámara.
              grants-blessing: true
              loot:
                - "GOLDEN_APPLE:1:2:0.80"
                - "DIAMOND:1:3:0.60"
                - "EXPERIENCE_BOTTLE:4:10:0.90"
                - "POTION_HEAL:1:1:0.50"
                - "NETHERITE_SCRAP:1:1:0.10"

            heat:
              # Niveles de Calor (dificultad opcional). Se elige en el menú del Heraldo de las Sombras
              # (requiere classes.enabled) y se desbloquean uno a uno al completar la dungeon.
              # El grupo juega con la media de lo que elijan los jugadores. Los niveles se acumulan:
              #   1 más vida de enemigos · 2 más daño · 3 más élites · 4 una vida menos · 5 oleadas extra
              enabled: true
              health-bonus: 0.15
              damage-bonus: 0.15
              elite-multiplier: 2.0
              extra-waves: 1
              # Cada nivel de Calor suma este % al puntaje final (0.10 = +10% por nivel).
              score-bonus-per-level: 0.10

            hub:
              # Hub: isla decorada (mundo vacío propio, se construye al arrancar) con un pedestal por jefe,
              # el Santuario de Ecos y el Salón de la Fama. Sin /dungeon setlobby, el lobby es el hub.
              enabled: true
              world: dungeon_lobby

            meta:
              # Ecos: moneda permanente. Se gana en cada partida (aunque pierdas) y se gasta en mejoras
              # permanentes con /dungeon meta o desde el Heraldo de las Sombras.
              enabled: true
              per-room: 3
              win-bonus: 25
              per-heat-level: 8

            classes:
              # Clases de inicio: en la sala de inicio habla con el Heraldo de las Sombras (clic derecho),
              # elige clase y confirma que estás listo. Con todos listos se abren las puertas.
              # Si lo pones en false vuelve la cuenta atrás clásica (players.start-countdown-seconds).
              enabled: true
              # Cuenta atrás cuando todos están listos.
              start-delay-seconds: 5
              # Si alguien no confirma a tiempo se le asigna una clase al azar y empieza la partida.
              timeout-seconds: 120

            event:
              # Sala de Evento: cada jugador elige una opción (riesgo y recompensa).
              # Los eventos están definidos en EventSystem.java (son datos: añadir uno es copiar un bloque).
              # Tiempo máximo para decidir (quien no elija se marcha sin más).
              seconds: 60

            merchant:
              # Mercader Fantasma: gasta BRASAS (se ganan matando) o VIDAS. Las brasas duran toda la partida.
              enabled: true
              # Tiempo máximo en la tienda (se abre antes si todos pulsan "Listo").
              seconds: 90
              souls-per-kill: 1
              souls-per-elite: 4
              # Brasas que recibe cada jugador al matar un mini jefe.
              souls-miniboss: 12
              # Precio en brasas de la reliquia según su rareza.
              price-common: 12
              price-rare: 20
              price-cursed: 10
              price-legendary: 32
              # Vidas que cuesta la segunda reliquia (precio de sangre).
              blood-relic-lives: 1
              # Esencia Espectral: cura (en puntos de vida, 2 = 1 corazón), compras máximas por visita.
              heal-cost: 6
              heal-amount: 8.0
              max-heals: 3
              # Vida Prestada: compra 1 vida (una por visita).
              life-cost: 40
              # Pacto de Sangre: entregas 1 vida y recibes estas brasas (uno por visita).
              pact-souls: 25

            sweep:
              # Faro Giratorio: recoge las 4 llaves esquivando los rayos (salta por encima o rodéalos).
              damage: 12.0
              key-seconds: 2
              # Velocidad del giro (radianes cada 2 ticks).
              speed: 0.045

            arena:
              # Suelo de la arena de Vorthak: patrones telegrafiados que se intensifican por fase.
              enabled: true
              # Segundos base entre patrones (se acorta en cada fase).
              interval-seconds: 11
              # Daño base (atraviesa armadura); se multiplica x0.8 / 0.95 / 1.1 / 1.3 según la fase.
              damage: 14.0

            rest:
              seconds: 25
              restore-lives: 1
              revive-fallen: true
              # Botín del cofre POR JUGADOR.
              chest-items:
                - "COOKED_BEEF:8:8:1.0"
                - "GOLDEN_APPLE:1:1:1.0"
                - "POTION_HEAL:2:2:1.0"
                - "POTION_REGEN:1:1:1.0"

            miniboss:
              sombra:     {health: 450.0, damage: 9.0}
              guardian:   {health: 700.0, damage: 10.0}
              nigromante: {health: 500.0, damage: 6.0}
              # Parte de la vida del Guardián que absorbe su escudo.
              guardian-shield-percent: 0.35
              xp: 600
              loot:
                - "GOLDEN_APPLE:2:3:1.0"
                - "DIAMOND:2:4:1.0"
                - "NETHERITE_SCRAP:1:2:0.50"
                - "EXPERIENCE_BOTTLE:8:16:1.0"

            rewards:
              # Recompensa personal al derrotar a Vorthak (además de su botín en el cofre).
              xp: 1000
              loot:
                - "GOLDEN_CARROT:16:16:1.0"
              # Comandos de consola; %player% = nombre. Ej: "eco give %player% 500"
              commands: []
              # Los eliminados reviven al terminar para poder abrir el cofre.
              revive-fallen: true
              auto-exit-seconds: 120

            score:
              base: 5000
              max-time-bonus: 3000
              # Los puntos de tiempo bajan 1 por cada X segundos... (puntos por segundo)
              time-points-per-second: 1.5
              death-penalty: 350
              no-death-bonus: 1000
              rank-s: 8000
              rank-a: 6500
              rank-b: 5000
              rank-c: 3500

            modifiers:
              enabled: true
              # Fuerza uno: HORDE, ELITE, FRENZY, GLASS, BLESSED (vacío = rota por día).
              force: ""
            """;
}
