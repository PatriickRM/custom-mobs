# Servidor Purpur 26.1.2 + CustomMobs

## Instalación
1. Instala **Java 25** (https://adoptium.net) y **Gradle 9+** (o usa el wrapper si existe en `CustomMobs/`).
2. Ejecuta `setup.bat` (Windows) o `./setup.sh` (Linux/Mac): descarga `purpur.jar`, compila el plugin y lo copia a `plugins/`.
3. Edita `eula.txt` y pon `eula=true`.
4. Ejecuta `start.bat` / `./start.sh`.
5. Entra al juego, hazte OP (`op TuNombre` en la consola) y usa `/mob spawn vorthak`.

## Comandos (permiso `custommobs.admin`)
- `/mob spawn vorthak [mundo x y z]`
- `/mob list`
- `/mob killall`
- `/mob reload`

## Vorthak, el Centinela de Ceniza
Wither Skeleton colosal (escala 1.7, 1000 HP) con 4 fases:

| Fase | Vida | Habilidades |
|---|---|---|
| I · Centinela de Ceniza | 100–70% | Golpe de Ceniza (onda), Almas Cautivas (súbditos) |
| II · Furia Infernal | 70–40% | + Onda de Almas (se esquiva saltando), Cráneos Marchitos (abanico), Salto Infernal |
| III · Ascensión del Vacío | 40–15% | + Parpadeo del Vacío, Juicio del Rayo, Colapso Gravitatorio, Égida Umbral (escudo 6s) |
| IV · Último Aliento | <15% | + Tormenta de Almas, aura de Wither, cooldowns a la mitad, +70% daño de habilidades |

Cada cambio de fase: transformación de 3s (invulnerable), título en pantalla, onda de choque y nueva oleada de súbditos.

**Requisito para herirlo** (editable en `plugins/CustomMobs/config.yml`): armadura de diamante completa con Protección III+ en cada pieza y espada de diamante con Afilado V+. Si no, el golpe se anula. Flechas, fuego, explosiones, pociones, etc. no le hacen daño.

**Loot:** Corazón de Ceniza (100%), 4–8 netherite scrap, 3 calaveras de wither, 2 manzanas encantadas, 4000 XP y 25% de Hoja de Ceniza.

## Bendiciones de la dungeon

Entre salas cada jugador elige una bendición (1 de 3 opciones, **2 relanzamientos** por partida). Duran toda la partida. Hay **21 bendiciones** en 4 escuelas, con rareza (Común / Rara / Épica / Legendaria) y sinergias.

**Sinergias:** reúne **2** o **4** niveles de una misma escuela para activar un bonus. Las escuelas en las que ya tienes bendiciones salen más a menudo en el menú.

| Escuela | 2 niveles | 4 niveles |
|---|---|---|
| 🔥 Ceniza | **Ignición:** tus golpes siempre prenden y marcan | **Pira:** los enemigos que mueren en llamas explotan |
| 💠 Almas | **Eco de Almas:** cada muerte te cura 1 ❤ | **Legión:** Cadena, Explosión y Guardianes +50% |
| 🩸 Sangre | **Sangre Caliente:** +50% robo de vida y regeneración | **Frenesí Carmesí:** con <40% de vida, +30% daño y +20% velocidad |
| 🌀 Vacío | **Paso Latente:** desbloqueas el Dash y recarga -20% | **Cazador del Vacío:** el dash daña y activa un Eco |

**Bendiciones:** Furia de Ceniza, Marca de Brasas, Pies de Ceniza, Estela Ígnea, Tormenta de Ceniza *(legendaria)* · Cosecha de Almas, Rayo Encadenado, Explosión de Almas, Guardianes Espectrales · Filo Vampírico, Vitalidad, Aliento de Vida, Espinas de Sangre, Pacto de Sangre, Sed de Sangre, Segundo Aliento · Piel de Obsidiana, Paso del Vacío, Eco del Vacío, Evasión Sombría · Corazón Extra.

**Dash:** con *Paso del Vacío* (o la sinergia del Vacío) pulsa **F** (cambiar de mano). El estado de dash, guardianes, frenesí y Segundo Aliento aparece en la barra de acción.

Los efectos extra (cadena, explosiones, estela, tormenta...) **no dañan a Vorthak ni a sus heraldos**: su combate sigue regido por el requisito de equipo.

Opciones en `dungeon.yml` → `blessings`: `enabled`, `every`, `choices`, `rerolls`, `miniboss-reward` (bendición rara extra al derrotar al mini jefe).


## Dungeon de Vorthak (`/dungeon join`)

Dungeon cooperativa (1-4 jugadores) con salas encadenadas hasta Vorthak. Cada jugador tiene vidas, se puede revivir a los caídos
y al terminar hay puntaje y rango (S/A/B/C/D). Hay un modificador distinto cada día.

### Camino a elegir
En `dungeon.yml`, `rooms:` acepta `"A|B"` para que el grupo **vote** entre dos salas (clic en el chat o `/dungeon path <n>`):
`rooms: [WAVES, "ALTAR|TRAP", "VAULT|PUZZLE", WAVES, "SWEEP|PUZZLE", MINIBOSS, REST]`

### Acertijos (uno distinto cada vez)
- **Los Vigilantes**: recoge 4 fragmentos; las estatuas solo se mueven cuando nadie las mira (y aparece una más por fragmento).
- **Portadores de Almas**: lleva cada orbe a su pedestal del mismo color mientras te atacan. Si te hieren, lo sueltas.
- **Resonancia**: rompe a golpes el cristal que brilla antes de que se acabe el tiempo; en grupo, de dos en dos.

### Bendiciones
Cuatro escuelas (Ceniza, Almas, Sangre, Vacío) con sinergias a 2 y 4 niveles, más:
- **Legendarias**: Tormenta de Ceniza, Juicio de Almas, Banquete Carmesí, Singularidad.
- **Riesgo**: Furia Desatada (+40% daño, +25% daño recibido), Pacto de Sangre.
- **Equipo** (solo salen en grupo): Estandarte de Guerra, Médico de Campaña.
- **Híbridas** (piden dos escuelas y un dash): Paso Ígneo (Ceniza+Vacío), Dash Carmesí (Sangre+Vacío).

## Enemigos con modelos 3D (ceniza e infierno)

Los enemigos de las salas de oleadas se dibujan con modelos propios del resource pack
(`resourcepack/CustomMobsPack.zip`). El mob vanilla queda invisible y cada pieza del modelo es un `ItemDisplay`
animado (caminar, respirar, atacar, mirar, muerte con ceniza).

| Enemigo | Base | Habilidad |
|---|---|---|
| Cenizo | Zombie | al morir suelta una nube de ceniza que ciega |
| Arquero Calcinado | Skeleton | flechas en llamas |
| Marchito | Husk | su golpe aplica Wither |
| Bruto Forjado | Vindicator | pisotón telegrafiado cada ~9 s |
| Espectro | Wither Skeleton | se teletransporta a tu espalda |
| Sacerdote de Almas | Stray | cura a sus aliados cada ~7 s (mátalo primero) |
| Bestia de Ceniza | Ravager | al morir deja un foso de brasas |

**Configuración** (`config.yml`, sección `models`): `enabled`, `resource-pack-url`, `sha1`, `required`.
Sube el zip a un sitio con enlace directo (por ejemplo el raw de GitHub) y pon su URL y su sha1
(`sha1sum resourcepack/CustomMobsPack.zip`). Se envía a cada jugador al entrar. Con `enabled: false`
los enemigos vuelven a ser los vanilla.

**Regenerar el pack**: `pip install pillow numpy pyyaml` y `python3 tools/mobgen/build.py`
(edita los diseños en `tools/mobgen/defs.py`; genera el zip, `mobmodels.yml` y una vista previa en
`tools/mobgen/out/preview.png`). Después actualiza el `sha1` de la config.

## Novedades (parte 2)
- **Minijefes con modelo 3D**: Sombra Veloz (asesina de vacío), Guardián de Brasas (coloso con escudo y hacha) y Nigromante de Almas (hechicero con cráneos orbitando). Voces y pasos propios.
- **Vorthak cambia de aspecto en cada fase** (`vorthak`, `vorthak2`, `vorthak3`, `vorthak4`) y gana 4 habilidades: *Erupciones de Ceniza* (I), *Muralla Ardiente* (II, se esquiva saltando), *Grietas del Vacío* (III) y *Sentencia* (IV).
- **Cofre de recompensas personal** al vencer a Vorthak, reloj detenido al caer, luz invisible en las salas y sonidos de ambiente/eventos.
- Los modelos se regeneran con `python3 tools/mobgen/build.py` (actualiza `resourcepack/CustomMobsPack.zip` y `mobmodels.yml`). Tras subir el pack nuevo, reinicia el servidor para que los jugadores lo descarguen.

## Novedades (parte 3): reliquias y feedback visual

### Reliquias (`/dungeon relic`)
Objetos pasivos que **cambian las reglas** de la partida. Se ofrecen en las salas intermedias, **alternando con las bendiciones**
(cada jugador elige una entre 3 en un menú; si no eliges, se te da una al azar al entrar en la siguiente sala). No se repiten.

| Reliquia | Rareza | Efecto |
|---|---|---|
| Corazón de Brasa | Común | Los enemigos que matas estallan en llamas y prenden a los cercanos |
| Linterna de Almas | Común | +2,5% de daño por alma (máx. 12, una por muerte); si te hieren pierdes la mitad |
| Cáliz Carmesí | Común | Cada muerte te cura (más si es élite) |
| Tambor de Guerra | Común | Cada muerte da velocidad; encadena kills para correr más |
| Sello del Cazador | Común | El primer golpe a un enemigo con vida casi llena hace +60% |
| Máscara del Verdugo | Rara | +80% de daño contra enemigos con <30% de vida |
| Aguja del Vacío | Rara | 18% de lanzar un rayo del vacío a otro enemigo cercano |
| Égida de Ceniza | Rara | Cada 15 s recuperas 2 corazones de absorción |
| Anillo de Espejo | Rara | 12% de anular por completo un golpe enemigo |
| Colmillo de Cristal | Maldita | +35% de daño infligido, +25% de daño recibido |
| Segador de Almas | Legendaria | Cada 8ª muerte desata una onda de almas |
| Pluma de Fénix | Legendaria | Una vida extra permanente |

Como las bendiciones, el daño "extra" de las reliquias **no afecta a Vorthak ni a sus heraldos**.
Opciones en `dungeon.yml` → `relics`: `enabled`, `every`, `choices`.

### Feedback visual (`dungeon.yml` → `fx`)
- **Números de daño flotantes** sobre los enemigos (color según el daño, críticos grandes y dorados).
- **Ambiente por sala**: cristales/bloques flotantes que giran y respiran, y partículas propias de cada tipo de sala
  (ceniza en oleadas, chispas rojas en trampas, almas en el altar, oro en la cámara de reliquias...).

## Novedades (parte 4): Mercader Fantasma y salas de Evento

Dos tipos de sala nuevos (`MERCHANT` y `EVENT` en `rooms:` de `dungeon.yml`; se pueden usar con el voto `"A|B"`).
**Ojo:** si tu `dungeon.yml` ya existe, el nuevo orden por defecto no se aplica solo: añade `MERCHANT` / `EVENT` a tu lista `rooms:`
(por ejemplo `rooms: [WAVES, "ALTAR|TRAP", "VAULT|PUZZLE", "WAVES|EVENT", "SWEEP|MERCHANT", MINIBOSS, REST]`).

### Mercader Fantasma (`/dungeon shop`)
Sala sin combate. Se gastan **brasas**, que se ganan matando (1 por enemigo, 4 por élite, 12 al vencer al mini jefe) y duran toda la partida.
Cada jugador ve su propia tienda:
- **Reliquia por brasas** (el precio depende de la rareza) y **reliquia por sangre** (cuesta vidas, nunca tu última).
- **Esencia Espectral**: te cura (hasta 3 por visita). **Vida Prestada**: recupera 1 vida (una por visita).
- **Pacto de Sangre**: entregas 1 vida y recibes brasas.
La sala se abre cuando todos pulsan «Listo» o se acaba el tiempo. Todos los precios están en `dungeon.yml` → `merchant`.

### Salas de Evento (`/dungeon event`)
Un texto y tres decisiones por jugador (seguras, con azar o con coste en brasas/vidas). Hay 5 eventos
(Altar Sangrante, Fuente Maldita, Cofre Trampa, Susurro del Vacío, Mendigo Espectral) y no se repiten en una misma partida.
Algunas salidas provocan una **emboscada**, que hay que superar antes de seguir. Los eventos son datos en `EventSystem.java`:
añadir uno es copiar un bloque `new Event(...)`. Tiempo para decidir en `dungeon.yml` → `event.seconds`.

## Novedades (parte 5): clases de inicio (`/dungeon class`)

En la sala de inicio aparece el **Heraldo de las Sombras** (clic derecho, o `/dungeon class`). Cada jugador elige su clase y
pulsa «Listo»; con todos listos hay una cuenta atrás corta y se abren las puertas. Si alguien tarda más de `classes.timeout-seconds`
se le asigna una clase al azar. Con `classes.enabled: false` vuelve la cuenta atrás clásica. Tras un wipe con reinicio se vuelve a elegir.

| Clase | Estilo | Kit inicial |
|---|---|---|
| Berserker | Frágil pero letal, dash que cura | Furia de Ceniza II, Filo Vampírico, Paso del Vacío, Dash Carmesí · **-1 vida** |
| Cazador del Vacío | Rápido y esquivo, el dash corta | Paso del Vacío III, Evasión Sombría, Pies de Ceniza · Sello del Cazador |
| Nigromante | Crece con cada muerte | Cosecha de Almas II, Rayo Encadenado, Paso del Vacío · Linterna de Almas · 10 brasas |
| Guardián | Aguanta y devuelve el golpe | Piel de Obsidiana II, Espinas de Sangre, Aliento de Vida · Égida de Ceniza · **+1 vida** |

Las clases son datos en `StartClass.java`: añadir una es copiar un bloque.

## Novedades (parte 6): niveles de Calor

Dificultad opcional estilo «ascensión». Cada jugador elige su nivel en el menú del Heraldo (botón de la hoguera). Los niveles se suman:

| Calor | Efecto |
|---|---|
| 1 · Marea de Ceniza | Enemigos con +15% de vida |
| 2 · Colmillos Afilados | Enemigos con +15% de daño |
| 3 · Élites Voraces | x2 de probabilidad de élites |
| 4 · Vidas Contadas | Una vida menos para todos |
| 5 · Sin Tregua | +1 oleada en las salas de oleadas |

Se desbloquean de uno en uno al **completar la dungeon** con el Calor anterior (se guarda por jugador en `dungeon-data.yml`).
El grupo juega con la media redondeada de las elecciones y el puntaje sube un 10% por nivel. Todo se ajusta en `dungeon.yml` → `heat`.
Requiere `classes.enabled: true`, porque el botón vive en el menú del Heraldo.

## Novedades (parte 7): Ecos y mejoras permanentes (`/dungeon meta`)

Meta-progresión: cada partida, **ganes o pierdas**, da **Ecos** (3 por sala superada; +25 por victoria y +8 por nivel de Calor).
Se gastan en el **Santuario de Ecos** (`/dungeon meta` o el botón del menú del Heraldo) en mejoras que valen para todas tus partidas futuras:

| Mejora | Niveles | Efecto |
|---|---|---|
| Bolsa de Brasas | 3 | +10 brasas iniciales por nivel |
| Fortuna | 3 | +1 relanzamiento de bendiciones por nivel |
| Segundo Aliento | 1 | +1 vida al empezar |
| Legado | 1 | Reliquia común al azar al empezar |

Se guarda en `dungeon-data.yml` (`echoes`, `perks`). Valores en `dungeon.yml` → `meta` (`enabled`, `per-room`, `win-bonus`, `per-heat-level`);
precios en `MetaSystem.Perk`. Los Ecos de una derrota con reinicio no se dan hasta el final definitivo de la partida.

## Novedades (parte 8): biomas

Cada partida se construye en un **bioma** distinto, con su paleta de bloques y partículas propias:
Catacumbas de Ceniza (clásico), Cripta Helada, Santuario del Vacío y Forja Carmesí. Se anuncia al abrirse las puertas.
En `dungeon.yml` → `biome`: `random` (por defecto), `ashen`, `frost`, `void` o `crimson`. Las paletas están en `Biome.java`
(solo se sustituyen bloques por otros de la misma forma: bloque, escalera, losa, muro). Las salas ya tenían ramas: en los pasos con
varias opciones (`"A|B"`) el grupo vota el camino.

## Novedades (parte 9): Hub de la dungeon y registro de jefes

Una **isla flotante decorada** (mundo vacío propio `dungeon_lobby`, se construye al arrancar y se borra al apagar) que hace de lobby:
- **Pedestales de jefe:** uno por cada entrada de `BossDef.ALL`. Clic derecho abre un menú para entrar en la cola, salirse o adelantar el inicio. Los jefes no disponibles aparecen **sellados**.
- **Santuario de Ecos** (clic derecho) y **Salón de la Fama** (top 3 de puntajes), más un cristal central que muestra la cola.
- Protegido: no se rompe ni se coloca nada, sin daño ni hambre, sin mobs (los admins en creativo pueden editar).

Comandos: `/dungeon hub` (ir al hub). Sin `/dungeon setlobby`, el lobby de la dungeon **es** el hub; si ya tenías uno fijado sigue mandando:
usa `/dungeon setlobby hub` para volver al hub. Config en `dungeon.yml` → `hub` (`enabled`, `world`).

**Añadir un jefe nuevo:** copia una línea de `BossDef.ALL` (id, nombre, color, icono, descripción, `available`). Su pedestal aparece solo en el hub
(hasta 5) y la cola ya es por jefe. El combate de cada jefe se implementa aparte: hoy solo Vorthak tiene combate (`DungeonInstance.spawnVorthak`).

## Novedades (parte 10): arenas grandes y objetivos de sala

Las salas de oleadas ya no son una caja vacía:
- **Más grandes** (45x45, 14 de alto por defecto) y con **arquitectura** distinta cada vez: *Ruinas* (columnas rotas, muros bajos y escombros como cobertura),
  *Terrazas* (cuatro plataformas elevadas con escaleras), *Catedral* (hileras de columnas altas con una nave central) o *Arcos* (anillo de arcos de piedra),
  más cadenas colgantes del techo. Siempre queda libre el pasillo entre las dos puertas.
- **Anclajes de objetivo:** cada arena marca 6 puntos en el suelo (altares) donde se colocan los objetivos.
- **Objetivos** (probabilidad `objectives.chance`, por defecto 65%; si no toca, oleada clásica):
  - *Cristales de invocación:* hay que **destruir** 3-5 cristales (vida propia, sube con el grupo y el Calor). Cada uno invoca enemigos sin parar y,
    con la mitad destruida, los que quedan invocan más rápido. Al destruir el último, los enemigos restantes se desvanecen.
  - *Faros:* hay que **capturar** 3 faros quedándote dentro del círculo (más gente = más rápido). Si hay 4+ enemigos encima el faro queda *disputado*;
    si te vas, el progreso baja. Llegan refuerzos continuos y, con el último faro, a veces un élite.

Ajustes en `dungeon.yml` → `objectives` (`enabled`, `chance`, `crystal-health`, `crystal-pulse-seconds`, `beacon-radius`, `beacon-seconds`,
`beacon-spawn-seconds`). **Ojo:** si tu `dungeon.yml` ya tiene el bloque `sizes:`, pon `WAVES: {size: 45, height: 14}` (o borra el bloque para usar los nuevos valores).

## Novedades (parte 11): secretos, vestíbulos y portales de camino

- **Vestíbulos:** los pasillos entre salas ahora miden 13 de largo y 9 de ancho, con alfombra, columnas y faroles; a veces tienen una **fuente curativa** (una vez por jugador) o un cofre oculto.
- **Cámaras ocultas:** en salas de oleadas/trampa/puzzle/barrido/altar puede haber un muro de ladrillo agrietado que se abre con una **palanca** escondida en la pared opuesta. Dentro hay un cofre grande.
- **Cofres secretos:** rincones con telarañas y calavera. El cofre es personal por jugador (botín, brasas, posible reliquia) y puede ser un **mímico**.
- **Portales de elección:** al terminar una sala aparecen arcos de portal junto a la salida; el grupo vota caminando hacia uno (el contador se actualiza en vivo). Hasta 3 opciones por paso.
- **Portal misterioso:** con cierta probabilidad una opción oculta su sala y da brasas extra al grupo.

Config en `dungeon.yml`: `secrets` (`chamber-chance`, `nook-chance`, `loot`, `souls-big`, `souls-small`, `relic-chance-big`, `relic-chance-small`, `mimic-chance`, `fountain-regen-seconds`) y `path` (`mystery-chance`, `mystery-souls`).

## Novedades (parte 12): Ysmera, Reina de Escarcha (segundo jefe final) y mobs por bioma

Un **segundo jefe final**, elegible en el Hub (pedestal azul de hielo). Fuerza el bioma **Cripta Helada** y construye su propia sala: *Corte de Escarcha*
(suelo rúnico helado con radios luminosos, trono de hielo, ocho columnas, cascadas heladas, carámbanos en el techo, cuatro **altares de cristal** y cuatro **hogueras** rodeadas de muretes).

**Modelo 3D propio en tres formas** (una por fase: reina azul, tormenta violeta con alas abiertas, cero absoluto negro y blanco con halo), con corona, cetro, alas de plumas de cristal,
fragmentos orbitando y carámbanos colgando. El código del jefe **dirige las animaciones** (nueva API en `MobVisuals`: `pose`, `lift`, `pulse`, `shake`, `tempo`, `drop`):
alza los brazos al lanzar, se eleva al canalizar, se encoge y reaparece al teletransportarse, tiembla antes de romperse...

| Fase | Qué pasa |
|---|---|
| **Intro** | Cinemática con franjas negras: la reina despierta y se eleva mientras la cámara orbita. |
| **I · Reina Gélida** | *Carámbanos* telegrafiados (caen del techo), *Onda helada* (**¡hay que saltarla!**, de 1 a 3 anillos), *Lanzas de escarcha* (proyectiles esquivables en abanico), *Salto glacial* (se teletransporta a tu espalda y hace un tajo en cono), *Invocación* de enemigos helados y **Agarre Helado**: congela a un jugador en un cubo de hielo y **un aliado debe acercarse** para descongelarlo (si nadie viene, recibe daño). |
| **Transición · Escudo de Cristal** (66%) | Se eleva inmune y despiertan **4 cristales** que debes romper (vida propia que sube con el grupo y el Calor) mientras invoca y dispara rayos desde cada cristal. Al caer el último queda **aturdida 11 s con +60% de daño recibido**. |
| **II · Tormenta** | Todo lo anterior más **Lanzas de hielo** en línea. Aparecen **ventiscas** con un **medidor de frío** (barra en pantalla y escarcha vanilla): estar fuera de las **hogueras** te congela; cada cierto tiempo una *ventisca* triplica el frío y empuja con el viento. |
| **Transición · Cero Absoluto** (33%) | 13 s de cuenta atrás: el suelo se congela desde el centro y solo se sobrevive junto a una **hoguera**. Al final, una explosión gélida daña a quien esté fuera. Después queda exhausta (+60% daño). |
| **III · Cero Absoluto** | Suelo de hielo **resbaladizo**, cooldowns más cortos, *Cometa glacial* (meteoro de hielo con onda expansiva) y **Frenesí** por debajo del 20% de vida. |
| **Muerte** | Cinemática de ~9 s: se congela por completo, se agrieta y estalla en cristales. |

Botín personal en el cofre: *Corazón de Escarcha*, cristales de prismarina, hielo azul, libros del jefe y, con probabilidad, el *Filo de Escarcha*.

**Mobs por bioma:** los cinco mobs helados (Congelado, Arquero de Escarcha, Yeti, Banshee de Ventisca y Chamán de Nieve) ya aparecen en las oleadas **cuando el bioma es Cripta Helada**
(los demás biomas siguen con los de ceniza; la Bestia aparece en todos).

**Arquitectura de jefes:** nueva interfaz `DungeonBoss` (spawn, tick, daño, muerte, botín...) y `Cinematic` (cámara reutilizable con franjas). Un jefe nuevo = una clase que implemente `DungeonBoss`,
una línea en `BossDef.ALL` y un `case` en `DungeonInstance.spawnCustomBoss`.

Ajustes en `dungeon.yml` → `ysmera` (`health`, `health-per-extra-player`, `crystal-health`, `blade-chance`, `xp`, `loot`). Como siempre, si ya tienes `dungeon.yml` se rellenan los valores que falten.
**Resource pack:** se ha regenerado (`CustomMobsPack.zip`) y `models.sha1` de `config.yml` actualizado: súbelo a `main` junto con el código o los modelos no cargarán.

## Novedades (parte 13): Ysmera mucho más dura, cinemáticas y objetivos nuevos

- **Ysmera**: más daño y menos vida efectiva (`ysmera.damage-mul` 1.4, `damage-taken-mul` 0.65, `cooldown-mul` 0.75), combos que encadenan ataques y **enrage** a los `ysmera.enrage-seconds` (480 s): +40 % de daño y lluvia de carámbanos continua.
- **Ataques nuevos**: *Charcos de escarcha* (zonas que ralentizan y congelan), *Vórtice* (succiona a todos y detona: aléjate del centro), *Rayo giratorio* (hay que saltarlo), *Fractura* (grietas radiales de lanzas: colócate entre ellas). Además **las hogueras se apagan** en las fases II y III; quédate en una apagada para reavivarla.
- **Cinemáticas**: intro de 3 planos, y una cámara propia en el escudo de cristal, al romperlo, en el Cero Absoluto, al terminarlo y una muerte de ~13 s.
- **Objetivos de sala nuevos** (además de cristales y faros, elegidos al azar): *Defender el núcleo* (resiste N s mientras los enemigos lo desgastan), *Runas en orden* (pisarlas en orden 1→N; si fallas, se reinician y aparecen enemigos) y *Caza al campeón* (un campeón brillante se teletransporta e invoca refuerzos). Claves: `objectives.core-health`, `core-seconds`, `rune-hold-seconds`, `champion-blink-seconds`, `champion-health-mul`.
