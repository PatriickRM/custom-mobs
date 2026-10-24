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
