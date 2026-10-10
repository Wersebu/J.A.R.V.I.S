---
id: wf-restart
type: workflow
status: active
---
# Restart usług Jarvisa

1. Poinformuj użytkownika, że Jarvis będzie niedostępny przez około minutę.
2. Zatrzymaj Core poleceniem `sudo systemctl stop jarvis`.
3. Sprawdź, czy Ollama działa: `systemctl status ollama`.
4. Uruchom ponownie wszystko poleceniem `sudo systemctl restart jarvis`.
5. Sprawdź `/api/health` i potwierdź użytkownikowi gotowość.
