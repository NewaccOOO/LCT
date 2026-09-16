# Цикл с точной подзадачей

- **Статус:** черновик
- **Размер:** Standard
- **Тип:** фича
- **Исполнение:** solo, задачи T-2 и T-3 можно параллельно
- **Согласовал:** —

## Документы

Порядок чтения для исполнителя сверху вниз. Факт живёт в одном документе, остальные ссылаются на его ID.

| Документ | Что в нём |
|---|---|
| [brief.md](brief.md) | зачем, цели `G-`, не-цели `NG-`, бюджет, границы автономии |
| [research.md](research.md) | находки `R-` из ночного ресёрча с номерами экспериментов, что говорит против, допущения |
| [requirements.md](requirements.md) | истории `US-`, критерии `AC-` и `NFR-`, ограничения `C-`, допущения `A-`, журнал уточнений `Q-` |
| [tasks.md](tasks.md) | задачи `T-` по волнам, контракт: свойства `heatnet.exact.*`, формат лога и журнала, журнал исполнения |
| [GATES.md](GATES.md) | гейты unlazy: у каждого `AC-` и `NFR-` свой гейт с тем же ID |

Журнал экспериментов, который проверяет заказчик: [docs/research/exact-subproblem/experiments.md](../../research/exact-subproblem/experiments.md), скрипт сверки `scripts/gates/exact_journal.py`.

## Проверка пакета

```bash
python3 ~/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/exact-subproblem
node ~/.claude/skills/unlazy/scripts/gate-lint.mjs docs/specs/exact-subproblem/GATES.md
node ~/.claude/skills/unlazy/scripts/gate-check.mjs --status docs/specs/exact-subproblem/GATES.md
```

## Промт запуска

```text
Ты автономный исполнитель. Используй скиллы goal-setter (режим исполнения, references/execution.md), unlazy, coding-flow и ponytail.
Пакет спеки: docs/specs/exact-subproblem/. Прочитай index.md и все документы по порядку до первого действия. Выполняй буквально.
Работай в ветке exact-subproblem от main, в main не пушь. Перед кодом сними базу стенда: scripts/bench.sh base на коммите без цикла.
Готово только когда `node ~/.claude/skills/unlazy/scripts/gate-check.mjs --cwd . --reverify docs/specs/exact-subproblem/GATES.md` печатает ALL MET и check_trace.py печатает TRACE OK.
Пользователя рядом нет: вопросы не задавай, решения фиксируй в журнале tasks.md. При конфликте побеждает спека.
Каждый прогон стенда с циклом — строка в docs/research/exact-subproblem/experiments.md и файл в data/out/exact/, отрицательные результаты тоже.
Независимые задачи одной волны запускай субагентами одновременно.
```
