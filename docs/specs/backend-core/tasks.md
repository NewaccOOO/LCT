# Задачи: бекенд трассировки тепловых сетей

Критерии — в [requirements.md](requirements.md), гейты — в [GATES.md](GATES.md), решения — в [design.md](design.md).

## Контракт исполнения

- **Режим:** параллельно, unlazy scope `backend-core`. Волна 0 и волны 4–5 выполняются одним агентом, волны 1–3 пачками субагентов.
- **Тулчейн и команды:** из корня репозитория, всегда после `source scripts/gates/env.sh`. JDK 11 из `brew --prefix openjdk@11`, Maven 3.9: `mvn -q verify`. Python через `uv run --project tools …`. Docker Desktop с `docker compose`; в скриптах бинарь берётся из переменной `COMPOSE` (по умолчанию `docker compose`), имена контейнеров не используются, только сервисы `app` и `postgres`. Все гейты запускаются `node ~/.claude/skills/unlazy/scripts/gate-check.mjs --cwd . --timeout 1800 docs/specs/backend-core/GATES.md`.
- **Интерфейсы** (заполнены до параллельного запуска, менять только через журнал):
  - Пакет `ru.lct.heatnet.model`, Lombok `@Value`, геометрия JTS в EPSG:32637. Входные: `Source(id, Point)`, `NetworkSegment(id, LineString, diameter, flowTph, upstreamId)`, `Chamber(id, Point, diameter, upstreamId)`, `FutureOks(id, Geometry, flowTph, heatLoad)`, `ConnectionPoint(id, Point, oksId)`, `ExistingOks(id, Geometry)`, `Restriction(id, Geometry, type)`, `Diagnostic(featureId, field, problem)`, `InputData` со списками всех входных и `diagnostics`. Выходные: `NewSegment`, `TieIn`, `Reconstruction`, `NewChamber`, `ChamberReconstruction`, `TechnicalNode`, `VariantSummary` с полями строго по разделу 13 CONSTRAINTS.md в camelCase, `Variant(id, списки, summary)`, `Result(List<Variant>)`.
  - `ru.lct.heatnet.core.Pipeline`: `Result run(InputData input)`. `PipelineImpl` собирается из `graph`, `plan`, `calc`. До волны 3 в `core` лежит `PipelineImpl`, бросающий `UnsupportedOperationException`.
  - `ru.lct.heatnet.rules.Rules`: `static Rules load()` читает `rules.json` из classpath; методы `diameterFor(flowTph)`, `diameter(dn)` с полями `capacityTph`, `maxLengthM`, `newRubM`, `reconRubM`, `widthM`; `chamberCost(dn)`, `tieInCost()`, `penalty(flowTph)`, `restriction(type)`, `score(cost, length)`.
  - `ru.lct.heatnet.io`: `InputData GeoJsonStreamReader.read(Path)`; `void GeoJsonStreamWriter.write(Result, Path)`; `Projector` с `toUtm(Geometry)` и `toWgs(Geometry)`.
  - REST и CLI — как в design.md, разделы D-4, D-5, D-16 и таблица интерфейсов.
  - Python: `python -m heatsynth --preset P --seed N --out FILE [--pad-mb M]`; `python -m heatsynth.check FILE` печатает `SYNTH SCHEMA OK`, `SYNTH NETWORK OK` и для `medium` `SYNTH MEDIUM OK`; `python -m heatcheck INPUT OUTPUT [--only RULE]`; `python -m heatcheck.rules_check CONSTRAINTS.md rules/rules.json`. `tools/pyproject.toml` один на оба пакета: hatchling, `requires-python >= 3.12`, зависимости `shapely>=2.1`, `pyproj>=3.7.1`, `pytest>=8`, `[tool.hatch.build.targets.wheel] packages = ["synth/heatsynth", "validator/heatcheck"]`; `heatcheck` берёт правила из `--rules`, по умолчанию `rules/rules.json` от текущего каталога. Маркеры `SYNTH …`, `RULE …`, `RULES MATCH` печатают только Python-модули, `PIPELINE DONE` только Java CLI; скрипты гейтов начинаются с `set -euo pipefail`, не содержат `|| true` и `exit 0` и печатают только свои маркеры `API … OK`, `PERF … OK`, `COMPOSE OK`, `MVN VERIFY OK …`, `CONSTRAINTS UNCHANGED`.
  - Скрипты гейтов в `scripts/gates/`: `env.sh` (экспорт JAVA_HOME, PATH); `produce.sh` (синтетика `small` и `medium`, сиды 1–3, в `data/synth/`, затем CLI в `data/out/<preset>-<seed>.geojson`; перегенерирует, если выход старше `target/heatnet.jar` или входа); `validate_rule.sh RULE` (вызывает `produce.sh`, затем `heatcheck --only RULE` на всех шести парах; сам маркер не печатает, а пропускает вывод `heatcheck` и завершается ненулевым кодом, если хоть один вызов упал, если сумма `checked` по шести файлам равна нулю, а для правил `reconstruction` и `special` — если суммарно нет ни одного объекта `heat_network_reconstruction` или участка `laying_method=special`); `perf.sh`; `api.sh <AC-1.1|AC-1.2|AC-1.3|AC-1.4|NFR-2|NFR-3>` (поднимает postgres из compose, стартует jar на свободном порту с `-Xmx1g`, выполняет проверку, гасит jar); `compose.sh`; `mvn_verify.sh`; `synth.sh`; `constraints_unchanged.sh`.

