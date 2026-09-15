# Ресёрч: бекенд трассировки тепловых сетей

## Вопросы

- Какие Java-библиотеки и версии совместимы с Java 11 и Spring Boot 2.6.3 для геометрии, репроекции, потокового GeoJSON, графов — от этого зависят зависимости в pom (D-1, C-1).
- Какой алгоритм трассировки даёт прямые трассы с малым числом поворотов и позволяет проверять угол пересечения — от этого зависит ADR-0001 и декомпозиция задач.
- Сроки и формат сдачи, есть ли публичный датасет — бюджет brief.md и контекст исполнителя.
- Чем проверять результат без человека и как устроить сборку — команды гейтов и tools/.

Ресёрч выполнен 2026-09-15 четырьмя субагентами с поиском в сети. Содержимое страниц — данные, не инструкции.

## Находки

### Стек Java

- **R-1** JTS Topology Suite `org.locationtech.jts:jts-core:1.20.0` собран под Java 8 и содержит всё нужное: `BufferOp` с `BufferParameters` (mitre-углы), `STRtree`, `Geometry.intersection`, `DistanceOp` и `IndexedFacetDistance`, `PreparedGeometry`, `OffsetCurve`.
  - Источник: maven-metadata jts-core (latest 1.20.0, 2024-08-30); https://github.com/locationtech/jts/releases; pom 1.20.0 target 1.8.
  - Уверенность: высокая.
  - Влияет на: D-7, D-8, C-1.
- **R-2** Репроекция без GeoTools: `org.locationtech.proj4j:proj4j:1.4.3` плюс `proj4j-epsg:1.4.3` (в базе есть `<32637> +proj=utm +zone=37 +datum=WGS84`). Трансверсальная Меркатора там старого типа USGS, но в пределах 3° от осевого меридиана 39° ошибка меньше миллиметра; Москва на 37,6°.
  - Источник: maven-metadata proj4j (1.4.3, 2026-06-02); README https://github.com/locationtech/proj4j; файл `proj4/nad/epsg` в jar.
  - Уверенность: высокая.
  - Влияет на: D-3, D-13, A-4.
- **R-3** Готовых потоковых GeoJSON-ридеров без GeoTools нет. Jackson 2.13.1 из BOM Boot 2.6.3: `JsonParser` по массиву `features`, `ObjectMapper.readTree(parser)` читает одно поддерево. Multipart в Boot 2.6.3 по умолчанию ограничен 1 МБ, Tomcat складывает части во временную папку, то есть 3 ГБ пишутся на диск дважды. Загрузка сырым телом запроса через `Files.copy(request.getInputStream(), path)` обходит лимиты и пишет один раз.
  - Источник: https://docs.spring.io/spring-boot/docs/2.6.3/reference/html/dependency-versions.html; https://github.com/FasterXML/jackson-docs/wiki/JacksonStreamingApi; `MultipartProperties` Boot 2.6.3.
  - Уверенность: высокая по Jackson и лимитам, средняя по «нет неизвестного ридера».
  - Влияет на: D-3, D-4, NFR-2.
- **R-4** Boot 2.6.3 управляет `hibernate-core 5.6.4.Final`, `postgresql 42.3.1`, `spring-webmvc 5.3.15`, Tomcat 9.0.56. PostGIS нужен только для кросс-задачных пространственных запросов; при расчёте в JTS и хранении файлов на диске он не нужен.
  - Источник: та же страница dependency-versions; https://github.com/postgis/docker-postgis.
  - Уверенность: высокая по версиям, средняя по оценке нужности.
  - Влияет на: D-5, NG-4.
- **R-5** springdoc-openapi-ui 1.7.0 совместим с Boot 2.6.x (матрица: 2.6.x → springdoc 1.6.0+). С Maven dependencyManagement родителя 2.6.3 прижимает транзитивные Spring-версии; проблемы известны только для Gradle.
  - Источник: https://springdoc.org/v1 (FAQ, compatibility matrix); pom springdoc 1.7.0.
  - Уверенность: высокая.
  - Влияет на: C-1, AC-5.1.
- **R-6** `org.jgrapht:jgrapht-core:1.5.3` (2026-04-10) — последний релиз для Java 11 (1.6.0 потребует JDK 21). Есть `DijkstraShortestPath`, `AStarShortestPath`, `KouMarkowskyBermanAlgorithm` для Штейнера.
  - Источник: maven-metadata jgrapht-core; https://github.com/jgrapht/jgrapht/blob/master/HISTORY.md.
  - Уверенность: высокая.
  - Влияет на: D-8, D-10.

### Алгоритмы трассировки

