<p align="center">
  <img src="assets/samcnpc-llm-logo.png" width="720" alt="SAMCNPC LLM" />
</p>

# SAMCNPC LLM

[English](README.md) | Polski

Opcjonalny moduł decyzji dla SAMCNPC na Minecraft Forge 1.20.1.
LLM wybiera operację; Behavior wykonuje zadanie i recovery; Core realizuje mechanikę.
Core i Behavior działają również bez LLM.

Gotowe: ograniczony adapter HTTP zgodny z OpenAI, typowany kontrakt, walidacja JSON,
timeout/anulowanie, jawne błędy, konfiguracja Forge oraz ekran Mods → SAMCNPC LLM → Config.
Domyślny adres **http://127.0.0.1:1234/v1** i ID modelu można zmienić.
Domyślnie integracja jest wyłączona; start moda nie wysyła żądań ani nie ładuje modelu.
[Konfiguracja](docs/LLM_CONFIGURATION.md).

**Prace trwają:** kontekst, dopuszczanie decyzji, kolejka oraz tryby
Translator → Supervisor → Planner. Ta wersja nie zamienia jeszcze poleceń gracza
na zadania NPC. Testy korzystają z lekkiego lokalnego emulatora HTTP, bez modelu i GPU.
Nie deklarujemy sprawdzonej jakości ani zgodności prawdziwych modeli.
[Plan](docs/LLM_INTEGRATION_PLAN.md) · [Granice modułu](docs/LLM_BOUNDARY.md).

Budowanie: Java 17, `git submodule update --init --recursive`, następnie
`gradlew.bat clean build` (Linux: `./gradlew clean build`).
Artefakt: `build/libs/samcnpc-llm-0.1.0.jar`. Potrzebne są też odpowiadające wersje
Core, Behavior i Kotlin for Forge 4.12.0. Forge: 47.4.21.

`test` uruchamia testy i emulator. `runServerLoadingSmoke` /
`runClientLoadingSmoke` sprawdzają rzeczywisty Forge, HTTP oraz konfigurację.
Serwer testowy wymaga zaakceptowania EULA Minecrafta.

Główny workspace: 401 testów jednostkowych, clean build, granice/dystrybucja,
dedicated oraz klient z GUI i emulatorem — PASS.
Wyniki osobnego repo: [PROJECT_STATE](PROJECT_STATE.md).
Test wyglądu skórek dwóch zalogowanych kont pozostaje ręczny i nie blokuje prac.
