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
Plan: **35/36**. Lokalny Qwen ma już sprawdzony profil i wąski test w grze;
pełny korpus jakości oraz inne backendy pozostają do sprawdzenia.

Domyślny endpoint to **http://127.0.0.1:1234/v1**. Adres i model zmienisz w Forge Mods
Config. Integracja jest domyślnie wyłączona; nie instaluje ani nie ładuje modelu.
Zwykłe testy używają emulatora HTTP. Osobny test Qwen3.5-4B Q4_K_M zaliczył
osiem scenariuszy, w tym pobieranie przedmiotów po polsku i angielsku oraz
ścinanie dębu z kontrolą obszaru i ilości. [Opis naprawy](docs/LLM_GAMEPLAY_REPAIR.md).

Komendy przyjmują nazwy i unikalne skróty tak jak Core, z podpowiedziami Tab:
`/samcnpc llm status Sam`. Pełne UUID nadal działają.

[Konfiguracja](docs/LLM_CONFIGURATION.md) · [Komendy Translatora](docs/LLM_TRANSLATOR.md) ·
[Supervisor](docs/LLM_SUPERVISOR.md) · [Planner](docs/LLM_PLANNER.md) ·
[Pamięć](docs/LLM_MEMORY.md) · [Plan](docs/LLM_INTEGRATION_PLAN.md)

Naprawa poleceń z 21.09: budżety dopasowane do profilu modelu, konfigurowalne
limity zapytań, komunikaty oczekiwania, obserwacja widocznych skrzyń oraz jawne
wartości domyślne parametrów. Próby z Qwenem obejmują pobieranie przedmiotów po
polsku i angielsku, dostawę, ruch oraz ścinanie dębu z kontrolą obszaru i ilości.
[Opis naprawy](docs/LLM_GAMEPLAY_REPAIR.md) · [Wyniki](docs/GAMEPLAY_REPAIR_VALIDATION.json).
Polecenia „weź wszystko” i samodzielne zakładanie zbroi wymagają dodatkowych operacji
Behavior; obecny katalog ich nie obsługuje. Pełny benchmark jakości modelu nadal czeka.
Poniższe liczby poprzedniej naprawy i wydania są historyczne.

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

Naprawa wyboru NPC i połączenia: 525 testów jednostkowych workspace (49/364/112),
siedem testów Python, regresja Translatora na serwerze i kliencie (8 scenariuszy /
9 HTTP) oraz 23 kontrole uprawnień komend. Build osobnego repo również przeszedł
(112 testów LLM, 128 zgodnych plików). Powtórzone próby serwerowego Supervisora,
Plannera i restartu celu przeszły. Qwen zaliczył osobny test trzech przypadków.
[Dowody naprawy](docs/CONNECTION_REPAIR_VALIDATION.json) wskazują aktualne artefakty
i zakres powtórzonych testów. Poniższe dowody wydania i testu godzinnego są historyczne.

Pełny clean build: 518 testów (49 Core/364 Behavior/105 LLM), 850 zgodnych hashy i
kontrola trzech JAR-ów. Dedicated i klient zaliczyły odpowiednio po: Translator 8
scenariuszy/9 HTTP, Supervisor 14/22, Planner 6/9. Sprawdzone fizyczne bilanse,
nieaktualne zasoby, awarie providera, ręczne sterowanie i opóźnione anulowanie.
Trzy zestawy restartów zachowały tożsamość/intencję, bez powielania operacji.
Serwer: 37 kontroli admission; komendy: 15 kontroli uprawnień/danych.

Build tego repo: 105 testów LLM, 122 zgodne pliki źródłowe.
[Dowody wydania i hashe](docs/RELEASE_VALIDATION.json) opisują poprzedni build wydania;
[dowody trybów](docs/PLANNER_VALIDATION.json) oddzielają weryfikację integracji
od jakości rzeczywistego modelu. Niezmienione Core 146/Behavior 215 native i Core 52
animacji zachowują wcześniejsze dowody. [Stan projektu](PROJECT_STATE.md) opisuje
ograniczenia. Godzinny test sześciu NPC w profilu patrol/melee zaliczony: 12 HTTP
i 20 minut wyłączonego endpointu. Świeży klient/serwer standalone również zaliczony.
Skórki dwóch zalogowanych kont są testem ręcznym i nie blokują prac.

[Core](https://github.com/DasIstEin20/SAMCNPC_Core) ·
[Behavior](https://github.com/DasIstEin20/SAMCNPC_Behavior)

[Zamrożony korpus i kryteria oceny](docs/LLM_EVALUATION.md): 56 przypadków, trzy powtórzenia emulatora; jakość prawdziwego modelu pozostaje do sprawdzenia.

[Instalacja](docs/LLM_INSTALLATION.md) · [Test godzinny](docs/LLM_ENDURANCE.md) · [Akceptacja wydania](docs/RELEASE_ACCEPTANCE.md) · [Artefakty](docs/ARTIFACTS.md) · [Ręczny test skórek](docs/MANUAL_SKIN_TEST.md)
