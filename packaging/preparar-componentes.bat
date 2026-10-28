@echo off
rem Proyecto DYSA - JARVIS Inventario
rem Autores: D: Didier - Y: Yoel - S: Sumi - A: Apaza
rem
rem Junta en packaging\incluir lo que el instalador mete junto a JARVIS.exe:
rem   voz\vosk   modelo de reconocimiento de voz en espanol
rem   voz\piper  piper.exe y la voz es_ES-davefx-medium
rem   ia\ollama  Ollama portable    ia\modelos  el modelo llama3.2:3b
rem Uso:  preparar-componentes.bat          (todo, unos 4 GB de descarga)
rem       preparar-componentes.bat sin-ia   (solo la voz)
rem Necesita Internet solo esta vez, en la PC que arma el instalador.
setlocal
cd /d "%~dp0"

set "INC=%CD%\incluir"
set "TMPD=%INC%\_descargas"
set "VOSK=vosk-model-small-es-0.42"
set "VOZ=es_ES-davefx-medium"
set "MODELO=llama3.2:3b"
set "MIO=%USERPROFILE%\jarvis-voz"
mkdir "%TMPD%" 2>nul

echo [1/3] Vosk
if exist "%INC%\voz\vosk\%VOSK%\am" (
  echo     ya esta
) else if exist "%MIO%\vosk\%VOSK%\am" (
  robocopy "%MIO%\vosk\%VOSK%" "%INC%\voz\vosk\%VOSK%" /e /njh /njs /nfl /ndl >nul
) else (
  curl -L --fail -o "%TMPD%\vosk.zip" "https://alphacephei.com/vosk/models/%VOSK%.zip" || goto error
  mkdir "%INC%\voz\vosk" 2>nul
  tar -xf "%TMPD%\vosk.zip" -C "%INC%\voz\vosk" || goto error
)

echo [2/3] Piper
if exist "%INC%\voz\piper\piper.exe" (
  echo     ya esta
) else if exist "%MIO%\piper\piper.exe" (
  robocopy "%MIO%\piper" "%INC%\voz\piper" /e /njh /njs /nfl /ndl /xf *.wav >nul
) else (
  curl -L --fail -o "%TMPD%\piper.zip" "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip" || goto error
  mkdir "%INC%\voz" 2>nul
  tar -xf "%TMPD%\piper.zip" -C "%INC%\voz" || goto error
)
if not exist "%INC%\voz\piper\voces\%VOZ%.onnx.json" (
  mkdir "%INC%\voz\piper\voces" 2>nul
  curl -L --fail -o "%INC%\voz\piper\voces\%VOZ%.onnx" "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/davefx/medium/%VOZ%.onnx" || goto error
  curl -L --fail -o "%INC%\voz\piper\voces\%VOZ%.onnx.json" "https://huggingface.co/rhasspy/piper-voices/resolve/main/es/es_ES/davefx/medium/%VOZ%.onnx.json" || goto error
)

if /i "%~1"=="sin-ia" (
  echo [3/3] IA avanzada: omitida
  if exist "%INC%\ia" rmdir /s /q "%INC%\ia"
  goto fin
)

echo [3/3] Ollama y el modelo %MODELO%
set "OLL=%INC%\ia\ollama\ollama.exe"
if not exist "%OLL%" (
  curl -L --fail -o "%TMPD%\ollama.zip" "https://github.com/ollama/ollama/releases/latest/download/ollama-windows-amd64.zip" || goto error
  mkdir "%INC%\ia\ollama" 2>nul
  tar -xf "%TMPD%\ollama.zip" -C "%INC%\ia\ollama" || goto error
)
rem Un Ollama aparte en el puerto 11500 para no tocar el que ya tengas abierto.
set "OLLAMA_HOST=127.0.0.1:11500"
set "OLLAMA_MODELS=%INC%\ia\modelos"
set "OPID="
for /f %%p in ('powershell -NoProfile -Command "(Start-Process -FilePath $env:OLL -ArgumentList 'serve' -WindowStyle Hidden -PassThru).Id"') do set "OPID=%%p"
if not defined OPID goto error
set /a N=0
:espera
"%OLL%" list >nul 2>&1 && goto bajar
set /a N+=1
if %N% geq 30 goto error_ia
timeout /t 1 /nobreak >nul
goto espera
:bajar
"%OLL%" pull %MODELO%
if errorlevel 1 goto error_ia
taskkill /pid %OPID% /t /f >nul 2>&1

:fin
rmdir /s /q "%TMPD%" 2>nul
echo.
echo ============================================================
echo  Listo: packaging\incluir
echo  Ahora ejecuta crear-exe.bat para armar JARVIS y su instalador.
echo ============================================================
pause
exit /b 0

:error_ia
taskkill /pid %OPID% /t /f >nul 2>&1
:error
echo.
echo [!] Algo fallo. Revisa la conexion a Internet y el mensaje de arriba.
pause
exit /b 1
