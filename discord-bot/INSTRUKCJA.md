# Jarvis Discord Bot — „Zrzęda"

Osobna apka w Javie: bot na Discordzie, który loguje się do **Jarvis Core jako własny user**
(osobne konto → osobny `globalPrompt` → własna osobowość), a odpowiedzi generuje przez `/api/v1/chat`.
To samo konto wykorzystasz później do poważniejszych rzeczy — wystarczy podmienić personę.

Apka gada z core **tylko po HTTP**, więc jest niezależna od builda core i można ją wrzucić na serwer osobno.

---

## Jak to działa (w skrócie)

1. Start → bot loguje się do core (`/api/v1/auth/login`) i dostaje token (ważny 14 dni, auto-odnawiany przy 401).
2. Jeśli `BOT_PUSH_PERSONA=true` → wysyła personę jako `globalPrompt` tego usera (`PUT /api/v1/users/me/settings`).
   Core wplata ją do każdego promptu jako `USER GLOBAL PROMPT`.
3. Łączy się z Discordem, słucha wiadomości.
4. Na wybrane wiadomości woła `POST /api/v1/chat` z `conversationId = discord-<idKanału>`
   (czyli **pamięta kontekst osobno dla każdego kanału**) i wrzuca odpowiedź.

---

## Krok 1 — Bot na Discordzie

1. Wejdź na https://discord.com/developers/applications → **New Application**, nazwij (np. „Zrzęda").
2. Zakładka **Bot**:
   - **Reset Token** → skopiuj token (to jest `DISCORD_TOKEN`). Trzymaj go w sekrecie.
   - Włącz **MESSAGE CONTENT INTENT** (sekcja *Privileged Gateway Intents*). **Bez tego bot nie widzi treści wiadomości.**
3. Zakładka **OAuth2 → URL Generator**:
   - Scopes: **bot** oraz **applications.commands** (to drugie jest potrzebne do slash-komendy `/trolbot`)
   - Bot Permissions: **View Channels**, **Send Messages**, **Read Message History**
   - Skopiuj wygenerowany URL, otwórz w przeglądarce i dodaj bota na swój serwer.

   Gotowy link (podmień `TWOJE_APP_ID` na Application ID z zakładki *General Information*):
   `https://discord.com/oauth2/authorize?client_id=TWOJE_APP_ID&scope=bot+applications.commands&permissions=68608`

   > Jeśli bot był już dodany tylko ze scope `bot`, otwórz powyższy link jeszcze raz (z `applications.commands`),
   > żeby slash-komenda `/trolbot` mogła się zarejestrować.

---

## Krok 2 — Konto bota w Jarvis Core

Core musi **działać** (domyślnie `http://localhost:8080`). Konto zakłada **administrator** core.
Nowe konta zakładane w ten sposób mają rolę `USER` (nie admin) — dokładnie o to chodzi.

### Jeśli masz już admina w core (normalny przypadek)

PowerShell (Windows):

```powershell
# 1. zaloguj swojego admina
$login = Invoke-RestMethod -Uri http://localhost:8080/api/v1/auth/login -Method Post `
  -ContentType application/json -Body '{"email":"TWOJ_ADMIN@mail","password":"HASLO_ADMINA"}'
$token = $login.accessToken

# 2. utwórz konto bota
$body = @{ email="bot-troll@jarvis.local"; password="dlugie-haslo-bota-min-8"; displayName="Zrzęda" } | ConvertTo-Json
Invoke-RestMethod -Uri http://localhost:8080/api/v1/auth/users -Method Post `
  -ContentType application/json -Headers @{Authorization="Bearer $token"} -Body $body
```

bash/curl (Linux/serwer):

```bash
TOKEN=$(curl -s http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"TWOJ_ADMIN@mail","password":"HASLO_ADMINA"}' | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')

curl -s http://localhost:8080/api/v1/auth/users \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{"email":"bot-troll@jarvis.local","password":"dlugie-haslo-bota-min-8","displayName":"Zrzęda"}'
```

### Jeśli core jest świeży (brak jakiegokolwiek usera — „bootstrap required")

Najpierw załóż **swojego** admina, potem konto bota jak wyżej:

```bash
curl -s http://localhost:8080/api/v1/auth/bootstrap \
  -H 'Content-Type: application/json' \
  -d '{"email":"ty@mail","password":"haslo-admina-min-8","displayName":"Admin"}'
```

> Hasło musi mieć min. 8 znaków, e-mail musi zawierać `@`. E-mail bota może być „techniczny" (np. `bot-troll@jarvis.local`) — to tylko login.

---

## Krok 3 — Konfiguracja

```bash
cp .env.example .env
```

Uzupełnij w `.env`:
- `DISCORD_TOKEN` — token z kroku 1
- `JARVIS_BASE_URL` — adres core (np. `http://localhost:8080`)
- `JARVIS_BOT_EMAIL` / `JARVIS_BOT_PASSWORD` — **dokładnie** te, które ustawiłeś przy zakładaniu konta bota

