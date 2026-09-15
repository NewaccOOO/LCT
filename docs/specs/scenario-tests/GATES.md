# Gates: сценарный прогон модели трассировки

Scope: не меньше 120 детерминированных сценариев и 300 случайных сидов проходят на сервисе, который следует ТЗ, с отчётом покрытия условий ТЗ

- [x] G0: пакет спеки связан, гейты не пустышки
  CHECK: python3 /Users/paveldurynin/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/scenario-tests && node /Users/paveldurynin/.claude/skills/unlazy/scripts/gate-lint.mjs docs/specs/scenario-tests/GATES.md
  EXPECT: LINT OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=db7eb9f27c9f79afb66cbfe26e73f27a197a09aaf19b9ce845207936471aeb42; exit=0; EXPECT=matched; output-sha256=00dcfca54f08119e2f6cd3d02090d77ccfbc11f0623012412b090c9d587efb0e; output-bytes=453; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-0.2: CONSTRAINTS.md не изменён относительно main
  CHECK: bash scripts/gates/constraints_unchanged.sh
  EXPECT: CONSTRAINTS UNCHANGED
  EVIDENCE: automatic-evidence=v1; definition-sha256=531006cb66abe1fc92e496fe3273dc048d42cb068042d81e554a3e131239c472; exit=0; EXPECT=matched; output-sha256=69141ac61dc7605cf5fe5ae28beeba010d869fd18daaf1e6a46438d3ff7071c0; output-bytes=22; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-3.2: rules.json содержит типы вне таблицы со значениями из research.md, сверка с CONSTRAINTS.md не изменилась
  CHECK: uv run --project tools python -m heatscen.rules_ext docs/specs/scenario-tests/research.md rules/rules.json && uv run --project tools python -m heatcheck.rules_check CONSTRAINTS.md rules/rules.json
  EXPECT: /RULES EXT MATCH[\s\S]*RULES MATCH/
  EVIDENCE: automatic-evidence=v1; definition-sha256=83d196e9c8302b224ee6d3ffd5907ab436f540b20230e9bd689b324e7bc21cd7; exit=0; EXPECT=matched; output-sha256=346b08483708ec0d8b58400e101e33f12369c009fdd38e9e2f4236e3d9f5729a; output-bytes=28; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.2: генератор размещает только перечисленные типы, ставит префикс ID и остаётся детерминированным
  CHECK: bash scripts/gates/synth_types.sh
  EXPECT: SYNTH TYPES OK subsets=4
  EVIDENCE: automatic-evidence=v1; definition-sha256=79bb849ff1f2e2869bb56039fc3c69aa4d29d9c84ba37d07f3658fd3121e4e0b; exit=0; EXPECT=matched; output-sha256=71fa77228b36c5d6a5ef0921fa00bff3ecb12425f65a3d2681c017dadc8f9a51; output-bytes=157; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-1.1: все сценарии проходят, ни один не пропущен
  CHECK: bash scripts/gates/scenarios.sh
  EXPECT: /SCENARIOS OK passed=(1[2-9]\d|[2-9]\d\d|\d{4,}) failed=0 skipped=0/
  EVIDENCE: automatic-evidence=v1; definition-sha256=afdc7467a761c34c818d223966b96b1317081bb77d0a3fe1189a6679b86a134e; exit=0; EXPECT=matched; output-sha256=42a612b4d50e8ef00e4371ff1e6f36f0708526336da134cc20625c18cd7cb598; output-bytes=395; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-3.1: required_diameter врезки равен диаметру нового участка от неё
  CHECK: bash scripts/gates/scenarios.sh -k required_diameter
  EXPECT: /SCENARIOS OK passed=([4-9]|\d{2,}) failed=0 skipped=0/
  EVIDENCE: automatic-evidence=v1; definition-sha256=6738c8b0a1be589a257abaeebec1a6f7ffef9ad9b7de4b6c406c48f22dbe5c9e; exit=0; EXPECT=matched; output-sha256=91e2092a377f88361029c03dde2a174766d7e9ada2c378427fa2da631a4fd3e8; output-bytes=155; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-3.3: неизвестный тип ограничения обходится как запрет с предупреждением, расчёт не падает
  CHECK: bash scripts/gates/scenarios.sh -k unknown_type
  EXPECT: /SCENARIOS OK passed=([2-9]|\d{2,}) failed=0 skipped=0/
  EVIDENCE: automatic-evidence=v1; definition-sha256=6c2e805dbeafc97b744d69b1d25381c721d119d89462c4302f9b474c9a28e390; exit=0; EXPECT=matched; output-sha256=68d8c316c81693a5447fdff98f376642b5457ff9f408ce0e3c1a9387d7581c8a; output-bytes=155; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-3.4: типы вне таблицы обрабатываются сервисом и валидатором по rules.json
  CHECK: bash scripts/gates/scenarios.sh -k S14
  EXPECT: /SCENARIOS OK passed=([8-9]|\d{2,}) failed=0 skipped=0/
  EVIDENCE: automatic-evidence=v1; definition-sha256=fc6cc71e3f7d6b9a46982e6da95de8c1d46a6d404a6226799e98a09f282a999d; exit=0; EXPECT=matched; output-sha256=d54d5ed2aca7e91ab98655ac49e2d6e190db213de176d69569710e9d388e74e2; output-bytes=157; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-1.3: каждое объявленное ожидание отвергает мутацию своего поля
  CHECK: uv run --project tools python -m heatscen.mutate
  EXPECT: /MUTATION OK scenarios=(1[2-9]\d|[2-9]\d\d|\d{4,}) expectations=\d+ missed=0/
  EVIDENCE: automatic-evidence=v1; definition-sha256=eb2f36ac322c088773cd3baea229bdc4913f1b0ee38047b6b33efa451eb19b34; exit=0; EXPECT=matched; output-sha256=7e68011b49704c63def84b451ef54d1424c32b3b0aa3e1c1fcb7f804db3b50d4; output-bytes=52; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.1: 300 сидов с подмножествами типов проходят валидатор, есть специальные участки и реконструкция
  CHECK: bash scripts/gates/sweep.sh
  EXPECT: /SWEEP OK seeds=300 failed=0 special=[1-9]\d* recon=[1-9]\d*/
  EVIDENCE: automatic-evidence=v1; definition-sha256=e32340160465f3ba0059f2d664045df19e3df5d18895472dbc2fcb755f629614; exit=0; EXPECT=matched; output-sha256=9c46524fdc66f75cf2be6d28ffcaa60e5d5ecdf1a1b2ab500a55630be27838d6; output-bytes=15782; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-1.2: каждое условие ТЗ в скоупе покрыто не меньше чем двумя разными сценариями
  CHECK: uv run --project tools python -m heatscen.report --check
  EXPECT: /COVERAGE OK items=\d+ uncovered=0/
  EVIDENCE: automatic-evidence=v1; definition-sha256=6e544feb56fa05598b3450c39fd9b5d882251d81f320a765b512721eee67bc6f; exit=0; EXPECT=matched; output-sha256=2f50c3bbaf7c9f1d62d57f313a59e0d650d6377c3d6be2eb9bdd2db8f97dc9d7; output-bytes=83; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-4.2: таблица дефектов заполнена и ссылается на существующие сценарии
  CHECK: uv run --project tools python -m heatscen.report --check
  EXPECT: /DEFECTS OK rows=\d+/
  EVIDENCE: automatic-evidence=v1; definition-sha256=7fb6d2beaa7d4b8871a25f4b97f1ff9832d7f5578e8ca4c72c36f05d7be71dd8; exit=0; EXPECT=matched; output-sha256=2f50c3bbaf7c9f1d62d57f313a59e0d650d6377c3d6be2eb9bdd2db8f97dc9d7; output-bytes=83; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] NFR-1: сценарии и случайный прогон вместе укладываются в 1500 секунд
  CHECK: uv run --project tools python -m heatscen.report --check
  EXPECT: /TIME OK elapsed=(\d{1,3}|1[0-4]\d\d|1500)s/
  EVIDENCE: automatic-evidence=v1; definition-sha256=2a1ce56c352ac86fac932396eb20438cfba80be8ff1e39291e2c86c8ac321b9e; exit=0; EXPECT=matched; output-sha256=2f50c3bbaf7c9f1d62d57f313a59e0d650d6377c3d6be2eb9bdd2db8f97dc9d7; output-bytes=83; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-4.1: отчёт пересобирается командой и совпадает с закоммиченным
  CHECK: uv run --project tools python -m heatscen.report --check
  EXPECT: REPORT OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=b2917c91c842ee437bdfddda9f16f5361b3da005833a4b84724d18bdb6511e56; exit=0; EXPECT=matched; output-sha256=2f50c3bbaf7c9f1d62d57f313a59e0d650d6377c3d6be2eb9bdd2db8f97dc9d7; output-bytes=83; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [ ] AC-0.1: все гейты backend-core зелёные после правок сервиса и валидатора
  CHECK: node /Users/paveldurynin/.claude/skills/unlazy/scripts/gate-check.mjs --cwd . --timeout 1800 --reverify docs/specs/backend-core/GATES.md
  EXPECT: ALL MET
  EVIDENCE: pending
