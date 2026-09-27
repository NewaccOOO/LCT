# Документы

Куда смотреть, чтобы запустить сервис, сверить правила кейса и понять, как устроен и как искался алгоритм.

## С чего начать

| Если нужно | Читать |
|---|---|
| запустить сервис и проверить результат | [INSTRUCTION.md](../INSTRUCTION.md) — пошагово: Docker, командная строка, API, проверщик |
| понять, что делает сервис, и увидеть качество по версиям | [README.md](../README.md) |
| сверить требования заказчика и что по ним сделано | [TASK.md](../TASK.md) |
| понять, как работает алгоритм и почему он не нарушает правила | [algorithm.md](algorithm.md) — шаги расчёта, таблица «правило → как соблюдается → проверка check18», угловые случаи с картинками |
| сверить правила кейса | [architecture/CONSTRAINTS.md](../architecture/CONSTRAINTS.md) — выписка из ТЗ и разъяснений организаторов, раздел 19 — редакция 18.09.2026 |
| понять, как читаются спорные места правил | [interpretation.md](interpretation.md) |
| разобраться в устройстве сервиса по классам | [architecture/ARCHITECTURE.md](../architecture/ARCHITECTURE.md) |
| API, критерии, сборка, тесты, границы применения | [architecture/BACKEND.md](../architecture/BACKEND.md) |
| узнать, какие идеи проверяли и что они дали | [research.md](research.md) |
| посмотреть замеры времени и качества последней версии | [performance.md](performance.md) |
| понять, почему выбран такой алгоритм | [adr/0001-routing-approach.md](adr/0001-routing-approach.md), [adr/0002-partition-local-search.md](adr/0002-partition-local-search.md) |

## Что где

| Путь | Что там |
|---|---|
| [algorithm.md](algorithm.md) | как работает алгоритм: конвейер, правила и их проверка, угловые случаи |
| [interpretation.md](interpretation.md) | толкования правил, одинаковые для сервиса и проверщика |
| [research.md](research.md) | история поиска алгоритма: хронология версий, что сработало, что нет |
| [performance.md](performance.md) | замеры версий 0.9.0 и 0.8.1 против предыдущих: датасет организаторов, плотные сцены, город 3,2 ГБ |
| [adr/](adr/) | записи о принятых архитектурных решениях |
| [assets/](assets/) | картинки README и ARCHITECTURE, пересобираются скриптами `scripts/readme_assets.py` и `scripts/docs_assets.py`; в [assets/algorithm/](assets/algorithm/) — картинки угловых случаев и их входы, пересобираются `scripts/algorithm_figures.py` |
