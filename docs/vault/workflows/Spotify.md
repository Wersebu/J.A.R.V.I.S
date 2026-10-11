---
id: jarvis-spotify-workflow-v1
type: workflow
title: Spotify — odtwarzanie i biblioteka
status: active
version: 2
tags: [Spotify, muzyka, piosenka, utwór, odtwarzanie, playlista, polubione, kolejka, głośność, telewizor, głośnik, soundbar]
aliases: [puść muzykę, odpal muzykę, co leci, co teraz leci, co gra, co teraz gra, jaka to piosenka, co to za piosenka, co to za utwór, czego słucham, następna piosenka, następny utwór, ścisz, podgłoś]
---

# Spotify — odtwarzanie i biblioteka

Do wszystkiego, co dotyczy Spotify, są gotowe narzędzia. Każde załatwia sprawę JEDNYM wywołaniem:
Core sam sprawdza połączenie, urządzenia, otwiera aplikację i weryfikuje, czy faktycznie gra.
Nie czytaj urządzeń ani tego dokumentu ponownie, tylko wybierz narzędzie i argumenty, a potem odpowiedz
krótko z wyniku (tytuł, wykonawca, urządzenie). Gdy narzędzie zgłasza problem, opisz go, nie powtarzaj wywołania.

## Odtwarzanie: pc__spotify_play

| Prośba użytkownika | Argumenty |
|---|---|
| „odpal coś”, „puść muzykę” | source=liked |
| „coś z polubionych” | source=liked |
| „coś zupełnie losowego”, „zaskocz mnie” | source=random |
| „puść <tytuł>”, „puść <wykonawca>” | source=search, query=<tytuł/wykonawca>, type=track |
| „puść album <nazwa>” | source=search, query=<nazwa>, type=album |
| „puść playlistę <nazwa>” | source=search, query=<nazwa>, type=playlist |
| „playlista <nazwa> od <osoba>” | source=search, query=<nazwa>, type=playlist, owner=<osoba> |
| „moja playlista <nazwa>” | source=library, query=<nazwa> |
| „dodaj <tytuł> do kolejki”, „potem puść <tytuł>” | source=search, query=<tytuł>, mode=queue |
| „przenieś to na telewizor”, „odpal to na TV” | source=current, device=telewizor |
| „puść coś na telewizorze” | source=liked, device=telewizor |

- device puste oznacza ten komputer. Inne urządzenie (telewizor, TV, głośnik, soundbar, telefon) tylko wtedy,
  gdy użytkownik je wymienił.
- pick=first bierze najlepsze dopasowanie (domyślne dla wyszukiwania), pick=random losuje (domyślne dla polubionych).

## Sterowanie tym, co gra: pc__spotify_control

| Prośba użytkownika | action |
|---|---|
| „co teraz leci?”, „co to za piosenka?”, „co gra?” (także bez słowa Spotify) | now |
| „polub to”, „dodaj do polubionych” | like |
| „następna”, „next”, „dalej” | next |
| „poprzednia”, „cofnij” | previous |
| „pauza”, „stop”, „zatrzymaj” | pause |
| „wznów”, „graj dalej” | resume |
| „głośniej/ciszej”, „głośność 40” | volume, value=0–100 |
| „losowo”, „bez losowania” | shuffle, state=true/false |
| „zapętl”, „zapętl utwór”, „bez zapętlania” | repeat, state=context/track/off |

Działa na urządzeniu, które właśnie gra (komputer albo telewizor).

## Zasady

- Nie używaj przeglądarki ani Web Playera. pc__media nie zastępuje Spotify.
- Nie twórz URI ani linków z nazw. Odpowiadaj tylko na podstawie wyniku narzędzia.
- Telewizor bez zalogowanego Spotify jest widoczny dopiero po jednorazowym połączeniu się z nim
  z aplikacji (Spotify Connect). Gdy narzędzie go nie widzi, powiedz to wprost.
- Nie pytaj o hasło, Client Secret ani token. Logowanie: pc__spotify action=connect tylko na prośbę użytkownika.
- Biblioteka i playlisty do odczytu (bez odtwarzania): pc__spotify action=liked|library|playlists|playlist_tracks|search.
  Tworzenie playlist i dodawanie do nich: pc__spotify create_playlist / add_to_playlist, tylko na wyraźną prośbę.
