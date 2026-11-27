#!/usr/bin/env python3
"""
Генератор конфигурации FreePBX для N GSM-шлюзов (телефонов).

Читает phones.csv и кладёт рядом готовые файлы:

  generated/extensions_custom.conf            dialplan: DID → агент, исходящая нога
  generated/pjsip.endpoint_custom.conf        эндпоинты телефонов
  generated/pjsip.aor_custom.conf             AOR телефонов
  generated/pjsip.auth_custom.conf            учётные данные телефонов
  generated/trunks-routes.sql                 транки и исходящие маршруты FreePBX
  generated/phone-app-settings.csv            что прописать в каждом телефоне

Запуск:  python3 gen.py [phones.csv]

Файлы pjsip.*_custom.conf нужны, только если SIP-аккаунты телефонов создаются
не в GUI, а этими файлами. Если аккаунты уже созданы руками — эти три файла
не применяйте, берите только extensions_custom.conf и trunks-routes.sql.

Колонки phones.csv:

  phone       — SIP-аккаунт телефона (имя эндпоинта, AOR и auth)
  own_number  — номер телефона: он же DID входящего INVITE, он же Route CID
                исходящего (CallerID, по которому приложение выбирает SIM-слот).
                В приложении это значение вписывается в поле «Own number»
                слота SIM.
  agent       — SIP-расширение агента: кому звонить по входящему DID и чьи
                исходящие идут через этот телефон (match_cid маршрута)
  peer_number — справочно: номер абонента, который звонит на этот телефон.
                В конфигурации не используется.
  password    — пароль SIP-аккаунта, свой на каждый телефон
"""

import csv
import hashlib
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "generated")

# Базовые идентификаторы объектов FreePBX. На сервере уже есть 248 (транк) и
# 351 (маршрут), поэтому берём заведомо свободный диапазон с 9000.
ID_BASE = 9000

# Шаблон эндпоинта — копия работающего [1001] с сервера. Менять здесь ничего
# не нужно: это ровно то, что FreePBX отдаёт телефону на MT6855.
ENDPOINT_TEMPLATE = """[%(phone)s]
type=endpoint
aors=%(phone)s
auth=%(phone)s-auth
tos_audio=ef
tos_video=af41
cos_audio=5
cos_video=4
allow=ulaw,alaw
context=from-gsm-gateway
callerid=%(phone)s <%(phone)s>

dtmf_mode=rfc4733
direct_media=no
outbound_auth=%(phone)s-auth
aggregate_mwi=yes
use_avpf=no
rtcp_mux=no
max_audio_streams=1
max_video_streams=1
bundle=no
ice_support=no
media_use_received_transport=no
trust_id_inbound=yes
user_eq_phone=no
send_connected_line=yes
media_encryption=no
timers=yes
timers_min_se=90
media_encryption_optimistic=no
"""

AOR_TEMPLATE = """[%(phone)s]
type=aor
max_contacts=1
remove_existing=yes
maximum_expiration=7200
minimum_expiration=60
qualify_frequency=60
"""

AUTH_TEMPLATE = """[%(phone)s-auth]
type=auth
auth_type=userpass
password=%(password)s
username=%(phone)s
"""


def read_phones(path):
    """Читает phones.csv и проверяет, что он пригоден для генерации."""
    rows = []
    with open(path, newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        required = ["phone", "own_number", "agent", "password"]
        optional = ["peer_number"]
        missing = [c for c in required if c not in (reader.fieldnames or [])]
        if missing:
            sys.exit("В phones.csv нет колонок: %s" % ", ".join(missing))
        for lineno, raw in enumerate(reader, start=2):
            row = {k: (raw.get(k) or "").strip() for k in required + optional}
            if not row["phone"]:
                continue
            for col in required:
                if not row[col]:
                    sys.exit(
                        "phones.csv:%d — пустая колонка %s\n"
                        "Заполните все обязательные колонки: телефон без номера "
                        "или без агента завести нельзя." % (lineno, col)
                    )
            # Телефон звонит на PBX своим «own number» в международном формате,
            # то есть с ведущим плюсом. Таблица DID в dialplan хранит его без
            # плюса — снимаем здесь, чтобы вручную ничего не исправлять.
            row["own_number"] = row["own_number"].lstrip("+")
            rows.append(row)
    if not rows:
        sys.exit("phones.csv пуст")
    phones = [r["phone"] for r in rows]
    if len(set(phones)) != len(phones):
        sys.exit("В phones.csv повторяются номера телефонов")
    dids = [r["own_number"] for r in rows]
    if len(set(dids)) != len(dids):
        sys.exit("В phones.csv повторяются own_number — каждому телефону нужен свой")
    return rows


def make_password(phone, salt):
    """Пароль по умолчанию, если пользователь не задал свой.

    Он выводится в phone-app-settings.csv и в pjsip.auth_custom.conf, так что
    менять его нужно только там, где есть причина.
    """
    digest = hashlib.sha256(("%s:%s" % (salt, phone)).encode("utf-8")).hexdigest()
    return digest[:12]


def gen_dialplan(rows):
    out = []
    out.append(";")
    out.append("; GSM-SIP шлюз — %d телефон(ов)." % len(rows))
    out.append("; Сгенерировано gen.py из phones.csv. Правьте phones.csv, не этот файл.")
    out.append(";")
    out.append("; Входящая GSM-нога: телефон шлёт INVITE с Request-URI = свой")
    out.append("; \"own number\" (номер, записанный в приложении в поле слота SIM) и")
    out.append("; P-Asserted-Identity = номер абонента. Контекст [from-gsm-gateway]")
    out.append("; снимает ведущий плюс и уходит в таблицу DID ниже.")
    out.append("")
    out.append("[from-gsm-gateway]")
    out.append("exten => _+X.,1,NoOp(GSM gateway: inbound DID=${EXTEN} from ${CALLERID(num)})")
    out.append(" same => n,Set(GSM_DID=${EXTEN})")
    out.append(" same => n,Goto(from-gsm-gateway-did,${EXTEN:1},1)")
    out.append(" same => n,Hangup()")
    out.append("")
    out.append("; DID -> агент. Ключ — DID без ведущего плюса, значение — SIP-расширение")
    out.append("; агента, которому звонить. Один агент может обслуживать несколько")
    out.append("; телефонов: тогда у нескольких DID один и тот же агент.")
    out.append("[from-gsm-gateway-did]")
    for row in rows:
        out.append(
            "exten => %s,1,NoOp(GSM DID ${GSM_DID} -> %s)"
            % (row["own_number"], row["agent"])
        )
        out.append(" same => n,Dial(PJSIP/%s,60)" % row["agent"])
        out.append(" same => n,Hangup()")
        out.append("")
    out.append("; DID не из таблицы — телефон не должен получать чужие вызовы.")
    out.append("exten => _X.,1,NoOp(GSM DID ${GSM_DID} not mapped)")
    out.append(" same => n,Hangup()")
    out.append("")
    out.append("; Исходящая нога вручную: сначала задать GSM_PHONE, потом")
    out.append("; Goto(gsm-outbound,<номер>,1). Обычно исходящий вызов агента идёт")
    out.append("; через маршрут FreePBX (trunks-routes.sql) — этому контексту.")
    out.append("; Если GSM_PHONE не задан — берётся первый телефон из phones.csv,")
    out.append("; как в старой ручной версии на 1001.")
    out.append("[gsm-outbound]")
    out.append("exten => _+X.,1,NoOp(GSM gateway: outbound to ${EXTEN} via ${GSM_PHONE})")
    out.append(" same => n,GotoIf($[\"${GSM_PHONE}\" != \"\"]?dial)")
    out.append(" same => n,Set(GSM_PHONE=%s)" % rows[0]["phone"])
    out.append(" same => n(dial),Dial(PJSIP/${EXTEN}@${GSM_PHONE},60)")
    out.append(" same => n,Hangup()")
    out.append("")
    out.append("; Заголовок для приложения: номер, который нужно набрать в GSM.")
    out.append("[add-gsm-forward]")
    out.append("exten => s,1,Set(PJSIP_HEADER(add,X-GSM-Forward)=${1})")
    out.append(" same => n,Return()")
    out.append("")
    return "\n".join(out)


def gen_endpoints(rows):
    return "\n".join(
        ENDPOINT_TEMPLATE % {"phone": r["phone"]} for r in rows
    )


def gen_aors(rows):
    return "\n".join(AOR_TEMPLATE % {"phone": r["phone"]} for r in rows)


def gen_auths(rows):
    return "\n".join(
        AUTH_TEMPLATE % {"phone": r["phone"], "password": r["password"]} for r in rows
    )


def gen_sql(rows):
    """Транки и маршруты FreePBX — то же, что делает GUI, только пакетно."""
    out = []
    out.append("-- Транки и исходящие маршруты для GSM-шлюзов.")
    out.append("-- Сгенерировано gen.py из phones.csv.")
    out.append("--")
    out.append("-- Сначала сносим свои объекты (идемпотентность):")
    out.append("DELETE FROM outbound_route_trunks WHERE route_id BETWEEN %d AND %d;"
               % (ID_BASE + 1, ID_BASE + len(rows)))
    out.append("DELETE FROM outbound_route_patterns WHERE route_id BETWEEN %d AND %d;"
               % (ID_BASE + 1, ID_BASE + len(rows)))
    out.append("DELETE FROM outbound_routes WHERE route_id BETWEEN %d AND %d;"
               % (ID_BASE + 1, ID_BASE + len(rows)))
    out.append("DELETE FROM trunk_dialpatterns WHERE trunkid BETWEEN %d AND %d;"
               % (ID_BASE + 1, ID_BASE + len(rows)))
    out.append("DELETE FROM trunks WHERE trunkid BETWEEN %d AND %d;"
               % (ID_BASE + 1, ID_BASE + len(rows)))
    out.append("")

    out.append("-- Транки: dial-string PJSIP/<набранный номер>@<эндпоинт телефона>.")
    out.append("-- Это единственная рабочая форма; PJSIP/<эндпоинт>/<номер> и")
    out.append("-- PJSIP/<эндпоинт>/sip:<номер>@<pbx> не работают.")
    trunk_values = []
    for index, row in enumerate(rows, start=1):
        trunk_values.append(
            "('%d','custom','PJSIP/$OUTNUM$@%s','gsm-gateway-%s','',"
            "'off','','','','notneeded','','off','off','on')"
            % (ID_BASE + index, row["phone"], row["phone"])
        )
    out.append("INSERT INTO trunks")
    out.append("  (trunkid,tech,channelid,name,outcid,keepcid,maxchans,failscript,")
    out.append("   dialoutprefix,usercontext,provider,disabled,continue,routedisplay)")
    out.append("VALUES")
    out.append(",\n".join(trunk_values) + ";")
    out.append("")

    out.append("-- Исходящие маршруты: по одному на телефон.")
    out.append("--")
    out.append("-- В маршруте FreePBX два разных поля с CID, путать их нельзя:")
    out.append("--   outcid  (GUI: \"Route CID\") — CallerID, который PBX подставляет")
    out.append("--     в исходящий INVITE к шлюзу. Шлюз сверяет его со своим")
    out.append("--     \"own number\" и по нему выбирает SIM-слот, поэтому здесь")
    out.append("--     стоит номер самого телефона (own_number), а не номер агента.")
    out.append("--   match_cid (GUI: \"CallerID\" в строке dial pattern) — ограничение:")
    out.append("--     какие CallerID могут пользоваться маршрутом. Здесь — номер")
    out.append("--     агента, чьи исходящие идут через этот телефон.")
    out.append("-- В match_cid можно дать шаблон (например 401[1-3]), если телефон")
    out.append("-- обслуживает несколько агентов.")
    route_values = []
    pattern_values = []
    link_values = []
    for index, row in enumerate(rows, start=1):
        rid = ID_BASE + index
        route_values.append(
            "('%d','gsm-%s','%s','','','','','default',NULL,"
            "'app-blackhole,hangup,1','',NULL,NULL,'default','call')"
            % (rid, row["phone"], row["own_number"])
        )
        pattern_values.append(
            "('%d','','+X.','%s','')" % (rid, row["agent"])
        )
        link_values.append("('%d','%d','0')" % (rid, rid))

    out.append("INSERT INTO outbound_routes")
    out.append("  (route_id,name,outcid,outcid_mode,password,emergency_route,")
    out.append("   intracompany_route,mohclass,time_group_id,dest,time_mode,")
    out.append("   calendar_id,calendar_group_id,timezone,notification_on)")
    out.append("VALUES")
    out.append(",\n".join(route_values) + ";")
    out.append("")

    out.append("INSERT INTO outbound_route_patterns")
    out.append("  (route_id,match_pattern_prefix,match_pattern_pass,match_cid,prepend_digits)")
    out.append("VALUES")
    out.append(",\n".join(pattern_values) + ";")
    out.append("")

    out.append("INSERT INTO outbound_route_trunks (route_id,trunk_id,seq) VALUES")
    out.append(",\n".join(link_values) + ";")
    out.append("")
    return "\n".join(out)


def gen_app_settings(rows):
    """Что прописать в приложении на каждом телефоне.

    own_number — то самое значение, которое надо вписать в поле «Own number»
    слота SIM: из него приложение берёт Request-URI входящего INVITE (DID)
    и по нему же сверяет CallerID исходящего INVITE (Route CID), выбирая
    слот. Значение одно и то же, поэтому расхождений быть не может.
    """
    out = ["phone,sip_account,sip_password,own_number,peer_number"]
    for row in rows:
        out.append(
            "%s,%s,%s,%s,%s"
            % (
                row["phone"],
                row["phone"],
                row["password"],
                row["own_number"],
                row.get("peer_number", ""),
            )
        )
    return "\n".join(out) + "\n"


def write(name, content):
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(content)
    print("  %s" % name)


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "phones.csv")
    rows = read_phones(src)
    os.makedirs(OUT, exist_ok=True)
    print("Телефонов: %d" % len(rows))
    write("extensions_custom.conf", gen_dialplan(rows))
    write("pjsip.endpoint_custom.conf", gen_endpoints(rows))
    write("pjsip.aor_custom.conf", gen_aors(rows))
    write("pjsip.auth_custom.conf", gen_auths(rows))
    write("trunks-routes.sql", gen_sql(rows))
    write("phone-app-settings.csv", gen_app_settings(rows))
    print("Готово: %s" % OUT)


if __name__ == "__main__":
    main()
