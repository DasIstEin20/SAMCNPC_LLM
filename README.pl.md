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

**Translator i ograniczony Supervisor zapasu są gotowe w zakresie emulatora.**
Translator zamienia jeden cel gracza na walidowaną operację Behavior. Supervisor
utrzymuje wskazany zapas z histerezą, świeżymi odczytami i ograniczoną historią awarii.
Komendy, budżety i odtwarzanie stanu opisują [Translator](docs/LLM_TRANSLATOR.md)
oraz [Supervisor](docs/LLM_SUPERVISOR.md). Plan: 28/36; następne są Planner i akceptacja.
Testy używają lekkiego emulatora HTTP. Rozumienie języka i profile prawdziwego modelu
pozostają niezweryfikowane. [Plan](docs/LLM_INTEGRATION_PLAN.md).

Odczyt zapasu wymaga widocznej, osiągalnej skrzyni vanilla i aktualnych uprawnień.
[Zakres sensora](docs/STOCK_OBSERVATION.md) opisuje ograniczenia.

Trwała [pamięć](docs/LLM_MEMORY.md) przechowuje nazwy miejsc gracza i potwierdzone
wyniki. `remember` / `forget_place` działają dla bezczynnego celu; zapamiętane
miejsce nie potwierdza aktualnej zawartości świata. Zapis v3 migruje starsze wersje.

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
`-PllmGoalRestartId=<id>`. Supervisor ma osobne zadania
`runServerSupervisorSmoke`, `runClientSupervisorSmoke`, serwerowe/klienckie
`SupervisorFaultsSmoke` oraz `runServerSupervisorSaveSmoke` / `runServerSupervisorLoadSmoke`.
Każda kampania wymaga nowego ID restartu. Serwer wymaga zaakceptowania Minecraft EULA.

## Weryfikacja

Pełny projekt: 509 testów jednostkowych (49 Core/364 Behavior/96 LLM), 833 zgodnych
hashy źródeł/buildów i kontrola trzech JAR-ów. Dedicated i klient zaliczyły po
14 scenariuszy Supervisora / 22 HTTP oraz osiem scenariuszy Translatora / dziewięć HTTP.
Supervisor przeszedł uzupełnianie i histerezę, nieaktualny stan skrzyni, pełny magazyn,
brak zasobu, pętle A/B i WAIT, ręczne sterowanie, wyłączenie providera, uszkodzony JSON
oraz powtarzane HTTP 503. Zdrowe zadanie nie powodowało dodatkowych wywołań modelu.
Klient wyrenderował 14 przypadków Supervisora i potwierdził chodzenie w pięciu.
Komendy pamięci, jej kontekst i 13 kontroli uprawnień także przeszły.
Restart dwóch JVM zachował nazwy miejsc i ID zadań, dokończył fizyczną dostawę, zatrzymał niepewne
przyjęcie operacji i ponownie odczytał zapas bez powielania zadań.

Build tego repo: 96 testów LLM, 105 zgodnych plików źródłowych.
[Aktualne dowody i hashe](docs/MEMORY_VALIDATION.json) oddzielają testy integracji od jakości
prawdziwego modelu. Niezmienione Core146/Behavior215 native i Core52 animacji zachowują
wcześniejsze dowody. [Stan projektu](PROJECT_STATE.md) opisuje ograniczenia.
Skórki dwóch kont pozostają ręczne i nieblokujące. Modele, Planner i aktywny godzinny
soak nadal czekają na testy.

[Core](https://github.com/DasIstEin20/SAMCNPC_Core) ·
[Behavior](https://github.com/DasIstEin20/SAMCNPC_Behavior)
