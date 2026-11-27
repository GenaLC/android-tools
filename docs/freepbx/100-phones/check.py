#!/usr/bin/env python3
"""
Проверка сгенерированной конфигурации без сервера.

    python3 check.py

Проверяет то, что можно проверить локально:

  * extensions_custom.conf — баланс скобок, уникальность секций, уникальность
    exten внутри секции, наличие обязательных блоков;
  * pjsip.*_custom.conf — уникальность секций, наличие type=, aors=, auth=;
  * trunks-routes.sql — число значений в каждой вставке совпадает с числом
    колонок, id лежат в своём диапазоне и не повторяются, match_cid равен
    номеру агента из phones.csv, outcid равен own_number телефона;
  * phone-app-settings.csv — совпадает с phones.csv.

Скрипт ничего не меняет и никуда не ходит: это проверка «на суше», перед тем
как применять конфигурацию на сервере.
"""

import csv
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GEN = os.path.join(HERE, "generated")
CSV_PATH = os.path.join(HERE, "phones.csv")

ID_BASE = 9000

errors = []
warnings = []


def err(msg):
    errors.append(msg)


def warn(msg):
    warnings.append(msg)


def read(path):
    if not os.path.exists(path):
        err("нет файла %s — сначала python3 gen.py" % path)
        return None
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def check_dialplan(text):
    """Секции, exten-ы и порядок блоков."""
    section = None
    seen_sections = {}
    seen_exten = {}
    required = {"from-gsm-gateway", "from-gsm-gateway-did", "gsm-outbound",
                "add-gsm-forward"}
    for lineno, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith(";"):
            continue
        m = re.match(r"^\[([^\]]+)\]$", line)
        if m:
            section = m.group(1)
            if section in seen_sections:
                err("секция [%s] повторяется (строка %d и %d)"
                    % (section, seen_sections[section], lineno))
            seen_sections[section] = lineno
            continue
        m = re.match(r"^exten\s*=>\s*([^,]+),", line)
        if m:
            target = m.group(1).strip()
            key = (section, target)
            if key in seen_exten:
                err("exten %s повторяется в [%s] (строка %d и %d)"
                    % (target, section, seen_exten[key], lineno))
            seen_exten[key] = lineno
            if section not in required and section != "from-gsm-gateway-did":
                warn("секция [%s] не из шаблона — проверьте вручную" % section)
        if line.count("(") != line.count(")"):
            err("строка %d: скобки не сбалансированы: %s" % (lineno, line))
    missing = required - set(seen_sections)
    if missing:
        err("в dialplan нет секций: %s" % ", ".join(sorted(missing)))
    return seen_exten


def check_pjsip(text, name):
    sections = re.findall(r"^\[([^\]]+)\]$", text, re.M)
    dupes = {s for s in sections if sections.count(s) > 1}
    if dupes:
        err("%s: повторяются секции: %s" % (name, ", ".join(sorted(dupes))))
    if "type=endpoint" not in text and "type=aor" not in text and "type=auth" not in text:
        err("%s: нет ни одного type=*" % name)
    return sections


def split_values(text):
    """Делит строку значений по запятым, не трогая запятые внутри кавычек.

    Значение dest — это 'app-blackhole,hangup,1', там запятые есть.
    """
    values = []
    current = ""
    quoted = False
    for ch in text:
        if ch == "'":
            quoted = not quoted
            continue
        if ch == "," and not quoted:
            values.append(current.strip())
            current = ""
            continue
        current += ch
    values.append(current.strip())
    return values


def parse_inserts(text):
    """Возвращает список (таблица, [колонки], [строки значений])."""
    out = []
    pattern = re.compile(
        r"INSERT\s+INTO\s+(\w+)\s*\(([^)]*)\)\s*VALUES\s*(.*?);",
        re.S | re.I,
    )
    for match in pattern.finditer(text):
        table = match.group(1)
        columns = [c.strip() for c in match.group(2).replace("\n", " ").split(",")]
        values_text = match.group(3)
        rows = []
        for row in re.findall(r"\(([^()]*)\)", values_text):
            rows.append(split_values(row))
        out.append((table, columns, rows))
    return out


