# Пробы масштаба «город» без полного city_run

Ветка: `city-dataset`. JAR: `mvn -B package -DskipTests`. Пробы: 2026-09-18.

## Краткий вывод

| Приоритет | Гипотеза | Эффект | Риск для организаторов |
|---|---|---|---|
| 1 | STRtree/grid для `groups()` **после** метрического порога (или фиксации порога в градусах ~0.0027) | На n=5000 naive ~380 ms vs STRtree ~45 ms (×8.6) | Нет (если результат `groups()` тот же) |
| 2 | `obstacleExtent`: bbox всех CP + `EXTENT_MARGIN_M` вместо `groups()`+`Region` | На организаторах `-Dheatnet.read.all=true` быстрее на ~4% при **побайтово том же** выходе | **OK** (проверено) |
| 3 | Не добавлять partition `singles` при n > 500 | Меньше черновиков/search; оценка по medium/large, не замерено отдельным флагом | Средний (может сменить rank>1) |
| 4 | k-means → сетка при size > M | k-means O(n²) на группе: 500 точек ~12 ms (микробенч) | Средний |
| 5 | `heatnet.search.budget=0` («large mode») | Время −54% на организаторах (22→10 с) | **REGRESSION** S rank=1: 12.596→12.981 |
| 6 | Тайлы `data/synth/tiles/` | Нет каталога — только оценка n/тайл после T-4 | — |

**Экстраполяция только `groups()` (текущий код, порог 300 в WGS84):**  
`t_ms ≈ 0.0198 · n²` (стабильно `t/n²` на n=2k…10k). Для n=3·10⁶ → **~49 ч** на один вызов (×2 на read+run через `obstacleExtent`). Это нижняя граница узкого места; полный pipeline на 100 ОКС (large synth) **~4.3 мин**, routing/search доминируют.

**После STRtree с метрическим порогом (экстраполяция с n=5000):** `groups` ~45 ms → масштаб ~O(n log n) → грубо **минуты**, не часы, на 3M (с большой погрешностью).

---

## Методология

- Микробенчмарк: `mvn -Dtest=CityScaleGroupsBench test` → `target/city-scale-groups-bench.txt`
- Pipeline: `java -jar target/heatnet.jar --cli IN OUT`, `/usr/bin/time -p`, строка `PIPELINE DONE elapsed=`
- Подвыборки: `scripts/city_scale_extract.py`, `scripts/city_scale_replicate.py` (копии CP на решётке)
- Организаторы: `data/real/dataset.geojson`

---

## Гипотеза 1: `groups()` через STRtree

**Метрика:** медиана wall time (5 прогонов), ms; нормировки `t/n`, `t/(n log n)`, `t/n²`.

**Текущий порог 300 (как в коде, WGS84 без проекции):** все точки города в одном компоненте; STRtree с `expandBy(300)` возвращает все пары → **медленнее** naive (~0.5×).

```
n     naive_ms  strtree_ms  t/n2_us
100   0.76      1.51
500   6.52      11.48       0.026
2000  69.61     146.13      0.017
5000  481.62    847.71      0.019
10000 1984.07   3744.23     0.020
```

**Динамика:** `t/n²` ≈ const → **O(n²)**. Ближе к **n²**, не n log n.

**С порогом ~300 м (0.0027°), тот же алгоритм STRtree:**

```
n     naive_ms  strtree_ms  ratio
500   5.59      2.37        2.4×
2000  57.49     10.42       5.5×
5000  383.24    44.73       8.6×
```

**Риск организаторов:** нет при эквивалентности групп.  
**Рекомендация:** внедрять STRtree вместе с **метрическим** расстоянием (проекция / `GeodeticCalculator`), порог N не нужен.

---

## Гипотеза 2: без partition `singles` при n > 500

**Метрика:** число стартовых черновиков в `run()` (сейчас всегда `partitions = [singles, groups, …]`).

**Метод:** код-ревью `VariantEnumerator.run()`; отдельный флаг не включался.

**Оценка:** при n=3000 singles добавляет partition из 3000 блоков → лишние `draft()` и ветки search. Ожидаемое ускорение **10–30%** на больших n (не замерено end-to-end).

**Риск:** может убрать вариант rank 2/3 на плотных кварталах.  
**Порог:** пробовать **500–1000** на dense-сценах (T-5), не на организаторах (n≈17).

---

## Гипотеза 3: `obstacleExtent` без `groups()`

**Метрика:** wall time CLI на организаторах; побайтовое сравнение выхода.

| Режим | real, s | S rank=1 | Выход |
|---|---:|---:|---|
| default (`obstacleExtent` с `groups`) | 22.78 | 12.596 | эталон |
| `-Dheatnet.read.all=true` (все препятствия, extent не режет) | 21.84 | 12.596 | **идентичен** эталону (`cmp`) |

