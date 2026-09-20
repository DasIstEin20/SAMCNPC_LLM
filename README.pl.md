<p align="center">
  <img src="assets/samcnpc-llm-logo.png" width="720" alt="SAMCNPC LLM" />
</p>

# SAMCNPC LLM

[English](README.md) | Polski

Opcjonalna integracja decyzji wysokiego poziomu dla Minecraft Forge 1.20.1.
LLM wybiera ograniczone operacje, Behavior wykonuje zadania i recovery, Core
obsługuje mechanikę. Core i Behavior działają samodzielnie bez tego moda.

Gotowe tryby: **Translator**, **Supervisor zapasu** i **ograniczony Planner**.
Translator wybiera jedną operację z katalogu 16 rodzin. Supervisor utrzymuje wskazany
zapas w widocznej skrzyni, z histerezą i ochroną przed pętlami. Planner wykonuje
jeden zwalidowany krok naraz, sprawdza świeże inventory i zapisuje ograniczoną
pamięć. Otwarty cel po zakończeniu kroków potwierdza gracz.
Plan: 31/36; końcowa akceptacja i aktywny test godzinny pozostają do wykonania.

Domyślny endpoint to **http://127.0.0.1:1234/v1**. Adres i model zmienisz w Forge Mods
Config. Integracja jest domyślnie wyłączona; nie instaluje ani nie ładuje modelu.
Testy używają lekkiego emulatora HTTP. Rozumienie języka przez rzeczywisty model
oraz profile backendu/tokenizera pozostają niezweryfikowane i odłożone przez gracza.

[Konfiguracja](docs/LLM_CONFIGURATION.md) · [Komendy Translatora](docs/LLM_TRANSLATOR.md) ·
[Supervisor](docs/LLM_SUPERVISOR.md) · [Planner](docs/LLM_PLANNER.md) ·
[Pamięć](docs/LLM_MEMORY.md) · [Plan](docs/LLM_INTEGRATION_PLAN.md)

## Budowanie

Java 17; przypięte Forge 47.4.21, Kotlin 2.2.21 i Kotlin for Forge 4.12.0.

```sh
git submodule update --init --recursive
./gradlew clean build
```

Windows: `gradlew.bat clean build`. Wynik: `build/libs/samcnpc-llm-0.1.0.jar`.
Wymagane odpowiadające wersje Core, Behavior i Kotlin for Forge.
Zależności są przypiętymi submodułami. Unit testy nie uruchamiają Minecrafta ani modelu.

Próby Forge: `runServerLoadingSmoke`, `runClientLoadingSmoke`,
`runClientGoalsSmoke`, serwerowe/klienckie `SupervisorSmoke`,
`SupervisorFaultsSmoke` i `PlannerSmoke`. Pary serwerowych zadań
`GoalsSaveSmoke`/`GoalsLoadSmoke`, `SupervisorSaveSmoke`/`SupervisorLoadSmoke`,
`PlannerSaveSmoke`/`PlannerLoadSmoke` wymagają nowego `-PllmGoalRestartId=<id>`.
Uruchomienie serwera wymaga zaakceptowania Minecraft EULA.

## Weryfikacja

Pełny clean build: 514 testów (49 Core/364 Behavior/101 LLM), 841 zgodnych hashy i
kontrola trzech JAR-ów. Dedicated i klient zaliczyły odpowiednio po: Translator 8
scenariuszy/9 HTTP, Supervisor 14/22, Planner 6/9. Sprawdzone fizyczne bilanse,
nieaktualne zasoby, awarie providera, ręczne sterowanie i opóźnione anulowanie.
Trzy zestawy restartów zachowały tożsamość/intencję, bez powielania operacji.
Serwer: 37 kontroli admission; komendy: 15 kontroli uprawnień/danych.

Build tego repo: 101 testów LLM, 113 zgodnych plików źródłowych.
[Dowody i hashe](docs/PLANNER_VALIDATION.json) oddzielają weryfikację integracji
od jakości rzeczywistego modelu. Niezmienione Core 146/Behavior 215 native i Core 52
animacji zachowują wcześniejsze dowody. [Stan projektu](PROJECT_STATE.md) opisuje
ograniczenia. Aktywny godzinny test wielu NPC pozostaje do wykonania.
Skórki dwóch zalogowanych kont są testem ręcznym i nie blokują prac.

[Core](https://github.com/DasIstEin20/SAMCNPC_Core) ·
[Behavior](https://github.com/DasIstEin20/SAMCNPC_Behavior)
