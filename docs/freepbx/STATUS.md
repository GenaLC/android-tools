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

Проверено `channel originate` + tcpdump/лог на телефоне и в
`/var/log/asterisk/full` на сервере. Замеры — 2026-11-18, телефон
`X100260121014541`, контакт AOR `1001` = `187.40.252.67:20716`.

| Форма | Результат |
|-------|-----------|
| `PJSIP/<номер>@1001` | ✅ INVITE доставлен на телефон; Request-URI `sip:<номер>@187.40.252.67:20716` — хост:порт живого контакта AOR, user = набранный номер |
| `PJSIP/1001/sip:<номер>@185.82.73.76` | ❌ `Transmitting SIP request to UDP:185.82.73.76:5060` — INVITE ушёл на сам PBX, `Packet timed out after 32000ms with no response` |
| `PJSIP/1001/<номер>` | ❌ `Could not create dialog to invalid URI '<номер>'` — диалог не создаётся вовсе |
| `PJSIP/1001` | ❌ номер теряется (текущее поведение маршрута) → `sip:1001@…` |

**Правило, которое за этим стоит:** res_pjsip отправляет INVITE **на хост,
указанный в URI**. Отсюда:

- `@1001` — хост берётся из **живого AOR-контакта** эндпоинта `1001`
  (то, что телефон заявил в `Contact` при регистрации) → пакет приходит
  туда, куда надо, а user-часть URI остаётся набранным номером;
- `@185.82.73.76` — хост берётся **дословно** из строки → INVITE уходит на
  сам PBX (и на порт 5060 вместо 6064) и истёкает по таймауту;
- `PJSIP/1001/<номер>` — res_pjsip не понимает такой хвост как URI → ошибка
  создания диалога.

Правило FreePBX, из которого следует приоритет «строка с `@` или двумя `/`
используется как есть» (`macroDialone.php`):

> «If this dialstring contains an @, or two /'s, then it's ALREADY something we
> can dial, and does not need to be extracted.»

То есть строка, содержащая `@` или два `/`, уходит в `Dial()` **как есть**,
без разворачивания в контакт AOR.

**Вывод:** номер можно доставить только dial-string'ом, который сам несёт URI
с номером **и** хостом, который res_pjsip умеет превратить в живой контакт —
то есть `PJSIP/<номер>@1001`. Путь «маршрут → расширение 1001» номер
доставить не может физически.

Подтверждённый результат на телефоне (лог приложения):

```
Incoming SIP call: fdd3c314-…, gsm_forward=null, outbound_cid=anonymous
GSM destination resolved: +380507137641
Outbound flow: dialing GSM +380507137641
Rejected inbound call … with 503 No Matching SIM
```

Номер распознан — `488` больше нет. Дальше упёрлись в отсутствие SIM.

### 3.1 Почему звонок висел 30 секунд (исправлено в приложении)

С маршрутизацией, починенной по §4, номер до шлюза доходит — но вызов
«зависал»: софтфон показывал `calling…`, а PBX через 30 секунд ронял
канал (HANGUP CAUSE 127).

Причина — не в FreePBX, а в шлюзе. tcpdump на телефоне:

```
08:32:17.297 tun0 Out IP 10.5.0.2.5062 > <адрес-чужого-стенда>.6064  SIP/2.0 503 No Matching SIM
```

Ответ `503` уходил **на чужой адрес** — тот, что стоит в
`external_signaling_address` транспорта и потому попадает в `Contact`
исходящего INVITE. Шлюз отвечал туда, PBX ответа не получал и ждал
таймаута INVITE-транзакции (32 с).

Причина в коде: ответ UAS должен уходить **на адрес источника запроса**
(RFC 3261 §8.1.3.3), а приложение отвечало на адрес из `Contact` — тот
адрес предназначен для запросов *внутри* диалога. `SipClient` при разборе
входящего INVITE сохранял `remoteContactAddress = msg.contactAddress`, и
`SipCall.reject()` слал ответ туда же.

Исправлено в приложении: у вызова появился `remoteSourceAddress` (адрес
пакета), а `reject()`, `accept()`, ответы на BYE/CANCEL и `180 Ringing`
уходят на `responseTarget()` — источник, а не Contact. Контакт остался
адресом для запросов внутри диалога.

После правки (та же проверка, что и раньше):

```
08:48:02.896 tun0 Out IP 10.5.0.2.5062 > 185.82.73.76.6064   SIP/2.0 503 No Matching SIM
```

и на сервере:

