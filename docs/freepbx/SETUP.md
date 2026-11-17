# Настройка FreePBX под GSM-шлюз — что и куда вписать

Пошаговая инструкция для вашей связки: **FreePBX 16.0.50 / Asterisk 18.26.4,
сервер `185.82.73.76`, транспорт UDP, порт 6064**.

Обозначения, которые надо заменить на свои:

| Метка | Что это | Пример |
|---|---|---|
| `<SIM>` | номер SIM шлюза в E.164 | `+4915112345678` |
| `<ПАРОЛЬ>` | пароль аккаунта шлюза (20+ случайных символов) | `Xk8…` |
| `<40181>` | внутреннее расширение софтфона, которому звонит шлюз | `40181` |

---

## 1. Расширение шлюза `1001`

`Applications → Extensions → Add Extension → Device` → **Device Type: SIP Phone**.

| Поле | Значение | Зачем |
|---|---|---|
| Extension Number | `1001` | логин шлюза в SIP |
| Display Name | `GSM Gateway` | имя в списке абонентов |
| Secret | `<ПАРОЛЬ>` | тот же пароль, что в приложении |
| Advanced → Context | `from-gsm-gateway` | сюда попадают входящие от шлюза (§3) |
| Advanced → Transport | **`0.0.0.0-udp`** (порт 6064) | транспорт на сервере уже создан |
| Advanced → Outbound Proxy | пусто | шлюз регистрируется к нам, не наоборот |
| Advanced → Codecs | `g722`, `alaw`, `ulaw` | должны пересекаться с оффертом шлюза `9 8 0 101` |
| Advanced → Direct Media | No | медиа идёт через Asterisk |
| Advanced → RTP Symmetric | Yes | телефон за NAT |
| Advanced → Rewrite Contact | Yes | телефон за NAT |
| Advanced → **Trust RPID** | **Yes** | `trust_id_inbound` — иначе PBX покажет «1001» вместо номера GSM-вызывающего |
| Advanced → **Send RPID** | **Send P-Asserted-Identity header** | `send_pai` — чтобы CallerID (номер SIM) доехал до шлюза в `P-Asserted-Identity` |

> В списке «Transport» может быть несколько транспортов — выберите тот, у
> которого UDP и порт **6064**. Если такого нет: `Settings → Asterisk SIP
> Settings → SIP Settings [chan_pjsip]` → Transport → Type **UDP**, Bind
> **0.0.0.0:6064**.

**Секция `identify` не нужна.** Шлюз представляется именем аккаунта в `From`
(`From: <sip:1001@pbx>`), и res_pjsip находит эндпоинт по имени из `From`
(`identify_by=username`) — с любого адреса, без белого IP.

---

## 2. Расширение софтфона `<40181>`

`Applications → Extensions → Add Extension → Device` → **Device Type: SIP Phone**.

| Поле | Значение | Зачем |
|---|---|---|
| Extension Number | `<40181>` | номер, на который звонит шлюз |
| Secret | свой пароль | — |
| Advanced → Transport | `0.0.0.0-udp` (6064) | тот же транспорт |
| Advanced → Codecs | `g722`, `alaw`, `ulaw` | — |
| Advanced → Direct Media | No | — |
| Advanced → **Trust RPID** | **Yes** | софтфон примет номер, присланный PBX |
| Advanced → **Send RPID** | **Send P-Asserted-Identity header** | иначе софтфон покажет «40181» вместо номера GSM-абонента |

Если конкретный софтфон номер не показывает — переключите **Send RPID** на
**Send Remote-Party-ID header** (`send_rpid=yes`): часть клиентов читает
только RPID. Шлюз понимает оба заголовка.

---

## 3. `extensions_custom.conf`

`Administration → Config Edit` → слева **Asterisk Custom Configuration Files**
→ `extensions_custom.conf` → вставить → Save → **Apply Config**.