## Список

- [ ] **T-0** Общая основа для параллельных волн лежит в ветке
  - Требования: AC-3.3, AC-0.1
  - Зависит от: —
  - Файлы: `rules/**`, `tools/pyproject.toml`, `scripts/gates/env.sh`, `scripts/gates/constraints_unchanged.sh`, `.gitignore`
  - Волна: 0
  - Содержание: ставит тулчейн по C-7, создаёт ветку `feature/backend-core`; `rules.json` по D-2 целиком из разделов 6, 7, 9, 10 CONSTRAINTS.md; `tools/pyproject.toml` по контракту с обоими пакетами и пустыми `__init__.py`; `.gitignore` с `data/*`, `!data/samples/`, `target/`, `.venv/`; `env.sh`; коммит и пуш ветки, чтобы субагенты волны 1 стартовали от него.

- [ ] **T-1** Каркас Java собирается, поднимается в compose, правила читаются из одного файла
  - Требования: AC-5.1, AC-5.2
  - Зависит от: T-0
  - Файлы: `pom.xml`, `Dockerfile`, `docker-compose.yml`, `src/main/java/ru/lct/heatnet/HeatNetApplication.java`, `src/main/java/ru/lct/heatnet/model/**`, `src/main/java/ru/lct/heatnet/core/**`, `src/main/java/ru/lct/heatnet/rules/**`, `src/main/resources/**`, `src/test/java/ru/lct/heatnet/rules/**`, `scripts/gates/mvn_verify.sh`, `scripts/gates/compose.sh`
  - Волна: 1
  - Содержание: pom по C-1 с Lombok, Testcontainers 1.21.4, surefire и failsafe 3.x, `finalName` `heatnet`, `rules/` как ресурс; тест `RulesMatchConstraintsTest` парсит таблицы CONSTRAINTS.md и сверяет с `rules.json`; `model`, `Pipeline` со стабом, `Rules` по контракту; `application.yml` с профилями `default` и `cli`, `schema.sql` по D-5; Dockerfile многоступенчатый с `mvn -DskipTests package` внутри образа, compose формата `3.8` без ключей compose v2, с `postgres:16`, healthcheck и `depends_on`; actuator health; `mvn_verify.sh` читает XML surefire и failsafe и печатает маркер только при `tests` не меньше 30 и `skipped` равном 0.

- [ ] **T-2** Генератор синтетики выдаёт наборы трёх пресетов по разделу 12
  - Требования: AC-4.1, AC-4.2, AC-4.3
  - Зависит от: T-0
  - Файлы: `tools/synth/**`, `scripts/gates/synth.sh`, `data/samples/small-1.geojson`
  - Волна: 1
  - Содержание: D-15; `heatsynth.check` проверяет схему раздела 12, цепочку `upstream_object_id`, монотонность расходов и диаметров, для `medium` считает ОКС с запрещённым полигоном на прямой к сети; `synth.sh` генерирует `small`, `medium`, `large` с сидом 1 и запускает `check`; фиксирует детерминизм сравнением двух запусков; `data/samples/small-1.geojson` это `small` с сидом 1, закоммичен.