```
Transmitting SIP request (969 bytes) to UDP:187.40.252.67:20716
Received SIP response (371 bytes) from UDP:187.40.252.67:20716   ← 100 Trying
Received SIP response (388 bytes) from UDP:187.40.252.67:20716   ← 180 Ringing
Received SIP response (396 bytes) from UDP:187.40.252.67:20716   ← 503 No Matching SIM
Transmitting SIP request (435 bytes) to UDP:187.40.252.67:20716   ← ACK
```

PBX ответ принял и подтвердил — звонок завершается сразу.

> **Что всё ещё требует правки на сервере:** `external_signaling_address` и
> `external_media_address` в транспорте. Ответы шлюза теперь идут на адрес
> источника, но `Contact`/`Via` в INVITE сервера и SDP `c=` всё ещё указывают
> на чужой адрес. Для **установленного** разговора это ломает in-dialog
> запросы (ACK и BYE шлюз шлёт на `Contact`) и медиапоток (RTP пойдёт на
> чужой адрес). Для отказа с `503` — уже не ломает.

## 4. Конфигурация, которую нужно применить
Правки выполняет администратор сервера.

### 4.1 Вариант A (основной): Custom Trunk

`Connectivity → Trunks → Add Trunk → Add Custom Trunk`:

| Поле | Значение |
|---|---|
| Trunk Name | `gsm-gateway` |
| Custom Dial String | `PJSIP/$OUTNUM$@1001` |
| Outbound CallerID | номер SIM в E.164 (тот же, что в настройках шлюза) |
| Maximum Channels | `1` |

Что FreePBX делает с этой строкой (`core/functions.inc.php:2070` + ветка
`customtrunk` в `macro-dialout-trunk`):

```
OUT_<id> = AMP:PJSIP/$OUTNUM$@1001
→ pre_num = PJSIP/ , the_num = OUTNUM , post_num = @1001
→ Dial(PJSIP/+380507137621@1001)
```

То есть на выходе — ровно та строка, которая проверена на проводе (§3).
Кастомный транк **не пишет ничего в `pjsip.conf`** (в `Core::addTrunk` для
`tech=custom` нет ветки) — значит, не конфликтует с рукописным эндпоинтом
`1001`.

Проводку транка в маршрут — `Connectivity → Outbound Routes → Outbound Route
40181`:

| Поле | Значение |
|---|---|
| Trunk Sequence | `gsm-gateway` (созданный Custom Trunk) |
| Route CID | номер SIM в E.164 (CallerID, по которому шлюз выбирает SIM) |
| Override Extension | Yes |
| Dial Patterns → Match Pattern | `+X.` |
| Dial Patterns → CallerID | пусто (это фильтр) |

### 4.2 Вариант B: кастомный контекст + правка `dest`

`/etc/asterisk/extensions_custom.conf` (файл подключён штатно: `#include
extensions_custom.conf` в `extensions.conf:31`; в Asterisk `#include` — не
комментарий, комментарий это `;`):

```
[gsm-outbound]
exten => _+X.,1,Dial(PJSIP/${EXTEN}@1001,60)
exten => _+X.,2,Hangup()
```

и маршрут должен уходить в этот контекст, а не в `from-did-direct`:

```sql
UPDATE outbound_routes SET dest='gsm-outbound,${EXTEN},1' WHERE route_id=351;
```

`dest` рендерится дословно → получится `Goto(gsm-outbound,${EXTEN},1)`, и в
момент перехода `EXTEN` ещё равен набранному номеру.

> Внимание: значение `dest` перезаписывается, если открыть этот маршрут в GUI
> и сохранить его. После Apply Config стоит проверить:
> `asterisk -rx "dialplan show outrt-351"` → последняя строка должна быть
> `Goto(gsm-outbound,${EXTEN},1)`.

### 4.3 Общее для обоих вариантов: убрать остаточный `dest`

Маршрут 351 сейчас имеет `dest='from-did-direct,1001,1'` — это «куда звонить,
если все транки отказали». Пока там это значение, любой отказ транка
приведёт к потере номера и `488`. После выбора варианта:

```sql
UPDATE outbound_routes SET dest='' WHERE route_id=351;
```

Затем — **Apply Config** (или `asterisk -rx "dialplan reload"`).

Проверка, что транк подхватился:

```bash
grep -E "^OUT_[0-9]+ ?=" /etc/asterisk/extensions_additional.conf | grep -i gsm
# ожидается:  OUT_244 = AMP:PJSIP/$OUTNUM$@1001
asterisk -rx "dialplan show outrt-351" | tail -3
```

### 4.4 Транспорт: внешний адрес

`/etc/asterisk/pjsip.transports.conf` — в `external_media_address` и
`external_signaling_address` сейчас прописан адрес **чужого стенда**, а не
`185.82.73.76`.

**Что именно сломано (замерено 2026-11-19 на живом звонке):**