```ini
; /etc/asterisk/extensions_custom.conf

; ─────────────────────────────────────────────────────────────
; GSM → PBX: абонент звонит на SIM, шлюз звонит на PBX
; ─────────────────────────────────────────────────────────────
; Шлюз шлёт:  From: <sip:1001@pbx>
;              To:   <sip:<SIM>@pbx>
;              P-Asserted-Identity: <sip:<номер звонящего>@pbx>
; Эндпоинт 1001 находится по имени из From, контекст — from-gsm-gateway.
; ${CALLERID(num)} = номер из P-Asserted-Identity (нужен Trust RPID = Yes).
[from-gsm-gateway]
exten => _+X.,1,NoOp(GSM gateway: inbound from ${CALLERID(num)})
 same => n,Dial(PJSIP/<40181>,60)
 same => n,Hangup()

; ─────────────────────────────────────────────────────────────
; PBX → GSM: агент звонит абоненту через сотовную ногу шлюза
; ─────────────────────────────────────────────────────────────
; Номер уходит в Request-URI шлюза как «дозвонная часть»:
;   Dial(PJSIP/1001/<номер>)  →  Request-URI sip:1001@pbx,
;                                To           sip:<номер>@pbx
; Шлюз берёт номер из X-GSM-Forward, затем Request-URI, затем To.
[gsm-outbound]
exten => _+X.,1,NoOp(GSM gateway: outbound to ${EXTEN})
 same => n,Dial(PJSIP/1001/${EXTEN},60)
 same => n,Hangup()

; Запасной вариант: номер отдельным заголовком (нужен, если PBX
; не доносит «дозвонную часть» ни в Request-URI, ни в To).
[add-gsm-forward]
exten => s,1,Set(PJSIP_HEADER(add,X-GSM-Forward)=${1})
 same => n,Return()
```

`Dial(...,60)` — 60 секунд: столько стоит установка сотовой ноги.

---

## 4. Исходящий маршрут (агент → абонент через SIM)

### Сначала: чем является `1001`

На сервере `1001` — это **PJSIP-эндпоинт**, созданный через
`Administration → Endpoints` (или вручную), а **не** расширение FreePBX и
**не** транк. Признаки: в `/etc/asterisk/extensions.conf` нет секции
`[ext-1001]`, а у эндпоинта пустые `mailboxes`, `named_call_group`,
`pickup_group`, `voicemail_extension`.

Отсюда важное следствие:

- **В «Trunk Sequence for Matched Routes» SIP-эндпоинт поставить нельзя** —
  этот список собирается из транков (модуль Trunks). Расширения и «голые»
  эндпоинты доступны только в списке назначений (например, «Optional
  Destination on Congestion»), который срабатывает только если все транки
  отказали. Как основной путь он не годится.
- **Для направления GSM → PBX эндпоинта `1001` достаточно как есть.** Шлюз
  шлёт INVITE с `From: <sip:1001@pbx>`, res_pjsip находит эндпоинт по имени
  (`identify_by=username,ip`, username — первый) и отдаёт запрос в его
  контекст `from-gsm-gateway`.
- **Для направления PBX → GSM нужен транк** — но не обязательно «SIP-транк с
  адресом шлюза», см. ниже.

Пометка «Note: Extension Routes is not registered» под полем Trunk Sequence
к нашему эндпоинту отношения не имеет: это про платный модуль FreePBX
«Extension Routing», не зарегистрированный на Sangoma. Игнорировать.

### Ответ: да, маршрут через сам SIP 1001 сделать можно

Вопрос «можно ли перенаправить звонок через SIP, указанный в маршруте» —
**да**. Нужен **Custom Trunk** (Asterisk Custom Configuration), у которого
диал-строка обращается прямо к эндпоинту `1001`:

```ini
PJSIP/1001/$OUTNUM$
```

Почему это работает без адреса шлюза: `PJSIP/1001` заставляет res_pjsip
взять **AOR-контакт эндпоинта `1001`**, а он у нас есть — телефон
зарегистрировался, и контакт живой (`Avail`). То есть Asterisk отправляет
INVITE ровно туда, куда телефон сам себя заявил, — на тот адрес и порт, что
в его `Contact`. Никакого «статического IP шлюза» в конфигурации транка
указывать не нужно, и он не нужен: адрес берётся из регистрации.

Это ключевое отличие от «SIP-транка с адресом шлюза» (§7): адрес шлюза там
задавался бы руками и должен был бы совпадать с тем, что телефон реально
показывает в `Contact`. Custom Trunk от этой ошибки страхует — он всегда
идёт по живому контакту.

Формат `$OUTNUM$` — стандартный для FreePBX: это номер, набранный
абонентом, после применения правил Dialed Number Manipulation (то есть
после Prefix/Prepend из Dial Patterns маршрута).

### Шаг 1. Создать Custom Trunk

`Connectivity → Trunks → Add Trunk → Add Custom Trunk`.

**Вкладка General:**

| Поле | Значение | Зачем |
|---|---|---|
| Trunk Name | `gsm-gateway` | имя транка в списке |
| Outbound CallerID | `<SIM>` (номер SIM в E.164) | CallerID по умолчанию, если Route CID не сработает |
| Maximum Channels | `1` | одна сотовная нога — больше некуда звонить |

**Вкладка custom Settings:**

| Поле | Значение |
|---|---|
| Custom Dial String | `PJSIP/1001/$OUTNUM$` |

**Submit → Apply Config.**

> Название вкладки в FreePBX 16 — «custom Settings» (с маленькой буквы),
> поле — «Custom Dial String».

### Шаг 2. Маршрут

