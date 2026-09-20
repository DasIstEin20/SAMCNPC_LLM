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

**Translator gotowy w zakresie emulatora:** komendy celu/statusu/odpowiedzi/stop/resume
łączą jedno polecenie gracza z walidowaną operacją Behavior, zachowują budżety i
bezpiecznie odtwarzają stan po restarcie. [Komendy](docs/LLM_TRANSLATOR.md).
Supervisor i Planner pozostają w budowie. Plan: 23/36. Testy używają lekkiego emulatora
HTTP; rozumienie języka i profile prawdziwego modelu pozostają niezweryfikowane.
[Plan](docs/LLM_INTEGRATION_PLAN.md), [granice modułu](docs/LLM_BOUNDARY.md).

Przygotowany jest też odczyt zapasu: 493 testy jednostkowe, 146 Core / 215 Behavior
native oraz po pięć fizycznych odczytów w kliencie i na serwerze.
[Zakres sensora i dowody](docs/STOCK_OBSERVATION.md). Supervisor pozostaje w budowie.

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
NPC lub ekran Mods Config. `runClientGoalsSmoke` sprawdza fizyczne zadania, a para
`runServerGoalsSaveSmoke` / `runServerGoalsLoadSmoke` restart z nowym
`-PllmGoalRestartId=<id>`. Serwer wymaga zaakceptowania Minecraft EULA.

## Weryfikacja

Pełny projekt: 491 testów jednostkowych (47 Core/364 Behavior/80 LLM). Dedicated i
klient zaliczyły po osiem fizycznych scenariuszy Translatora / dziewięć HTTP; klient
również 11 kontroli uprawnień/danych. Dostawa, transport, ścinka, brak zasobu, pełny
magazyn, pauza, wyłączenie providera i anulowanie: PASS. Restart dwóch JVM zachował
ID zadań, dokończył znane zadanie i zatrzymał niepewne potwierdzenie bez ponownego
assignmentu. Zgodne 809 hashy źródeł/buildów i trzy JAR-y. Korpus 16 rodzin sprawdza
kontrakty ze stałymi odpowiedziami emulatora, nie jakość językową modelu. Niezmienione
Core141/Behavior214 native i 12 scenariuszy klienta zachowują wcześniejsze dowody.
Build tego repo: 80 testów LLM, 88 zgodnych plików źródłowych; [dowody i hashe](docs/TRANSLATOR_VALIDATION.json).
[Stan projektu](PROJECT_STATE.md) opisuje ograniczenia. Skórki dwóch kont pozostają
ręczne i nieblokujące. Rzeczywiste modele oraz godzinny soak nadal czekają na testy.

[Core](https://github.com/DasIstEin20/SAMCNPC_Core) ·
[Behavior](https://github.com/DasIstEin20/SAMCNPC_Behavior)
