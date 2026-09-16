# Задачи: цикл с точной подзадачей

Критерии — в [requirements.md](requirements.md), гейты — в [GATES.md](GATES.md).

## Контракт исполнения

- **Режим:** solo; параллельно только T-2 и T-3, у них разные файлы.
- **Тулчейн и команды:** JDK 11 через `source scripts/gates/env.sh`; сборка `mvn -q -B -DskipTests package`; тесты `make test-unit`, `make test-python`, `make test-scenarios`; стенд `scripts/bench.sh <метка>`; гейты пакета `python3 scripts/gates/exact_bench.py`, `python3 scripts/gates/exact_journal.py`, `python3 scripts/gates/exact_iteration.py`, `scripts/gates/exact_regress.sh`. Всё из корня репозитория, ветка `exact-subproblem`.
- **Интерфейсы:**
  - Свойства JVM: `heatnet.exact.iterations` (число итераций цикла, 0 — выключен; значение по умолчанию выбирает исполнитель по журналу), `heatnet.exact.kmin` и `heatnet.exact.kmax` (размер снимаемой группы, по умолчанию 5 и 7), `heatnet.exact.region` (радиус графа области в метрах, по умолчанию 150), `heatnet.exact.repair` (бюджет сборок починки, по умолчанию 100), `heatnet.exact.seed` (сид выбора подмножеств, по умолчанию 1).
  - Точка входа: `VariantEnumerator.run` после `search` вызывает `ExactCycle.improve(Draft best, int iterations)` и берёт результат, если его S меньше.
  - Лог итерации: одна строка INFO вида `exact: iteration=<n> removed=<ids через запятую> before=<S> after=<S> accepted=<true|false> reason=<текст> ms=<миллисекунды>`.
  - Выходы экспериментов: `data/out/exact/<E-n>-<сцена>.geojson`; журнал `docs/research/exact-subproblem/experiments.md` по таблице из его шапки.

## Список

- [ ] **T-1** База стенда снята: `scripts/bench.sh base` на коммите до цикла, файлы `data/out/bench/<сцена>-base.geojson` на месте, `data/synth/medium-{1,2,3}.geojson` есть
  - Требования: AC-0.1
  - Зависит от: —
  - Файлы: data/out/bench/**, data/synth/**
  - Волна: 1
- [ ] **T-2** Граф области и точная модель: класс `ExactModel` строит граф вокруг снятых ОКС радиусом `region` из графа видимости `Router` с кандидатами врезок от всех ОКС в трёх радиусах, считает Флойд–Уоршелл и динамику Дрейфуса–Вагнера по подмножествам снятых ОКС для каждой врезки-кандидата, возвращает самое дешёвое дерево по весу с оценкой реконструкции цепочки к источнику; юнит-тест на сцене из 3 ОКС сверяет дерево с перебором
  - Требования: AC-1.2, NFR-2
  - Зависит от: —
  - Файлы: src/main/java/ru/lct/heatnet/plan/ExactModel.java, src/test/java/ru/lct/heatnet/plan/ExactModelTest.java
  - Волна: 2
- [ ] **T-3** Выбор подмножеств и память: класс `SubsetPicker` даёт по сиду детерминированную последовательность групп из `kmin`–`kmax` соседних ОКС, с вероятностью 0,5 берёт все ОКС одного-двух соседних деревьев, помнит уже решённые подмножества при текущем решении и не повторяет их; юнит-тест на детерминированность и отсутствие повторов
  - Требования: AC-1.2
  - Зависит от: —
  - Файлы: src/main/java/ru/lct/heatnet/plan/SubsetPicker.java, src/test/java/ru/lct/heatnet/plan/SubsetPickerTest.java
  - Волна: 2
- [ ] **T-4** Цикл: класс `ExactCycle` снимает группу из черновика, решает подзадачу `ExactModel`, подставляет дерево, чинит спуском `VariantEnumerator.search` с бюджетом `repair`, принимает при снижении S, пишет строку лога на итерацию; `VariantEnumerator.run` вызывает цикл при `iterations` больше нуля; юнит-тест на фикстуре `PlanFixture` с пятью ОКС: цикл не ухудшает S и при `iterations=0` результат побайтно прежний
  - Требования: AC-1.1, AC-1.2, AC-1.3, AC-1.4, AC-0.1, NFR-1
  - Зависит от: T-2, T-3
  - Файлы: src/main/java/ru/lct/heatnet/plan/ExactCycle.java, src/main/java/ru/lct/heatnet/plan/VariantEnumerator.java, src/test/java/ru/lct/heatnet/plan/ExactCycleTest.java
  - Волна: 3
- [ ] **T-5** Эксперименты и журнал: не меньше шести прогонов по стенду с разными `iterations`, `kmin`/`kmax`, `region`, `repair`, включая датасет и не меньше двух сцен medium, каждый со строкой в `experiments.md` и файлом в `data/out/exact/`; по журналу выбрано значение `iterations` по умолчанию и решение, включать ли цикл по умолчанию
  - Требования: AC-3.1, AC-3.2, AC-1.1, NFR-1
  - Зависит от: T-4
  - Файлы: docs/research/exact-subproblem/experiments.md, data/out/exact/**
  - Волна: 4
- [ ] **T-6** Документация: раздел о цикле в `architecture/ARCHITECTURE.md` и запись H-6 в `docs/research/hypotheses.md` с числами стенда и решением
  - Требования: AC-2.1, AC-2.2
  - Зависит от: T-5
  - Файлы: architecture/ARCHITECTURE.md, docs/research/hypotheses.md
  - Волна: 5
- [ ] **T-7** Сквозная проверка: все гейты перепроверены `--reverify`, прежние тесты зелёные, отчёт сверен с brief.md
  - Требования: AC-0.2, AC-1.1, AC-1.2, AC-1.3, AC-1.4, AC-2.1, AC-2.2, AC-3.1, AC-3.2, AC-0.1, NFR-1, NFR-2
  - Зависит от: T-1, T-2, T-3, T-4, T-5, T-6
  - Файлы: —
  - Волна: 6

## Журнал исполнения

Заполняет исполнитель, только дописывает, не переписывает.

### Прогресс

- (пусто)

### Отклонения и находки

- (пусто)

### Самревью циклов

- (пусто)
