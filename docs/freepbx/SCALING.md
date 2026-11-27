# Несколько шлюзов на одном FreePBX

Рецепт наращивания: сколько объектов нужно на сервере на каждый телефон и что
происходит с `extensions_custom.conf`.

Пусть телефон №1 — это текущий `1001` с SIM `+380939104994`, телефон №2 —
`1002` с SIM `+380939104995`.

## 1. Что нужно на сервере на один телефон

| Объект | Где | Имя | Зачем |
|---|---|---|---|
| AOR | `pjsip.aor.conf` | `100N` | адрес, на котором телефон регистрируется |
| Auth | `pjsip.auth.conf` | `100N-auth` | логин/пароль (пароль свой на каждый телефон) |
| Endpoint | `pjsip.endpoint.conf` | `100N` | сам эндпоинт: контекст, кодеки, NAT |
| Custom Trunk | GUI: Trunks → Add Custom Trunk | `gsm-gateway-N` | исходящая нога: `PJSIP/$OUTNUM$@100N` |
| Outbound Route | GUI: Outbound Routes | по одному на телефон | выбор слота SIM по Route CID |
| Входящий DID | `extensions_custom.conf` | номер SIM → расширение | маршрутизация входящего GSM-звонка |

И в приложении на телефоне: свой SIP-аккаунт (`100N`), свой номер SIM в поле
«Own number» слота, свой пароль.

Endpoint (`pjsip.endpoint.conf`) — копия существующего `1001`:

```ini
[1002]
type=endpoint
aors=1002
auth=1002-auth
allow=(ulaw|alaw)
context=from-trunk            ; см. §2 — два варианта
callerid=1002 <1002>
trust_id_inbound=yes          ; номер звонящего берётся из P-Asserted-Identity
send_pai=yes                  ; ... и отправляется в INVITE
rewrite_contact=yes           ; in-dialog запросы идут на реальный адрес телефона
rtp_symmetric=yes
force_rport=yes
direct_media=no
dtmf_mode=rfc4733
```

AOR:

```ini
[1002]
type=aor
max_contacts=1
contact=sip:1002@<адрес-телефона>:5062
default_expiration=3600
qualify_frequency=60
```

Auth:

```ini
[1002-auth]
type=auth
auth_type=userpass
username=1002
password=<свой пароль>
```

Custom Trunk (GUI, `Connectivity → Trunks → Add Trunk → Add Custom Trunk`):

| Поле | Значение |
|---|---|
| Trunk Name | `gsm-gateway-2` |
| Custom Dial String | `PJSIP/$OUTNUM$@1002` |

Dial-string — **только** эта форма. `PJSIP/1002/<номер>` и
`PJSIP/1002/sip:<номер>@<адрес PBX>` не работают: res_pjsip отправляет INVITE
на хост из URI, а `PJSIP/<номер>@100N` кладёт набранный номер в user-часть и
адрес AOR — в хост (см. `STATUS.md` §3).

Outbound Route (по одному на телефон):

| Поле | Значение |
|---|---|
| Name | `gsm-1002` |
| Trunk Sequence | `gsm-gateway-2` |
| Route CID | **номер SIM этого телефона** в E.164 |
| Dial Patterns → Match Pattern | `+X.` |

Route CID — это то, по чему приложение выбирает слот: номер из CallerID
сверяется с номером SIM. Поэтому у каждого телефона свой маршрут со своим
Route CID; общий маршрут выбрал бы не тот слот.

## 2. Что делать с `extensions_custom.conf`

Здесь и лежит маршрутизация **входящих** GSM-звонков. Шлюз шлёт INVITE с
Request-URI = номер SIM (DID) и `P-Asserted-Identity` = номер звонящего.
Дальше Dialplan решает, кому звонить.

### Вариант A (тот, что уже настроен): своя таблица DID

Контекст эндпоинта остаётся `from-gsm-gateway`, и на каждый телефон
добавляется **одна строка**:

```ini
[from-gsm-gateway]
exten => _+X.,1,NoOp(GSM gateway: inbound DID=${EXTEN} from ${CALLERID(num)})
 same => n,Set(GSM_DID=${EXTEN})
 same => n,Goto(from-gsm-gateway-did,${EXTEN:1},1)
 same => n,Hangup()

[from-gsm-gateway-did]
exten => 380939104994,1,NoOp(GSM DID ${GSM_DID} -> 40181)
 same => n,Dial(PJSIP/40181,60)
 same => n,Hangup()

exten => 380939104995,1,NoOp(GSM DID ${GSM_DID} -> 40182)
 same => n,Dial(PJSIP/40182,60)
 same => n,Hangup()

exten => _X.,1,NoOp(GSM DID ${GSM_DID} not mapped)
 same => n,Hangup()

[gsm-outbound]
exten => _+X.,1,Dial(PJSIP/${EXTEN}@1001,60)
 same => n,Hangup()
```

Плюсы: предсказуемо, ничего лишнего не затрагивается, привязка телефона к
DID видна в одном месте. Минусы: маршрутизация «в обход» FreePBX — нет
очередей, IVR и групп вызова per DID.

### Вариант B (масштабируемый): родной механизм DID FreePBX

Контекст эндпоинтов меняется на `from-trunk`
(`from-trunk → from-pstn → ext-did → ext-did-custom`), а маршруты переносятся
в `[ext-did-custom]` — этот контекст FreePBX включает сам:

```ini
[ext-did-custom]
exten => 380939104994,1,Goto(ext-local,40181,1)
 same => n,Hangup()
exten => 380939104995,1,Goto(ext-local,40182,1)
 same => n,Hangup()
```

Тогда входящий GSM-звонок идёт обычным путём FreePBX: можно направлять его
в группу вызова, в IVR, на запись, — всё, что есть в GUI. Неописанные DID
попадают в `ext-did-catchall` → «no service» → шлюз честно рвёт сотовый
вызов.

> На этой инсталляции таблицы `incoming_routes` нет (расширения ведутся через
> Sangoma Endpoint Manager), поэтому DID через GUI добавить нельзя — отсюда
> ручной `[ext-did-custom]`. На стенде с обычным FreePBX этот же маршрут
> добавляется через GUI: `Applications → Inbound Routes`.

### Что выбрать

* 2–3 телефона, фиксированная схема → **вариант A**: меньше движущихся
  частей, ничего лишнего не затрагивается;
* больше телефонов, нужны группы вызова/IVR/запись → **вариант B**.

## 3. Ограничения, о которых надо знать заранее

* **Локальный SIP-порт приложения фиксирован** — `5062`
  (`GatewayService.kt:1282`). Два телефона за одним NAT (один Wi-Fi) схватят
  один порт: AOR-контакт каждого придётся указывать явно (`rewrite_contact`
  перепишет контакт на адрес, с которого пришёл REGISTER, так что для
  телефонов в одной сети это надо проверить). Для телефонов с разными
  SIM/NAT проблемы нет. Сделать порт настраиваемым — отдельная правка.
* **RTP**: у каждого эндпоинта свои порты, PBX разруливает сам; ограничение
  — число одновременных каналов на транке (`Maximum Channels`).
* **`max_contacts=1`** на AOR: один телефон — один контакт. Для резерва
  (телефон с двумя сетями) нужно `max_contacts=2`.
* **Пароль на каждый телефон свой**: эндпоинт `1001` обслуживает и
  `identify`/auth любого, кто знает пароль. Одинаковый пароль на всех
  телефонах — это общий вход в любой из них.
* **Аутентификация**: идентификация по имени аккаунта в `From` +
  `P-Asserted-Identity` (`trust_id_inbound=yes`), без `identify` и без
  статического IP — это и позволяет каждому телефону заходить из своей сети.
