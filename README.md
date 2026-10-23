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
