# SAMCNPC LLM

[EN / PL / DE](README.md#polski)

Translator wybiera typowane operacje, Supervisor reaguje na ograniczone obserwacje, a Planner proponuje pracę przez publiczne API Behavior. Każde zlecenie przechodzi kontrolę aktualnych uprawnień, świeżości i tożsamości zadania. Sekrety, HTTP i inferencja należą wyłącznie do tego modułu. Model nie wykonuje dowolnych komend ani bezpośrednich zmian świata i nie przejmuje kanałów ciała. Integracja jest domyślnie wyłączona. Planner V1 pozostaje domyślny, V2 jest eksperymentalną opcją. Niezawodność długich misji rzeczywistego modelu nie została potwierdzona. Test deterministyczny nie dowodzi jakości modelu.