| Часть пакета | Что в нём сейчас | Следствие |
|---|---|---|
| `Via:` и `Contact:` в исходящих запросах PBX | чужой адрес | ACK/BYE внутри диалога уходят в никуда; шлюз отвечает на `Contact`, т.е. туда же |
| `c=IN IP4` в SDP | **уже правильный** — `185.82.73.76` | RTP в установленном звонке PBX→GSM идёт и идёт в обе стороны (проверено: `RTP: tx=403 rx=396`) |

Причина того, что SDP оказался правильным: `local_net` сейчас указывает на
подсеть чужого стенда, поэтому адрес сервера считается «внешним» и
res_pjsip подставляет в SDP локальный адрес, игнорируя `external_media_address`.
Как только `local_net` будет исправлен на `185.82.73.0/24`, в SDP начнёт
подставляться `external_media_address` — поэтому **оба** значения обязаны
быть `185.82.73.76`, иначе SDP снова сломается.

**Правка через GUI (правильный путь — файл генерируется FreePBX):**

`Settings → SIP Settings → General Settings → NAT Settings`

| Поле | Значение |
|---|---|
| External IP | `185.82.73.76` |
| Local Networks | `185.82.73.0/24` (старую запись чужой подсети удалить) |
| External Host | оставить пустым — иначе он перебьёт External IP в `Contact` |
| NAT | `yes`, режим `public` (без изменений) |

Затем **Submit → Apply Config** и перезагрузка транспорта:

```
asterisk -rx "pjsip show transports"
asterisk -rx "module reload res_pjsip"   # или перезапуск asterisk
```

Откуда FreePBX берёт эти значения (`core/functions.inc/drivers/PJSip.class.php`):

```php
$extip = $ss->getConfig("externip");                 // → External IP
$transport[$t]['external_media_address']    = $extip;
$transport[$t]['external_signaling_address'] = $extip;
$localnets = $ss->getConfig('localnets');            // → Local Networks
```

То есть значения лежат в `kvstore_Sipsettings` (ключи `externip` и `localnets`),
а файл `pjsip.transports.conf` каждый раз перегенерируется из них.

**Запасной путь (если GUI недоступен):** `pjsip.transports_custom.conf`
подключается **до** сгенерированной секции, поэтому переопределить его нельзя.
Зато `pjsip.transports_custom_post.conf` подключается **после**
(`core/functions.inc/drivers/PJSip.class.php:430`):

```
#include pjsip_custom.conf
#include pjsip.transports.conf
#include pjsip.transports_custom_post.conf      ← сюда
```

Туда можно положить полное переопределение секции (в Asterisk повторное
определение объекта заменяет предыдущее целиком):

```ini
[0.0.0.0-udp]
type=transport
protocol=udp
bind=0.0.0.0:6064
external_media_address=185.82.73.76
external_signaling_address=185.82.73.76
allow_reload=no
tos=cs3
cos=3
local_net=185.82.73.0/24
```

### 4.5 Заглушка в `[from-gsm-gateway]`

`extensions_custom.conf` содержит `Dial(PJSIP/<40181>,60)` — плейсхолдер
вместо номера. Для входящего GSM→PBX нужно `Dial(PJSIP/40181,60)`.
Подробный разбор и полный рабочий контекст — §8.

## 5. Что осталось за пределами маршрутизации

1. **SIM в телефоне установлена**: `gsm.sim.state = LOADED,ABSENT` (слот 0,
   оператор `25506`, LTE). С 2026-11-19 сотовная нога работает; выбора слота
   по CallerID больше не требуется — вызов PBX→GSM прошёл end-to-end
   2026-11-19 13:31 (см. §8.3).
2. **Выбор SIM по CallerID**: при тестовом INVITE (CallerID `anonymous`)
   приложение ответило `503 No Matching SIM` —
   `Outbound CID anonymous matched 0 SIM accounts`. С маршрутом, у которого
   задан Route CID, приложение получит CallerID этого маршрута и должно
   выбрать слот по нему. Нужен реальный SIM и совпадение номера SIM с
   «Own Number» в настройках шлюза.
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
  (`tun0`; публичный IP и внешний порт меняются при переподключении — на
  момент замера контакт AOR был `sip:1001@187.40.252.67:20716`).
  Локальный SIP-сокет `10.5.0.2:5062`.
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

---

## 8. GSM → PBX: разбор `404` и точная конфигурация входящего маршрута

Замер — 2026-11-19, 13:34:58, `/var/log/asterisk/full`.

### 8.1 Что прислал шлюз (INVITE корректен)