Reszta (szansa odpowiedzi, cooldown, lista kanałów) ma sensowne domyślne wartości — opis przy każdej w `.env.example`.

---

## Krok 4 — Budowanie

Wymaga JDK 21 i Mavena (pierwszy build pobiera JDA/Jackson z internetu).

```bash
mvn -q clean package
```

Powstaje jeden plik: `target/jarvis-discord-bot.jar` (fat jar, ma w sobie wszystkie zależności).

> Na serwerze bez Mavena/internetu: zbuduj jar tam, gdzie masz Maven, i skopiuj na serwer
> sam **`target/jarvis-discord-bot.jar` + `.env` + `run.sh`**. Do uruchomienia wystarczy Java 21.

---

## Krok 5 — Uruchomienie

Linux/serwer:

```bash
./run.sh
```

Windows:

```bat
run.cmd
```

Albo ręcznie (zmienne z `.env` ustawione w środowisku):

```bash
java -jar target/jarvis-discord-bot.jar
```

W logach zobaczysz `Logged in to Jarvis Core...`, `Persona published...` i `Jarvis troll bot is online...`.

---

## Uruchomienie jako usługa (Ubuntu, systemd) — opcjonalnie

`/etc/systemd/system/jarvis-discord-bot.service`:

```ini
[Unit]
Description=Jarvis Discord Bot
After=network-online.target
Wants=network-online.target

[Service]
WorkingDirectory=/opt/jarvis-discord-bot
EnvironmentFile=/opt/jarvis-discord-bot/.env
ExecStart=/usr/bin/java -jar /opt/jarvis-discord-bot/target/jarvis-discord-bot.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now jarvis-discord-bot
journalctl -u jarvis-discord-bot -f
```

---

## Sterowanie na żywo — slash-komenda

Na kanale (musisz mieć uprawnienie **Zarządzanie serwerem**):

```
/trolbot randompickmessage value:0.5
```

- Ustawia szansę odpowiedzi **na tym konkretnym kanale**, na wiadomości **bez pingu**:
  `0` = nigdy, `0.5` = 50% wiadomości, `1` = na każdą wiadomość.
- Na **ping** (`@TrollBot`) bot odpowiada **zawsze**, niezależnie od tej wartości.
- Ustawienie zapisuje się do pliku `channel-settings.json` i **przeżywa restart**.
- Każda odpowiedź idzie z **kontekstem ostatnich ~50 wiadomości kanału** (w tym własnych bota),
  a wskaźnik **„pisze…"** trzyma się aż do wysłania pełnej odpowiedzi.

## Strojenie (zmienne w `.env`)

- **Osobowość**: edytuj `src/main/java/com/jarvis/discordbot/Persona.java` i przebuduj,
  albo ustaw `BOT_PERSONA_FILE=/sciezka/do/persona.txt` (bez przebudowy).
  Zmiana trafia do core przy następnym starcie (gdy `BOT_PUSH_PERSONA=true`).
- **Domyślna szansa** (dla kanałów bez `/trolbot ...`): `BOT_REPLY_CHANCE` (0..1).
  Oznaczenie `@bota` zawsze wywołuje odpowiedź (jeśli `BOT_ALWAYS_REPLY_ON_MENTION=true`).
- **Ile kontekstu**: `BOT_CONTEXT_MESSAGES=50` — ile ostatnich wiadomości kanału bot dołącza do odpowiedzi.
- **Tylko wybrane kanały**: `BOT_ALLOWED_CHANNEL_IDS=123...,456...` (ID kanału: prawy klik → *Copy Channel ID*,
  po włączeniu trybu developerskiego w Discordzie).

> Dłuższą ciągłość rozmowy trzyma pamięć core per kanał. Żeby sięgała dokładnie ~50 wiadomości,
> uruchom core ze zmienną `JARVIS_CONVERSATION_HISTORY_MAX_MESSAGES=50` (domyślne 30 też działa).

## Uwaga o tonie

Persona w `Persona.java` ma celowe zabezpieczenia: dogryza **stylowi gry i wiadomościom**, nie atakuje
prawdziwych cech ludzi, bez wulgaryzmów wymierzonych w osobę, gróźb i treści dyskryminujących, a przy realnym
problemie gracza wychodzi z roli. Jak będziesz ją przerabiać — warto te zasady zostawić, żeby „beka" nie
zrobiła się przykra.

## Późniejsze „poważne" użycie

To samo konto bota możesz wykorzystać do innych zadań — zmień `globalPrompt` (persona) na np. asystenta do
pracy z danymi, a resztę (logowanie, pamięć per kanał, obsługa 401) masz już gotową.
