@echo off
setlocal
cd /d "%~dp0"

if not exist "target\jarvis-0.1.0.jar" (
  echo.
  echo [!] No encuentro target\jarvis-0.1.0.jar
  echo     Primero compila en IntelliJ: panel Maven ^(derecha^) ^> jarvis ^> Lifecycle ^> package
  echo.
  pause
  exit /b 1
)

set "JP="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jpackage.exe" set "JP=%JAVA_HOME%\bin\jpackage.exe"
if not defined JP for /f "delims=" %%i in ('where jpackage 2^>nul') do if not defined JP set "JP=%%i"
if not defined JP for /d %%d in ("%USERPROFILE%\.jdks\*") do if exist "%%d\bin\jpackage.exe" set "JP=%%d\bin\jpackage.exe"
if not defined JP (
  echo [!] No encontre jpackage. Configura JAVA_HOME con la ruta de tu JDK.
  pause
  exit /b 1
)
echo Usando: %JP%

if exist "target\dist" rmdir /s /q "target\dist"
if exist "dist\JARVIS" rmdir /s /q "dist\JARVIS"
mkdir "target\dist"
copy /y "target\jarvis-0.1.0.jar" "target\dist\" >nul

"%JP%" --type app-image ^
  --name JARVIS ^
  --app-version 0.1.0 ^
  --vendor "JARVIS" ^
  --input "target\dist" ^
  --main-jar jarvis-0.1.0.jar ^
  --icon "packaging\jarvis.ico" ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --dest dist

if errorlevel 1 (
  echo [!] jpackage fallo. Revisa el mensaje de arriba.
  pause
  exit /b 1
)

echo.
echo ============================================================
echo  Listo:  dist\JARVIS\JARVIS.exe
echo  Copia la carpeta dist\JARVIS completa donde quieras.
echo  Clic derecho en JARVIS.exe ^> Enviar a ^> Escritorio (crear acceso directo)
echo ============================================================
explorer "dist\JARVIS"
pause
