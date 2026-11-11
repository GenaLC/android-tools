# FreePBX: PJSIP-эндпоинт и dialplan для GSM-SIP шлюза

Готовые к вставке конфиги. Сервер **не трогаем** — файлы применяются вручную
после проверки.

Идентификаторы в примерах замените на свои:

| Переменная в примере | Значение | Как выбрать |
|---|---|---|
| `1001` | Номер расширения, под которым шлюз регистрируется в PBX | Свободный номер в вашем плане нумерации |
| `+4915112345678` | Номер SIM-шлюза в строгом E.164 | `X-GSM-From` — кто звонит через шлюз |
| `trunk-name` | Имя вашего PSTN-транка в PJSIP | Как он назван в FreePBX |

## 1. Соглашение о маршрутизации

Шлюз маршрутизирует **только** по заголовкам `X-GSM-From` и `X-GSM-To`
(оба обязательны, строгий `+E.164`):

- `X-GSM-From` — номер SIM, который должен звонить (для исходящего с PBX),
  либо номер вызывающего (для входящего с SIM);
- `X-GSM-To` — номер абонента, кому звонить.

Request-URI адресует SIP-аккаунт и **не является** запасным маршрутом.
INVITE без обеих пар — отклоняется шлюзом кодом `488`.

## 2. Требования к TLS

Приложение проверяет цепочку сертификатов **и** имя хоста, и не имеет опции
«доверять всем» (иначе пароль от регистрации ушёл бы кому попало). Отсюда:

- нужен FQDN, совпадающий с SAN сертификата; подключение по IP не сработает;
- сертификат должен выдавать CA, которому доверяет телефон. Варианты:
  1. публичный CA (Let's Encrypt) на имени, доступном телефону — проще всего;
  2. собственный CA, установленный в хранилище доверия телефона
     (для рутованного телефона — системный CA через Magisk-модуль,
     `/system/etc/security/cacerts/<hash>.0`).

На стороне PBX:

```bash
# TLS-слушатель должен быть включён и иметь сертификат
asterisk -rx "pjsip show tls"
asterisk -rx "pjsip show transports"
```

В `http.conf` TLS-слушатель Asterisk берётся из `asterisk.conf`:

```ini
[options]
tlscertfile = /etc/asterisk/keys/asterisk.crt
tlskeyfile  = /etc/asterisk/keys/asterisk.key
```

После правки: `asterisk -rx "pjsip set tls on"`.

## 3. `pjsip.conf` — эндпоинт шлюза

Если вы работаете через веб-панель FreePBX, создайте расширение
**Administration → Extensions → Add Extension → Device** с этими значениями:

| Поле | Значение |
|---|---|
| Extension Number | `1001` |
| Device Type | Device |
| Secret / Password | пароль шлюза |
| Context | `from-gsm-gateway` (см. §4) |
| Advanced → Codecs | `g722` (или `alaw`, `ulaw` — см. примечание) |
| Advanced → Transports | TLS only |
| Advanced → Outbound Proxy | пусто (шлюз регистрируется к нам, не наоборот) |
| Advanced → RTP → Direct Media | No |

Готовые файлы конфигурации:

```ini
; /etc/asterisk/pjsip.conf

[1001]
type=endpoint
; Контекст входящих вызовов от шлюза (и звонков на 1001 от агентов).
context=from-gsm-gateway
; Медиа не шоркаутим: Asterisk — B2BUA, RTP между агентом и шлюзом идёт
; через Asterisk, шлюз держит вторую ногу (сотовую) сам.
direct_media=no
; Кодеки. G.722 — широкополосный; сотовная нога обычно 8 кГц (AMR-NB),
; поэтому выигрыш есть только при AMR-WB у оператора. Если сомневаетесь —
; оставьте alaw,ulaw: это то, что гарантированно умеет любой транк.
; ВНИМАНИЕ: если предложить одновременно g722 и alaw, сервер чаще выберет
; alaw, и весь разговор останется узкополосным.
allow=!all,g722,alaw,ulaw
; Регистрация шлюза с digest-аутентификацией.
authenticate=yes
aors=1001
callerid=GSM Gateway
; Не отвечать 180 раньше времени: шлюз сам сообщает 180, когда пошла
; сотовная нога.
direct_media=no
; SRTP по умолчанию не включаем: G.722 + TLS достаточно, а SRTP добавляет
; согласование ключей, которое придётся проверять отдельно.
; Включать, если транк требует:
;media_encryption=sdes
;media_encryption=dtls
; Свои SIP-заголовки не отбрасываем.
allow_transfer=yes

[1001]
type=aor
max_contacts=1
remove_existing=yes
qualify_frequency=60

[1001]
type=auth
auth_type=userpass
username=1001
password=CHANGE_ME_STRONG_PASSWORD
```

Идентификаторы `1001` в секциях `endpoint`/`aor`/`auth` — одинаковые имена
секций в PJSIP допустимы (это разные типы), но во FreePBX UI они
создаются автоматически.

## 4. `extensions.conf` — маршрутизация

```ini
; /etc/asterisk/extensions.conf

; ── Исходящий: агент звонит абоненту через сотовную ногу шлюза ──
; Пример: агент набирает 79001234567 (номер абонента). Номер уходит в
; X-GSM-To, шлюз звонит с SIM.
;
; На Asterisk 22+ наследование SIP-заголовков от вызывающего канала к
; вызываемому стало строже, поэтому заголовки добавляются pre-dial-обработчиком
; на КАНАЛЕ ВЫЗЫВАЕМОГО (Dial(...,b(...))) — тогда ничего наследовать не нужно.
;
; На более старых Asterisk (18/20) работает простой вариант:
;   same => n,Set(PJSIP_HEADER(add,X-GSM-From)=${SIM_NUMBER})
;   same => n,Set(PJSIP_HEADER(add,X-GSM-To)=${EXTEN})
;   same => n,Dial(PJSIP/1001,60)

[gsm-outbound]
exten => _+X.,1,NoOp(GSM gateway: outbound to ${EXTEN})
 same => n,Set(GW_DEST=${EXTEN})
 same => n,Dial(PJSIP/1001,60,b(add-gw-headers^s^1))
 same => n,Hangup()

[add-gw-headers]
exten => s,1,Set(PJSIP_HEADER(add,X-GSM-From)=${SIM_NUMBER})
 same => n,Set(PJSIP_HEADER(add,X-GSM-To)=${GW_DEST})
 same => n,Return()

; ── Входящий: абонент звонит на SIM, шлюз звонит в PBX ──
; Шлюз сам ставит INVITE на 1001 с X-GSM-From (кто звонит) и
; X-GSM-To (номер SIM). Контекст эндпоинта — from-gsm-gateway, поэтому
; INVITE попадает сюда.
;
; PJSIP_HEADER(read,...) читается только на PJSIP-канале — здесь он
; именно такой (канал к зарегистрированному эндпоинту).
;
; Номер для Dial(PJSIP/trunk-name/...) берём из X-GSM-From. Если номер в
; E.164 с плюсом, а транк ждёт без плюса — снимите его:
;   same => n,Set(GW_FROM=${PJSIP_HEADER(read,X-GSM-From)})
;   same => n,Set(GW_FROM=${GW_FROM:0})       ; если нужно убрать "+"

[from-gsm-gateway]
exten => 1001,1,NoOp(GSM gateway: inbound from ${PJSIP_HEADER(read,X-GSM-From)})
 same => n,Set(GW_FROM=${PJSIP_HEADER(read,X-GSM-From)})
 same => n,Set(GW_FROM=${GW_FROM:0})
 same => n,Set(GW_TO=${PJSIP_HEADER(read,X-GSM-To)})
 same => n,Dial(PJSIP/trunk-name/${GW_FROM},60)
 same => n,Hangup()

; Прямые звонки на 1001 от агента (без номера абонента) — тот же исходящий
; путь: если агент звонит на 1001, номер абонента берётся из аргумента.
exten => _1001,1,NoOp(GSM gateway: agent dialled gateway directly)
 same => n,Goto(gsm-outbound,${EXTEN:1},1)
```

`${SIM_NUMBER}` — переменная контекста с номером SIM-шлюза в E.164. Её нужно
задать (например, в `extensions.conf` в секции `[global]` или через
`/etc/asterisk/custom/extensions.conf`):

```ini
[global]
SIM_NUMBER = +4915112345678
```

Если глобальная секция недоступна — подставьте номер прямо в `Set()`.

### Про `Dial()` и таймаут

Для GSM-замыкания нужно **не меньше 60 секунд**: время установки сотовной
ноги (дозвон, ответ абонента) существенно больше длительности SIP-транзакции.
20 секунд — недостаточно, разрыв будет выглядеть как таймаут.

## 5. Проверка на сервере

```bash
# Регистрация шлюза
asterisk -rx "pjsip show registrations"
asterisk -rx "pjsip show endpoint 1001"

# Входящий INVITE от шлюза: должен попасть в контекст from-gsm-gateway
asterisk -rx "dialplan show from-gsm-gateway"

# Живой вызов: смотрите заголовки на входящем INVITE
grep -E "X-GSM-(From|To)" /var/log/asterisk/full

# Исходящий: заголовки на канале к шлюзу
asterisk -rx "pjsip show channelstats"
```

Типичные ошибки:

| Симптом | Причина |
|---|---|
| `No matching endpoint found` | Регистрация не прошла или contact не совпадает; проверьте `pjsip show registrations` |
| Шлюз отвечает `488` | В INVITE нет `X-GSM-From` и/или `X-GSM-To` |
| Заголовки не видны в `full` | `PJSIP_HEADER(add,...)` не поддержан на вашей версии Asterisk — нужен pre-dial-обработчик либо более новая версия |
| Звонок идёт, но через ~20 с обрывается | Слишком короткий `Dial()` — поставьте 60 |
| Входящий от SIM не маршрутизируется | Контекст эндпоинта не `from-gsm-gateway` либо `Dial()` уходит не в тот транк |

## 6. Что нужно от оператора сотовой связи

- Номер SIM должен принимать входящие вызовы (для направления GSM → SIP).
- Номер должен быть в E.164 и совпадать с `X-GSM-To`.
- Если SIM заведена с **VoWiFi (WFC)** — его нужно отключить: в режиме WFC
  сотовной ноги нет, модемного аудио-канала не существует, мост невозможен.
- При **VoLTE/IMS** голос идёт пакетным вызовом; работает ли аудио-мост на
  IMS-вызове, проверяется на живом вызове (см. `docs/DEPLOYMENT.md`).