```
INVITE sip:+380939104994@185.82.73.76 SIP/2.0
Via: SIP/2.0/UDP 185.158.242.51:5062;branch=z9hG4bK283281243;rport
To: <sip:+380939104994@185.82.73.76>
From: <sip:1001@185.82.73.76>;tag=gw956349106
Contact: <sip:1001@185.158.242.51:5062>
P-Asserted-Identity: "+380507127621" <sip:+380507127621@185.82.73.76>
Authorization: Digest username="1001", realm="asterisk", ...
```

* Request-URI user = `+380939104994` — это **DID** (номер SIM, по которому
  звонили абоненту);
* `P-Asserted-Identity` = номер звонящего (CallerID);
* `From` = `1001` — аккаунт шлюза.

Эндпоинт `1001` зарегистрирован (`Contact: 1001/sip:1001@185.158.242.51:8934`,
`Avail`), аутентификация прошла, `trust_id_inbound=yes` отработал: в логе
диалплана `GSM gateway: inbound from +380507127621`. **Шлюз отправил всё
правильно** — `404` родился на стороне PBX.

### 8.2 Где рождается `404`

```
Executing [+380939104994@from-gsm-gateway:1] NoOp("PJSIP/1001-00000020", "GSM gateway: inbound from +380507127621")
Executing [+380939104994@from-gsm-gateway:2] Dial("PJSIP/1001-00000020", "PJSIP/<40181>,60")
ERROR chan_pjsip.c: Unable to create PJSIP channel - endpoint '<40181>' was not found
NOTICE app_dial.c: Unable to create channel of type 'PJSIP' (cause 3 - No route to destination)
Executing [+380939104994@from-gsm-gateway:3] Hangup("PJSIP/1001-00000020", "")
→ SIP/2.0 404 Not Found
```

`Dial(PJSIP/<40181>,60)` — в имени эндпоинта стоят **литеральные** угловые
скобки. Эндпоинта `<40181>` не существует; настоящий `40181` есть и
зарегистрирован (`Contact: 40181/sip:40181@192.142.55.37:61093`, `Avail`).
Номер в скобках — плейсхолдер, из-за него `Dial()` упал, PBX не нашёл
куда звонить и вернул `404`.

### 8.3 Почему маршрут приходится писать руками

На этой инсталляции **нет таблицы `incoming_routes`** (и нет `extensions`) —
расширения ведутся через Sangoma Endpoint Manager (`endpoint_extensions`,
`extensionroutes`), а `ext-did-0001` / `ext-did-0002` на диске отсутствуют
(это пустые `include`). GUI FreePBX «Inbound Routes» здесь нерабочий, поэтому
входящий маршрут для сотовой ноги описывается вручную в
`/etc/asterisk/extensions_custom.conf`.

### 8.4 Рабочий `extensions_custom.conf`

```ini
; /etc/asterisk/extensions_custom.conf

; ─────────────────────────────────────────────────────────────
; GSM → PBX: абонент звонит на номер SIM, шлюз звонит на PBX
; ─────────────────────────────────────────────────────────────
; Шлюз шлёт:  From: <sip:1001@pbx>
;              To:   <sip:<номер SIM>@pbx>        ← Request-URI = DID
;              P-Asserted-Identity: <sip:<номер звонящего>@pbx>
; Эндпоинт 1001: context=from-gsm-gateway (pjsip.endpoint.conf)

[from-gsm-gateway]
exten => _+X.,1,NoOp(GSM gateway: inbound DID=${EXTEN} from ${CALLERID(num)})
 same => n,Set(GSM_DID=${EXTEN})
 same => n,Goto(from-gsm-gateway-did,${EXTEN:1},1)
 same => n,Hangup()

; Входящий маршрут (DID) сотовой ноги.
; Ключ — номер SIM без ведущего «+».
[from-gsm-gateway-did]
exten => 380939104994,1,NoOp(GSM DID ${GSM_DID} -> 40181)
 same => n,Dial(PJSIP/40181,60)
 same => n,Hangup()

; Неописанные DID — в никуда.
exten => _X.,1,NoOp(GSM DID ${GSM_DID} not mapped)
 same => n,Hangup()

; ─────────────────────────────────────────────────────────────
; PBX → GSM
; ─────────────────────────────────────────────────────────────
; Рабочая форма dial-string — только PJSIP/<номер>@1001 (§3).
[gsm-outbound]
exten => _+X.,1,NoOp(GSM gateway: outbound to ${EXTEN})
 same => n,Dial(PJSIP/${EXTEN}@1001,60)
 same => n,Hangup()

[add-gsm-forward]
exten => s,1,Set(PJSIP_HEADER(add,X-GSM-Forward)=${1})
 same => n,Return()
```

Почему именно так:

