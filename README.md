<p align="center"><img src="assets/samcnpc-llm-logo.png" width="760" alt="SAMCNPC LLM" /></p>

# SAMCNPC LLM

[English](#english) · [Polski](#polski) · [Deutsch](#deutsch)

> Early development source publication · Minecraft 1.20.1 · Forge 47.4.21 · Java 17 · Kotlin for Forge 4.12.0

<a id="english"></a>
## English

**Optional high-level translation, supervision and planning for Minecraft Forge 1.20.1.**

Translator selects supported typed operations; Supervisor reacts to bounded observations; Planner proposes finite work through published Behavior APIs. Every admitted request is checked for current authority, freshness and task identity. Credentials, HTTP clients and inference belong only here. The model cannot execute commands, mutate the world directly or take over body channels. Integration is disabled by default; ordinary deterministic work does not need this mod. Planner V1 remains the default strategy and experimental Planner V2 is opt-in. Real-model long-mission reliability is not established. Native scripted passes are not model-quality evidence.

### Requirements and build

Requires matching Core, Behavior and Kotlin for Forge. The provider is optional and disabled by default. Dependencies are pinned; use compatible module revisions.
The umbrella SAMCNPC checkout records the tested sibling revisions. This repository
contains no dependency Git submodules and never downloads sibling source code during a build.

```bash
./gradlew clean build -PsamcnpcCoreDir=../samcnpc-core -PsamcnpcBehaviorDir=../samcnpc-behavior
```

Use `gradlew.bat` on Windows. In PowerShell, quote each `-Pname=path` argument.
Building from the umbrella checkout configures the sibling projects automatically.
JARs are written under `build/libs/`; do not install sources or development remapping artifacts.

### Evaluation

Test new work in disposable worlds. Provide an exact revision, input document,
initial world/inventory, reproduction steps and the actual outcome in bug reports.
Compilation alone is not evidence of physical navigation, combat, persistence or skin behavior.
The two-authenticated-account skin/refresh check remains MANUAL_PENDING and nonblocking;
automated loading, permissions, synchronization and persistence checks remain required.

<a id="polski"></a>
## Polski

**Opcjonalne tłumaczenie, nadzór i planowanie wysokiego poziomu dla Minecraft Forge 1.20.1.**

Translator wybiera typowane operacje, Supervisor reaguje na ograniczone obserwacje, a Planner proponuje pracę przez publiczne API Behavior. Każde zlecenie przechodzi kontrolę aktualnych uprawnień, świeżości i tożsamości zadania. Sekrety, HTTP i inferencja należą wyłącznie do tego modułu. Model nie wykonuje dowolnych komend ani bezpośrednich zmian świata i nie przejmuje kanałów ciała. Integracja jest domyślnie wyłączona. Planner V1 pozostaje domyślny, V2 jest eksperymentalną opcją. Niezawodność długich misji rzeczywistego modelu nie została potwierdzona. Test deterministyczny nie dowodzi jakości modelu.

Zestaw docelowy: Minecraft 1.20.1, Forge 47.4.21, Java 17 i Kotlin for Forge 4.12.0.
Zgodne wersje zależności są wymagane. Główne repozytorium SAMCNPC przypina rewizje
sąsiednich modułów; tutaj nie ma zagnieżdżonych submodułów Git. Polecenie kompilacji
znajduje się wyżej; Windows używa `gradlew.bat`, a argumenty `-Pname=path` w PowerShell
należy ująć w cudzysłowy. JAR powstaje w `build/libs/`.

Nowe funkcje sprawdzaj na jednorazowych światach. Zgłoszenie powinno zawierać rewizję,
dokument wejściowy, stan początkowy i odtwarzalne kroki. Sam wynik kompilacji nie
potwierdza zachowania w grze. Wizualny test skina z dwoma kontami pozostaje
MANUAL_PENDING; testy automatyczne nadal obowiązują.

<a id="deutsch"></a>
## Deutsch

**Optionale Übersetzung, Aufsicht und Planung auf hoher Ebene für Minecraft Forge 1.20.1.**

Translator wählt typisierte Operationen, Supervisor reagiert auf begrenzte Beobachtungen und Planner schlägt Arbeit über öffentliche Behavior-APIs vor. Berechtigung, Aktualität und Aufgabenidentität werden erneut geprüft. Zugangsdaten, HTTP und Inferenz bleiben in diesem Modul. Das Modell darf weder beliebige Befehle ausführen noch die Welt direkt verändern oder Körperkanäle übernehmen. Die Integration ist standardmäßig deaktiviert. Planner V1 bleibt Standard, V2 ist experimentell und optional. Zuverlässigkeit langer Modellmissionen ist nicht nachgewiesen. Deterministische Tests sind kein Nachweis für Modellqualität.

Zielplattform: Minecraft 1.20.1, Forge 47.4.21, Java 17 und Kotlin for Forge 4.12.0.
Kompatible Abhängigkeitsversionen sind erforderlich. Das Hauptrepository SAMCNPC
pinnt die benachbarten Modulrevisionen; dieses Repository hat keine verschachtelten
Git-Submodule. Der Build-Befehl steht oben. Unter Windows `gradlew.bat` verwenden;
PowerShell-Argumente `-Pname=path` in Anführungszeichen setzen. JAR-Ausgabe: `build/libs/`.

Neue Funktionen in entbehrlichen Welten testen. Fehlerberichte brauchen Revision,
Eingabedokument, Ausgangszustand und reproduzierbare Schritte. Kompilierung allein
belegt kein Spielverhalten. Der visuelle Skin-Test mit zwei Konten bleibt
MANUAL_PENDING; automatisierte Prüfungen bleiben erforderlich.

## License

See [LICENSE](LICENSE). Minecraft and third-party dependencies retain their own terms.
