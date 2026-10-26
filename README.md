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
