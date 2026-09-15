"""Разбор и проверка таблицы правил разумной трассы docs/routing-quality.md (AC-2.1).

Цена правила проверяется пересчётом: «Метод цены» — арифметика над позициями таблицы цен, например
«2 × P-1 + P-6 / 2,97»; итог обязан совпасть с колонкой «Цена, руб.» до рубля, у каждой позиции — источник и дата.
"""
import ast
import operator
import re
import sys
from pathlib import Path

from heatopt import metrics
from heatopt.model import QualityRule

COLUMNS = ["ID", "Правило", "Уровень", "Источник", "Формула", "Порог", "Применение", "Метрика", "Цена, руб.", "Метод цены"]
PRICE_COLUMNS = ["ID", "Позиция", "Типоразмер", "Цена, руб.", "Единица", "Источник", "Дата"]
LEVELS = ("A", "B", "C")
APPLICATIONS = ("objective", "tie-break", "report")
EMPTY = ("", "—", "-")
MIN_RULES = 12
MIN_PRICED = 6
PRICE_TOL_RUB = 1.0
RULE_ID = re.compile(r"RQ-\d+")
PRICE_ID = re.compile(r"P-\d+")
LINK = re.compile(r"https?://\S+")
RULES_JSON = "rules/rules.json"
OPERATORS = {ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul, ast.Div: operator.truediv}


def cells(line: str) -> list[str]:
    return [cell.strip().strip("`").strip() for cell in line.strip().strip("|").split("|")]


def table(text: str, columns: list[str]) -> list[list[str]] | None:
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if line.startswith("|") and cells(line) == columns:
            rows = []
            for row in lines[i + 2:]:
                if not row.startswith("|"):
                    break
                rows.append(cells(row))
            return rows
    return None


def number(text: str) -> float | None:
    if text in EMPTY:
        return None
    return float(text.replace(" ", "").replace(" ", "").replace(",", "."))


def parse(text: str) -> tuple[list[QualityRule], list[str]]:
    rows = table(text, COLUMNS)
    if rows is None:
        return [], [f"нет таблицы с колонками: {' | '.join(COLUMNS)}"]
    rules, errors = [], []
    for row in rows:
        if len(row) != len(COLUMNS):
            errors.append(f"строка {row[0]}: ячеек {len(row)}, нужно {len(COLUMNS)}")
            continue
        try:
            price = number(row[8])
        except ValueError:
            errors.append(f"{row[0]}: цена «{row[8]}» не число")
            continue
        method = "" if row[9] in EMPTY else row[9]
        rules.append(QualityRule(row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7], price, method))
    return rules, errors


def load(path: Path) -> list[QualityRule]:
    rules, errors = parse(Path(path).read_text(encoding="utf-8"))
    if errors:
        raise ValueError("; ".join(errors))
    return rules


def price_table(text: str) -> tuple[dict[str, float], list[str]]:
    rows = table(text, PRICE_COLUMNS)
    if rows is None:
        return {}, [f"нет таблицы цен с колонками: {' | '.join(PRICE_COLUMNS)}"]
    prices, errors = {}, []
    for row in rows:
        if len(row) != len(PRICE_COLUMNS) or not PRICE_ID.fullmatch(row[0]):
            errors.append(f"таблица цен: строка {row[0]} с ячейками {len(row)}")
            continue
        if not (LINK.search(row[5]) or RULES_JSON in row[5]) or row[6] in EMPTY:
            errors.append(f"{row[0]}: у позиции нет ссылки на источник или даты")
        try:
            value = number(row[3])
        except ValueError:
            value = None
        if value is None or value <= 0:
            errors.append(f"{row[0]}: цена позиции «{row[3]}» не положительное число")
            continue
        prices[row[0]] = value
    return prices, errors


