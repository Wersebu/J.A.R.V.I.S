---
id: kb-ollama
title: Ollama i modele
type: knowledge
tags: [ollama, modele, ai]
---
# Ollama i modele

## Model czatu

Głównym modelem czatu jest gemma4:12b uruchamiany w Ollamie na karcie RTX 4060 Ti. Okno kontekstu ustawiono na 32768
tokenów, a model pozostaje załadowany (keep-alive) przez cały czas pracy Core.

## Embeddingi

Embeddingi wiedzy liczy osobna usługa na CPU: text-embeddings-inference z modelem intfloat/multilingual-e5-base.
Wektory mają 768 wymiarów, są normalizowane, a zapytania i fragmenty dostają prefiksy query: oraz passage:.

## Pamięć GPU

### Wpis 1

Rutynowa kontrola (vram) numer 1: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 2

Rutynowa kontrola (vram) numer 2: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 3

Rutynowa kontrola (vram) numer 3: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 4

Rutynowa kontrola (vram) numer 4: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 5

Rutynowa kontrola (vram) numer 5: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 6

Rutynowa kontrola (vram) numer 6: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 7

Rutynowa kontrola (vram) numer 7: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 8

Rutynowa kontrola (vram) numer 8: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 9

Rutynowa kontrola (vram) numer 9: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.

### Wpis 10

Rutynowa kontrola (vram) numer 10: sprawdzono logi systemowe, obciążenie procesora, temperatury dysków oraz stan aktualizacji pakietów. Nie stwierdzono nieprawidłowości, konfiguracja usług pozostała bez zmian.


## Ograniczenia

Nie wolno uruchamiać dwóch modeli 12B jednocześnie na GPU - zabraknie pamięci i Ollama zacznie przenosić warstwy na CPU.
