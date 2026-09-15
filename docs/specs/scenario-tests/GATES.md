# Gates: сценарный прогон модели трассировки

Scope: не меньше 120 детерминированных сценариев и 300 случайных сидов проходят на сервисе, который следует ТЗ, с отчётом покрытия условий ТЗ

- [ ] G0: пакет спеки связан, гейты не пустышки
  CHECK: python3 /Users/paveldurynin/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/scenario-tests && node /Users/paveldurynin/.claude/skills/unlazy/scripts/gate-lint.mjs docs/specs/scenario-tests/GATES.md
  EXPECT: LINT OK
  EVIDENCE: pending

- [ ] AC-0.2: CONSTRAINTS.md не изменён относительно main
  CHECK: bash scripts/gates/constraints_unchanged.sh
  EXPECT: CONSTRAINTS UNCHANGED
  EVIDENCE: pending

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

- [ ] AC-0.1: все гейты backend-core зелёные после правок сервиса и валидатора
  CHECK: node /Users/paveldurynin/.claude/skills/unlazy/scripts/gate-check.mjs --cwd . --timeout 1800 --reverify docs/specs/backend-core/GATES.md
  EXPECT: ALL MET
  EVIDENCE: pending
