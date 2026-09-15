# Gates: сценарный прогон модели трассировки

Scope: не меньше 120 детерминированных сценариев и 300 случайных сидов проходят на сервисе, который следует ТЗ, с отчётом покрытия условий ТЗ

- [x] G0: пакет спеки связан, гейты не пустышки
  CHECK: python3 /Users/paveldurynin/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/scenario-tests && node /Users/paveldurynin/.claude/skills/unlazy/scripts/gate-lint.mjs docs/specs/scenario-tests/GATES.md
  EXPECT: LINT OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=db7eb9f27c9f79afb66cbfe26e73f27a197a09aaf19b9ce845207936471aeb42; exit=0; EXPECT=matched; output-sha256=907658a6d1cbde73163917aa4a5a83983e291d899f0912b862193fd0ff3803f6; output-bytes=83; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-0.2: CONSTRAINTS.md не изменён относительно main
  CHECK: bash scripts/gates/constraints_unchanged.sh
  EXPECT: CONSTRAINTS UNCHANGED
  EVIDENCE: automatic-evidence=v1; definition-sha256=531006cb66abe1fc92e496fe3273dc048d42cb068042d81e554a3e131239c472; exit=0; EXPECT=matched; output-sha256=69141ac61dc7605cf5fe5ae28beeba010d869fd18daaf1e6a46438d3ff7071c0; output-bytes=22; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [ ] AC-3.2: rules.json содержит типы вне таблицы со значениями из research.md, сверка с CONSTRAINTS.md не изменилась
  CHECK: uv run --project tools python -m heatscen.rules_ext docs/specs/scenario-tests/research.md rules/rules.json && uv run --project tools python -m heatcheck.rules_check CONSTRAINTS.md rules/rules.json
  EXPECT: /RULES EXT MATCH[\s\S]*RULES MATCH/
  EVIDENCE: pending

- [ ] AC-2.2: генератор размещает только перечисленные типы, ставит префикс ID и остаётся детерминированным
  CHECK: bash scripts/gates/synth_types.sh
  EXPECT: SYNTH TYPES OK subsets=4
  EVIDENCE: pending

- [ ] AC-1.1: все сценарии проходят, ни один не пропущен
  CHECK: bash scripts/gates/scenarios.sh
  EXPECT: /SCENARIOS OK passed=(1[2-9]\d|[2-9]\d\d|\d{4,}) failed=0 skipped=0/
  EVIDENCE: pending

- [ ] AC-3.1: required_diameter врезки равен диаметру нового участка от неё
  CHECK: bash scripts/gates/scenarios.sh -k required_diameter
  EXPECT: /SCENARIOS OK passed=([4-9]|\d{2,}) failed=0 skipped=0/
  EVIDENCE: pending

- [ ] AC-3.3: неизвестный тип ограничения обходится как запрет с предупреждением, расчёт не падает
  CHECK: bash scripts/gates/scenarios.sh -k unknown_type
  EXPECT: /SCENARIOS OK passed=([2-9]|\d{2,}) failed=0 skipped=0/
  EVIDENCE: pending

- [ ] AC-3.4: типы вне таблицы обрабатываются сервисом и валидатором по rules.json
  CHECK: bash scripts/gates/scenarios.sh -k S14
  EXPECT: /SCENARIOS OK passed=([8-9]|\d{2,}) failed=0 skipped=0/
  EVIDENCE: pending

- [ ] AC-1.3: каждое объявленное ожидание отвергает мутацию своего поля
  CHECK: uv run --project tools python -m heatscen.mutate
  EXPECT: /MUTATION OK scenarios=(1[2-9]\d|[2-9]\d\d|\d{4,}) expectations=\d+ missed=0/
  EVIDENCE: pending

- [ ] AC-2.1: 300 сидов с подмножествами типов проходят валидатор, есть специальные участки и реконструкция
  CHECK: bash scripts/gates/sweep.sh
  EXPECT: /SWEEP OK seeds=300 failed=0 special=[1-9]\d* recon=[1-9]\d*/
  EVIDENCE: pending

- [ ] AC-1.2: каждое условие ТЗ в скоупе покрыто не меньше чем двумя разными сценариями
  CHECK: uv run --project tools python -m heatscen.report --check
  EXPECT: /COVERAGE OK items=\d+ uncovered=0/
  EVIDENCE: pending

- [ ] AC-4.2: таблица дефектов заполнена и ссылается на существующие сценарии
  CHECK: uv run --project tools python -m heatscen.report --check
  EXPECT: /DEFECTS OK rows=\d+/
  EVIDENCE: pending

- [ ] NFR-1: сценарии и случайный прогон вместе укладываются в 1500 секунд
  CHECK: uv run --project tools python -m heatscen.report --check
  EXPECT: /TIME OK elapsed=(\d{1,3}|1[0-4]\d\d|1500)s/
  EVIDENCE: pending

- [ ] AC-4.1: отчёт пересобирается командой и совпадает с закоммиченным
  CHECK: uv run --project tools python -m heatscen.report --check
  EXPECT: REPORT OK
  EVIDENCE: pending

- [x] AC-0.1: все гейты backend-core зелёные после правок сервиса и валидатора
  CHECK: node /Users/paveldurynin/.claude/skills/unlazy/scripts/gate-check.mjs --cwd . --timeout 1800 --reverify docs/specs/backend-core/GATES.md
  EXPECT: ALL MET
  EVIDENCE: automatic-evidence=v1; definition-sha256=85fc3db45862e34e679389b905a201fabe847d6b4c5b6184c51ef50b9da30cc1; exit=0; EXPECT=matched; output-sha256=f01cb4ab401d67ccbfb16f3e9516974ea0e7abcfa9c4a407bbe1c2237007d7a7; output-bytes=34564; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries
