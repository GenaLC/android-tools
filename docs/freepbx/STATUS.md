# Состояние стыка с FreePBX (замер на живом стенде)

Документ фиксирует разбор одной конкретной проблемы: **шлюз отвечает `488`
на входящий INVITE, потому что номер назначения не доезжает в URI**.

Все утверждения ниже — наблюдаемые факты (логи, конфиги, tcpdump), а не
догадки. Путь `185.82.73.76` — рабочий сервер FreePBX 16.0.50 / Asterisk
18.19.0, UDP-порт `6064`.

---

## 1. Симптом

`outgoing: 488 Not Acceptable Here` — Asterisk отклоняет INVITE, не понимая,
кому звонить. В логе шлюза:

```
Incoming SIP call: ..., gsm_forward=null, outbound_cid=anonymous
```

`gsm_forward=null` — заголовка `X-GSM-Forward` в INVITE нет; номер должен был
прийти в Request-URI, но не пришёл.

## 2. Полная цепочка причины (доказана по коду FreePBX и логам)

Звонок: софтфон `40181` набирает `+380507137621`.

| # | Слой | Что происходит | Доказательство |
|---|------|----------------|---------------|
| 1 | `outrt-351` | Маршрут FreePBX. Финальное действие — `Goto(from-did-direct,1001,1)` | `extensions_additional.conf:39508`, `dialplan show outrt-351` |
| 2 | `outbound_routes.dest` | В БД `dest='from-did-direct,1001,1'`; FreePBX рендерит его **дословно** | `core/functions.inc.php:2303` → `new ext_goto($route['dest'])` |
| 3 | Потеря номера | `Goto` переписывает `EXTEN` на `1001` — набранный номер исчезает | лог: `Goto("PJSIP/40181-00000006", "from-did-direct,1001,1")` |
| 4 | `from-did-direct` | Контекст из статического `extensions.conf` (не перегенерируется): `include => ext-findmefollow`, `include => ext-local` | `/etc/asterisk/extensions.conf:265` |
| 5 | `ext-local` → `macro-dial-one` | Разворачивает dial-string устройства | `core/Dialplan/macroDialone.php` |
| 6 | `devices.dial` | Для `1001` = `PJSIP/1001` — **без номера** | `select dial from devices where id='1001'` → `PJSIP/1001` |
| 7 | `PJSIP_DIAL_CONTACTS` | `PJSIP/1001` разворачивается в контакт AOR → `PJSIP/1001/sip:1001@<contact-host>:<port>` | `macroDialone.php`: `Set(THISDIAL, ${PJSIP_DIAL_CONTACTS(${THISDIAL:6})})` |
| 8 | Request-URI | `user` = `1001` (имя аккаунта), а не набранный номер | tcpdump телефона: `INVITE sip:1001@…` |
| 9 | Шлюз | Нет номера назначения → `488` | лог шлюза |

**Дополнительно:** у маршрута `351` транк-sequence указывает на `trunk_id=339`,
которого **нет** в таблице `trunks` (247 записей, ни одна не 339). Поэтому
FreePBX не добавил в маршрут ни одного trunk-макроса — маршрут сразу уходит
в `dest`-переход. Это и есть архитектурный изъян: маршрут настроен как
«позвонить на расширение 1001», а не «позвонить на шлюз с набранным
номером».

## 3. Подтверждённые экспериментом формы dial-string

Проверено `channel originate` + tcpdump на телефоне (`/data/local/tmp/sip_test.txt`).

| Форма | Результат |
|-------|-----------|
| `PJSIP/1001/sip:<номер>@<хост>` | ✅ Request-URI `sip:<номер>@<host-контакта>` |
| `PJSIP/<номер>@1001` | ✅ Request-URI `sip:<номер>@<host-контакта>` |
| `PJSIP/1001/sip:<номер>@<contact-host:port>` | ✅ то же |
| `PJSIP/1001/<номер>` | ❌ номер теряется, остаётся `sip:1001@…` |
| `PJSIP/1001` | ❌ номер теряется (текущее поведение) |

Правило FreePBX, из которого это следует (`macroDialone.php`):

> «If this dialstring contains an @, or two /'s, then it's ALREADY something we
> can dial, and does not need to be extracted.»

То есть строка, содержащая `@` или два `/`, уходит в `Dial()` **как есть**,
без разворачивания в контакт AOR. Именно поэтому работают только формы с
полным URI, а `PJSIP/1001/<номер>` (два слэша, но не URI) уходит в res_pjsip
как «эндпоинт + непонятный хвост» и молча деградирует до контакта AOR.

**Вывод:** номер можно доставить только dial-string'ом, который сам несёт URI
с номером. Путь «маршрут → расширение 1001» номер доставить не может
физически.

## 4. Конфигурация, которую нужно применить

Правки выполняет администратор сервера.

### 4.1 Кастомный контекст, который звонит на шлюз с номером

`/etc/asterisk/extensions_custom.conf` (файл подключён штатно: `#include
extensions_custom.conf` в `extensions.conf:31`; в Asterisk `#include` — не
комментарий, комментарий это `;`):

```
[gsm-outbound]
exten => _+X.,1,Dial(PJSIP/${EXTEN}@1001,60)
exten => _+X.,2,Hangup()
```