* `exten => _+X.` — единственный шаблон в контексте эндпоинта `1001`;
  `X` = одна цифра, `.` = хвост, поэтому `+380939104994` подходит;
* `Goto(...,${EXTEN:1},1)` — снимает ведущий `+`, чтобы в контексте DID
  использовать чистый номер как **точное** имя расширения (точный ключ
  всегда приоритетнее шаблонов);
* `Dial(PJSIP/40181,60)` — без скобок; CallerID доезжает автоматически из
  `P-Asserted-Identity`;
* в `[gsm-outbound]` — рабочая форма `PJSIP/<номер>@1001` вместо
  нерабочей `PJSIP/1001/${EXTEN}` (§3).

Применение и проверка:

```bash
asterisk -rx "dialplan reload"
asterisk -rx "dialplan show from-gsm-gateway"
asterisk -rx "dialplan show from-gsm-gateway-did"
```

### 8.5 Что уже работает, что осталось

**Работает (2026-11-19 13:31, направление PBX → GSM):** софтфон `40181` →
маршрут `351` → custom trunk `PJSIP/$OUTNUM$@1001` → INVITE шлюзу → сотовный
вызов → `ACTIVE` → `180 Ringing` → `200 OK` → `Bridge: BRIDGED (outbound)` →
RTP в обе стороны (`First RX: pt=8`, `RTP: tx=403 rx=396`).

**Не работает (GSM → PBX):** `404` из-за плейсхолдера `<40181>` (§8.2).

**Закрыть заменой заготовки §8.4 + правкой транспорта §4.4.**

После правок ожидаемая последовательность в `/var/log/asterisk/full`:

```
Executing [+380939104994@from-gsm-gateway:2] Goto("PJSIP/1001-…", "from-gsm-gateway-did,380939104994,1")
Executing [380939104994@from-gsm-gateway-did:2] Dial("PJSIP/1001-…", "PJSIP/40181,60")
→ SIP/2.0 180 Ringing → SIP/2.0 200 OK
```

и в SDP исходящего INVITE — `c=IN IP4 185.82.73.76`.

---

## 9. «Звонок висит на FreePBX до таймаута» — разбор 2026-11-19 15:29

Симптом: GSM-абонент положил трубку во время разговора, PBX не завершил
вызов — висел ~32 секунды, пока не сработал таймаут.

### 9.1 Хронология по логам обеих сторон

| Время | Сторона | Событие |
|-------|---------|---------|
| 15:29:17 | PBX | INVITE от софтфона `40181` → `100 Trying` |
| 15:29:17 | PBX | INVITE на шлюз (trunk `PJSIP/$OUTNUM$@1001`), `Contact: <sip:asterisk@<адрес-чужого-стенда>:6064>` |
| 15:29:17 | шлюз | `100 Trying` + `180 Ringing` → PBX отправляет `180` софтфону |
| 15:29:34 | шлюз | `200 OK` (с SDP) → PBX шлёт ACK шлюзу |
| 15:29:34 | PBX | `200 OK` софтфону, **в нём `Contact: <sip:asterisk@<адрес-чужого-стенда>:6064>`** |
| 15:29:35…15:30:06 | PBX | тот же `200 OK` (CSeq 22661) **ретрансмитится 10 раз** — ACK не пришёл |
| 15:29:50 | шлюз | GSM-абонент положил трубку → шлюз рвёт мост и **отправляет BYE** |
| — | шлюз→PBX | BYE ушёл на `Contact` из INVITE PBX = адрес чужого стенда → **потерян** |
| 15:30:06 | PBX | Timer F исчерпан → PBX шлёт BYE софтфону → `481 Call/Transaction Does Not Exist` |
| 15:30:06 | PBX | мост разобран, PBX шлёт BYE шлюзу → шлюз отвечает `200 OK` мгновенно |

Лог приложения в момент 15:29:50:

```
I/GsmCallManager: GSM call disconnected (cause=3)      ← CM_NORMAL_CALL_CLR
I/CallOrchestrator: Tearing down bridge: GSM call ended
I/SipCall: Sent BYE for call 9ddda845-2076-4e5d-8bd4-a2490d473439
```

Лог PBX: между 15:29:17 и 15:30:11 **ни одного** `Received SIP request … BYE`
от шлюза — BYE физически не дошёл.

### 9.2 Единая причина

`external_signaling_address` транспорта указывает на чужой адрес, поэтому PBX
подставляет его в `Contact` своих запросов и ответов:

```
Contact: <sip:asterisk@<адрес-чужого-стенда>:6064>
```

В-dialog запросы (RFC 3261 §12.2.1.1) UA отправляет на **remote target** —
то есть на `Contact` из ответа. Отсюда:

