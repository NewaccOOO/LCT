# Сторонние компоненты и их лицензии

Код сервиса, платформы «Тепломаршрут» и инструментов написан командой. Ниже — чужие компоненты, которые лежат в
репозитории или попадают в собранный `heatnet.jar`, и данные, на которых построены демо-наборы. Тексты лицензий —
по ссылкам; библиотеки в jar лежат без изменений, их исходники доступны в Maven Central.

## В репозитории

| Компонент | Где | Лицензия |
|---|---|---|
| three.js r170 | `platform/vendor/three/` | MIT, © 2010–2024 three.js authors — [LICENSE](../platform/vendor/three/LICENSE) |
| deck.gl 9.1 (с luma.gl, loaders.gl, math.gl) | `scripts/ui3d/vendor/deck.min.js` | MIT, © Vis.gl contributors — [LICENSE](../scripts/ui3d/vendor/LICENSE) |
| Gradle Wrapper 8.13 | `gradlew`, `gradlew.bat`, `gradle/wrapper/` | Apache-2.0 |

## В `heatnet.jar`

| Компонент | Лицензия |
|---|---|
| Spring Boot 2.6.3, Spring Framework, Tomcat, Jackson, springdoc-openapi 1.7.0, swagger-ui 4.18.2 | Apache-2.0 |
| proj4j 1.4.3, proj4j-epsg 1.4.3 | Apache-2.0; база EPSG — [условия EPSG](https://raw.githubusercontent.com/locationtech/proj4j/master/LICENSE.EPSG) |
| JTS Topology Suite 1.20.0 | EPL-2.0 / EDL-1.0 |
| JGraphT 1.5.3 | LGPL-2.1 / EPL-2.0; jheaps — Apache-2.0, apfloat — MIT |
| Hibernate ORM 5.6.4, hibernate-commons-annotations | LGPL-2.1 |
| Logback 1.2.10 | EPL-1.0 / LGPL-2.1 |
| AspectJ weaver | EPL-2.0 |
| Jakarta Persistence, Transaction, Annotation API | EPL-2.0 / EDL-1.0, GPL-2.0 with Classpath Exception |
| Jakarta XML Binding API, istack-commons | EDL-1.0 |
| PostgreSQL JDBC 42.3.1, HdrHistogram | BSD-2-Clause |
| checker-qual | MIT |
| ANTLR 2.7.7 | BSD |

Docker-образ собирается на `eclipse-temurin:11-jre-noble`: OpenJDK — GPL-2.0 with Classpath Exception, от базового образа.

Python-инструменты (`tools/`) в репозитории представлены только `uv.lock`, сами пакеты ставит `uv`: shapely, numpy,
Pygments — BSD; pyproj, pytest, pluggy, iniconfig — MIT; pillow — MIT-CMU; certifi — MPL-2.0; packaging — Apache-2.0 / BSD.

## Данные

| Данные | Где | Условия |
|---|---|---|
| OpenStreetMap | демо `platform/demo/spb-kupchino.*`, входы `data/cities/*` | © участники OpenStreetMap, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/); производные базы — на тех же условиях. Подпись показывается в платформе рядом со сценой |
| Синтетика `heatsynth` | `data/samples/`, демо `small-1` | создана генератором команды |

Датасет организаторов в репозиторий не входит: его кладут в `data/real/` сами.
