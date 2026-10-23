@echo off
REM Descarga Purpur 26.1.2 (requiere Java 25) y compila el plugin CustomMobs.
cd /d "%~dp0"
set VERSION=26.1.2

where java >nul 2>nul || (echo Instala Java 25 primero: https://adoptium.net & pause & exit /b 1)

echo Descargando Purpur %VERSION%...
curl.exe -fL -o purpur.jar "https://api.purpurmc.org/v2/purpur/%VERSION%/latest/download" || (echo Fallo la descarga & pause & exit /b 1)

echo Compilando plugin...
pushd CustomMobs
if exist gradlew.bat (call gradlew.bat build) else (call gradle build)
if errorlevel 1 (echo Fallo la compilacion & popd & pause & exit /b 1)
popd

if not exist plugins mkdir plugins
copy /y CustomMobs\build\libs\CustomMobs-*.jar plugins\ >nul

echo.
echo Listo. Abre eula.txt, pon eula=true y ejecuta start.bat
pause
