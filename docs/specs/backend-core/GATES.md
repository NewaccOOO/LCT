# Gates: бекенд трассировки тепловых сетей

Scope: REST-сервис на Java 11 строит варианты подключения ОКС по правилам CONSTRAINTS.md, а независимый Python-валидатор подтверждает их на синтетических наборах

- [x] G0: пакет спеки связан, гейты не пустышки
  CHECK: python3 ~/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/backend-core && node ~/.claude/skills/unlazy/scripts/gate-lint.mjs docs/specs/backend-core/GATES.md
  EXPECT: LINT OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=bcd5c4169670622cbdad6d4590450f1fabcf3b19b0920493b3a396e124f2192d; exit=0; EXPECT=matched; output-sha256=43f9b8273f71e7784f452075c5da268658b1ea1a64e410390527e2e2270a72a9; output-bytes=83; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [ ] G1: скрипты гейтов строгие и не глушат ошибки
  CHECK: bash -c 'set -e; n=0; for f in scripts/gates/*.sh; do [ "$(basename "$f")" = env.sh ] && continue; n=$((n+1)); head -5 "$f" | grep -q "set -euo pipefail" || { echo "no strict mode: $f"; exit 1; }; grep -nE "\|\| *(true|:)|^ *exit 0|; *true *$" "$f" && { echo "error muted: $f"; exit 1; }; done; test "$n" -ge 8 && echo "GATE SCRIPTS STRICT count=$n"'
  EXPECT: /GATE SCRIPTS STRICT count=\d+/
  EVIDENCE: pending

- [ ] AC-0.1: CONSTRAINTS.md не изменён относительно main
  CHECK: bash scripts/gates/constraints_unchanged.sh
  EXPECT: CONSTRAINTS UNCHANGED
  EVIDENCE: pending

- [ ] AC-5.2: сборка и все тесты проходят, ни один тест не пропущен
  CHECK: bash scripts/gates/mvn_verify.sh
  EXPECT: /MVN VERIFY OK tests=([3-9]\d|\d{3,}) skipped=0/
  EVIDENCE: pending

- [ ] AC-5.1: compose поднимает приложение и базу, Swagger отвечает
  CHECK: bash scripts/gates/compose.sh
  EXPECT: COMPOSE OK
  EVIDENCE: pending

- [ ] AC-4.1: генератор даёт файлы по схеме раздела 12, детерминированно по сиду
  CHECK: bash scripts/gates/synth.sh
  EXPECT: SYNTH SCHEMA OK
  EVIDENCE: pending

- [ ] AC-4.2: средний пресет имеет нужный размер и препятствия на пути
  CHECK: bash scripts/gates/synth.sh
  EXPECT: SYNTH MEDIUM OK
  EVIDENCE: pending

- [ ] AC-4.3: существующая сеть в синтетике связна и монотонна к источнику
  CHECK: bash scripts/gates/synth.sh
  EXPECT: SYNTH NETWORK OK
  EVIDENCE: pending

- [ ] AC-3.3: rules.json совпадает с таблицами CONSTRAINTS.md
  CHECK: uv run --project tools python -m heatcheck.rules_check CONSTRAINTS.md rules/rules.json
  EXPECT: RULES MATCH
  EVIDENCE: pending

- [ ] AC-3.2: валидатор падает на испорченной фикстуре по каждому из 15 правил
  CHECK: uv run --project tools pytest -q tools/validator/tests
  EXPECT: /^(1[5-9]|[2-9]\d|\d{3,}) passed in [\d.]+s/m
  EVIDENCE: pending

- [ ] NFR-1: расчёт среднего пресета укладывается в 120 секунд на каждом из трёх сидов
  CHECK: bash scripts/gates/perf.sh
  EXPECT: /PERF medium OK max=\d+s/
  EVIDENCE: pending

- [ ] AC-3.1: валидатор принимает выходы сервиса по правилу schema
  CHECK: bash scripts/gates/validate_rule.sh schema
  EXPECT: RULE schema: PASSED
  EVIDENCE: pending

- [ ] AC-2.1: каждый ОКС подключён, список неподключённых пуст
  CHECK: bash scripts/gates/validate_rule.sh coverage
  EXPECT: RULE coverage: PASSED
  EVIDENCE: pending

