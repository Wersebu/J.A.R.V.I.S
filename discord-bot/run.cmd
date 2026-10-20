@echo off
REM Uruchamia bota na Windowsie. Czyta zmienne z pliku .env w tym katalogu.
setlocal enabledelayedexpansion
cd /d "%~dp0"

if not exist ".env" (
  echo Brak pliku .env - skopiuj .env.example do .env i uzupelnij.
  exit /b 1
)

for /f "usebackq tokens=1,* delims==" %%a in (".env") do (
  set "line=%%a"
  if not "!line:~0,1!"=="#" if not "%%a"=="" set "%%a=%%b"
)

if not exist "target\jarvis-discord-bot.jar" (
  echo Brak target\jarvis-discord-bot.jar - zbuduj najpierw: mvn -q package
  exit /b 1
)

java -jar "target\jarvis-discord-bot.jar"
endlocal
