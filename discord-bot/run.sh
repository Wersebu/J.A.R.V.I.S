#!/usr/bin/env bash
# Uruchamia bota na Linuxie/serwerze. Czyta zmienne z pliku .env w tym katalogu.
set -euo pipefail
cd "$(dirname "$0")"

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
else
  echo "Brak pliku .env - skopiuj .env.example do .env i uzupełnij." >&2
  exit 1
fi

JAR="target/jarvis-discord-bot.jar"
if [ ! -f "$JAR" ]; then
  echo "Brak $JAR - zbuduj najpierw: mvn -q package" >&2
  exit 1
fi

exec java -jar "$JAR"