- [ ] **T-3** Валидатор проверяет выходной файл по 15 правилам и падает на испорченных фикстурах
  - Требования: AC-3.1, AC-3.2, AC-3.3
  - Зависит от: T-0
  - Файлы: `tools/validator/**`, `scripts/gates/validate_rule.sh`, `scripts/gates/produce.sh`
  - Волна: 1
  - Содержание: D-14; ручная фикстура в `tools/validator/tests/fixtures/`: вход с источником, двумя участками сети, двумя камерами, одним ОКС, одним запрещённым полигоном и одной дорогой; выход с двумя вариантами, врезанными в разные камеры, один из них с реконструкцией участка и специальным проходом через дорогу; тесты параметризованы списком `heatcheck.RULES` и мутируют фикстуру в памяти по одному полю на правило; `rules_check` парсит markdown-таблицы CONSTRAINTS.md; `produce.sh` пишется здесь по контракту и до волны 3 завершается ошибкой «jar не собран».

- [ ] **T-4** Потоковое чтение входа и запись выхода работают на файлах в сотни мегабайт
  - Требования: AC-1.3, AC-1.4, NFR-2
  - Зависит от: T-1
  - Файлы: `src/main/java/ru/lct/heatnet/io/**`, `src/test/java/ru/lct/heatnet/io/**`
  - Волна: 2
  - Содержание: D-3, D-13; диагностики без исключений; тест на файл 50 МБ, сгенерированный в тесте, с `-Xmx256m` через surefire `argLine`; репроекция туда и обратно с расхождением меньше 1 мм на точках Москвы.

- [ ] **T-5** Расчётные функции считают расходы, диаметры, предельную длину, реконструкцию, стоимость и score
  - Требования: AC-2.4, AC-2.5, AC-2.6, AC-2.8, AC-2.9, AC-2.10, AC-2.11
  - Зависит от: T-1
  - Файлы: `src/main/java/ru/lct/heatnet/calc/**`, `src/test/java/ru/lct/heatnet/calc/**`
  - Волна: 2
  - Содержание: D-12 без геометрии маршрута: функции принимают дерево участков с длинами и расходами; юнит-тесты на каждую формулу раздела 9 с числами, посчитанными вручную в тесте; реконструкция по цепочке с врезкой внутри участка и с двумя врезками на общем участке.

- [ ] **T-6** Препятствия, буферы и visibility graph дают прямой путь между двумя точками с учётом всех 10 типов ограничений
  - Требования: AC-2.7, AC-2.13
  - Зависит от: T-1
  - Файлы: `src/main/java/ru/lct/heatnet/graph/**`, `src/test/java/ru/lct/heatnet/graph/**`
  - Волна: 2
  - Содержание: D-6, D-7, D-8; тесты: путь огибает прямоугольник с отступом, ребро под 30° к дороге отбрасывается, ребро под 60° получает вес с `k_special`, пересечение газопровода даёт специальный участок 4 м; замер времени построения графа на 200 случайных прямоугольниках пишется в журнал.

- [ ] **T-7** Планировщик строит варианты, собирает сеть и доступен через CLI
  - Требования: AC-2.1, AC-2.2, AC-2.3, AC-2.12, AC-2.13, NFR-1
  - Зависит от: T-4, T-5, T-6
  - Файлы: `src/main/java/ru/lct/heatnet/plan/**`, `src/main/java/ru/lct/heatnet/core/PipelineImpl.java`, `src/main/java/ru/lct/heatnet/cli/**`, `src/test/java/ru/lct/heatnet/plan/**`, `src/test/java/ru/lct/heatnet/core/**`, `scripts/gates/perf.sh`
  - Волна: 3
  - Содержание: D-9, D-10, D-11, D-12, D-16; сквозной тест на `data/samples/small-1.geojson` из генератора с прогоном валидатора через `ProcessBuilder` помечен как `*IT`.

- [ ] **T-8** REST принимает файл потоком, ведёт задачи в PostgreSQL и отдаёт результат
  - Требования: AC-1.1, AC-1.2, AC-1.3, AC-1.4, NFR-2, NFR-3
  - Зависит от: T-1, T-4
  - Файлы: `src/main/java/ru/lct/heatnet/api/**`, `src/test/java/ru/lct/heatnet/api/**`, `scripts/gates/api.sh`
  - Волна: 3
  - Содержание: D-4, D-5, Swagger-описания на русском по C-9; `JobApiIT` на Testcontainers (при A-5 ложном — postgres из compose); `api.sh` по контракту.

- [ ] **T-9** Все гейты волн 1–3 зелёные на шести синтетических наборах
  - Требования: AC-2.1, AC-2.2, AC-2.3, AC-2.4, AC-2.5, AC-2.6, AC-2.7, AC-2.8, AC-2.9, AC-2.10, AC-2.11, AC-2.12, AC-2.13, NFR-1, NFR-2, NFR-3
  - Зависит от: T-7, T-8
  - Файлы: любые, кроме документов пакета
  - Волна: 4
  - Содержание: прогон `gate-check` целиком без `--approve` (гейты одобрены до запуска; новый гейт добавляется с `ABANDON` до одобрения человеком), разбор каждого красного гейта до причины в коде или в правиле валидатора; спорная трактовка правила решается в пользу CONSTRAINTS.md и записывается в журнал; сюда же замеры времени по NFR-1 для трёх сидов.

