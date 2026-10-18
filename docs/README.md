# Документы

Куда смотреть, чтобы запустить сервис, сверить правила кейса и понять, как устроен и как искался алгоритм.

## С чего начать

| Если нужно | Читать |
|---|---|
| запустить сервис и проверить результат | [INSTRUCTION.md](../INSTRUCTION.md) — пошагово: Docker, командная строка, API, проверщик |
| понять всё решение по одному файлу | [DOCUMENTATION.md](DOCUMENTATION.md) — сопроводительная документация: алгоритм, ОКС без маршрута, выход, глубина, границы применения |
| понять, что делает сервис | [README.md](../README.md) |
| увидеть качество и время по версиям | [CHANGELOG.md](../CHANGELOG.md) |
| сверить требования заказчика и что по ним сделано | [TASK.md](TASK.md) |
| сверить правила кейса | [architecture/CONSTRAINTS.md](architecture/CONSTRAINTS.md) — выписка из ТЗ и разъяснений организаторов, раздел 19 — редакция 18.09.2026 |
| понять, как работает алгоритм и почему он не нарушает правила | [algorithm.md](algorithm.md) — шаги расчёта, таблица «правило → как соблюдается → проверка check18», угловые случаи с картинками |
| разобраться в устройстве сервиса по классам | [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md) |
| API, критерии, сборка, тесты, границы применения | [architecture/BACKEND.md](architecture/BACKEND.md) |
| посмотреть результат в 3D | [platform/README.md](../platform/README.md) — платформа инженера «Тепломаршрут» |
| внести изменение или выпустить версию | [CONTRIBUTING.md](CONTRIBUTING.md) |

## Что где

| Путь | Что там |
|---|---|
| [DOCUMENTATION.md](DOCUMENTATION.md) | сопроводительная документация для экспертов в одном файле |
| [TASK.md](TASK.md) | задача хакатона, требования заказчика, открытые вопросы и статус |
| [CONTRIBUTING.md](CONTRIBUTING.md) | порядок чтения, правила для изменений, проверки, выпуск версий |
| [architecture/](architecture) | [CONSTRAINTS.md](architecture/CONSTRAINTS.md) — правила кейса, [ARCHITECTURE.md](architecture/ARCHITECTURE.md) — устройство сервиса, [BACKEND.md](architecture/BACKEND.md) — запуск, API, сборка |
| [algorithm.md](algorithm.md) | как работает алгоритм: конвейер, правила и их проверка, угловые случаи |
| [interpretation.md](interpretation.md) | толкования правил, одинаковые для сервиса и проверщика |
| [depth-optimization.md](depth-optimization.md) | режим с учётом глубины: чек-лист правил, доказательство оптимальности профиля, замеры |
| [depth-agent-guide.md](depth-agent-guide.md) | режим глубины для разработчика: файлы, конвейер `DepthProfiler`, константы, как прогнать проверки |
| [real-cities.md](real-cities.md) | Москва, Петербург и Нижний Новгород по открытым данным: что пришлось изменить в сервисе и результат |
| [research.md](research.md) | история поиска алгоритма: хронология версий, что сработало, что нет |
| [performance.md](performance.md) | замеры версий 0.9.0 и 0.8.1 против предыдущих: датасет организаторов, плотные сцены, город 3,2 ГБ; новые версии — в [CHANGELOG.md](../CHANGELOG.md) |
| [adr/](adr) | записи о принятых архитектурных решениях |
| [handoff/](handoff) | доработки после встречи с организаторами 16.10.2026: задачи, патчи, стенд замеров |
| [assets/](assets) | картинки README и документов, пересобираются скриптами `scripts/readme_assets.py` и `scripts/docs_assets.py`; в [assets/algorithm/](assets/algorithm) — картинки угловых случаев и их входы, пересобираются `scripts/algorithm_figures.py` |