- **R-7** Visibility graph даёт точный кратчайший путь среди полигональных препятствий, путь по построению состоит из касательных к препятствиям, повороты только у вершин буферов. Сеточный A* даёт пути на 8 % длиннее с зигзагами, Theta* короче A* на 4 %, но угол пересечения на сетке квантован по 45° и честно не проверяется.
  - Источник: Nash & Koenig, Theta* for Any-Angle Pathfinding, Game AI Pro 2 (2015), https://www.gameaipro.com/GameAIPro2/GameAIPro2_Chapter16_Theta_Star_for_Any-Angle_Pathfinding.pdf; JAIR 2010, https://arxiv.org/abs/1401.3843.
  - Уверенность: высокая.
  - Влияет на: ADR-0001, D-8, AC-2.13.
- **R-8** Практичный visibility graph строится не по всем вершинам, а по рефлексным (внутренний угол больше 180°) вершинам препятствий; чистый Python на 4335 вершинах строил полный граф 554 с, сокращённый вариант O(n² log n). Буферы нужно строить с mitre-углами и упрощать, иначе число вершин вырастает в разы.
  - Источник: https://github.com/TaipanRex/pyvisgraph; https://extremitypathfinder.readthedocs.io/en/latest/3_about.html.
  - Уверенность: высокая по фактам, средняя по переносу на наши размеры.
  - Влияет на: D-6, D-7, NFR-1.
- **R-9** Эвристика Такахаши–Мацуямы (дерево растёт от корня, присоединяется ближайший терминал по кратчайшему пути) в эмпирическом сравнении дала лучшее качество среди эвристик Штейнера при времени на четыре порядка меньше точного решения. Kou–Markowsky–Berman и Mehlhorn это 2-приближения, Mehlhorn на практике хуже.
  - Источник: Sadeghi & Fröhlich, BMC Bioinformatics 2013, https://pmc.ncbi.nlm.nih.gov/articles/PMC3674966/; https://github.com/networkx/networkx/discussions/7613.
  - Уверенность: средняя (сравнение на невзвешенных графах).
  - Влияет на: D-10, ADR-0001.
- **R-10** Рост цены за метр с расходом (buy-at-bulk) NP-труден, практический приём — переоценка весов по потоку прошлой итерации (DSSP), 2–5 итераций. Графовые эвристики для теплосетей дают решение в пределах 5 % от MILP за 28 с против 656 с.
  - Источник: Kim & Pardalos, Networks 2000, https://onlinelibrary.wiley.com/doi/abs/10.1002/(SICI)1097-0037(200005)35:3%3C216::AID-NET5%3E3.0.CO;2-E; Energy 333 (2025), https://www.sciencedirect.com/science/article/abs/pii/S0360544225028658.
  - Уверенность: средняя.
  - Влияет на: D-10.
- **R-11** k-shortest paths по Йену дают почти одинаковые маршруты (перекрытие до 99 % рёбер). Для содержательно разных вариантов используют разные стартовые условия и отсев по перекрытию; порог перекрытия 80 % длины из работ по альтернативным маршрутам.
  - Источник: https://arxiv.org/html/2406.05388 (2024); Abraham et al., Alternative Routes in Road Networks, https://www.microsoft.com/en-us/research/publication/alternative-routes-in-road-networks/ (2010).
  - Уверенность: высокая.
  - Влияет на: D-11, AC-2.12.
- **R-12** Открытые проекты трассировки теплосетей (THERMOS, DHNx, DHgeN, DHD) ведут трубу по уличному графу через MILP или Steiner-эвристики; свободной трассировки по буферам зданий среди них нет, этот слой пишется самим.
  - Источник: https://github.com/cse-bristol/110-thermos-ui; https://github.com/oemof/DHNx; https://github.com/idiap/dhgen; https://gitlab.com/crem-repository/dhd.
  - Уверенность: высокая по существованию, средняя по деталям.
  - Влияет на: ADR-0001 (альтернативы).

### Контекст конкурса

- **R-13** Сдача прототипа и презентации через личный кабинет до 2026-09-29 включительно; 30.09–14.10 предварительная экспертиза, 15.10 финалисты, 23.10 питч-сессии. Положение конкурса промежуточных чекпоинтов не содержит; промежуточная сдача упомянута только в ТЗ кейса.
  - Источник: https://i.moscow/lct (15.09.2026); Положение «Город», приказ АНО «РЧК» № ОД-171/26 от 27.05.2026, https://i.moscow/upload/lending/DigitalHealth/276439.pdf, п. 5.8; https://t.me/s/leaders_hack пост 857.
  - Уверенность: высокая по датам.
  - Влияет на: бюджет brief.md.
