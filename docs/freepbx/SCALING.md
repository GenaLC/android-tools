# Несколько шлюзов на одном FreePBX

Рецепт наращивания: сколько объектов нужно на сервере на каждый телефон и что
происходит с `extensions_custom.conf`.

> Готовая конфигурация на любое число телефонов (в комплекте — 100) лежит в
> [`100-phones/`](100-phones/): генератор, скрипт применения с бэкапом и
> офлайн-проверка. Этот документ объясняет, почему она устроена именно так.

Пусть телефон №1 — это текущий `1001` с SIM `+380939104994`, телефон №2 —
`1002` с SIM `+380939104995`.

## 0. Два разных CID в маршруте FreePBX

В исходящем маршруте FreePBX есть **два** независимых поля с CallerID, и их
 постоянно путают. Проверено на живом маршруте 351 и в коде FreePBX
(`admin/modules/core/functions.inc.php`, `views/routing/form.php`):

| Поле | Колонка БД | Что делает | В рабочей схеме |
|---|---|---|---|
| **Route CID** (форма маршрута) | `outbound_routes.outcid` | CallerID, который PBX подставляет в исходящий INVITE к шлюзу: рендерится как `Set(TRUNKCIDOVERRIDE=<outcid>)` | номер телефона (`own_number`) — по нему приложение выбирает SIM-слот |
| **CallerID** в строке dial pattern | `outbound_route_patterns.match_cid` | ограничение маршрута: какие CallerID могут им пользоваться. Рендерится как шаблон `_+X./40181` | номер агента (`40181`) |

То есть `match_cid` — это «кто имеет право звонить по этому маршруту», а
`outcid` — «каким номером шлюз увидит вызов». Это разные вещи, и в старой
редакции этого файла они были смешаны.

Как это выглядит в живом маршруте 351:

```sql
-- outbound_routes
route_id=351, name=40181, outcid=+4915112345678, outcid_mode='', dest='app-blackhole,hangup,1'
-- outbound_route_patterns
route_id=351, match_pattern_prefix='', match_pattern_pass='+X.', match_cid=40181
```

и в dialplan после `fwconsole reload`:

```ini
[outrt-351] ; 40181
exten => _+X./40181,1,Macro(user-callerid,LIMIT,EXTERNAL,)
exten => _+X./40181,n,ExecIf($["${KEEPCID}"!="TRUE" & ${LEN(${DB(AMPUSER/${AMPUSER}/outboundcid)})}=0 & ${LEN(${TRUNKCIDOVERRIDE})}=0]?Set(TRUNKCIDOVERRIDE=+4915112345678))
exten => _+X./40181,n,Macro(dialout-trunk,248,${EXTEN},,off)
```

`TRUNKCIDOVERRIDE=+4915112345678` — это `outcid`; `/40181` в шаблоне — это
`match_cid`.

### Как шлюз выбирает SIM

Приложение получает CallerID исходящего INVITE (`sipCall.callerNumber`),
сравнивает его со значением «Own number» каждого слота SIM и выбирает тот
слот, для которого совпадение единственное. Отсюда:

* **Route CID (`outcid`) должен совпадать с «Own number» слота**, иначе на
  двух-SIM-телефоне шлюз не сможет выбрать слот и откажется звонить
  (`503 No Matching SIM`). На телефоне с одной SIM он всё равно позвонит —
  выбирать не из чего.
* **DID входящего INVITE берётся из того же поля** («Own number» слота, а если
  оно не задано — из номера SIM). Поэтому `outcid` и DID — это одно и то же
  число: `own_number` телефона.

Живой пример: на Cubot в приложении `own_number = +4915112345678` (совпадает
с `outcid` маршрута), поле слота не задано, поэтому DID входящего INVITE
взят с карты SIM — `+380939104994`, и именно он стоит в Request-URI.

## 1. Что нужно на сервере на один телефон

| Объект | Где | Имя | Зачем |
|---|---|---|---|
| AOR | `pjsip.aor.conf` | `100N` | адрес, на котором телефон регистрируется |
| Auth | `pjsip.auth.conf` | `100N-auth` | логин/пароль (пароль свой на каждый телефон) |
| Endpoint | `pjsip.endpoint.conf` | `100N` | сам эндпоинт: контекст, кодеки, NAT |
| Custom Trunk | GUI: Trunks → Add Custom Trunk | `gsm-gateway-N` | исходящая нога: `PJSIP/$OUTNUM$@100N` |
| Outbound Route | GUI: Outbound Routes | по одному на телефон | Route CID = номер телефона, match_cid = агент |
| Входящий DID | `extensions_custom.conf` | номер телефона → агент | маршрутизация входящего GSM-звонка |

И в приложении на телефоне: свой SIP-аккаунт (`100N`), свой номер в поле
«Own number» слота SIM, свой пароль.

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
| Route CID (`outcid`) | **номер телефона** (его же «Own number» в приложении) |
| Override Extension | No (`outcid_mode` = пусто) |
| Dial Patterns → Match Pattern | `+X.` |
| Dial Patterns → CallerID (`match_cid`) | **номер агента** этого телефона, например `40182` |

Два CID — две разные роли (см. §0):

* `Route CID` — номер, под которым шлюз увидит исходящий вызов; приложение
  сверяет его со своими «Own number» и по совпадению выбирает SIM-слот;
* `CallerID` в строке паттерна — ограничение маршрута: только эти CallerID
  могут звонить по нему. Здесь это номер агента.

У каждого телефона своя пара (Route CID = номер телефона, CallerID = агент),
поэтому общий маршрут на всех телефонов работать не будет: он увёл бы
исходящие не в тот слот. Если телефон обслуживает нескольких агентов, в
`match_cid` пишется шаблон (`4018[1-5]`).

## 2. Что делать с `extensions_custom.conf`

Здесь и лежит маршрутизация **входящих** GSM-звонков. Шлюз шлёт INVITE с
Request-URI = свой DID (номер телефона — «Own number» из приложения; если поле
слота не задано, берётся номер SIM) и `P-Asserted-Identity` = номер звонящего.
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
