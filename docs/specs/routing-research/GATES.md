# Gates: ресёрч алгоритмов трассировки и правил разумной трассы

Scope: бенчмарк из 90 сцен с нижними оценками, четыре прототипа, правила разумной трассы с ценами и отчёт с рекомендацией

- [x] G0: пакет спеки связан, гейты не пустышки
  CHECK: python3 /Users/paveldurynin/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/routing-research && node /Users/paveldurynin/.claude/skills/unlazy/scripts/gate-lint.mjs docs/specs/routing-research/GATES.md
  EXPECT: LINT OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=62f2a9fe67a29d5ef996c34693bbe7f4592ad408d6c1a1d55e5c4890ccc4a1bd; exit=0; EXPECT=matched; output-sha256=0938517c472cf93ef91eeba7bdd67b76b77af46312e28f65f858c6c954d5f3c0; output-bytes=82; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-0.2: CONSTRAINTS.md не изменён относительно main
  CHECK: bash scripts/gates/constraints_unchanged.sh
  EXPECT: CONSTRAINTS UNCHANGED
  EVIDENCE: automatic-evidence=v1; definition-sha256=531006cb66abe1fc92e496fe3273dc048d42cb068042d81e554a3e131239c472; exit=0; EXPECT=matched; output-sha256=69141ac61dc7605cf5fe5ae28beeba010d869fd18daaf1e6a46438d3ff7071c0; output-bytes=22; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [ ] AC-0.1: сервис, генератор, валидатор и rules.json не тронуты в ветке ресёрча
  CHECK: bash scripts/gates/research_untouched.sh
  EXPECT: SERVICE UNTOUCHED
  EVIDENCE: pending

- [ ] AC-3.1: точная модель возвращает известный оптимум на пяти ручных сценах
  CHECK: uv run --project tools/research pytest -q tools/research/tests/test_exact.py
  EXPECT: /^5 passed/m
  EVIDENCE: pending

- [ ] AC-2.1: правила разумной трассы с уровнями, источниками, формулами и ценами
  CHECK: uv run --project tools/research python -m heatopt.rules_check docs/routing-quality.md
  EXPECT: /RULES DOC OK rules=(1[2-9]|[2-9]\d) priced=([6-9]|\d{2,})/
  EVIDENCE: pending

- [ ] AC-2.2: калькулятор метрик считает каждое правило и ловит мутации
  CHECK: uv run --project tools/research python -m heatopt.metrics --self-test
  EXPECT: /METRICS OK outputs=7 rules=(1[2-9]|[2-9]\d) mutations=(1[2-9]|[2-9]\d)/
  EVIDENCE: pending

- [ ] AC-1.1: бенчмарк полон, пересчёт трёх сцен совпал с записанным
  CHECK: uv run --project tools/research python -m heatopt.bench --check
  EXPECT: /BENCH OK scenes=90 service=90 candidates=4 exact=([4-9]\d|\d{3,}) lp=90/
  EVIDENCE: pending

- [ ] AC-3.2: нижние оценки не выше любого решения на каждой сцене
  CHECK: uv run --project tools/research python -m heatopt.bench --check
  EXPECT: BOUNDS OK scenes=90
  EVIDENCE: pending

- [ ] AC-1.2: рекомендованный кандидат в пределах 1 % в среднем и 3 % максимум от оптимума
  CHECK: uv run --project tools/research python -m heatopt.bench --check
  EXPECT: /GAP OK candidate=\w+ mean=(0(\.\d+)?|1(\.0+)?)% max=([0-2](\.\d+)?|3(\.0+)?)% scenes_s=([2-9]\d|\d{3,}) scenes_m=([2-9]\d|\d{3,})/
  EVIDENCE: pending

- [ ] AC-1.4: рекомендованный кандидат не хуже сервиса по поворотам, изломам и камерам
  CHECK: uv run --project tools/research python -m heatopt.bench --check
  EXPECT: /RATIONALITY OK candidate=\w+ s_vs_service=(-\d+(\.\d+)?|0(\.0+)?)%/
  EVIDENCE: pending

- [ ] NFR-1: полный бенчмарк уложился в 12 часов
  CHECK: uv run --project tools/research python -m heatopt.bench --check
  EXPECT: /BENCH TIME OK elapsed=(\d{1,4}|[1-3]\d{4}|4[0-2]\d{3}|43[01]\d{2}|43200)s/
  EVIDENCE: pending

- [ ] NFR-2: каждый кандидат укладывается в 120 секунд на классе L в 95 % сцен
  CHECK: uv run --project tools/research python -m heatopt.bench --check
  EXPECT: /CANDIDATE TIME OK class=L p95=(\d{1,2}(\.\d+)?|1[01]\d(\.\d+)?|120(\.0+)?)s/
  EVIDENCE: pending

- [ ] AC-1.3: таблица бенчмарка по пяти алгоритмам и трём классам
  CHECK: uv run --project tools/research python -m heatopt.report --check
  EXPECT: /BENCH REPORT OK rows=(1[5-9]|[2-9]\d)/
  EVIDENCE: pending

- [ ] AC-2.3: правила с ценой вошли в целевую функцию и в столбец бенчмарка
  CHECK: uv run --project tools/research python -m heatopt.report --check
  EXPECT: /OBJECTIVE OK priced_rules=([6-9]|\d{2,})/
  EVIDENCE: pending

- [ ] AC-4.1: отчёт с рекомендацией, числа сверены с результатами бенчмарка
  CHECK: uv run --project tools/research python -m heatopt.report --check
  EXPECT: REPORT OK
  EVIDENCE: pending
