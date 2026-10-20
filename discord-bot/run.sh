#!/usr/bin/env bash
# Uruchamia bota na Linuxie/serwerze. Czyta zmienne z pliku .env w tym katalogu.
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "Brak pliku .env - skopiuj .env.example do .env i uzupełnij." >&2
  exit 1
fi

# Wczytuje .env BEZ wykonywania zawartości jako poleceń, więc wartości ze spacjami
# i znakami specjalnymi (np. hasło) nie psują startu.
while IFS= read -r line || [ -n "$line" ]; do
  line=${line%$'\r'}                 # usuń CR z plików zapisanych pod Windowsem
  case "$line" in
    ''|\#*) continue ;;              # pomiń puste linie i komentarze
    *=*) : ;;                        # linia musi mieć '='
    *) continue ;;
  esac
  key=${line%%=*}
  val=${line#*=}
  key=${key// /}                     # klucz bez spacji
  case "$val" in                     # zdejmij otaczające cudzysłowy, jeśli są
    \"*\") val=${val#\"}; val=${val%\"} ;;
    \'*\') val=${val#\'}; val=${val%\'} ;;
  esac
  [ -n "$key" ] && export "$key=$val"
done < .env

JAR="target/jarvis-discord-bot.jar"
if [ ! -f "$JAR" ]; then
  echo "Brak $JAR - zbuduj najpierw: mvn -q clean package" >&2
  exit 1
fi

exec java -jar "$JAR"
