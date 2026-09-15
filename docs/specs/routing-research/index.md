# Ресёрч алгоритмов трассировки и правил разумной трассы

- **Статус:** согласовано
- **Размер:** Standard
- **Тип:** ресёрч
- **Исполнение:** параллельно, unlazy scope `routing-research`
- **Согласовал:** Pavel Durynin, 2026-09-15

## Документы

Порядок чтения для исполнителя сверху вниз. Факт живёт в одном документе, остальные ссылаются на его ID.

| Документ | Что в нём |
|---|---|
| [../../../architecture/CONSTRAINTS.md](../../../architecture/CONSTRAINTS.md) | правила кейса и формула S; при расхождении правы документы организатора в `docs/source/` |
| [brief.md](brief.md) | зачем, цели `G-`, не-цели `NG-`, бюджет, границы автономии |
| [research.md](research.md) | находки `R-`: шов в коде, модели и солверы, эвристики, нормы, практика; уровни доказательности правил |
| [requirements.md](requirements.md) | истории `US-`, критерии `AC-` и `NFR-`, ограничения `C-`, допущения `A-`, журнал уточнений `Q-` |
| [tasks.md](tasks.md) | контракт исполнения с интерфейсами `heatopt`, задачи `T-` по волнам, журнал исполнения |
| [GATES.md](GATES.md) | гейты unlazy: у каждого `AC-` и `NFR-` свой гейт с тем же ID |
| [../backend-core/design.md](../backend-core/design.md), [../backend-core/adr/0001-routing-approach.md](../backend-core/adr/0001-routing-approach.md) | как устроен текущий алгоритм |
| [../../interpretation.md](../../interpretation.md) | трактовки формата выхода для модели стоимости |

## Проверка пакета

```bash
python3 ~/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/routing-research
node ~/.claude/skills/unlazy/scripts/gate-lint.mjs docs/specs/routing-research/GATES.md
node ~/.claude/skills/unlazy/scripts/gate-check.mjs --status docs/specs/routing-research/GATES.md
```

Прогон гейтов всегда из корня worktree с таймаутом 1800 секунд, потому что одобрение привязано к таймауту:

```bash
node ~/.claude/skills/unlazy/scripts/gate-check.mjs --cwd . --timeout 1800 docs/specs/routing-research/GATES.md
```

## Промт запуска

```text
Ты автономный исполнитель. Используй скиллы goal-setter (режим исполнения, references/execution.md) и unlazy.
Пакет спеки: docs/specs/routing-research/. Прочитай index.md и все документы по порядку до первого действия. Выполняй буквально.
Готово только когда `node ~/.claude/skills/unlazy/scripts/gate-check.mjs --cwd . --timeout 1800 --reverify docs/specs/routing-research/GATES.md` печатает ALL MET и `python3 ~/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/routing-research` печатает TRACE OK.
Пользователя рядом нет: вопросы не задавай, решения фиксируй в журнале tasks.md. При конфликте побеждает спека, а внутри спеки — документы организатора.
Ты запущен в worktree ../LCT-research на ветке research/routing, гейты одобрены в нём; в основной репозиторий и другие worktree не ходи. Независимые задачи одной волны запускай субагентами одновременно, не больше пяти.
```
