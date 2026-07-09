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
echo   Сборка Lecture Notes
echo ==========================================
echo.

echo [1/5] Сборка проекта...
call .\gradlew assembleDebug --quiet
if errorlevel 1 (
    echo.
    echo [ОШИБКА] Сборка упала!
    echo Исправь код и нажми любую клавишу для повтора...
    pause >nul
    goto BUILD_LOOP
)
echo [OK] Сборка успешна!
echo.

echo [2/5] Проверка подключения устройства...
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo [ОШИБКА] Устройство не найдено!
    echo Подключи телефон и нажми любую клавишу...
    pause >nul
    goto BUILD_LOOP
)
echo [OK] Устройство найдено
echo.

echo [3/5] Установка APK...
"%ADB%" install -r -d "%APK%" >nul 2>&1
if errorlevel 1 (
    echo [ОШИБКА] Установка не удалась!
    pause
    goto BUILD_LOOP
)
echo [OK] Установлено
echo.

echo [4/5] Запуск приложения...
"%ADB%" shell am start -n %PKG%/.MainActivity >nul
echo [OK] Приложение запущено!
echo.

echo ==========================================
echo   Сборка и запуск завершены!
echo ==========================================
echo.

REM Проверяем, есть ли изменения для коммита
git status --porcelain > "%TEMP%\git_status.txt" 2>nul
for %%A in ("%TEMP%\git_status.txt") do set "FSIZE=%%~zA"
if "!FSIZE!"=="0" (
    echo Изменений нет.
    echo.
    echo [ENTER] = пересобрать
    echo [Q]     = выйти
    echo.
    set "CHOICE="
    set /p CHOICE="Ваш выбор: "
    if /i "!CHOICE!"=="Q" exit
    goto BUILD_LOOP
)

echo Обнаружены изменения в проекте.
echo.
echo [1] Сохранить в новую ветку на GitHub
echo [2] Выйти без сохранения
echo.
set "ACTION="
set /p ACTION="Выбери (1/2): "

if "!ACTION!"=="2" exit
if not "!ACTION!"=="1" (
    echo Неверный выбор. Выход.
    exit
)

echo.
set "BRANCH="
set /p BRANCH="Введи имя ветки (например: streaming-fix): "
if "!BRANCH!"=="" (
    echo Имя ветки не указано. Выход.
    exit
)

REM Проверяем, существует ли ветка
git rev-parse --verify "!BRANCH!" >nul 2>&1
if not errorlevel 1 (
    REM Ветка существует, добавляем timestamp
    for /f "tokens=1-3 delims=/. " %%a in ('date /t') do set "D=%%a%%b%%c"
    for /f "tokens=1-2 delims=: " %%a in ('time /t') do set "T=%%a%%b"
    set "T=!T: =0!"
    set "BRANCH=!BRANCH!-!D!-!T!"
    echo Ветка существует, используем: !BRANCH!
)

echo.
echo [5/5] Создание ветки и сохранение...
git checkout -b "!BRANCH!" >nul 2>&1
if errorlevel 1 (
    echo [ОШИБКА] Не удалось создать ветку!
    pause
    goto BUILD_LOOP
)

git add . >nul 2>&1
git commit -m "Сборка: !BRANCH!" >nul 2>&1

echo Отправка на GitHub...
git push -u origin "!BRANCH!" >nul 2>&1
if errorlevel 1 (
    echo [ПРЕДУПРЕЖДЕНИЕ] Не удалось отправить на GitHub. Коммит сохранён локально.
) else (
    echo [OK] Отправлено на GitHub: !BRANCH!
)

echo.
echo ==========================================
echo   Готово! Ветка: !BRANCH!
echo ==========================================
echo.
echo [ENTER] = пересобрать
echo [Q]     = выйти
echo.
set "CHOICE="
set /p CHOICE="Ваш выбор: "
if /i "!CHOICE!"=="Q" exit
goto BUILD_LOOP
'@ | Set-Content -Path "build.bat" -Encoding ASCII