- [ ] AC-2.2: врезка в камеру только по правилу 10 м и четырёх участков
  CHECK: bash scripts/gates/validate_rule.sh tie_in
  EXPECT: RULE tie_in: PASSED
  EVIDENCE: pending

- [ ] AC-2.3: новая сеть это дерево с ветвлением в камерах без самопересечений
  CHECK: bash scripts/gates/validate_rule.sh topology
  EXPECT: RULE topology: PASSED
  EVIDENCE: pending

- [ ] AC-2.4: расход участка равен сумме расходов ОКС ниже по дереву
  CHECK: bash scripts/gates/validate_rule.sh flow
  EXPECT: RULE flow: PASSED
  EVIDENCE: pending

- [ ] AC-2.5: диаметр участка минимальный по таблице пропускной способности
  CHECK: bash scripts/gates/validate_rule.sh diameter
  EXPECT: RULE diameter: PASSED
  EVIDENCE: pending

- [ ] AC-2.6: цепочки одного диаметра не длиннее предельной длины
  CHECK: bash scripts/gates/validate_rule.sh length_limit
  EXPECT: RULE length_limit: PASSED
  EVIDENCE: pending

- [ ] AC-2.7: запрещённые объекты обойдены с отступом, специальные проходы оформлены и под углом
  CHECK: bash scripts/gates/validate_rule.sh forbid && bash scripts/gates/validate_rule.sh special
  EXPECT: /RULE forbid: PASSED[\s\S]*RULE special: PASSED/
  EVIDENCE: pending

- [ ] AC-2.8: реконструкция существующих участков по цепочке к источнику
  CHECK: bash scripts/gates/validate_rule.sh reconstruction
  EXPECT: RULE reconstruction: PASSED
  EVIDENCE: pending

- [ ] AC-2.9: реконструкция камер врезки только при превышении диаметра
  CHECK: bash scripts/gates/validate_rule.sh chamber_recon
  EXPECT: RULE chamber_recon: PASSED
  EVIDENCE: pending

- [ ] AC-2.10: стоимости пересчитываются по формулам с расхождением до 1 рубля
  CHECK: bash scripts/gates/validate_rule.sh cost
  EXPECT: RULE cost: PASSED
  EVIDENCE: pending

- [ ] AC-2.11: score по формуле, ранги по возрастанию
  CHECK: bash scripts/gates/validate_rule.sh score
  EXPECT: RULE score: PASSED
  EVIDENCE: pending

- [ ] AC-2.12: не меньше двух содержательно разных вариантов
  CHECK: bash scripts/gates/validate_rule.sh variants
  EXPECT: RULE variants: PASSED
  EVIDENCE: pending

- [ ] AC-2.13: трассы прямые, без лишних вершин и ступенек
  CHECK: bash scripts/gates/validate_rule.sh geometry
  EXPECT: RULE geometry: PASSED
  EVIDENCE: pending

- [ ] AC-1.1: загрузка файла отвечает 202 с id, пустое тело отвечает 400
  CHECK: bash scripts/gates/api.sh AC-1.1
  EXPECT: API AC-1.1 OK
  EVIDENCE: pending

- [ ] AC-1.2: статус задачи проходит QUEUED, RUNNING, DONE и содержит сводки
  CHECK: bash scripts/gates/api.sh AC-1.2
  EXPECT: API AC-1.2 OK
  EVIDENCE: pending

- [ ] AC-1.3: результат и вход скачиваются, до DONE результат отвечает 409
  CHECK: bash scripts/gates/api.sh AC-1.3
  EXPECT: API AC-1.3 OK
  EVIDENCE: pending

- [ ] AC-1.4: битый вход даёт FAILED со списком диагностик
  CHECK: bash scripts/gates/api.sh AC-1.4
  EXPECT: API AC-1.4 OK
  EVIDENCE: pending

- [ ] NFR-2: файл 300 МБ проходит через API при куче 1 ГБ
  CHECK: bash scripts/gates/api.sh NFR-2
  EXPECT: API NFR-2 OK
  EVIDENCE: pending

- [ ] NFR-3: пять одновременных задач завершаются за 180 секунд
  CHECK: bash scripts/gates/api.sh NFR-3
  EXPECT: API NFR-3 OK
  EVIDENCE: pending