`Connectivity → Outbound Routes → Add Outbound Route`.

**Вкладка Route Settings:**

| Поле | Значение | Зачем |
|---|---|---|
| Name | `GSM via gateway` | имя маршрута в списке |
| Trunk Sequence | `gsm-gateway` (созданный Custom Trunk) | куда звонить |
| **Route CID** | **`<SIM>`** (номер SIM в E.164) | CallerID, который уйдёт к шлюзу: по нему шлюз выбирает SIM и ставит его номер в сторону PSTN |
| **Override Extension** | **Yes** | чтобы Route CID был важнее, чем Outbound CID расширения |
| Route Type | **Permanent** (по умолчанию) | не Intra-Company — иначе PBX сохранит внутренний CallerID и не поставит номер SIM |
| Route Password | пусто | — |

**Вкладка Dial Patterns:**

| Поле | Значение | Зачем |
|---|---|---|
| Prepend | пусто | — |
| Prefix | пусто | — |
| Match Pattern | `+X.` | международный номер абонента |
| **CallerID** | **пусто** | это **фильтр**, а не «куда передать номер» |

**Submit → Apply Config** (кнопка вверху справа, оранжевая).

### CallerID в Dial Patterns — это фильтр

Два разных поля называются похоже, но работают противоположно:

| Поле | Смысл | Что писать нам |
|---|---|---|
| Dial Patterns → **CallerID** | **фильтр**: набранный номер подходит под Match Pattern только если CallerID вызывающего совпадает с этим полем. Для внутренних вызовов сравнивается **номер расширения** вызывающего, а не его Outbound CID | **пусто** — иначе маршрут не сработает никогда (звонит `40181`, а в поле стоит номер SIM) |
| Route Settings → **Route CID** | **CallerID, который уходит в линию**: номер, который увидит шлюз (и потом абонент в PSTN) | **`<SIM>`**, Override Extension = Yes |

Если номер SIM стоял в CallerID dial pattern'а, FreePBX не нашёл бы
подходящего маршрута: звонящий transmits «40181», а фильтр ждал «+49…».
Это тот самый случай «no route matches / все линии заняты».

Иерархия CallerID в FreePBX (от высшей к низшей):

1. Route CID при Override Extension = Yes
2. Outbound CID расширения
3. Route CID при Override Extension = No
4. CallerID транка

### Проверьте, что сгенерировал FreePBX

После **Apply Config** выполните на сервере:

```bash
grep -n "1001" /etc/asterisk/extensions.conf | head -20
```

Нужна строка с `Dial(PJSIP/1001/…)` — то есть с «дозвонной частью» в
диал-строке. Custom Trunk с диал-строкой `PJSIP/1001/$OUTNUM$` даёт именно
такую строку, потому что FreePBX подставляет `$OUTNUM$` на этапе генерации.

Если номер в диал-строке не появился (получилось `Dial(PJSIP/1001)` без
номера), номер надо передать отдельным заголовком — в `extensions_custom.conf`
заменить строку `Dial(PJSIP/1001/${EXTEN},60)` на
`Dial(PJSIP/1001,60,b(add-gsm-forward^s^1(${EXTEN})))`.
Заголовок `X-GSM-Forward` добавляется уже на вызываемом канале и доходит
до шлюза в любом случае.

Ещё одна проверка — что транк вообще виден и рабочий:

```bash
asterisk -rx "dialplan show from-1"      # или номер контекста вашего маршрута
asterisk -rx "pjsip show contacts"        # контакт 1001 должен быть Avail
```

---

## 5. Настройки телефона

Приложение → вкладка **Settings**:

| Поле | Значение |
|---|---|
| SIP Server | `185.82.73.76` |
| SIP Port | `6064` |
| SIP Username | `1001` |
| SIP Password | `<ПАРОЛЬ>` (тот же, что в расширении) |
| Own Number (SIM) | `<SIM>` — в E.164, с плюсом |
| Enable TLS | **выключено** |
| Audio Codec | как есть (по умолчанию g722) |

Отдельно: если SIM в телефоне две, задайте номер **для каждого слота** —
список «Own Number (SIM)» под полем. Приложение выбирает SIM по номеру
CallerID, который приходит из PBX.

### 5.1 Два обязательных условия на телефоне

**1. Приложение должно быть дефолтным набором номера.**
Входящий GSM-звонок перехватывается через `InCallService`, а Telecom
поднимает его только для дефолтного набора. Пока роль не занята, входящие
звонки до шлюза не доходят — при этом SIP зарегистрирован и всё выглядит
рабочим.

```bash
adb shell cmd role get-role-holders android.app.role.DIALER
adb shell cmd role add-role-holder android.app.role.DIALER com.callagent.gateway 0
```

