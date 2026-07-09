@'
@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

set "ADB=C:\Users\Infinity\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "APK=app\build\outputs\apk\debug\app-debug.apk"
set "PKG=com.example.lecturenotes"

:BUILD_LOOP
cls
echo ==========================================
echo   Lecture Notes Build
echo ==========================================
echo.
echo [1/4] Build...
call .\gradlew assembleDebug --quiet

if errorlevel 1 (
    echo.
    echo [ERROR] Build failed!
    echo Fix code and press any key to retry...
    pause >nul
    goto BUILD_LOOP
)

echo [OK] Build successful!
echo.
echo [2/4] Checking device...
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo [ERROR] No device connected!
    echo Connect device and press any key...
    pause >nul
    goto BUILD_LOOP
)

echo [OK] Device found
echo.
echo [3/4] Installing...
"%ADB%" install -r -d "%APK%" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Install failed!
    pause
    goto BUILD_LOOP
)

echo [OK] Installed
echo.
echo [4/4] Launching...
"%ADB%" shell am start -n %PKG%/.MainActivity >nul

echo.
echo ==========================================
echo   App launched!
echo ==========================================
echo.
echo [ENTER] = rebuild
echo [Q]     = quit
echo.
set "CHOICE="
set /p CHOICE="Choose: "
if /i "!CHOICE!"=="Q" exit
goto BUILD_LOOP
'@ | Set-Content -Path "build.bat" -Encoding ASCII