`Dial(PJSIP/<номер>@1001)` — форма №2 из таблицы выше: берёт контакт AOR
живого эндпоинта `1001`, но ставит в user-часть URI набранный номер.

### 4.2 Маршрут должен уходить в этот контекст, а не в `from-did-direct`

```sql
UPDATE outbound_routes SET dest='gsm-outbound,${EXTEN},1' WHERE route_id=351;
```

`dest` рендерится дословно → получится `Goto(gsm-outbound,${EXTEN},1)`, и в
момент перехода `EXTEN` ещё равен набранному номеру.

После этого — **Apply Config** (или `asterisk -rx "dialplan reload"`).

> Внимание: значение `dest` перезаписывается, если открыть этот маршрут в GUI
> и сохранить его. После Apply Config стоит проверить:
> `asterisk -rx "dialplan show outrt-351"` → последняя строка должна быть
> `Goto(gsm-outbound,${EXTEN},1)`.

### 4.3 Транспорт: внешний адрес

`/etc/asterisk/pjsip.transports.conf` — в `external_media_address` и
`external_signaling_address` сейчас прописан адрес **чужого стенда**, а не
`185.82.73.76`. Из-за этого в INVITE уходит чужой `Via`/`Contact`, а в SDP —
чужой `c=IN IP4`, что сломает RTP и обратный маршрут.

```
external_media_address=185.82.73.76
external_signaling_address=185.82.73.76
```

Затем перезагрузить транспорт:

```
asterisk -rx "pjsip show transports"     # запомнить имя транспорта
asterisk -rx "module reload res_pjsip"   # или перезапуск asterisk
```

### 4.4 Заглушка в `[from-gsm-gateway]`

`extensions_custom.conf` содержит `Dial(PJSIP/<40181>,60)` — плейсхолдер
вместо номера. Для входящего GSM→PBX нужно `Dial(PJSIP/40181,60)`.

## 5. Что осталось за пределами маршрутизации

1. **SIM в телефоне отсутствует**: `gsm.sim.state = ABSENT,ABSENT`. Сотовная
   нога физически не заработает.
2. **Выбор SIM по CallerID**: тестовые INVITE дали
   `Cannot dial GSM: SIP Outbound CID anonymous does not select a unique SIM`.
   Нужен маппинг CallerID (`40181`) → слот SIM.
3. **Входящая доступность PBX → телефон**: телефон за CGNAT Starlink
   (`145.224.123.93`) плюс NordVPN. Работает только если INVITE приходит с
   того же адреса и порта, что и REGISTER (NAT-отображение уже создано) —
   это обеспечивает `rewrite_contact=yes` у эндпоинта `1001`. Если пакет не
   доходит — нужен port forwarding / WireGuard / Dedicated Server.
4. **Неизвестный трафик**: `INVITE` от `sip:1000@185.82.73.76` с
   `160.119.76.173:60626-60628` («Failed to authenticate» каждые ~6 минут) —
   чужой клиент, нужно выяснить, чей.

## 6. Состояние окружения

- **Телефон** `X100260121014541` (Cubot X100, Android 15, Magisk root),
  приложение v1.4.19 (versionCode 458), роль DIALER = `com.callagent.gateway`.
  Зарегистрирован: `Registered with 185.82.73.76`. VPN NordVPN активен
  (`tun0`, публичный IP `187.40.252.72`). Локальный SIP-сокет `10.5.0.2:5062`.
- **Сервер**: `freepbx.sangoma.local`, `inet 185.82.73.76/24`, Asterisk
  18.19.0, FreePBX 16.0.50, единственный транспорт `0.0.0.0-udp:6064`.
  Эндпоинт `1001`: `context=from-gsm-gateway`, `allow=(ulaw|alaw)`,
  `rewrite_contact=true`, `rtp_symmetric=true`, `force_rport=true`,
  `trust_id_inbound=yes`, `send_pai=yes`, `identify_by=username,ip`.
  AOR `1001`: `max_contacts=1`, `contact=sip:1001@187.40.252.72:61909`,
  `qualify_frequency=60`.
- **Доступ**: read-only через `~/.ssh/fpbx-run.sh '<команда>'` (пароль в
  `~/.ssh/.fpbx_pw`, права 600). Алиас `freepbx-d` в `~/.ssh/config`.
- **Оставлено на сервере**: `pjsip set logger on` (включён для диагностики,
  выключить: `asterisk -rx "pjsip set logger off"`).
- **Оставлено на телефоне**: tcpdump пишет в `/data/local/tmp/sip_test.txt`.

## 7. Проверка после правок

```bash
# 1. на сервере: маршрут ушёл в кастомный контекст
asterisk -rx "dialplan show outrt-351" | tail -3

# 2. позвонить с софтфона 40181 на +380507137621

# 3. на телефоне: в INVITE должен быть номер
adb shell su -c 'tcpdump -i any -n -A udp port 5062' | grep -A1 INVITE

# 4. в логе шлюза вместо 488 должно быть:
#    GSM destination resolved: +380507137621
```

Ожидаемый следующий барьер после починки маршрута — уже не `488`, а
`No matching SIM for Outbound CID` (нужна SIM и маппинг CallerID → слот).
