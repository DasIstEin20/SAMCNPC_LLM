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
Gotowy jest też ścisły kontrakt ośmiu decyzji, walidacja polityki/świeżości,
odrzucanie ponownych odpowiedzi i odczyt potwierdzeń korekt przez API Behavior.
[Konfiguracja](docs/LLM_CONFIGURATION.md), [kontekst](docs/LLM_CONTEXT.md),
[kontrakt decyzji](docs/LLM_DECISIONS.md), [harmonogram wywołań](docs/LLM_SCHEDULING.md).

Gotowa jest kolejka zdarzeń, anulowanie workerów, ograniczone retry i budżety zasobów.

**W budowie:** Translator → Supervisor →
Planner. Ta wersja jeszcze nie zamienia poleceń gracza na pracę NPC. Plan: 18/36.
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

Pełny projekt: 473 testy jednostkowe (47 Core/364 Behavior/62 LLM), 15 scenariuszy
harmonogramu na rzeczywistym serwerze / 15 HTTP, 30 prób admission i pełny kontekst
przez HTTP. GUI/logo oraz trzy JAR-y: PASS. Zegar limitów w próbach jest wirtualny.
Niezmienione Core141/Behavior214 native i 12 scenariuszy klienta zachowują wcześniejsze
dowody; nie powtarzano ich dla tego etapu dotyczącego wyłącznie LLM.
Build tego repo, zgodność źródeł i hashe: [dowody](docs/SCHEDULER_VALIDATION.json).
[Stan projektu](PROJECT_STATE.md) opisuje ograniczenia. Skórki dwóch kont pozostają
ręczne i nieblokujące. Rzeczywiste modele oraz godzinny soak nadal czekają na testy.

[Core](https://github.com/DasIstEin20/SAMCNPC_Core) ·
[Behavior](https://github.com/DasIstEin20/SAMCNPC_Behavior)