- [ ] **T-10** Сквозная проверка: все гейты перепроверены, отчёт сверен с brief.md
  - Требования: AC-0.1, AC-1.1, AC-1.2, AC-1.3, AC-1.4, AC-2.1, AC-2.2, AC-2.3, AC-2.4, AC-2.5, AC-2.6, AC-2.7, AC-2.8, AC-2.9, AC-2.10, AC-2.11, AC-2.12, AC-2.13, AC-3.1, AC-3.2, AC-3.3, AC-4.1, AC-4.2, AC-4.3, AC-5.1, AC-5.2, NFR-1, NFR-2, NFR-3
  - Зависит от: T-9
  - Файлы: `docs/specs/backend-core/tasks.md` (журнал), `docs/specs/backend-core/index.md` (статус)
  - Волна: 5
  - Содержание: `gate-check --reverify`, `check_trace.py`, перечитать brief.md и раздел «Как выполнить формально без пользы», отчёт с ID гейтов, коммит и пуш ветки `feature/backend-core`.

## Журнал исполнения

Заполняет исполнитель, только дописывает, не переписывает.

### Прогресс

- 2026-09-15, T-0 готова. JDK 11 и Maven поставлены, ветка `feature/backend-core` создана и запушена. `rules/rules.json` сверен с CONSTRAINTS.md: `RULES MATCH`, семь порченых копий дают `RULES MISMATCH` с именем поля. Лист 1.0 перепроверен драйвером: AC-3.3, AC-0.1 и проверка ветки зелёные.
- 2026-09-15, волна 1 запущена: T-1, T-2, T-3 тремя фоновыми агентами, каждый в своём git worktree.

### Отклонения и находки

- `heatcheck/rules_check.py` написан в волне 0, а не в T-3. Так общий `rules.json` сверен до параллельных волн, и три агента не стартуют от непроверенных чисел. Файл лежит в `tools/validator/**` и дальше принадлежит T-3.
- Служебный ключ `_comment` про диапазон DN 500 перенесён из объекта `restrictions` внутрь `restrictions.oks_existing`. Иначе код, который перебирает типы ограничений, получил бы лишний «тип» `_comment`.
- Добавлен `docs/interpretation.md`: как читать места формата выхода, где CONSTRAINTS.md допускает несколько прочтений (узлы и направление участков, совпадение врезки и новой камеры, цепочка одного диаметра как связное множество с суммой длин, допуски длины и стоимости, отступ от сети у врезки, части реконструкции между двумя врезками). Сервис и валидатор пишут разные агенты, без общего документа они проверяли бы разные правила. Спорные места решены строже.
- В `scripts/gates/env.sh` добавлена функция `ensure_jar`: `produce.sh`, `perf.sh` и `api.sh` пересобирают jar, если исходники новее, и не зависят от порядка гейтов.
- Порт PostgreSQL для хоста в compose — `${POSTGRES_HOST_PORT:-55432}`: 5432 на машине исполнителя занят другим контейнером. Внутри compose приложение ходит в базу по 5432.
- Дороги и трамвайные пути в генераторе — полосы шириной ровно 20 м. D-15 задаёт 8–20 м, AC-4.2 требует сторону полигона не меньше 20 м; 20 м подходит под оба.
- Surefire запускает юнит-тесты с `-Xmx256m` для всего проекта, потому что T-4 проверяет чтение 50 МБ через `argLine`, а `pom.xml` принадлежит T-1.
- Листы волн 1–3 работают в отдельных git worktree: Maven пишет в общий `target/`, и недописанный код соседнего агента ломал бы компиляцию. Агент коммитит свои файлы в ветку worktree, драйвер вливает её и перепроверяет леджер листа в основном дереве.
- Леджеры листов в `.unlazy/backend-core/gates/` и проверки `.unlazy/backend-core/checks/*.sh` написал и одобрил драйвер. Гейты пакета в `GATES.md` не менялись, их одобрил пользователь до запуска.
- В T-1 добавлен `.dockerignore`: без него в контекст сборки образа уходят `data/` с файлами в сотни мегабайт и `tools/.venv`.

### Самревью циклов

- (пусто до старта)