* ACK софтфона `40181` ушёл на чужой адрес → PBX не получил ACK →
  `200 OK` ретрансмитился по Timer F (0.5, 1, 2, 4, 4, 4, 4, 4, 4, 4 с ≈ 32 с) →
  **это и есть «висит до таймаута»**;
* BYE шлюза ушёл на тот же чужой адрес → PBX не узнал о завершении GSM-стороны
  и держал канал до конца.

Правка — та же, что §4.4: `external_signaling_address` = `external_media_address` =
`185.82.73.76`, `local_net` = `185.82.73.0/24`. После неё `Contact` станет
`sip:asterisk@185.82.73.76:6064`, и обе ноги (ACK софтфона и BYE шлюза) уйдут
по адресу.

### 9.3 Что это значит для приложения

Приложение отправляет in-dialog BYE на `remoteContactAddress` — адрес из
`Contact` полученного INVITE (`SipCall.kt:528`). С исправленным транспортом
этот адрес корректен, поэтому **правки в приложении не требуются** — это
соответствует ранее принятому решению не делать клиентский fallback для BYE.

Если захочется страховки, симметричная уже сделанной правке (§3.1) механика:
брать адрес назначения in-dialog запросов из адреса, с которого пришёл пакет
(`remoteSourceAddress`), а не из `Contact`. Это отдельное изменение, сейчас
не обязательное.

### 9.4 Побочный эффект для входящего направления

В направлении GSM → PBX ACK шлюза уходит на `remoteContactUri` — `Contact` из
ответа PBX (`SipCall.kt:471`). Пока `external_signaling_address` не исправлен,
ACK уходит на чужой адрес, PBX ретрансмитит `200 OK` 32 секунды и рвёт вызов.
То есть **входящее направление тоже не заработает без правки транспорта** —
это не отдельная проблема, а тот же корень.

---

## 10. Состояние на 20.11.2026, 00:45

### 10.1 Что работает

* Входящий GSM → PBX: мобильный звонит на номер SIM шлюза, PBX отвечает
  `200 OK` на INVITE шлюза, шлюз отвечает на сотовый вызов **после**
  `STATE_ACTIVE`, голос идёт в обе стороны (итерация 5, подтверждено
  пользователем 18:03).
* Исходящий SIP → GSM: агент звонит на сотовый номер, контекст
  `gsm-outbound` отдаёт `Dial(PJSIP/${EXTEN}@1001,60)`, шлюз ставит сотовый
  звонок. **Тестировать исходящее направление нужно на другом номере**, а не
  на номере самого шлюза: звонок на свой номер — это самозвонок, и вторая
  нога слышит первую («звук из лишнего звена», `docs/VOICE_BRIDGE.md` §13).
* Динамик и ухо телефона глушатся на выходе устройства (`RCV Mux=Open`,
  `LOL Mux=Open`) — голосовой путь модема при этом не страдает.

### 10.2 Что не работает

* **Микрофон телефона остаётся в сотовом аплинке.** Два способа это убрать
  проверены, и оба берут свою цену:
  * вендорский `Set_SpeechCall_UL_Mute=1` — микрофон уходит, но голос абонента
    пропадает из захвата агента (`capRMS` 13 → 1, замер 19.11 в одном звонке);
  * `UL_SRC_MUX=DMIC` — то же самое, подтверждено на слух 24.11: микрофон
    выпал из звонка, но `capRMS` упал в 1 и агент абонента не слышит.
  На MT6855 любой рычаг, убирающий микрофон из аплинка, уносит и отвод
  модемного downlink'а. Кандидаты и протокол — `docs/VOICE_BRIDGE.md` §12.
* **Телефон играет голос дальней стороны в комнату** (направление SIP → GSM,
  24.11): отсюда «звук из лишнего звена» — микрофон подхватывает эхо, и
  абонент слышит повтор собственной реплики. Мут выхода (`RCV Mux` /
  `LOL Mux`) записывается, но HAL переписывает эти контролы при
  программировании голосового пути, а наш лог показывал *записанное* значение,
  а не оставшееся. В приложение добавлена read-back проверка выходного пути
  (`Handset output path:` в логе) — следующий звонок покажет, кто и как часто
  мукс возвращает. Детали — `docs/VOICE_BRIDGE.md` §15.

### 10.3 Что требует действий на сервере (правки выполняет пользователь)

1. **NAT Settings** — External IP `185.82.73.76`, Local Networks
   `185.82.73.0/24`, затем reload транспорта. Без этого in-dialog запросы
   (ACK/BYE) уходят на чужой адрес, и завершение звонка не проверено.