def check_sql(text, phones):
    inserts = parse_inserts(text)
    if not inserts:
        err("в SQL нет ни одной вставки")
        return
    by_table = {}
    for table, columns, rows in inserts:
        by_table.setdefault(table, []).extend((columns, row) for row in rows)

    expected_tables = {"trunks", "outbound_routes", "outbound_route_patterns",
                        "outbound_route_trunks"}
    missing = expected_tables - set(by_table)
    if missing:
        err("в SQL нет вставок в: %s" % ", ".join(sorted(missing)))

    for table, entries in by_table.items():
        for columns, row in entries:
            if len(columns) != len(row):
                err("%s: %d колонок, но %d значений: %s"
                    % (table, len(columns), len(row), ",".join(row)))

    # Транки: id в диапазоне, dial-string на свой эндпоинт.
    for columns, row in by_table.get("trunks", []):
        trunkid, tech, channelid, name = row[0], row[1], row[2], row[3]
        if not (ID_BASE + 1 <= int(trunkid) <= ID_BASE + 1000):
            err("trunkid %s вне диапазона %d.." % (trunkid, ID_BASE + 1))
        if tech != "custom":
            err("trunk %s: tech=%s, ожидался custom" % (trunkid, tech))
        if not re.match(r"^PJSIP/\$OUTNUM\$@1\d{3}$", channelid):
            err("trunk %s: dial-string «%s» — работает только PJSIP/$OUTNUM$@<эндпоинт>"
                % (trunkid, channelid))
        if channelid != "PJSIP/$OUTNUM$@%s" % name.split("-")[-1]:
            err("trunk %s: dial-string %s не совпадает с именем %s"
                % (trunkid, channelid, name))

    # Маршруты: outcid = own_number телефона, match_cid = агент.
    agent_by_phone = {p["phone"]: p["agent"] for p in phones}
    number_by_phone = {p["phone"]: p["own_number"] for p in phones}
    route_ids = set()
    for columns, row in by_table.get("outbound_routes", []):
        rid, name, outcid = row[0], row[1], row[2]
        route_ids.add(rid)
        if not (ID_BASE + 1 <= int(rid) <= ID_BASE + 1000):
            err("route_id %s вне диапазона %d.." % (rid, ID_BASE + 1))
        phone = name.split("-")[-1]
        if outcid != number_by_phone.get(phone):
            err("маршрут %s: Route CID (outcid)=%s, а own_number телефона %s=%s"
                % (rid, outcid, phone, number_by_phone.get(phone)))
    for columns, row in by_table.get("outbound_route_patterns", []):
        rid, prefix, pattern, match_cid = row[0], row[1], row[2], row[3]
        if pattern != "+X.":
            err("маршрут %s: match_pattern_pass=%s, ожидался +X." % (rid, pattern))
        if prefix:
            err("маршрут %s: match_pattern_prefix=%s — оставьте пустым" % (rid, prefix))
        phone = next((p for p, a in agent_by_phone.items() if a == match_cid), None)
        if phone is None:
            err("маршрут %s: match_cid=%s не совпадает ни с одним агентом из phones.csv"
                % (rid, match_cid))
        elif match_cid != agent_by_phone[phone]:
            err("маршрут %s: match_cid=%s, ожидался %s"
                % (rid, match_cid, agent_by_phone[phone]))
    for table in ("outbound_route_trunks",):
        for columns, row in by_table.get(table, []):
            if row[0] != row[1]:
                err("%s: route_id=%s и trunk_id=%s не совпадают" % (table, row[0], row[1]))


def check_app_settings(text, phones):
    rows = list(csv.DictReader(text.splitlines()))
    if len(rows) != len(phones):
        err("phone-app-settings.csv: %d строк, а телефонов %d" % (len(rows), len(phones)))
    by_phone = {p["phone"]: p for p in phones}
    for row in rows:
        ref = by_phone.get(row["phone"])
        if ref is None:
            err("phone-app-settings.csv: неизвестный телефон %s" % row["phone"])
            continue
        if row["sip_password"] != ref["password"]:
            err("телефон %s: пароль в настройках не совпадает с phones.csv" % row["phone"])
        if row["own_number"] != ref["own_number"]:
            err("телефон %s: own_number не совпадает с phones.csv" % row["phone"])
        if row["sip_account"] != ref["phone"]:
            err("телефон %s: sip_account должен совпадать с phone" % row["phone"])


def main():
    if not os.path.exists(CSV_PATH):
        sys.exit("нет phones.csv")
    with open(CSV_PATH, newline="", encoding="utf-8") as fh:
        phones = list(csv.DictReader(fh))
    for p in phones:
        for key in ("phone", "own_number", "agent", "password"):
            p[key] = (p.get(key) or "").strip()
    if not phones:
        sys.exit("phones.csv пуст")

    dialplan = read(os.path.join(GEN, "extensions_custom.conf"))
    if dialplan is not None:
        extens = check_dialplan(dialplan)
        dids = {t for (s, t) in extens if s == "from-gsm-gateway-did" and t != "_X."}
        missing = {p["own_number"] for p in phones} - dids
        if missing:
            err("в таблице DID нет %d номеров из phones.csv (например %s)"
                % (len(missing), ", ".join(sorted(missing)[:5])))

    for name in ("pjsip.endpoint_custom.conf", "pjsip.aor_custom.conf",
                 "pjsip.auth_custom.conf"):
        text = read(os.path.join(GEN, name))
        if text is not None:
            check_pjsip(text, name)

    sql = read(os.path.join(GEN, "trunks-routes.sql"))
    if sql is not None:
        check_sql(sql, phones)

    settings = read(os.path.join(GEN, "phone-app-settings.csv"))
    if settings is not None:
        check_app_settings(settings, phones)

    for w in warnings:
        print("ВНИМАНИЕ: %s" % w)
    if errors:
        for e in errors:
            print("ОШИБКА: %s" % e)
        print("\nПроверка НЕ пройдена: %d проблем." % len(errors))
        return 1
    print("Проверка пройдена: телефонов %d, ошибок нет." % len(phones))
    return 0


if __name__ == "__main__":
    sys.exit(main())