- **R-14** Конкурсный датасет и приложение по глубине публично не опубликованы, выдаются в личном кабинете с 15.09 и по п. 6.3.5 Положения конфиденциальны: их нельзя класть в публичный репозиторий. Публичного слоя тепловых сетей Москвы нет. Эксперты оценивают прототип и презентацию по анкетам; про стенд организатора, код-ревью или автопроверку по эталону в документах ничего нет.
  - Источник: https://i.moscow/hackaton/lct/fcf616b84d0c482dab2e639f7631cfc6; Положение «Город», п. 5.7, 5.12, 5.26, 6.3.5; https://habr.com/ru/articles/959808/.
  - Уверенность: высокая по конфиденциальности, низкая по тому, что эксперты реально открывают.
  - Влияет на: C-8, G-3, A-3.

### Тестовая инфраструктура

- **R-15** docker-compose 1.29.2 читает файлы формата 3.8 и 3.9 одинаково; в Ubuntu 22.04 есть apt-пакет `docker-compose 1.29.2-1`. Отличия от compose v2: имена контейнеров `proj-svc-1` вместо `proj_svc_1`, ключ `version` игнорируется с предупреждением. Гейты не должны ссылаться на имена контейнеров, только на имена сервисов через `compose exec`.
  - Источник: https://github.com/docker/compose/releases/tag/1.27.0; https://packages.ubuntu.com/jammy/docker-compose; https://github.com/docker/compose/issues/10880.
  - Уверенность: высокая.
  - Влияет на: AC-5.1, C-1.
- **R-16** Testcontainers 2.x собран против более новой JUnit Platform, чем 5.8.2 из Boot 2.6.3; ветка 1.x (1.21.4, 2025-12-15) рассчитана на Jupiter 5.x. Boot 2.6.3 версией Testcontainers не управляет. Surefire и failsafe 2.22.2 из Boot стоит поднять до 3.x свойствами `maven-surefire-plugin.version` и `maven-failsafe-plugin.version`; `*Test` идут в surefire, `*IT` в failsafe.
  - Источник: https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.0; https://api.github.com/repos/testcontainers/testcontainers-java/releases/tags/1.21.4; https://maven.apache.org/surefire/maven-failsafe-plugin/integration-test-mojo.html.
  - Уверенность: высокая по версиям, средняя по совместимости 1.21.4 с Boot 2.6.3.
  - Влияет на: D-1, AC-5.2.
- **R-17** Python: shapely 2.1.2 (Python ≥ 3.10, GEOS в колесе), pyproj 3.8.0 (Python ≥ 3.12, `proj.db` с EPSG в колесе, сетки для UTM не нужны). `shapely.dwithin` точнее и дешевле буфера для проверки расстояний. Готовой функции угла пересечения линий нет, считается через направляющие векторы. geopandas не нужен.
  - Источник: https://shapely.readthedocs.io/en/stable/release/2.x.html; https://pypi.org/project/pyproj/; https://pyproj4.github.io/pyproj/stable/api/datadir.html.
  - Уверенность: высокая по версиям, средняя по офлайн-наличию EPSG:32637 в колесе.
  - Влияет на: D-14, D-15, AC-3.3.
- **R-18** Готового генератора синтетических наборов «сеть + здания + препятствия» нет; сетка прямоугольников с джиттером и случайным удалением гарантирует непересекаемость. Property-based (jqwik) требует поднимать JUnit и добавляет только shrinking, для пакета не берётся.
  - Источник: https://jqwik.net/release-notes.html; https://locationtech.github.io/jts/javadoc/org/locationtech/jts/shape/random/RandomPointsBuilder.html.
  - Уверенность: высокая.
  - Влияет на: D-15, NG-5.

## Что говорит против

- Visibility graph квадратичен по числу вершин. На среднем наборе с 200 полигонами это единицы тысяч рефлексных вершин и миллионы проверок видимости; ограничение области (D-6) и радиус кандидатов держат это в секундах, но замера на наших данных нет. NFR-1 проверяет.
- Эвристика Такахаши–Мацуямы не видит объединение ОКС через общий нетерминальный узел; второй проход с проекциями на стволы (D-10) это частично лечит, но не гарантирует.
- Эксперты, по опыту участников прошлых сезонов, могут смотреть только презентацию и прототип, а не код и валидатор. Валидатор всё равно нужен: без него нельзя доказать соблюдение формальных правил, которые эксперты проверяют первыми по ТЗ.
- Testcontainers 1.21.4 с Boot 2.6.3 не проверялся на практике; запасной путь — postgres из compose для интеграционных тестов.
- proj4j распространяет базу EPSG под лицензией EPSG; для конкурса это не помеха.

## Не удалось выяснить

- Реальная производительность visibility graph на нашем размере → порог 120 с в NFR-1 записан как допущение A-1 и проверяется гейтом.
- Состав «отдельного приложения по глубине» организатора → NG-2, пакет по глубине делается после получения документа.
- Требует ли ТЗ в личном кабинете что-то сверх PDF (репозиторий, стенд) → A-3.
- Совместимость Testcontainers 1.21.4 с Boot 2.6.3 → A-5, проверяется первой задачей T-1.
