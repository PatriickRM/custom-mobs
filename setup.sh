#!/usr/bin/env bash
# Descarga Purpur 26.1.2 (requiere Java 25) y compila el plugin CustomMobs.
set -e
cd "$(dirname "$0")"

VERSION="26.1.2"

if ! command -v java >/dev/null; then
  echo "Instala Java 25 primero (https://adoptium.net)"; exit 1
fi

echo "Descargando Purpur $VERSION..."
curl -fL -o purpur.jar "https://api.purpurmc.org/v2/purpur/$VERSION/latest/download"

echo "Compilando plugin..."
cd CustomMobs
if [ -x ./gradlew ]; then ./gradlew build; else gradle build; fi
cd ..

mkdir -p plugins
cp CustomMobs/build/libs/CustomMobs-*.jar plugins/

echo
echo "Listo. Abre eula.txt, pon eula=true y ejecuta ./start.sh"
