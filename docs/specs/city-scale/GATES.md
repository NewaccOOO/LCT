# Gates: расчёт файла «город» за разумное время

OWNS: src/**, scripts/city_scale_extract.py, scripts/gates/city_scale.sh, docs/specs/city-scale/**, docs/testing/city-scale-run.md, docs/README.md, architecture/ARCHITECTURE.md

Scope: сервис считает `data/synth/city-1.geojson` (3,2 ГБ, 3,14 млн ОКС) целиком не дольше часа при куче 12 ГБ, датасет организаторов считается как раньше, эксперименты и числа записаны в отчёт

- [x] G1: выход на датасете организаторов побайтово равен эталону коммита e2e1018
  CHECK: scripts/gates/city_scale.sh organizers
  EXPECT: ORGANIZERS SAME
  EVIDENCE: automatic-evidence=v1; definition-sha256=5595a9604ed54747035756e90466c718d2ac22ec6b023a4dbb2807f1a7b2165f; exit=0; EXPECT=matched; output-sha256=098957a8ac7a20316d082ca41079192e4c7bd27e43346c5ce969ced06e89515a; output-bytes=16; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT-city; path=253c039e0374/25 entries

- [x] G2: юнит-тесты и регрессия организаторов зелёные
  CHECK: bash -c 'source scripts/gates/env.sh && mvn -B test 2>&1 | tail -30'
  EXPECT: BUILD SUCCESS
  EVIDENCE: automatic-evidence=v1; definition-sha256=0a118f5433c70c702d72f73d37b9f134e5590f92f16b932f841ed90ed626ab13; exit=0; EXPECT=matched; output-sha256=ecc1e3fb014593bbff1f455a90bc0fc5500138af3ba9512a0ff447bf94c32785; output-bytes=2172; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT-city; path=253c039e0374/25 entries

- [x] G3: на сценах «густо» подключено не меньше ОКС, чем у эталона e2e1018, S не хуже эталона больше чем на 1 %
  CHECK: scripts/gates/city_scale.sh dense
  EXPECT: DENSE OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=2c79b0b459b17ed4a94669775680de9f4b34d58d3b4f11e6544cbb23a6534d62; exit=0; EXPECT=matched; output-sha256=71ebdbbeb04192586380e56fc2995ffe7b11946943238e59e8aedfcbfe83aed4; output-bytes=308; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT-city; path=253c039e0374/25 entries

- [x] G4: срез города на 1600 ОКС, где сети хватает не всем, считается и проходит валидатор
  CHECK: scripts/gates/city_scale.sh slice
  EXPECT: SLICE VALID
  EVIDENCE: automatic-evidence=v1; definition-sha256=2ed61adec9dd683fd2c2fa2af164979472c48c9d6b6c3b7e248f92f20633ec24; exit=0; EXPECT=matched; output-sha256=4eedc105abc0276b203f95170b690831ee6435883b2985723d111b4715100a6d; output-bytes=102; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT-city; path=253c039e0374/25 entries

- [x] G5: файл «город» считается целиком не дольше часа при куче 12 ГБ
  CHECK: scripts/gates/city_scale.sh city
  EXPECT: CITY SCALE OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=953366318ca11a4a9d730ca45c58936688a7fb80d4a55652ea33ac9f875a1f85; exit=0; EXPECT=matched; output-sha256=fa2d9cd8e1a3a749317006e07844948f42c25da2305e32bdae91372f4985232d; output-bytes=70; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT-city; path=253c039e0374/25 entries

- [x] G6: отчёт docs/testing/city-scale-run.md содержит базовую линию, гипотезы с замерами и итоговое время города
  EVIDENCE: 2026-09-22, проверено вручную: разделы «Итог», «С чего начали» (3 суток и 7 часов без результата, графы 25 809 и 14 199 узлов), таблица этапов 0–11 с временем по фазам, S и числом подключённых, итог 152 с (этап 11, район 16), гейт G5 — 166 с