2. **Блокировка перебора паролей** с `158.69.246.20` — iptables/fail2ban.
   Сейчас лог сервера засыпает (~3 INVITE в 2,5 с).
3. Опционально: запись CDR/ MixMonitor на стороне PBX для проверки, что BYE
   шлюза доходит и разговор закрывается на обеих сторонах.

### 10.4 Не проверено

* Доходит ли BYE шлюза до PBX после правки транспорта.
* Входящая доступность PBX → телефон (CGNAT Starlink + NordVPN без
   проброса) — не решается.

---

## 11. Состояние на 27.11.2026, 13:29

### 11.1 Закрыто

* **Комната больше не в захвате агента.** Микрофон телефона отключён на
  аналоговом входе (`PGA_L_Mux = None`), голос абонента в захвате при этом
  остался (1757 RMS в момент речи, фон 1 RMS вместо 12–28). Рычаг вшит в
  приложение: применяется при старте захвата и повторно через 3 с,
  перепроверяется каждый тик, микрофон возвращается после звонка.
  Детали и замеры — `docs/VOICE_BRIDGE.md` §18.
* **Шумовой гейт отменён** (`noiseGateThreshold = 0`): он маскировал симптом
  и резал тихую речь абонента.
* **Патч `audio_policy_configuration.xml` признан нерабочим**: в HAL MediaTek
  нет поддержки `VOICE_DOWNLINK`, отдельного DL-захвата не существует.

Подтверждено двумя контрольными звонками (13:24 и 13:28): агент слышит
только голос абонента, абонент слышит голос агента, шлюз молчит.

### 11.2 NAT Settings — уже применены (проверено 27.11 13:45)

Проверка на сервере показала, что правка уже внесена и действует:

| что | где | значение |
|---|---|---|
| `externip` | `kvstore_Sipsettings` | `185.82.73.76` |
| `localnets` | `kvstore_Sipsettings` | `[{"net":"185.82.73.0","mask":"24"}]` |
| `external_media_address` | `pjsip.transports.conf` | `185.82.73.76` |
| `external_signaling_address` | `pjsip.transports.conf` | `185.82.73.76` |
| `local_net` | `pjsip.transports.conf` | `185.82.73.0/24` |
| те же значения в живом Asterisk | `pjsip show transport 0.0.0.0-udp` | `185.82.73.76` / `185.82.73.0/255.255.255.0` |

То есть значения доехали не только до файла, но и до работающего транспорта —
пункт 1 из §10.3 закрыт.

**Оговорка на будущее:** у транспорта `allow_reload=no` (FreePBX пишет так по
умолчанию). Следующая правка NAT-параметров не подхватитсяreload'ом — нужен
перезапуск Asterisk либо `pjsip_allow_reload=yes`.

### 11.3 CDR закрывается — проверено 27.11 13:50

Все пять звонков сегодняшнего дня записались в CDR как `ANSWERED` с
нормальными длительностью и `billsec`:

| calldate | src | dst | duration | billsec | disposition |
|---|---|---|---|---|---|
| 13:08:41 | +4915112345678 | +380507127621 | 483 | 476 | ANSWERED |
| 13:18:08 | +4915112345678 | +380507127621 | 42 | 35 | ANSWERED |
| 13:23:58 | +4915112345678 | +380507127621 | 36 | 30 | ANSWERED |
| 13:24:49 | +380507127621 | 380939104994 | 22 | 20 | ANSWERED |
| 13:27:51 | +4915112345678 | +380507127621 | 43 | 32 | ANSWERED |

Самого BYE в `/var/log/asterisk/full` нет ни одной строки за всё время — на
этой сборке тело SIP-сообщений не пишется в лог, поэтому отсутствие BYE в
логе ничего не доказывает. Показательнее CDR: обе ноги закрылись штатно, без
таймаута и без «no answer».

### 11.4 Осталось на сервере (правки выполняет пользователь)

1. **Блокировка перебора паролей** с `158.69.246.20` (iptables/fail2ban) —
   единственное из §10.3, что ещё не сделано.
2. Опционально: MixMonitor, если нужно видеть RTP на стороне PBX.

### 11.5 Релиз

`v1.4.20` опубликован в `GenaLC/android-tools` (APK + Magisk-модуль,
контрольные суммы в тексте релиза). Публичный репозиторий
`pulpoff/gsm2sip` остался на `v1.4.18` — права на запись в него у учётной
записи нет, это отдельная правка.

### 11.6 Не проверено

* Входящая доступность PBX → телефон (CGNAT Starlink + NordVPN) — не решается.
* Работа Cubot'а как обычного телефона после серии звонков шлюза: микрофон
  возвращается (`PGA_L_Mux` = `AIN0` при следующем звонке, HAL программирует
  путь сам), но отдельной проверки «позвонить с Cubot'а вручную» не было.

