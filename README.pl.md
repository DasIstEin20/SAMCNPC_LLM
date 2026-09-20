<p align="center">
  <img src="assets/samcnpc-llm-logo.png" width="720" alt="SAMCNPC LLM" />
</p>

# SAMCNPC LLM

[English](README.md) | Polski

Opcjonalna integracja decyzji wysokiego poziomu dla Minecraft Forge 1.20.1.
LLM wybiera operację, Behavior wykonuje zadanie i recovery, Core obsługuje mechanikę.
Core i Behavior działają samodzielnie bez tego moda.

Gotowe: ograniczony transport HTTP zgodny z OpenAI, timeout/anulowanie i obsługa
błędów, konfiguracja Forge Mods oraz autoryzowany kontekst NPC: wszystkie 36 slotów,
zadanie/postęp, legalne sensory i dzienniki zdarzeń. Domyślny endpoint to
**http://127.0.0.1:1234/v1**; adres i model można zmienić w konfiguracji.
Integracja jest domyślnie wyłączona. Nie wysyła żądania na starcie i nie instaluje modelu.
[Konfiguracja](docs/LLM_CONFIGURATION.md), [kontekst](docs/LLM_CONTEXT.md).

**W budowie:** walidacja i wykonanie decyzji, kolejka oraz Translator → Supervisor →
Planner. Ta wersja jeszcze nie zamienia poleceń gracza na pracę NPC. Plan: 12/36.
Testy używają lekkiego lokalnego emulatora HTTP; rzeczywisty model użytkownik poda później.
[Plan](docs/LLM_INTEGRATION_PLAN.md), [granice modułu](docs/LLM_BOUNDARY.md).

## Budowanie

Java 17; przypięte Forge 47.4.21, Kotlin 2.2.21 i Kotlin for Forge 4.12.0.

```sh
git submodule update --init --recursive
./gradlew clean build
```

Windows: `gradlew.bat clean build`. Wynik: `build/libs/samcnpc-llm-0.1.0.jar`.
Wymagane odpowiadające wersje Core, Behavior i Kotlin for Forge.
`test` nie uruchamia modelu ani klienta gry. `runServerLoadingSmoke` i
`runClientLoadingSmoke` sprawdzają rzeczywiste Forge, HTTP, konfigurację oraz kontekst
NPC lub ekran Mods Config. Serwer wymaga zaakceptowania Minecraft EULA.

## Weryfikacja

Pełny projekt: 436 testów jednostkowych (47 Core/364 Behavior/25 LLM), rzeczywisty
serwer z kontekstem/HTTP, klient GUI/HTTP oraz granice i trzy JAR-y: PASS.
To repo: clean build i 25 testów LLM; [dowody](docs/CONTEXT_VALIDATION.json).
[Stan projektu](PROJECT_STATE.md) rozróżnia bieżące testy od zachowanych dowodów
rozgrywki Core/Behavior. Skórki dwóch kont pozostają testem ręcznym, bez blokowania prac.

[Core](https://github.com/DasIstEin20/SAMCNPC_Core) ·
[Behavior](https://github.com/DasIstEin20/SAMCNPC_Behavior)
