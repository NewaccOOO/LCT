# Сценарный прогон модели трассировки

- **Статус:** согласовано
- **Размер:** Full
- **Тип:** фича
- **Исполнение:** параллельно, unlazy scope `scenario-tests`
- **Согласовал:** Pavel Durynin, 2026-09-15

## Документы

Порядок чтения для исполнителя сверху вниз. Факт живёт в одном документе, остальные ссылаются на его ID.

| Документ | Что в нём |
|---|---|
| [../../../CONSTRAINTS.md](../../../CONSTRAINTS.md) | правила кейса; при расхождении правы «2. ДИТ.pdf» и «Техническое приложение.docx» в корне репозитория |
| [tz-inventory.md](tz-inventory.md) | условия ТЗ `TZ-n` со скоупом, на них ссылаются сценарии |
| [brief.md](brief.md) | зачем, цели `G-`, не-цели `NG-`, бюджет, границы автономии |
| [research.md](research.md) | находки `R-` с источниками, таблица типов ограничений по нормам, что говорит против |
| [requirements.md](requirements.md) | истории `US-`, критерии `AC-` и `NFR-`, ограничения `C-`, допущения `A-`, журнал уточнений `Q-` |
| [design.md](design.md) | решения `D-`, поля ожиданий, мутации, состав семейств сценариев |
| [adr/0001-scenario-oracle.md](adr/0001-scenario-oracle.md) | почему ожидания — решающие факты плюс валидатор плюс мутации |
| [tasks.md](tasks.md) | контракт исполнения, задачи `T-` по волнам, журнал исполнения |
| [GATES.md](GATES.md) | гейты unlazy: у каждого `AC-` и `NFR-` свой гейт с тем же ID |
| [../../interpretation.md](../../interpretation.md) | трактовки формата выхода; меняется вместе с сервисом и валидатором |

## Проверка пакета

```bash
python3 ~/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/scenario-tests
node ~/.claude/skills/unlazy/scripts/gate-lint.mjs docs/specs/scenario-tests/GATES.md
node ~/.claude/skills/unlazy/scripts/gate-check.mjs --status docs/specs/scenario-tests/GATES.md
```

Прогон гейтов всегда из корня репозитория с таймаутом 1800 секунд, потому что одобрение привязано к таймауту:

```bash
node ~/.claude/skills/unlazy/scripts/gate-check.mjs --cwd . --timeout 1800 docs/specs/scenario-tests/GATES.md
```

## Промт запуска

```text
Ты автономный исполнитель. Используй скиллы goal-setter (режим исполнения, references/execution.md) и unlazy.
Пакет спеки: docs/specs/scenario-tests/. Прочитай index.md и все документы по порядку до первого действия. Выполняй буквально.
Готово только когда `node ~/.claude/skills/unlazy/scripts/gate-check.mjs --cwd . --timeout 1800 --reverify docs/specs/scenario-tests/GATES.md` печатает ALL MET и `python3 ~/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/scenario-tests` печатает TRACE OK.
Пользователя рядом нет: вопросы не задавай, решения фиксируй в журнале tasks.md. При конфликте побеждает спека, а внутри спеки — документы организатора.
Работай в ветке feature/scenario-tests. Независимые задачи одной волны запускай субагентами одновременно, не больше пяти.
```