## 12. Два разных CID в исходящем маршруте (проверено 27.11, 16:10)

В маршруте FreePBX есть **два** независимых поля с CallerID. Их регулярно
путают — в старой редакции `SCALING.md` они были смешаны, из-за чего схема
масштабирования была описана неверно.

| Поле GUI | Колонка БД | Что делает | В маршруте 351 |
|---|---|---|---|
| **Route CID** (форма маршрута) | `outbound_routes.outcid` | CallerID, который PBX подставляет в исходящий INVITE к шлюзу: рендерится как `Set(TRUNKCIDOVERRIDE=<outcid>)` | `+4915112345678` |
| **CallerID** в строке dial pattern | `outbound_route_patterns.match_cid` | ограничение маршрута: какие CallerID могут им пользоваться; рендерится как шаблон `_+X./40181` | `40181` |

### Доказательства (сервер, только чтение)

Схема таблиц:

```
outbound_routes:            route_id, name, outcid, outcid_mode, password,
                            emergency_route, intracompany_route, mohclass,
                            time_group_id, dest, time_mode, calendar_id,
                            calendar_group_id, timezone, notification_on
outbound_route_patterns:    route_id, match_pattern_prefix,
                            match_pattern_pass, match_cid, prepend_digits
```

Живая строка маршрута 351:

```sql
('351','40181','+4915112345678','','','','','default',NULL,
 'app-blackhole,hangup,1','',NULL,NULL,'default','call')
--  route_id  name  outcid      outcid_mode='' password='' emergency='' intra='' mohclass='default' ...
('351','','+X.','40181','')   -- match_cid = 40181
```

Как это превращается в dialplan (`/etc/asterisk/extensions_additional.conf`,
после `fwconsole reload`):

```ini
[outrt-351] ; 40181
exten => _+X./40181,1,Macro(user-callerid,LIMIT,EXTERNAL,)
exten => _+X./40181,n,ExecIf($["${KEEPCID}"!="TRUE" & ${LEN(${DB(AMPUSER/${AMPUSER}/outboundcid)})}=0 & ${LEN(${TRUNKCIDOVERRIDE})}=0]?Set(TRUNKCIDOVERRIDE=+4915112345678))
exten => _+X./40181,n,Macro(dialout-trunk,248,${EXTEN},,off)
```

`/40181` в шаблоне — это `match_cid`; `TRUNKCIDOVERRIDE=+4915112345678` — это
`outcid`. Код FreePBX, который это рендерит:
`/var/www/html/admin/modules/core/functions.inc.php` (~строка 2258) и форма
`views/routing/form.php` (там же поле называется «Route CID», а
`outcid_mode` — это флажок «Override Extension» со значениями
`override_extension` / пусто, а не список режимов CID).

### Как это выглядит в CDR

| Нога | src | dst | Канал |
|---|---|---|---|
| Входящая GSM→SIP | `+380507127621` (абонент) | `380939104994` (DID шлюза) | `PJSIP/1001-…` |
| Исходящая SIP→GSM | `+4915112345678` (Route CID = `outcid`) | `+380507127621` | `PJSIP/40181-…` |

### Как шлюз выбирает SIM

`CallOrchestrator` берёт CallerID исходящего INVITE (`sipCall.callerNumber`)
и передаёт его в `GsmCallManager.makeCall(..., outboundCid = cid)`.
`findPhoneAccountForCid` сравнивает его со значением «Own number» каждого
слота SIM (`own_number_slot_N`) и выбирает слот, для которого совпадение
единственное. Отсюда:

* Route CID (`outcid`) должен совпадать с «Own number» слота — иначе на
  двух-SIM-телефоне исходящий вызов падает с `503 No Matching SIM`
  (`GsmCallManager.kt`, ветка `matches.size != 1`). На одном-SIM телефон звонит
  в любом случае.
* DID входящего INVITE берётся из того же поля «Own number», а если оно не
  задано — из номера SIM (`CallOrchestrator.inboundSipDestination`).

Живой пример на Cubot: в приложении `own_number = +4915112345678` (legacy-ключ,
совпадает с `outcid` маршрута), поле слота не задано, поэтому DID входящего
INVITE взят с карты SIM — `+380939104994`; именно он стоит в Request-URI.

**Вывод для масштабирования:** на телефон нужно одно число — `own_number`; оно
же идёт в Route CID маршрута, и его же надо вписать в приложение в поле
«Own number» слота SIM. Номер агента идёт в `match_cid`. Готовая реализация
этой схемы на любое число телефонов — в [`100-phones/`](100-phones/).
