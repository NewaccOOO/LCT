# Как запустить и проверить сервис

Инструкция для проверки решения с нуля: от пустой машины до готового GeoJSON с вариантами трассы и проверки его по правилам кейса. Сервис можно запустить тремя способами:

| Способ | Когда подходит | Что нужно |
|---|---|---|
| [A. Docker Compose](#a-docker-compose-сервис-с-api) | проверить REST API и Swagger, как в эксплуатации | Docker |
| [B. Командная строка](#b-командная-строка-без-веба-и-базы) | посчитать файл и посмотреть результат, без веба и базы | JDK 11 и Maven или Gradle, либо только Docker |
| [C. jar с PostgreSQL](#c-jar-с-api-без-docker) | API без Docker | JDK 11, PostgreSQL |

Все команды выполняются из корня репозитория.

## Что понадобится

| Инструмент | Версия | Для чего |
|---|---|---|
| Docker с Compose | Docker 20+, `docker compose` v2 или `docker-compose` 1.29 | способ A, сборка без JDK |
| JDK | 11 | способы B и C |
| Maven или Gradle | Maven 3.9; Gradle ставить не нужно, есть обёртка `./gradlew` | сборка jar |
| Python и uv | Python 3.12, [uv](https://docs.astral.sh/uv/) | проверщик результата `check18.py` и генератор примеров |

Память: обычному входу на сотни зданий хватает 1 ГБ кучи JVM. Файлу на 3 ГБ с миллионами зданий нужно `-Xmx12g` и 16 ГБ оперативной памяти на машине (раздел [«Большие файлы»](#большие-файлы)).

Пример входа лежит в `data/samples/small-1.geojson`: три здания, сеть и ограничения. На нём удобно проверить, что всё работает. Свой файл положите, например, в `data/real/dataset.geojson`: каталог `data/` кроме `samples/` в git не попадает.

## A. Docker Compose: сервис с API

1. Соберите и поднимите приложение и PostgreSQL:

   ```bash
   docker compose up -d --build
   ```

   Первая сборка скачивает зависимости Maven и занимает несколько минут. Если установлен старый Compose, команда пишется через дефис: `docker-compose up -d --build`.

2. Дождитесь, пока сервис ответит:

   ```bash
   curl http://localhost:8080/actuator/health
   ```

   Ответ `{"status":"UP"}` значит, что сервис готов.

3. Отправьте файл на расчёт. Файл уходит сырым телом запроса, не формой:

   ```bash
   curl -s -X POST -H 'Content-Type: application/geo+json' \
     --data-binary @data/samples/small-1.geojson http://localhost:8080/api/v1/jobs
   ```

   Ответ содержит номер задачи: `{"id":"…","status":"QUEUED"}`.

4. Спрашивайте статус, пока он не станет `DONE` или `FAILED`:

   ```bash
   curl -s http://localhost:8080/api/v1/jobs/<id>
   ```

   После `DONE` в ответе есть сводки вариантов с показателем S и дополнительными критериями. Если во входе ошибки данных, статус `FAILED`, а в `error.errors` перечислены объекты и поля с ошибками.

5. Скачайте результат:

   ```bash
   curl -s http://localhost:8080/api/v1/jobs/<id>/result -o result.geojson
   ```

6. Остановите сервис и удалите данные задач:

   ```bash
   docker compose down -v
   ```

Swagger UI с описанием всех запросов открывается по адресу `http://localhost:8080/swagger-ui/index.html`.

| Запрос | Что делает |
|---|---|
| `POST /api/v1/jobs` | принимает GeoJSON, ставит задачу в очередь, отвечает `202` с `id` |
| `GET /api/v1/jobs` | список задач, новые первыми |
| `GET /api/v1/jobs/{id}` | статус `QUEUED`, `RUNNING`, `DONE` или `FAILED`, ошибки входа, сводки вариантов |
| `GET /api/v1/jobs/{id}/result` | выходной GeoJSON; `409`, пока задача не готова |
| `GET /api/v1/jobs/{id}/input` | исходный файл байт в байт |

Порты и параметры меняются переменными окружения перед `docker compose up`:

| Переменная | По умолчанию | Что задаёт |
|---|---|---|
| `APP_PORT` | `8080` | порт сервиса на машине |
| `POSTGRES_HOST_PORT` | `55432` | порт PostgreSQL на машине |
| `JOB_WORKERS` | `2` | сколько задач считается одновременно |
| `JAVA_OPTS` | `-XX:MaxNewSize=512m` | параметры JVM, например `-Xmx4g -XX:MaxNewSize=512m` |

Например, если порт 8080 занят:

```bash
APP_PORT=18080 docker compose up -d --build
```

## B. Командная строка: без веба и базы

Этот способ удобен, чтобы посчитать файл и сразу получить результат на диск.

1. Соберите jar одной из команд:

   ```bash
   mvn -B -DskipTests package   # Maven: target/heatnet.jar
   ./gradlew bootJar            # Gradle: build/libs/heatnet.jar
   ```

   Maven и Gradle должны видеть JDK 11: проверьте `java -version`. На macOS с Homebrew JDK 11 ставится командой `brew install openjdk@11`, а `source scripts/env.sh` подставляет его в `JAVA_HOME`.

2. Посчитайте файл:

   ```bash
   java -jar target/heatnet.jar --cli data/samples/small-1.geojson data/out/small-1.geojson
   ```

   В конце печатается `PIPELINE DONE variants=3 elapsed=…s`. Рядом с выходом появится `data/out/small-1.criteria.json` с дополнительными критериями вариантов.

   | Код выхода | Что значит |
   |---|---|
   | `0` | расчёт завершён, результат записан |
   | `2` | во входе ошибки данных; они напечатаны по объектам, выходной файл не создаётся |
   | `1` | другая ошибка, текст напечатан |

Без JDK то же самое делает собранный Docker-образ:

```bash
docker build -t heatnet .
docker run --rm -v "$PWD/data:/work" heatnet --cli /work/samples/small-1.geojson /work/out/small-1.geojson
```

То же через `make`: `make cli IN=<вход> OUT=<выход>`.

## C. jar с API без Docker

Нужна база PostgreSQL с базой, пользователем и паролем `heatnet`. Таблица создаётся при старте сама.

```bash
java -jar target/heatnet.jar
```

По умолчанию сервис слушает порт 8080 и ищет базу на `localhost:55432`. Другой адрес базы задаётся переменной `SPRING_DATASOURCE_URL`, другой порт — аргументом `--server.port`, каталог файлов задач — переменной `DATA_DIR` (по умолчанию `data/`):

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/heatnet java -jar target/heatnet.jar --server.port=18080
```

Дальше работа с API — как в пунктах 2–5 способа A, с вашим портом.

## Что лежит в результате

Результат — один GeoJSON в EPSG:4326 с объектами всех вариантов. У каждого объекта есть `variant_id`.

| `object_type` | Что это |
|---|---|
| `heat_network` | новый участок сети: диаметр, расход, длина, способ прокладки, стоимость |
| `heat_chamber` | новая камера: в точке врезки на трубу и в узлах ветвления |
| `technical_node` | технический узел на границе специального прохода |
| `variant_summary` | сводка варианта: стоимость, длина новой сети, показатель S, ранг, неподключённые объекты капитального строительства (ОКС) |

Вариантов до трёх, ранг 1 — лучший по S (чем меньше, тем лучше). Файл `.criteria.json` рядом с выходом CLI объясняет варианты: сколько ОКС подключено, почему остальные нет, сколько камер и спецпроходов, самый крутой поворот. В API те же критерии приходят в сводке задачи.

## Проверка результата

Независимый проверщик `tools/validator/check18.py` не использует код сервиса. Он проверяет выход по техническому приложению от 18.09.2026: состав объектов и ссылки, повороты до 90°, отступы, диаметры, камеры, стоимость и сводку.

```bash
uv run --project tools python tools/validator/check18.py data/samples/small-1.geojson data/out/small-1.geojson
```

Последняя строка `CHECK18 OK` значит, что нарушений нет. Строки с пометкой `i` — справочные, не нарушения. `make run IN=<вход> OUT=<выход>` считает файл и сразу проверяет его.

Посмотреть результат на карте:

```bash
python3 scripts/visualize.py data/samples/small-1.geojson data/out/small-1.geojson
```

Рядом с выходом появится `small-1.html`. Страница открывается в браузере без интернета: варианты во вкладках, сводка справа, щелчок по объекту показывает его свойства.

## Большие файлы

Сервис читает вход потоком и не держит файл в памяти целиком. Если зданий с точками подключения больше 500, включается городской режим: точки рядом с сетью подключаются прямыми участками, остальные считаются районами параллельно. Районы дальних точек (5–11 км от сети) не начинаются после срока `heatnet.city.deadline`, по умолчанию 300 секунд от начала расчёта. На синтетическом городе в 3,2 ГБ и 3,14 млн зданий расчёт занимает около 5,5 минуты.

```bash
java -Xmx12g -XX:MaxNewSize=512m -jar target/heatnet.jar --cli big.geojson big-out.geojson
```

Параметр `-XX:MaxNewSize=512m` оставляйте и со своими настройками: без него на большом входе молодое поколение кучи раздувается на гигабайты, и машина уходит в подкачку. Для такого файла через API поднимите сервис с той же кучей и одной задачей за раз:

```bash
JAVA_OPTS="-Xmx12g -XX:MaxNewSize=512m" JOB_WORKERS=1 docker compose up -d --build
```

## Правила кейса и другой ресурс

Все таблицы и коэффициенты — диаметры, цены, отступы, коэффициенты спецпроходов — лежат в `rules/rules.json` и попадают в jar. Другой файл правил задаётся аргументом `--rules=<путь>`, параметром `-Dheatnet.rules=<путь>` или переменной `HEATNET_RULES`. Пример для водопровода с условными числами:

```bash
java -jar target/heatnet.jar --cli --rules=classpath:/examples/water-supply.json \
  data/samples/small-1.geojson data/out/small-1-water.geojson
```

## Если что-то не работает

| Что видно | Что сделать |
|---|---|
| сборка падает на компиляции | проверьте, что `java -version` и `mvn -version` показывают JDK 11 |
| `port is already allocated` при `docker compose up` | порт занят, задайте другой: `APP_PORT=18080` или `POSTGRES_HOST_PORT=55433` |
| задача в `FAILED` с перечнем объектов | во входе ошибки данных: у каждой записи `featureId`, поле и что не так |
| задача в `FAILED` после перезапуска сервиса | очередь в памяти после рестарта не восстанавливается, отправьте файл заново |
| `OutOfMemoryError` на большом файле | увеличьте кучу `-Xmx` и оставьте `-XX:MaxNewSize=512m` |
| `uv: command not found` | поставьте uv по [инструкции](https://docs.astral.sh/uv/getting-started/installation/) или запустите проверщик своим Python 3.12 с пакетами `shapely` и `pyproj` |

## Где читать дальше

| Документ | Что в нём |
|---|---|
| [README.md](README.md) | что делает сервис, качество и время по версиям |
| [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md) | как устроен расчёт по шагам |
| [architecture/BACKEND.md](architecture/BACKEND.md) | API, критерии, сборка и тесты подробно |
| [architecture/CONSTRAINTS.md](architecture/CONSTRAINTS.md) | правила кейса, которым следует расчёт |
| [docs/interpretation.md](docs/interpretation.md) | как читаются спорные места правил |
