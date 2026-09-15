# Бекенд трассировки тепловых сетей

- **Статус:** согласовано
- **Размер:** Full
- **Тип:** фича
- **Исполнение:** параллельно, unlazy scope `backend-core`
- **Согласовал:** Pavel Durynin, 2026-09-15

## Документы

Порядок чтения для исполнителя сверху вниз. Факт живёт в одном документе, остальные ссылаются на его ID.

| Документ | Что в нём |
|---|---|
| [../../../CONSTRAINTS.md](../../../CONSTRAINTS.md) | правила кейса: таблицы, формулы, схемы входа и выхода |
| [brief.md](brief.md) | зачем, цели `G-`, не-цели `NG-`, бюджет, границы автономии |
| [research.md](research.md) | находки `R-` с источниками, что говорит против, допущения |
| [requirements.md](requirements.md) | истории `US-`, критерии `AC-` и `NFR-`, ограничения `C-`, допущения `A-`, журнал уточнений `Q-` |
| [design.md](design.md) | решения `D-`, интерфейсы, альтернативы, выкатка и откат |
| [adr/0001-routing-approach.md](adr/0001-routing-approach.md) | почему visibility graph и эвристика Штейнера |
| [tasks.md](tasks.md) | контракт исполнения, задачи `T-` по волнам, журнал исполнения |
| [GATES.md](GATES.md) | гейты unlazy: у каждого `AC-` и `NFR-` свой гейт с тем же ID |

## Проверка пакета

```bash
python3 ~/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/backend-core
node ~/.claude/skills/unlazy/scripts/gate-lint.mjs docs/specs/backend-core/GATES.md
node ~/.claude/skills/unlazy/scripts/gate-check.mjs --status docs/specs/backend-core/GATES.md
```

Прогон гейтов всегда из корня репозитория с таймаутом 1800 секунд, потому что одобрение привязано к таймауту:

```bash
node ~/.claude/skills/unlazy/scripts/gate-check.mjs --cwd . --timeout 1800 docs/specs/backend-core/GATES.md
```

## Промт запуска

```text
Ты автономный исполнитель. Используй скиллы goal-setter (режим исполнения, references/execution.md) и unlazy.
Пакет спеки: docs/specs/backend-core/. Прочитай index.md и все документы по порядку до первого действия. Выполняй буквально.
Готово только когда `node ~/.claude/skills/unlazy/scripts/gate-check.mjs --cwd . --timeout 1800 --reverify docs/specs/backend-core/GATES.md` печатает ALL MET и `python3 ~/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/backend-core` печатает TRACE OK.
Пользователя рядом нет: вопросы не задавай, решения фиксируй в журнале tasks.md. При конфликте побеждает спека.
Работай в ветке feature/backend-core. Независимые задачи одной волны запускай субагентами одновременно.
```
