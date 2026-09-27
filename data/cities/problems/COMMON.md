# Общие условия для агентов по проблемам (читать первым)

Главный worktree (данные, прогоны): /Users/a1111/HACK/LCT/LCT/.claude/worktrees/geojson-heat-network-moscow-spb-ea030e
Далее $MAIN.

## Данные
- Реальный город, Санкт-Петербург, Купчино, версия 1: $MAIN/data/cities/runs/spb-v1/
  input.geojson (29 889 объектов: 3 731 oks_existing, 65 oks_future + точки, 4 229 heat_network, 2 162 камеры,
  19 636 restriction: road 4602, power_cable 3693, gas_pipeline 3470, sewer 3450, water_supply 3391, …),
  out.geojson + out.criteria.json (выход v0.9.0), cli.log, check18.txt.
  У объектов входа есть служебное `_source` (real/restored/inferred) — сервис его игнорирует.
  Сеть в v1 достроена по улицам (inferred) + 300 участков реальных труб OSM. Скоро будут версии с восстановленной
  реальной сетью (Москва — сеть ТЭЦ-16 по схеме МОЭК, СПб — ТЭЦ-22); они появятся в $MAIN/data/cities/<город>/input.geojson.
- Датасет организаторов: $MAIN/data/cities/runs/bench/dataset.geojson и dataset-corrected.geojson (+ .out.geojson).
- Синтетика medium-1..3: $MAIN/data/cities/runs/bench/medium-N.geojson (+ .out.geojson).
- Сводка прогона: `python3 $MAIN/data/cities/runs/analyze.py имя вход выход [лог]`.

## Правила кейса
$MAIN/architecture/CONSTRAINTS.md (разделы 5–7, 12–13, 18–19), $MAIN/docs/interpretation.md, $MAIN/rules/rules.json.
Проверщик: $MAIN/tools/validator/check18.py. Устройство алгоритма: $MAIN/docs/algorithm.md.

## Запуск тяжёлого (обязательно через очередь)
Машина: 11 ядер, 18 ГБ ОЗУ, параллельно работают другие агенты. Любой запуск CLI и любую сборку Gradle — только через
очередь (не больше двух одновременно на всех агентов):
  $MAIN/data/cities/runs/run_cli.sh java -Xmx5g -jar <jar> --cli <вход> <выход>
  $MAIN/data/cities/runs/run_cli.sh ./gradlew --no-daemon -q bootJar -x test      (из своего worktree)
Готовый jar текущей версии: $MAIN/build/libs/heatnet.jar. Python: `uv run --project $MAIN/tools python ...`
(не создавай новый .venv в своём worktree).

## Диск почти полон (свободно ~3,5 ГБ)
Свои выходы пиши в $MAIN/data/cities/problems/<твой-код>/, перезаписывай, не копи. Большие входы не копируй —
читай по абсолютному пути. После прототипа удали build/ в своём worktree.

## Что можно
- Анализировать код сервиса ($MAIN/src/main/java/ru/lct/heatnet), логи, выходы.
- Прототипировать исправление в СВОЁМ git worktree (если тебя запустили с изоляцией) и мерить его на данных выше.
  В главном worktree src/ не трогать. Не коммитить в main, не пушить.
- rules/rules.json не менять (это правила организаторов).

## Что сдать
Файл $MAIN/data/cities/problems/<твой-код>/REPORT.md:
1. Проблема: симптом, числа, примеры id.
2. Причина: где в коде (файл:строка), почему на синтетике/датасете не проявляется, а на реальном городе проявляется.
3. Сверка с правилами и данными организаторов: что говорят CONSTRAINTS/interpretation/протоколы; есть ли похожие
   конфигурации в датасете организаторов; является ли поведение нарушением правил или законным следствием.
4. Решение (1–3 варианта с оценкой): что менять, риск для датасета организаторов (S=12,7483 и 0 нарушений check18
   должны сохраниться или улучшиться), ожидаемый эффект на городе. Если есть прототип — путь к ветке/worktree,
   diff, замеры до/после (S, подключено, время, check18) на СПб v1, датасете и medium-1..3.
5. Если проблема не в сервисе, а во входе (нереалистичная генерация) — так и скажи, с доказательством.
Кратко отчитайся в главный разговор: SendMessage to="main" (загрузи через ToolSearch «select:SendMessage»),
5–10 строк, когда REPORT.md готов.