def method_total(method: str, prices: dict[str, float]) -> tuple[float | None, list[str]]:
    refs = PRICE_ID.findall(method)
    if not refs:
        return None, ["в методе нет позиций таблицы цен P-n"]
    missing = sorted({pid for pid in refs if pid not in prices})
    if missing:
        return None, [f"позиций {', '.join(missing)} нет в таблице цен"]
    expression = PRICE_ID.sub(lambda m: m.group(0).replace("-", "_"), method)
    expression = re.sub(r"(\d),(\d)", r"\1.\2", expression).replace("×", "*").replace("÷", "/")
    try:
        return arithmetic(ast.parse(expression, mode="eval").body, {k.replace("-", "_"): v for k, v in prices.items()}), []
    except (SyntaxError, ValueError, ZeroDivisionError):
        return None, [f"метод «{method}» не арифметика над позициями P-n"]


def arithmetic(node: ast.AST, names: dict[str, float]) -> float:
    if isinstance(node, ast.Constant) and isinstance(node.value, int | float):
        return node.value
    if isinstance(node, ast.Name) and node.id in names:
        return names[node.id]
    if isinstance(node, ast.BinOp) and type(node.op) in OPERATORS:
        return OPERATORS[type(node.op)](arithmetic(node.left, names), arithmetic(node.right, names))
    raise ValueError(ast.dump(node))


def check(text: str) -> tuple[list[QualityRule], list[str]]:
    rules, errors = parse(text)
    prices, price_errors = price_table(text)
    errors += price_errors
    ids = [rule.id for rule in rules]
    for rule in rules:
        def add(message: str) -> None:
            errors.append(f"{rule.id}: {message}")

        if not RULE_ID.fullmatch(rule.id):
            add("ID не вида RQ-n")
        if ids.count(rule.id) > 1:
            add("ID повторяется")
        if rule.level not in LEVELS:
            add(f"уровень «{rule.level}» не из {'/'.join(LEVELS)}")
        if not LINK.search(rule.source):
            add("в источнике нет ссылки http(s)")
        for name, value in (("правило", rule.title), ("формула", rule.formula), ("порог", rule.threshold)):
            if value in EMPTY:
                add(f"пустое поле «{name}»")
        if rule.application not in APPLICATIONS:
            add(f"применение «{rule.application}» не из {', '.join(APPLICATIONS)}")
        if rule.metric not in metrics.METRICS:
            add(f"метрику «{rule.metric}» не считает metrics.evaluate")
        if rule.level == "C" and (rule.application == "objective" or rule.price_rub is not None):
            add("правило уровня C только tie-break или report и без цены")
        if rule.application != "objective" and (rule.price_rub is not None or rule.price_method):
            add("цена и метод цены бывают только у правил objective")
        if rule.application == "objective" and rule.level in ("A", "B"):
            if rule.price_rub is None or rule.price_rub <= 0:
                add("у правила objective нет цены больше нуля")
            elif not rule.price_method:
                add("у правила objective нет метода цены")
            else:
                total, method_errors = method_total(rule.price_method, prices)
                for message in method_errors:
                    add(message)
                if total is not None and abs(total - rule.price_rub) > PRICE_TOL_RUB:
                    add(f"цена {rule.price_rub:.0f} не равна расчёту по методу {total:.0f}")
    priced = sum(rule.priced for rule in rules)
    if len(rules) < MIN_RULES:
        errors.append(f"правил {len(rules)}, нужно не меньше {MIN_RULES}")
    if priced < MIN_PRICED:
        errors.append(f"правил с ценой {priced}, нужно не меньше {MIN_PRICED}")
    return rules, errors


def main() -> None:
    path = Path(sys.argv[1])
    rules, errors = check(path.read_text(encoding="utf-8"))
    if errors:
        print("RULES DOC FAILED")
        for error in errors:
            print(f"  {error}")
        sys.exit(1)
    print(f"RULES DOC OK rules={len(rules)} priced={sum(rule.priced for rule in rules)}")


if __name__ == "__main__":
    main()