В логах приложения после этого должно быть `Default dialer: yes`, а не
`WARNING: not the default dialer …`.

**2. VPN на телефоне должен быть выключен** (для направления PBX → GSM).
Подробно — в [`README.md`](README.md) §0.4. Коротко: пока активен Surfshark,
сервер видит адрес VPN-выхода, и входящий INVITE до шлюза не доходит.

Проверить, что VPN выключен и какой адрес видит сервер:

```bash
adb shell dumpsys connectivity | grep -A2 "VPN CONNECTED"   # не должно быть VPN CONNECTED
```

В логе приложения строка `STUN public IP: <ip>:<port>` — это тот адрес,
который видит интернет. Он должен совпадать с адресом в `Contact` на
сервере.

**3. SIM должна быть вставлена.** Проверка:

```bash
adb shell getprop gsm.sim.state      # READY, а не ABSENT
adb shell dumpsys isub | head -20     # ICCID не N/A
```

---

## 6. Проверка

На сервере:

```bash
asterisk -rx "pjsip show endpoint 1001"     # эндпоинт есть, контекст from-gsm-gateway
asterisk -rx "pjsip show contacts"           # контакт шлюза (если зарегистрировался)
asterisk -rx "pjsip show registrations"      # регистрации
asterisk -rx "core show function PJSIP_INHERITABLE_HEADER"   # на Asterisk 18 её нет — это норма
```

Лог в реальном времени:

```bash
asterisk -rx "pjsip set logger on"
tail -f /var/log/asterisk/full
```

Что смотреть:

| Сообщение | Что значит |
|---|---|
| `Request 'INVITE' from '<sip:1001@185.82.73.76>' … No matching endpoint found` | PBX не нашёл эндпоинт по имени из `From` — проверьте, что расширение называется ровно `1001` и что `Trust RPID = Yes` |
| `403 Forbidden` / `401 Unauthorized` и обрывы | не сходится пароль или не включён `trust_id_inbound` |
| `603 Declined` | эндпоинт найден, но AOR без контакта — шлюз не зарегистрировался (проверьте пароль и порт) |
| `488` от шлюза в логе приложения | INVITE пришёл без номера — см. §4 |
| INVITE ушёл, ответа нет, `No response` / таймаут | INVITE не дошёл до шлюза: VPN активен либо входящие UDP на роутер не доходят — см. README §0.4 |

В логе приложения (`Settings → Logs`):

| Сообщение | Что значит |
|---|---|
| `SIP registered — ready for calls` | REGISTER принят |
| `GSM destination resolved: +49…` | номер взят — из Request-URI или To |
| `INVITE with no GSM destination — rejected` | номер не пришёл ни в одном заголовке |
| `503 No Matching SIM` | CallerID не совпал с номером ни одного слота — проверьте CallerID маршрута и «Own Number (SIM)» |

---

## 7. Что пока не закрыто

- **Маршрут должен быть сгенерирован.** После правки маршрута в GUI нажмите
  **Apply Config** — иначе в `/etc/asterisk/extensions.conf` не будет ни
  одного упоминания `1001`, и маршрута просто не существует. Проверка:
  `grep -n "1001" /etc/asterisk/extensions.conf` должна что-то найти.

- **Входящий INVITE с сервера должен доходить до шлюза.** Это не вопрос
  настройки FreePBX, а вопрос сети: телефон должен принимать UDP из интернета
  по адресу, который он сам объявил в `Contact`. Пока на телефоне включён
  Surfshark, адрес в `Contact` — это адрес VPN-выхода, и пакеты от сервера
  туда не попадут. Варианты:

  1. **Выключить VPN** (или исключить приложение из туннеля в Split
     tunneling). Тогда в `Contact` будет реальный адрес Starlink-роутера
     (`145.224.123.93` на момент проверки) — но нужен ещё проброс порта
     `UDP 5062` на самом роутере Starlink, иначе пакет до телефона не дойдёт.
  2. **Туннель с обратным входом** (WireGuard, Tailscale) между телефоном и
     сервером. Тогда телефон получает адрес из туннеля, и в `Contact`
     попадёт именно он — сервер достучится напрямую. Это единственный
     вариант, который работает при CGNAT.

  Проверка достижимости снаружи (с любого внешнего хоста):

  ```bash
  # на телефоне — смотреть, пришёл ли пакет
  adb shell su -c 'tcpdump -i any udp port 5062'
  # с внешнего хоста — отправить
  nc -u -w 3 <адрес-из-Contact> 5062
  ```

  Если пакет не появился в tcpdump — сеть его не доставляет, и это не
  вопрос настроек FreePBX.

- **Номер SIM в SIM-карте.** Пока SIM не вставлена, приложение не знает
  номера — задайте его вручную в настройках.
