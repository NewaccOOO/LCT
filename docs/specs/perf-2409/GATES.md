# Gates: время и качество без регресса

OWNS: src/**, scripts/gates/perf_2409.sh, scripts/city_cut.py, docs/specs/perf-2409/**, docs/testing/perf-2409-run.md, docs/research/hypotheses-1809.md, docs/README.md, architecture/ARCHITECTURE.md, architecture/BACKEND.md, README.md, pom.xml, build.gradle, docs/assets/**

Scope: сервис считает быстрее v0.6.3, вариант 1 не хуже по S и числу неподключённых на датасетах организаторов, сценах «густо», 203 сценариях и городе, правила 18.09 соблюдены

- [x] G1: юнит-тесты зелёные
  CHECK: bash -c 'source scripts/gates/env.sh && mvn -B test 2>&1 | tail -30'
  EXPECT: BUILD SUCCESS
  EVIDENCE: automatic-evidence=v1; definition-sha256=0a118f5433c70c702d72f73d37b9f134e5590f92f16b932f841ed90ed626ab13; exit=0; EXPECT=matched; output-sha256=4af4b47f6e50e3c07ee43f6f8b55589187ab113342d1cfcfcbc426378973c240; output-bytes=2155; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT-city; path=253c039e0374/25 entries

- [x] G2: оба датасета организаторов считаются, все точки подключены, check18 без нарушений
  CHECK: scripts/gates/rules_1809.sh organizers
  EXPECT: ORGANIZERS OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=61982096e9bbf0dbe5c115b1cff2f32b2e4900bee76b8634b54a5de5fc994fec; exit=0; EXPECT=matched; output-sha256=2871afad5bb71f72e99872b6de2014be895b4e681b49afd132264232d24092d1; output-bytes=429; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT-city; path=253c039e0374/25 entries

- [x] G3: «густо» проходят check18, вариант 1 на датасетах организаторов и «густо» не хуже v0.6.3 по S и неподключённым
  CHECK: bash -c 'scripts/gates/rules_1809.sh dense && scripts/gates/perf_2409.sh small'
  EXPECT: SMALL NO REGRESSION
  EVIDENCE: automatic-evidence=v1; definition-sha256=24e1c989a6f665997bf53dda486ce20b723fde8dfe116589851925fc94e5bacc; exit=0; EXPECT=matched; output-sha256=4e0bf445cf005121f0464eeb3ac34f11e9aeb3ce2afad16d472dc62dec5bff14; output-bytes=545; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT-city; path=253c039e0374/25 entries

- [x] G4: город считается целиком, срез проходит check18, неподключённых не больше, чем у v0.6.3
  CHECK: bash -c 'scripts/gates/rules_1809.sh city && scripts/gates/perf_2409.sh city'
  EXPECT: CITY NO REGRESSION
  EVIDENCE: automatic-evidence=v1; definition-sha256=1ac35f4ab6680b28ac86b754d7a9cc7c1ac81bbb3b0d3b0cd958e599cc102fe8; exit=0; EXPECT=matched; output-sha256=8314bcc048785da59e200d8efcfe5a774d28ff0989808ecd3ac2e4fe2db34a11; output-bytes=307; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT-city; path=253c039e0374/25 entries

- [x] G5: на 203 сценариях S00–S14 вариант 1 не хуже v0.6.3, вариантов нигде не меньше
  EVIDENCE: 2026-09-25, jar 0.7.0 и jar v0.6.3 прогнаны по 203 сценариям (data/scenarios/S*/input.geojson), сводки сравнены по сценариям: «вариантов меньше: 0; больше: 28; один вместо нескольких: []», «вариант 1: неподключённых больше 0; S хуже 0; лучше 27», «сумма S варианта 1: 222.937 -> 220.631»

- [x] G6: датасет организаторов, «густо» и город считаются быстрее v0.6.3 на той же машине при одинаковой загрузке
  EVIDENCE: 2026-09-25, прогоны подряд A B B A по три пары, медианы PIPELINE DONE v0.6.3 → 0.7.0: датасет 6,1 → 4,7 с, исправленный 6,0 → 4,7 с, «густо» 50/100/200 4,1 → 2,5, 6,8 → 3,5, 13,4 → 6,6 с; город (G4 этого прогона) 324,8 с против 1056 с у v0.6.3 (прогон 23.09.2026 на той же машине, копия лога в data/out/opt/base-r18/city-1.err)

- [x] G7: принятые и отклонённые гипотезы с числами записаны в docs/testing/perf-2409-run.md и docs/research/hypotheses-1809.md
  EVIDENCE: 2026-09-25, проверено вручную: в отчёте разделы «Итог», «Где уходило время», «Что ускорило без изменения выхода», «Что улучшило качество», «Город», «Что проверено и не принято» (9 отклонённых ходов с числами), «Что стоит знать»; в журнале раздел «Время и качество 24–25.09.2026» с таблицей 15 гипотез и решениями
