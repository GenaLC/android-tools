# TLS: сертификат для FreePBX и импорт CA на телефоне

**Состояние вашего сервера:** TLS-транспорта на нём нет вообще — `pjsip show
transports` выдаёт единственный `0.0.0.0-udp` на порту 6064. То есть сейчас
шлюз говорит с PBX по UDP, и пароль digest-аутентификации ходит открытым
текстом. Этот раздел — про то, как включить TLS, когда вы решите это сделать;
сертификат для адреса `159.100.13.216` уже сгенерирован (`~/pbx-cert/`).

Шлюз говорит с PBX по SIP over TLS и проверяет сертификат сервера: и цепочку,
и имя хоста. Проверку имени нельзя отключить — она защищает пароль
регистрации, который передаётся внутри TLS.

Публичный CA для этого не нужен: ни DNS, ни Let's Encrypt. PBX получает
сертификат от собственного CA, а телефон импортирует этот CA в приложение.
Публичные CA продолжают доверяться как обычно — импорт добавляет якорь, а не
заменяет хранилище.

## 1. Создать CA и сертификат сервера

Скрипт генерирует всё, что нужно, и проверяет цепочку через `openssl verify`:

```bash
tools/gen-pbx-cert.sh --host pbx.example.com --outdir ~/pbx-cert
```

Ключи:

| Файл | Куда |
|---|---|
| `ca.pem` | В приложение на телефоне (импорт CA) |
| `ca-key.pem` | **Офлайн, на PBX не копировать.** CA может выпустить сертификат на что угодно |
| `server.pem` | На PBX: `tlscertfile` |
| `server-key.pem` | На PBX: `tlskeyfile` |
| `server-fullchain.pem` | На PBX, если серверу нужен один файл с цепочкой |

`--host` — то имя, по которому телефон будет подключаться. Оно должно
совпадать с SAN в сертификате. Для обращения по IP передайте `--host 10.0.0.5`:
скрипт добавит SAN типа IP.

Полезные флаги: `--days N` (срок действия, по умолчанию 10 лет),
`--ca-name "..."`, `--force` (перезаписать существующие файлы).

## 2. Установить сертификат на PBX

```bash
sudo install -m 644 ~/pbx-cert/server.pem /etc/asterisk/keys/asterisk.crt
sudo install -m 600 ~/pbx-cert/server-key.pem /etc/asterisk/keys/asterisk.key
```

В `/etc/asterisk/asterisk.conf`, секция `[options]`:

```ini
[options]
tlscertfile = /etc/asterisk/keys/asterisk.crt
tlskeyfile  = /etc/asterisk/keys/asterisk.key
```

Это делает FreePBX (SIP Settings) и использует `chan_sip`. Для `res_pjsip`
сертификат указывается **в самой секции транспорта** — там опции называются
`cert_file=` и `priv_key_file=` (не `tls_cert_file`/`tls_key_file`):

```ini
[0.0.0.0-tls]
type=transport
protocol=tls
bind=0.0.0.0:5061
cert_file=/etc/asterisk/keys/asterisk.crt
priv_key_file=/etc/asterisk/keys/asterisk.key
method=tlsv1_2
```

Затем включить TLS-слушатель и проверить:

```bash
asterisk -rx "pjsip set tls on"
asterisk -rx "pjsip show tls"
```

В `pjsip show tls` должны быть видны ваши файлы и состояние `Enabled: yes`.
Порт TLS по умолчанию — 5061; он должен быть открыт на PBX и достижим с
телефона.

### Версия TLS на транспорте

Android 15 **запрещает** TLS 1.0 и 1.1 для приложений с `targetSdk 35` —
Conscrypt отключает их на уровне платформы ([Conscrypt: Changes in Android
15](https://source.android.com/docs/core/ota/modular-system/conscrypt)).
Шлюз в силу этого говорит только **TLS 1.2 и 1.3**, и никогда не согласует
1.0/1.1.

Значит, TLS-транспорт PBX должен быть на 1.2. Проверьте:

```bash
grep -A15 '\[0.0.0.0-tls\]' /etc/asterisk/pjsip.transports.conf
asterisk -rx "pjsip show transports"
```

В секции транспорта должна быть строка:

```ini
method=tlsv1_2
```

Как её получить:

| Способ | Что делать |
|---|---|
| Через GUI (правильный) | Settings → Asterisk SIP Settings → `chan_pjsip` → **SSL Method: tlsv1_2**, затем Apply Config |
| Если GUI не записал строку | Известная особенность FreePBX: поле SSL Method не всегда попадает в `pjsip.transports.conf`. Тогда строку надо дописать в файл руками — но FreePBX перегенерирует этот файл при Apply Config, поэтому проверяйте результат после каждого Apply |

Если транспорт остался на `method=tlsv1` или `tlsv1_1` (а в некоторых
сборках FreePBX строка `method=` отсутствует вовсе и берётся значение по
умолчанию), handshake не состоится. В логе телефона это выглядит так:

```
no usable TLS version for 159.100.13.216:5061 — this phone speaks TLS 1.2 and 1.3,
the peer offers TLSv1; the PBX TLS transport must be method=tlsv1_2
```

Альтернатива — не использовать TLS для этой ноги: приложение умеет и обычный
SIP over UDP на 5060, но тогда пароль digest-аутентификации передаётся открыто.

## 3. Импортировать CA на телефоне

1. Перекинуть `ca.pem` на телефон (кабелем, на SD-карту, любым мессенджером).
2. В приложении: **Settings → Certificate authority (private PBX) → IMPORT CA**.
3. Выбрать `ca.pem`.
4. Статус покажет: «1 imported certificate(s) — trusted in addition to the
   system CAs».

Импорт применяется при следующем подключении: после импорта дайте шлюзу
переподключиться (он делает это сам при смене сети или по кнопке).

Убрать CA: **REMOVE CA** в том же месте, с подтверждением.

## 4. Что означают ошибки в логе

| Сообщение | Что делать |
|---|---|
| `the server's certificate is not trusted: it is not signed by a CA in the system store and no certificate authority has been imported in Settings` | CA не импортирован — импортируйте `ca.pem` |
| `... nor by the N imported certificate(s)` | Импортирован другой CA. Проверьте, что на PBX установлен сертификат, подписанный именно им |
| `TLS handshake timed out to host:5061 — is 5061 the TLS port?` | На порту нет TLS. Проверьте `pjsip show tls` и то, что телефон стучится на 5061, а не на 5060 |
| `no usable TLS version for host:port — ... the PBX TLS transport must be method=tlsv1_2` | Транспорт PBX ограничен TLS 1.0/1.1, а Android 15 их не принимает. Нужно `method=tlsv1_2` в секции транспорта — см. §2 «Версия TLS на транспорте» |
| Ошибка проверки имени (hostname mismatch) | Имя в SAN не совпадает с тем, что в поле Server. Сертификат проверяется по имени, импорт CA этого не меняет |

## 5. Если сертификат на PBX всё-таки есть

Если PBX уже имеет сертификат от публичного CA (Let's Encrypt и подобные) —
импортировать ничего не нужно, телефон доверяет публичным CA из коробки.
Достаточно указать в настройках FQDN, совпадающий с SAN.

## 6. Где хранится импортированный CA

Файл `files/gsm2sip-tls/ca.pem` в приватном хранилище приложения. Он доступен
только приложению, переживает перезапуск и удаляется вместе с приложением или
кнопкой REMOVE CA.

CA — не секрет: он публичный и лежит на PBX. Секретом является `ca-key.pem`,
который остаётся офлайн.
