# Бекенд: запуск, API, сборка и инструменты

Практическая часть про сервис на Java: как поднять, как дёргать API, как собрать и проверить, какие есть Python-инструменты. Как сервис устроен внутри и как идёт расчёт, описано в [ARCHITECTURE.md](ARCHITECTURE.md), правила кейса в [CONSTRAINTS.md](CONSTRAINTS.md), требования и журнал разработки в пакете спеки [docs/specs/backend-core/](../docs/specs/backend-core/index.md).

## Состав

Сервис лежит в `src/main/java/ru/lct/heatnet/`, Java 11 и Spring Boot 2.6.3. Все числа кейса читаются из `rules/rules.json`, тот же файл читают Python-инструменты.

Пакеты Java:

- `io` — потоковое чтение входа и запись выхода, перевод между EPSG:4326 и EPSG:32637;
- `rules` — загрузка `rules.json`;
- `graph` — препятствия, visibility graph (граф прямой видимости между вершинами препятствий), кратчайший путь;
- `plan` — кандидаты врезки, деревья, варианты, сборка сети;
- `calc` — расходы, диаметры, предельная длина, реконструкция, стоимость, score (показатель ранжирования из раздела 10 CONSTRAINTS.md, чем меньше, тем лучше);
- `api` — REST и очередь задач в PostgreSQL;
- `cli` — запуск расчёта из командной строки без веба и базы.

## Запуск в Docker

Нужен Docker с docker-compose 1.29.2 или compose v2. Файл compose написан в формате 3.8.

```bash
docker-compose up -d --build
```

После старта:

- API: `http://localhost:8080/api/v1/jobs`;
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`;
- состояние: `http://localhost:8080/actuator/health`.

Остановить и удалить контейнеры вместе с томами:

```bash
docker-compose down -v
```

