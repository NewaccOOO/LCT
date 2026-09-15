# Тесты

Три уровня проверок, все команды из корня репозитория.

**Java.** Юнит-тесты `*Test` и интеграционные `*IT` лежат в `src/test/java`, их запускает Maven. Перед ним нужен JDK 11 из `scripts/gates/env.sh`:

```bash
source scripts/gates/env.sh
mvn -B verify
```

**Python.** Тесты в этой папке, зависимости ставит uv из `tools/pyproject.toml`:

```bash
uv run --project tools pytest tests
```

- `validator/` проверяет валидатор heatcheck на фикстурах из `validator/fixtures/`. Фикстуры пересобирает `build_fixture.py`.
- `scenarios/` гоняет сценарии S00–S14 через CLI сервиса, поэтому нужен собранный `target/heatnet.jar`. Удобнее запускать через гейт, он сам собирает jar и пишет итог в `data/scenarios/`:

```bash
scripts/gates/scenarios.sh -k S00
```

**Гейты приёмки.** Скрипты в `scripts/gates/` соответствуют критериям из `docs/specs/<slug>/GATES.md`. Каждый запускается отдельно, например `scripts/gates/mvn_verify.sh` или `scripts/gates/sweep.sh`.
