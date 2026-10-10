---
id: dec-magazyn-indeksu
type: decision
status: active
---
# Decyzja: magazyn indeksu wiedzy

Wybraliśmy SQLite zamiast pgvector, bo indeks działa bez dodatkowej usługi, mieści się w jednym pliku i można go w każdej
chwili usunąć i odbudować z plików Markdown.