Переменные окружения:

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `APP_PORT` | `8080` | порт приложения на хосте |
| `POSTGRES_HOST_PORT` | `55432` | порт PostgreSQL на хосте; внутри compose приложение ходит в базу по 5432 |
| `JOB_WORKERS` | `2` | сколько задач считается одновременно |
| `JAVA_OPTS` | пусто | параметры JVM, например `-Xmx4g` |
| `DATA_DIR` | `/data` в контейнере, `data` локально | каталог входных и выходных файлов задач |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:55432/heatnet` | адрес базы при запуске jar без compose |

## REST API

Расчёт идёт асинхронно: клиент загружает файл, получает `id` задачи и опрашивает её статус.

| Запрос | Ответ |
|---|---|
| `POST /api/v1/jobs`, тело — сам GeoJSON (`application/json` или `application/geo+json`) | `202 {"id", "status": "QUEUED"}`; `400`, если тело пустое или не начинается с `{` |
| `GET /api/v1/jobs` | список задач, новые первыми |
| `GET /api/v1/jobs/{id}` | статус `QUEUED`, `RUNNING`, `DONE` или `FAILED`, времена, ошибка, после `DONE` — сводки вариантов |
| `GET /api/v1/jobs/{id}/result` | выходной GeoJSON; `409`, пока задача не `DONE` |
| `GET /api/v1/jobs/{id}/input` | исходный файл байт в байт |

Файл загружается сырым телом, а не multipart: так вход до 3 ГБ пишется на диск потоком один раз и не упирается в лимиты Tomcat.

```bash
curl -s -X POST -H 'Content-Type: application/geo+json' \
  --data-binary @data/samples/small-1.geojson http://localhost:8080/api/v1/jobs
```

Если во входе нет обязательного атрибута, значение не того типа, `id` повторяется или ссылка ведёт на несуществующий объект, задача завершается `FAILED`. Каждая запись в `error.errors` содержит `featureId`, `field` и `problem`. Хранится до 1000 записей, общее число ошибок написано в `message`.

Ошибки API приходят в одном формате: `{"message": "…", "errors": [...]}`.

## Запуск из командной строки

Для отладки и замеров расчёт можно запустить без веба и базы:

```bash
java -jar target/heatnet.jar --cli data/samples/small-1.geojson data/out/small-1.geojson
```

При успехе команда печатает `PIPELINE DONE variants=<n> elapsed=<s>s` и завершается кодом 0. Если во входе есть ошибки данных, она печатает их и завершается кодом 2 без выходного файла. Любая другая ошибка даёт код 1.

## Сборка и тесты локально

Нужны JDK 11, Maven 3.9, [uv](https://docs.astral.sh/uv/) для Python-инструментов и Docker для интеграционных тестов на Testcontainers. На macOS:

```bash
brew install openjdk@11 maven
```

Скрипт `scripts/gates/env.sh` выставляет `JAVA_HOME` на JDK 11 из Homebrew; перед Maven его нужно подключить:

```bash
source scripts/gates/env.sh
mvn -B verify
```

`mvn verify` собирает `target/heatnet.jar` и запускает юнит-тесты (`*Test`, surefire) и интеграционные (`*IT`, failsafe). `JobApiIT` поднимает PostgreSQL в контейнере, `PipelineSampleIT` считает `data/samples/small-1.geojson` и проверяет результат валидатором.

## Генератор и валидатор

Конкурсного набора в репозитории нет: он конфиденциален, и каталог `data/` кроме `data/samples/` исключён из git. Для разработки и проверок есть генератор синтетики той же структуры. Пресеты: `small` (3 ОКС), `medium` (20 ОКС, 200 ограничений, 300 участков сети), `large` (100 ОКС). Одинаковый сид даёт одинаковый файл.

```bash
uv run --project tools python -m heatsynth --preset medium --seed 1 --out data/synth/medium-1.geojson
uv run --project tools python -m heatsynth.check data/synth/medium-1.geojson --preset medium
```

Флаг `--pad-mb 300` дописывает далёкие полигоны, пока файл не вырастет до 300 МБ. Так проверяется потоковая обработка больших входов.

Валидатор не использует код сервиса и проверяет выходной файл по правилам разделов 5–13 CONSTRAINTS.md: схему, дерево, врезки, расходы, диаметры, предельную длину, ограничения, реконструкцию, стоимость, score, покрытие ОКС, различие вариантов и форму трасс.

```bash
uv run --project tools python -m heatcheck data/synth/medium-1.geojson data/out/medium-1.geojson
uv run --project tools python -m heatcheck data/synth/medium-1.geojson data/out/medium-1.geojson --only cost
```

Полный прогон печатает таблицу по правилам и заканчивается `VALIDATION PASSED` или `VALIDATION FAILED: <правила>`.

Сверка `rules/rules.json` с таблицами CONSTRAINTS.md:

```bash
uv run --project tools python -m heatcheck.rules_check architecture/CONSTRAINTS.md rules/rules.json
```

## Границы применения

- Расчёт только в плане, 2D. Задача с глубиной не реализована, `depth_start` и `depth_end` в выходе всегда `null`.
- Решение эвристическое, минимум стоимости не гарантируется.
- Сервис проверен только на синтетике. На `medium` расчёт занимает 6–16 секунд на 11-ядерной машине. Размеры и геометрия конкурсного набора могут дать другое время.
- Если точка подключения ОКС лежит внутри зоны отступа от препятствия, маршрута к ней нет, и ОКС уходит в неподключённые.
- Граф строится за время, квадратичное по числу узлов, поэтому для каждой группы ОКС он строится на ограниченную область вокруг неё.
- Отступ от газопровода, кабеля или теплосети снимается со всего ребра, которое этот объект пересекает. Сборка отбрасывает деревья, где из-за этого нарушен отступ; на реальных данных это может стоить варианта.
- При длинной трассе и малом расходе между цепочками минимального диаметра появляются короткие вставки на ступень больше. Правилам это соответствует, но инженер может счесть такое решение странным.
- Файл 300 МБ проверен при куче JVM 1 ГБ. Для входа до 3 ГБ в `JAVA_OPTS` нужна пропорционально большая куча и `JOB_WORKERS=1`.
- Совместимость `docker-compose.yml` с docker-compose 1.29.2 проверена разбором файла, развёртывание на Ubuntu Server 22 не проверялось.