**Вывод:** bbox CP + margin даст тот же отбор на организаторах; `groups()` в `obstacleExtent` — лишняя **O(n²)** на read. На 3M выгода памяти/IO может быть больше, чем −4% времени.

**Риск организаторов:** **OK** (на текущем датасете).

---

## Гипотеза 4: крупные группы — сетка вместо k-means

**Метрика:** ms на один вызов `VariantEnumerator.kMeans` (одна группа).

```
n    ms
50   1
100  1
200  6
500  12
```

**Динамика:** между **O(n²)** и **O(n log n)** на малых n; при size>200 растёт заметно.

**Риск:** смена split-крупных-групп → версия Y.  
**Порог M:** **>200–300** только в «large/city» режиме.

---

## Гипотеза 5: large mode — budget 0, один draft

**Метрика:** `-Dheatnet.search.budget=0`, организаторы.

| | S rank=1 | rank 2/3 | real, s | exit |
|---|---:|---|---:|---|
| baseline | 12.596 | 25.378 / 28.515 | 22.78 | 0 |
| budget=0 | **12.981** | 25.378 / 28.515 | **~10** | 0 |

**Риск:** **REGRESSION** (+0.385 на rank=1). Для city-only флаг допустим, не для организаторов.

---

## Гипотеза 6: по тайлам

**Статус:** `data/synth/tiles/` отсутствует (T-4 не выполнен). Оценка n/тайл — после генерации; ожидаем порядок **(3M / 64) ≈ 47k** ОКС/тайл при 8×8.

---

## Pipeline на синтетике (ограничения)

| Сцена | n CP | PIPELINE elapsed | real |
|---|---:|---:|---:|
| medium-1 | 20 | 15.3 s | 16.7 s |
| large-1 | 100 | ~258 s | ~264 s |
| replicate large → 100 | 100 | ~264 s | (много общих групп, routing) |

Прогон n=500 на replicate **не завершён** (>10 мин ожидания) — search O(n) с большим коэффициентом. Экстраполяция полного города **не линейна** по n.

**Грубая прикидка после фиксов groups+extent (не полный pipeline):**  
узкое место groups 49 ч → **<10 мин** (STRtree+метрика); итог city_run останется **часами** без budget/упрощения draft/search и/или тайлинга.

---

## Регрессия организаторов (итог)

| Проверка | Результат |
|---|---|
| baseline vs `-Dheatnet.read.all=true` | **OK** (S и GeoJSON совпадают) |
| baseline vs `-Dheatnet.search.budget=0` | **REGRESSION** S rank=1, время лучше |
| Время baseline | **22.78 s** (< few sec? **нет**, но стабильно exit 0) |

*Примечание:* в ТЗ пробы «< few sec» — для организаторов текущий baseline ~23 s; пробы city-scale не меняют этот факт.

---

## Внедрено (2026-09-18)

| # | Изменение | Файл / тест |
|---|---|---|
| 1 | `obstacleExtent`: bbox CP перспективных ОКС + `EXTENT_MARGIN_M`, без `groups()` и `Region` | `VariantEnumerator.obstacleExtent` |
| 2 | `groups()`: union-find + STRtree, порог **300 м** в координатах EPSG:32637 (как после `GeoJsonStreamReader`) | `groupsFast` / `groupsNaive`, `GroupsEquivalenceTest` |
| 3 | Partition `singles` только при `n ≤ SINGLES_MAX` (по умолчанию **500**, `-Dheatnet.scale.singles.max`) | `VariantEnumerator.run` |
| 4 | `splitGroup`: k-means при `size ≤ KMEANS_MAX` (**500**, `-Dheatnet.scale.kmeans.max`), иначе разрез bbox по длинной оси UTM | `splitGroup`, `spatialGridSplit` |

**Регрессия организаторов после внедрения:** S rank=1 = **12.596** (допуск 1e-6), выход GeoJSON **побайтово** совпадает с baseline; `mvn test`, `synth_regress`, `mvn_verify` — OK. Java-тест: `OrganizerDatasetRegressionTest`.

**Не внедрено (out of scope):** `heatnet.search.budget=0`, per-tile CLI, STRtree без метрики в WGS.

**TODO:** end-to-end прогон dense/city после T-4/T-5 с новыми порогами singles/k-means.

---

## Артефакты

- `src/test/java/ru/lct/heatnet/plan/CityScaleGroupsBench.java`
- `src/test/java/ru/lct/heatnet/plan/GroupsEquivalenceTest.java`
- `src/test/java/ru/lct/heatnet/plan/OrganizerDatasetRegressionTest.java`
- `scripts/city_scale_extract.py`, `scripts/city_scale_replicate.py`
- `target/city-scale-groups-bench.txt` (локально после прогона)
