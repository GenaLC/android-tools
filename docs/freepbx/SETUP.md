# Настройка FreePBX под GSM-шлюз — что и куда вписать

Пошаговая инструкция для вашей связки: **FreePBX 16.0.50 / Asterisk 18.26.4,
сервер `159.100.13.216`, транспорт UDP, порт 6064**.

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

`Connectivity → Outbound Routes → Add Outbound Route`.

| Поле | Значение |
|---|---|
| Name | `GSM via gateway` |
| Trunk Sequence | `SIP Phone → 1001` |
| Dial Patterns → Match Pattern | `+X.` |
| Dial Patterns → CallerID | `<SIM>` (номер SIM в E.164) |

**CallerID маршрута = номер SIM.** По нему шлюз выбирает SIM (на
двухсимочном — по номеру слотa) и ставит его номер в сторону PSTN. Если
оставить CallerID `40181`, будет `503 No Matching SIM`, а абонент увидит
«40181» вместо номера SIM.

### Проверьте, что сгенерировал FreePBX

После **Apply Config** выполните на сервере:

```bash
grep -n "1001" /etc/asterisk/extensions.conf | head -20
```

Нужна строка с `Dial(PJSIP/1001/…)` — то есть с «дозвонной частью» в
диал-строке. Если её нет и получилось `Dial(PJSIP/1001)` без номера, то
FreePBX номер в INVITE не положил, и нужно одно из двух:

1. **Вариант с заголовком** — в `extensions_custom.conf` заменить строку
   `Dial(PJSIP/1001/${EXTEN},60)` на
   `Dial(PJSIP/1001,60,b(add-gsm-forward^s^1(${EXTEN})))`.
   Заголовок `X-GSM-Forward` добавляется уже на вызываемом канале и доходит
   до шлюза в любом случае.
2. **Вариант с транком** — если номер должен идти «штатным» способом:
   `Connectivity → Trunks → Add SIP (chan_pjsip) Trunk`, SIP Server = адрес
   шлюза, Context = `from-internal`, и Outbound Route уже на этот транк. Но
   транку нужен **статический адрес шлюза** — за CGNAT его нет (см. §7).

---

## 5. Настройки телефона

Приложение → вкладка **Settings**:

| Поле | Значение |
|---|---|
| SIP Server | `159.100.13.216` |
| SIP Port | `6064` |
| SIP Username | `1001` |
| SIP Password | `<ПАРОЛЬ>` (тот же, что в расширении) |
| Own Number (SIM) | `<SIM>` — в E.164, с плюсом |
| Enable TLS | **выключено** |
| Audio Codec | как есть (по умолчанию g722) |

Отдельно: если SIM в телефоне две, задайте номер **для каждого слота** —
список «Own Number (SIM)» под полем. Приложение выбирает SIM по номеру
CallerID, который приходит из PBX.

На телефоне: SIM вставлена, root-доступ приложению выдан, Magisk разрешил
приложению работать в фоне.

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
| `Request 'INVITE' from '<sip:1001@159.100.13.216>' … No matching endpoint found` | PBX не нашёл эндпоинт по имени из `From` — проверьте, что расширение называется ровно `1001` и что `Trust RPID = Yes` |
| `403 Forbidden` / `401 Unauthorized` и обрывы | не сходится пароль или не включён `trust_id_inbound` |
| `603 Declined` | эндпоинт найден, но AOR без контакта — шлюз не зарегистрировался (проверьте пароль и порт) |
| `488` от шлюза в логе приложения | INVITE пришёл без номера — см. §4 |

В логе приложения (`Settings → Logs`):

| Сообщение | Что значит |
|---|---|
| `SIP registered — ready for calls` | REGISTER принят |
| `GSM destination resolved: +49…` | номер взят — из Request-URI или To |
| `INVITE with no GSM destination — rejected` | номер не пришёл ни в одном заголовке |
| `503 No Matching SIM` | CallerID не совпал с номером ни одного слота — проверьте CallerID маршрута и «Own Number (SIM)» |

---

## 7. Что пока не закрыто

- **PBX → GSM из интернета.** PBX шлёт INVITE на адрес из `Contact` — то
  есть на публичный адрес телефона. Мобильные операторы входящие UDP-пакеты
  за CGNAT обычно не заворачивают. Нужен белый IP (свой или Wi-Fi с белым
  IP) либо туннель с обратным (WireGuard, Tailscale).
- **Номер SIM в SIM-карте.** Пока SIM не вставлена, приложение не знает
  номера — задайте его вручную в настройках.
