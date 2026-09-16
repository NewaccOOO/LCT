# Gates: большой реалистичный датасет

OWNS: tools/synth/heatsynth/**, tests/synth/**, Makefile, docs/testing/dataset-report.md, docs/README.md, .gitignore, data/synth/**, data/out/city/**, data/out/dense/**

Scope: потоковый генератор файла «город» от 3 ГБ и сцен «густо» в формате датасета организаторов с распределениями застройки, похожими на реальные, и прогон сервиса на них

- [ ] G0: пакет спеки связан, гейты не пустышки
  CHECK: python3 ~/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/city-dataset && node ~/.claude/skills/unlazy/scripts/gate-lint.mjs docs/specs/city-dataset/GATES.md
  EXPECT: LINT OK
  EVIDENCE: pending

- [ ] AC-1.1: файл «город» не меньше 3 ГБ и миллиона объектов
  CHECK: python3 scripts/gates/city_dataset.py city
  EXPECT: CITY FILE OK
  EVIDENCE: pending

- [ ] AC-1.2: файл «город» проходит потоковую проверку heatsynth.check
  CHECK: python3 scripts/gates/city_dataset.py city
  EXPECT: CITY FILE OK
  EVIDENCE: pending

- [ ] AC-1.3: сервис считает файл «город» до конца при куче 12 ГБ
  CHECK: scripts/gates/city_run.sh
  EXPECT: CITY RUN OK
  EVIDENCE: pending

- [ ] AC-2.1: три сцены «густо» с 50, 100 и 200 точками подключения проходят проверку
  CHECK: python3 scripts/gates/city_dataset.py dense
  EXPECT: DENSE FILES OK
  EVIDENCE: pending

- [ ] AC-2.2: на сценах «густо» сервис подключает все ОКС
  CHECK: scripts/gates/dense_run.sh
  EXPECT: DENSE RUN OK
  EVIDENCE: pending

- [ ] AC-3.1: отчёт с распределениями, медианы вершин и площади зданий города в пределах организаторов
  CHECK: python3 scripts/gates/city_dataset.py stats
  EXPECT: DATASET STATS OK
  EVIDENCE: pending

- [ ] AC-3.2: застройка кварталами вдоль дорог, в городе не меньше 1000 дорог
  CHECK: test "$(grep -c '"road"' data/synth/city-1.geojson)" -ge 1000 && grep -q 'Как устроена сцена' docs/testing/dataset-report.md && echo "STREETS OK"
  EXPECT: STREETS OK
  EVIDENCE: pending

- [ ] AC-0.1: пресет medium даёт побайтно прежний файл
  CHECK: scripts/gates/synth_regress.sh
  EXPECT: SYNTH REGRESS OK
  EVIDENCE: pending

- [ ] AC-0.2: прежние тесты валидатора и сценариев зелёные
  CHECK: scripts/gates/synth_regress.sh
  EXPECT: SYNTH TESTS OK
  EVIDENCE: pending

- [ ] NFR-1: генерация «города» не дольше 30 минут и не больше 4 ГБ памяти
  CHECK: python3 scripts/gates/city_dataset.py stats
  EXPECT: CITY GEN TIME OK
  EVIDENCE: pending

- [ ] NFR-2: расчёт «города» сервисом не дольше 60 минут
  CHECK: scripts/gates/city_run.sh
  EXPECT: CITY RUN OK
  EVIDENCE: pending
