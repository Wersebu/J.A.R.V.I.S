---
id: kb-serwer-domowy
title: Serwer domowy
type: knowledge
tags: [infrastruktura, serwer]
status: active
updated: 2026-10-01
---
# Serwer domowy

Serwer domowy obsługuje J.A.R.V.I.S. Core, Ollamę oraz usługi głosowe. Działa bez przerwy, a dostęp z zewnątrz jest
możliwy wyłącznie przez VPN.

## Sprzęt

- Procesor: AMD Ryzen 7 7700, 8 rdzeni.
- Pamięć RAM: 64 GB DDR5.
- Karta graficzna: NVIDIA RTX 4060 Ti z 16 GB VRAM, współdzielona przez model czatu i syntezę mowy.
- Dyski: systemowy NVMe oraz dysk na dane.

## System

Ubuntu Server 24.04 LTS. Usługi są zarządzane przez systemd. Core działa jako użytkownik `jarvis` w katalogu
`/opt/jarvis`.

## Monitoring

### Wpis 1

Rutynowa kontrola (serwer) numer 1: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 2

Rutynowa kontrola (serwer) numer 2: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 3

Rutynowa kontrola (serwer) numer 3: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 4

Rutynowa kontrola (serwer) numer 4: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 5

Rutynowa kontrola (serwer) numer 5: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 6

Rutynowa kontrola (serwer) numer 6: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 7

Rutynowa kontrola (serwer) numer 7: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 8

Rutynowa kontrola (serwer) numer 8: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 9

Rutynowa kontrola (serwer) numer 9: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 10

Rutynowa kontrola (serwer) numer 10: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 11

Rutynowa kontrola (serwer) numer 11: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 12

Rutynowa kontrola (serwer) numer 12: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 13

Rutynowa kontrola (serwer) numer 13: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 14

Rutynowa kontrola (serwer) numer 14: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.


## Kopie zapasowe

Nocna kopia zapasowa startuje codziennie o 03:15 i obejmuje katalogi `knowledge`, `data` oraz `config`.

## Dostęp

SSH nasłuchuje na porcie 2222, logowanie wyłącznie kluczem. Hasła są wyłączone.

## Zasilanie

Serwer chroni zasilacz awaryjny APC Back-UPS 1400, który podtrzymuje pracę przez około 20 minut.
