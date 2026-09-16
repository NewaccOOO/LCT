# Gates: цикл с точной подзадачей

OWNS: src/main/java/ru/lct/heatnet/plan/**, src/test/java/ru/lct/heatnet/plan/**, docs/research/exact-subproblem/**, data/out/exact/**, architecture/ARCHITECTURE.md, docs/research/hypotheses.md

Scope: детерминированный цикл с точной подзадачей в Java-сервисе, который на датасете организаторов даёт S не выше 12,80, не меняет поведение без цикла и оставляет проверяемый журнал экспериментов

- [ ] G0: пакет спеки связан, гейты не пустышки
  CHECK: python3 ~/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/exact-subproblem && node ~/.claude/skills/unlazy/scripts/gate-lint.mjs docs/specs/exact-subproblem/GATES.md
  EXPECT: LINT OK
  EVIDENCE: pending

- [ ] AC-1.1: с циклом датасет даёт S не выше 12,80, сцены medium не хуже базы больше чем на 0,10
  CHECK: python3 scripts/gates/exact_bench.py
  EXPECT: EXACT GAIN OK
  EVIDENCE: pending

- [ ] AC-1.2: два прогона с циклом на датасете побайтно равны
  CHECK: python3 scripts/gates/exact_bench.py
  EXPECT: EXACT DETERMINISTIC
  EVIDENCE: pending

- [ ] AC-1.3: выходы с циклом проходят валидатор на всех сценах стенда
  CHECK: python3 scripts/gates/exact_bench.py
  EXPECT: EXACT VALID
  EVIDENCE: pending

- [ ] AC-1.4: на каждую итерацию цикла есть строка лога exact:
  CHECK: java -Dheatnet.exact.iterations=3 -jar target/heatnet.jar --cli data/samples/small-1.geojson data/out/exact/gate-log.geojson 2>&1 | grep -c "exact:" | awk '{ if ($1 >= 3) print "EXACT LOG OK"; else print "строк exact: " $1 }'
  EXPECT: EXACT LOG OK
  EVIDENCE: pending

- [ ] AC-2.1: цикл описан в ARCHITECTURE.md со свойствами heatnet.exact
  CHECK: test "$(grep -c 'heatnet.exact' architecture/ARCHITECTURE.md)" -ge 2 && grep -q 'точной подзадачей' architecture/ARCHITECTURE.md && echo "ARCH DOC OK"
  EXPECT: ARCH DOC OK
  EVIDENCE: pending

- [ ] AC-2.2: в журнале гипотез есть запись H-6 с решением
  CHECK: test "$(grep -c '### H-6' docs/research/hypotheses.md)" -eq 1 && echo "H6 OK"
  EXPECT: H6 OK
  EVIDENCE: pending

- [ ] AC-3.1: журнал экспериментов сходится с файлами выходов и коммитами, не меньше 6 записей и 3 сцен, есть отрицательный результат
  CHECK: python3 scripts/gates/exact_journal.py
  EXPECT: EXACT JOURNAL OK
  EVIDENCE: pending

- [ ] AC-3.2: файлы выходов экспериментов на месте и читаются
  CHECK: python3 scripts/gates/exact_journal.py
  EXPECT: EXACT JOURNAL OK
  EVIDENCE: pending

- [ ] AC-0.1: без цикла выходы побайтно равны базе, датасет не дольше 60 с
  CHECK: python3 scripts/gates/exact_bench.py
  EXPECT: EXACT OFF UNCHANGED
  EVIDENCE: pending

- [ ] AC-0.2: юнит-тесты, тесты валидатора и сценарии зелёные
  CHECK: scripts/gates/exact_regress.sh
  EXPECT: REGRESS OK
  EVIDENCE: pending

- [ ] NFR-1: датасет с циклом по умолчанию не дольше 360 с
  CHECK: python3 scripts/gates/exact_bench.py
  EXPECT: EXACT TIME OK
  EVIDENCE: pending

- [ ] NFR-2: одна итерация цикла на датасете не дольше 15 с
  CHECK: python3 scripts/gates/exact_iteration.py
  EXPECT: EXACT ITERATION OK
  EVIDENCE: pending
