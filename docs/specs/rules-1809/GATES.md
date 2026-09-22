# Gates: правила 18.09

OWNS: src/**, rules/**, tools/validator/check18.py, scripts/gates/rules_1809.sh, docs/specs/rules-1809/**, docs/testing/rules-1809-run.md, docs/source/**, architecture/CONSTRAINTS.md

Scope: сервис считает по техническому приложению от 18.09 без нарушений на датасетах организаторов и на городе, качество до и после записано

- [x] G1: юнит-тесты зелёные
  CHECK: bash -c 'source scripts/gates/env.sh && mvn -B test 2>&1 | tail -30'
  EXPECT: BUILD SUCCESS
  EVIDENCE: automatic-evidence=v1; definition-sha256=0a118f5433c70c702d72f73d37b9f134e5590f92f16b932f841ed90ed626ab13; exit=0; EXPECT=matched; output-sha256=65246cf8a0de59218979f308f7c061cc1465cd89594ac2051cf20de0eed93a2c; output-bytes=2143; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT-city; path=253c039e0374/25 entries

- [x] G2: оба датасета организаторов считаются, все точки подключены, проверщик правил 18.09 не находит нарушений
  CHECK: scripts/gates/rules_1809.sh organizers
  EXPECT: ORGANIZERS OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=61982096e9bbf0dbe5c115b1cff2f32b2e4900bee76b8634b54a5de5fc994fec; exit=0; EXPECT=matched; output-sha256=0945ab6f308cee08613a0428c5165e030761ff5f046193147368f307c4f9a902; output-bytes=298; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT-city; path=253c039e0374/25 entries

- [x] G3: сцены «густо» 50/100/200 считаются без нарушений правил 18.09
  CHECK: scripts/gates/rules_1809.sh dense
  EXPECT: DENSE OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=f6c386362518f20f1f450839a8d7ea5d7448b4ca9cfa25f6728758a99e3e0bfd; exit=0; EXPECT=matched; output-sha256=e4263df45f123f019f6e90f9dab29757f80fa8551a0874c49765db7d11246c07; output-bytes=214; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT-city; path=253c039e0374/25 entries

- [x] G4: город 3,2 ГБ считается не дольше часа при куче 12 ГБ, срез выхода вокруг новой сети проходит проверщик
  CHECK: scripts/gates/rules_1809.sh city
  EXPECT: CITY OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=eedc5f7ec248e43860aa64ffe1b1d5c6ddcc02b0a96c093712f670824db2ecbe; exit=0; EXPECT=matched; output-sha256=56581e734c66e5248626759a5fcf7a8e98e065d22f5bbf48f0a6ccaa7132a24a; output-bytes=215; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT-city; path=253c039e0374/25 entries

- [x] G5: отчёт docs/testing/rules-1809-run.md содержит S и число подключённых до и после на датасетах организаторов, сценах «густо» и городе, и вывод, что изменилось
  EVIDENCE: 2026-09-22, проверено вручную: таблица «Итог» (датасет организаторов 12,596 → 13,492 при 17/17; «густо» 49/94/185 → 50/98/199 и S по формулам 18.09 90,4/259,7/576,3 → 57,7/116,8/237,2; город 952 → 13 178, S 9 678 224 → 9 641 367, 152 с → 2192 с), разделы «Что изменилось в расчёте», «Датасет организаторов», «Сцены «густо»», «Город», «Что ещё можно улучшить»
