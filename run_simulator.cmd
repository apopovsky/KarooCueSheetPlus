@echo off
setlocal
set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
set "EMULATOR=%SDK%\emulator\emulator.exe"
set "APK=%~dp0simulator\build\outputs\apk\debug\simulator-debug.apk"

echo [1/4] Verificando si ya hay un emulador/dispositivo conectado...
"%ADB%" start-server >nul 2>&1
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo Iniciando emulador Medium_Phone_API_35 en tu escritorio...
    start "" "%EMULATOR%" -avd Medium_Phone_API_35
)

echo [2/4] Esperando a que Android termine de iniciar...
"%ADB%" wait-for-device
:wait_boot
for /f "delims=" %%i in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set "BOOT=%%i"
if not "%BOOT%"=="1" (
    timeout /t 2 /nobreak >nul
    goto wait_boot
)

echo [3/4] Instalando CueSheet+ Simulator (con Desafio Rio Pinto 2022)...
"%ADB%" install -r "%APK%"

echo [4/4] Abriendo CueSheet+ Simulator...
"%ADB%" shell am start -n com.karoocuesheetplus.simulator/.CueSheetSimulatorActivity
echo Listo!
