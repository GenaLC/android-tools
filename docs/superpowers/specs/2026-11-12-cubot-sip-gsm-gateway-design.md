# GSM-SIP шлюз на Cubot: дизайн

Дата: 2026-11-12
Статус: утверждён к реализации
Ветка: `feature/cubot-gsm-gateway` (форк `pulpoff/gsm2sip`, MIT)

## 1. Задача

Заменить аппаратный GSM-шлюз (SIM-модем + SIP-шлюз) связкой из рутованного смартфона
на Android 15 и FreePBX/Asterisk. Телефон регистрируется в FreePBX как SIP-эндпоинт
и работает мостом между SIP-стороной и сотовой ногой (SIM).

Оба направления должны работать:

- **SIP → GSM**: PBX отправляет INVITE на расширение телефона, телефон ставит
  сотовый вызов через SIM на указанный номер и мостит аудио в обе стороны.
- **GSM → SIP**: на SIM приходит вызов, телефон ставит SIP-вызов в FreePBX,
  FreePBX маршрутизирует его в PSTN-транк, аудио мостится в обе стороны.

## 2. Принятые решения

| Решение | Значение | Обоснование |
|---|---|---|
| Основа | Форк `pulpoff/gsm2sip` (MIT) | Готовая, отлаженная реализация той же задачи: свой SIP-стек без нативного кода, TLS, digest-auth, RTP, Magisk-модуль, чип-профили |
| `minSdk` | 24 (Android 7) — до 15 | Исходное требование «7–15». Первый проход ставил 31, но аудит показал: в upstream нет требования API 31 (там minSdk 26), а снижение до 24 — это 3 guard'а в `GatewayService.kt` |
| `targetSdk` / `compileSdk` | 35 / 35 | Целевые устройства — Android 15 |
| Устройства | Cubot X90 (MediaTek Helio G99, MT6789), Cubot X100 (MediaTek Dimensity 7025, MT6855V) | Роут (Magisk), кастомная прошивка, IMEI заменён. Прошивку не трогаем, Android не обновляем |
| Оба устройства | MediaTek — одно чип-семейство | X100 — это Dimensity 7025 (MT6855V), а не Unisoc. Unisoc T7200/T606 — это Cubot A40 |
| Объём v1 | Только голос в обе стороны + конфиг FreePBX | SMS-over-SIP и Linux-демон исключены из первой версии |
| Сигнализация | Прямой SIP over TLS + постоянный foreground service | Без своего сервера и без push |
| Сертификат PBX | Собственный CA + импорт CA в приложение | На FreePBX сертификата нет; публичный CA требует публичного DNS. Импорт CA в приложение не требует ни DNS, ни правки системного хранилища телефона. Проверка имени хоста остаётся включённой |

## 3. Основа: что уже есть в upstream

Проверено в клоне `pulpoff/gsm2sip@7b4dc33` (v1.4.18):

- **SIP-стек** (`sip/`): `SipClient`, `SipMessage`, `SipAuth`, `SipTransport`.
  Транспорты UDP и TLS (`TlsSipTransport`, RFC 3261 §18, с проверкой имени хоста
  через `SNIHostName`/endpoint identification). Digest-аутентификация.
- **RTP** (`rtp/`): `RtpSession`, `RtpPacket`, `G722Codec`, `Srtp`, `Dtmf`,
  `CaptureSource`, `RootPcm`/`RootPcmCapture`. Кодеки G.722 (payload 9),
  G.711 A/μ (8/0), telephone-event (101).
- **Сотовая нога** (`gsm/`): `GsmCallManager` (Telecom + `ConnectionService`),
  `GsmCallService`, `CallConnectionService`.
- **Мост** (`bridge/CallOrchestrator.kt`): сопряжение SIP-ноги и сотовой ноги.
- **Сервисы** (`service/`): `GatewayService` (foreground), `BootReceiver`,
  `CallLogStore`.
- **UI**: `MainActivity` + XML-разметка, журнал звонков, настройки.
- **Аудио-профили** (`DeviceProfile.kt`): Qualcomm SM6150/SM7150 (incall_music),
  Exynos 9820 (ABOX), Google Tensor GS201 (AoC), MediaTek MT6735/MT6753 (BGS-player),
  generic-профили.
- **Magisk-модуль** (`magisk/`): привилегированная установка APK, privapp-permissions,
  снятие лимитов конкурентности аудио, `tinymix`/`tinycap` под обе ABI,
  `sepolicy.rule` для Android 16+.
- **Инструменты**: `tools/check-device.sh` — проверка, есть ли на устройстве
  цифровой аудио-путь (проверка audio policy, mixer-контролов, HAL).

Проверено сборкой: `assembleDebug` + `testDebugUnitTest` — BUILD SUCCESSFUL, APK 6.4 МБ.

## 4. Архитектура

```
   ┌──────────────┐   SIP/TLS + RTP/G.722   ┌────────────────────┐
   │   FreePBX    │◄───────────────────────►│  Android (root)     │
   │  (Asterisk)  │                          │                    │
   └──────┬───────┘                          │  SipClient/Gateway │
          │                                  │        │           │
          │ PSTN-транк                       │  CallOrchestrator  │
          │                                  │        │           │
   ┌──────▼───────┐   сотовый канал (SIM)   │  ┌─────▼─────┐     │
   │ PSTN / SIP   │◄───────────────────────►│  │GsmCallMgr │     │
   │  оператор    │                          │  └─────┬─────┘     │
   └──────────────┘                          │        │           │
                                            │  ┌─────▼──────┐    │
                                            │  │DeviceProfile│    │
                                            │  │ (чип-профиль)│   │
                                            │  └────────────┘    │
                                            └────────────────────┘
```

Маршрутизация — через SIP-заголовки `X-GSM-From` и `X-GSM-To` (строгий `+E.164`),
которые обязательны в обе стороны. Request-URI только адресует SIP-аккаунт
и не является запасным маршрутом.

Инъекция аудио оператора в аппаратный аплинк модема — через чип-специфичный
профиль: `incall_music` (Qualcomm), ABOX (Exynos), BGS-player (MediaTek).

## 5. Работа с устройствами

### 5.1 Общее

- `DeviceProfile.detect()` определяет профиль по `ro.board.platform`, затем
  `ro.soc.model`, затем `ro.product.board`. Профили добавляются по SoC, а не по
  названию модели.
- Профилировщик не вправе менять прошивку. Допустимы только: Magisk-модуль
  (systemless overlay) и `su`-команды.
- Кастомная прошивка с заменённым IMEI — не трогаем; обновление Android не требуется.

### 5.2 Cubot X90 — MediaTek Helio G99 (MT6789) и X100 — Dimensity 7025 (MT6855V)

Оба устройства — MediaTek, поэтому аудио-стратегия одна на оба.

Upstream содержит профиль MediaTek MT6735/MT6753, работающий через BGS-player
модема: HAL-параметры `Set_BGS_UL_Mute` / `Set_BGS_DL_Mute` /
`Set_SpeechCall_UL_Mute` / `Set_SpeechCall_DL_Mute`, захват через
`AudioSource.VOICE_DOWNLINK`, воспроизведение с `USAGE_VOICE_COMMUNICATION`.
Но он проверен только на Android 8.1 (MT6735/MT6753) — это другое поколение HAL,
чем MT6789/MT6855V на Android 15. Поэтому профиль для Cubot — гипотеза,
проверяемая на устройстве, а не готовый факт.

Кандидаты инъекции в аплинк, по убыванию приоритета:

1. **BGS-player через HAL-параметры** — как в профиле `mt6753()`. Требует, чтобы
   HAL MT6855V/MT6789 отдавал поток в `AudioALSAPlaybackHandlerVoice → BGSPlayer`.
2. **`incall_music` mixPort → `Telephony Tx`** — маршрут, выраженный в
   `/vendor/etc/audio_policy_configuration.xml`
   (`AUDIO_OUTPUT_FLAG_INCALL_MUSIC` + `AUDIO_DEVICE_OUT_TELEPHONY_TX`).
   Референсный проект зафиксировал, что у POCO C65 (Helio G99) этот порт
   в политике присутствует, но результат не был определён (устройство без root).
   Проверяется на устройстве без root: `grep incall_music
   /vendor/etc/audio_policy_configuration.xml`.
3. **AOSP-перехват аудио разговора** (`CALL_AUDIO_INTERCEPTION`,
   `CAPTURE_VOICE_COMMUNICATION_OUTPUT`) — стандартный механизм AOSP 12+.
   Проба готовности:
   `adb shell "dumpsys media.audio_policy | grep -E 'TELEPHONY_TX|TELEPHONY_RX'"`.
4. **`tinycap` с PCM-канала telephony-rx** — чтение абонента напрямую из PCM
   (`Incall Capture Stream0`), рабочий путь на Tensor. Фолбэк для захвата.

Ограничения, подтверждённые в AOSP:

- `CAPTURE_AUDIO_OUTPUT` имеет protection level `signature|privileged` и **не
  является runtime-разрешением**: `pm grant` бросает `SecurityException`
  независимо от root. Единственный поддерживаемый путь — установка APK как
  системного priv-app с allowlist-файлом `privapp-permissions-*.xml`.
  Magisk-модуль upstream это уже делает.
- Захват сотовой ноги нужно делать через `VOICE_DOWNLINK`, а не `VOICE_CALL`:
  `VOICE_CALL` смешивает аплинк, куда мы инъецируем голос оператора, и агент
  слышит сам себя.
- `ro.telephony.same_phone_use_voice_call` — авторитетных источников не найдено,
  на этом свойстве не строим.

Отдельно проверяется на устройстве: тип сотового вызова. При **VoWiFi (WFC)**
сотовой ноги нет вообще — HAL-аудио разговора отсутствует, шлюз работать не
будет; WFC нужно отключить на SIM. При IMS/VoLTE голос идёт пакетным вызовом,
и HAL-путь может отличаться от классического CS. Замена IMEI может помешать
провижингу IMS у оператора — это либо фолбэк на CS (нам полезно), либо потеря
голоса (неприемлемо).

### 5.3 Порядок проверки на устройстве

До написания профиля на устройстве выполняется `tools/check-device-cubot.sh`
(адаптация `tools/check-device.sh` под MediaTek), который проверяет:

- `ro.board.platform`, `ro.soc.model`, `ro.product.board` — определение SoC;
- наличие `incall_music` и `Telephony Tx` в audio policy (без root);
- наличие `TELEPHONY_TX`/`TELEPHONY_RX` в `dumpsys media.audio_policy`;
- HAL-параметры BGS через `su`;
- тип сотового вызова (CS / IMS / WFC) по `logcat -b radio`.

Результат однозначно определяет, какой из кандидатов §5.2 реализуем, а какой
остаётся фолбэком. Без этого профиль пишется вслепую.

## 6. Сторона FreePBX

Сервер сейчас не трогаем — конфиг готовится как готовый к вставке.

### 6.1 Фактический контракт заголовков (проверено аудитом кода)

- Заголовков `X-GSM-From` / `X-GSM-To` в коде **нет**. Единственный
  пользовательский заголовок, который читает шлюз, — `X-GSM-Forward`: это
  номер, который шлюз должен набрать через SIM. Заголовок добавляет
  dialplan на стороне PBX.
- Номер вызывающего сотовой стороны передаётся стандартными заголовками SIP:
  `P-Asserted-Identity` → `Remote-Party-ID` → `From`.
- `PJSIP_HEADER(add,...)` в обычном контексте dialplan добавляет заголовок
  **вызывающему** каналу и не попадает в исходящий INVITE, который создаёт
  `Dial()`. Чтобы заголовок ушёл шлюзу, нужен `PJSIP_INHERITABLE_HEADER(add,...)`
  либо pre-dial-обработчик (`Dial(PJSIP/<ext>,60,b(handler^s^1))`), где
  `PJSIP_HEADER` работает на канале вызываемого.
- Плоского SIP-over-TCP в приложении нет: транспорт только `udp` или `tls`.
- На pjsip-эндпоинте обязательно `direct_media=no`: шлюз не пересогласовывает
  медиа при re-INVITE и продолжает слать RTP на запомненный адрес.
- RTCP не реализован и `a=rtcp` не объявляется: RTCP Asterisk уходит на
  RTP-порт+1 и просто не отвечается. Для голоса безвредно.

### 6.2 PJSIP-эндпоинт

Требования: digest-аутентификация, `transport=tls`, один контакт, контекст
для входящих вызовов, кодеки `g722` и/или `alaw`/`ulaw`, `direct_media=no`,
`identify` по адресу регистрации (для входящих INVITE от самого шлюза).

Критично: TLS-сертификат Asterisk должен содержать SAN с FQDN (или IP),
совпадающим с именем, по которому телефон подключается — проверка имени хоста
в приложении включена и не отключается.

### 6.3 Dialplan

- **PBX → SIM**: агент звонит абоненту, dialplan добавляет
  `X-GSM-Forward: <номер>` и звонит `Dial(PJSIP/<ext>,60)`.
- **SIM → PBX**: шлюз сам шлёт INVITE на расширение, номер вызывающего берётся
  из `From`/`P-Asserted-Identity`, dialplan звонит в PSTN-транк.
- В `Dial()` для GSM-замыкания нужно не меньше ~60 с: время установки сотовой
  ноги больше, чем длительности SIP-транзакции.

### 6.4 Кодеки

Аудит выявил два дефекта, ломающих стыковку со стоковым FreePBX:

1. дефолтное предложение было только G.722 (`9 101`) → на эндпоинте с
   `allow=alaw` прилетает `488 Not Acceptable`;
2. `PCMU` (payload 0) был объявлен в SDP, но кодека µ-law в проекте нет —
   при выборе PT 0 шлюз слал бы байты G.722 с тегом PT 0, то есть шум.

Оба исправляются в форке: реализуется µ-law, дефолтное предложение становится
`9 8 0 101`, и SDP ответа объявляет реально согласованный payload.

### 6.5 Digest-аутентификация

Upstream считает только `MD5(HA1:nonce:HA2)`, игнорируя `qop`. Против
Asterisk это работает (pjproject проверяет по qop клиента), но не соответствует
RFC и не сработает на серверах, требующих qop. В форке добавляется
`qop=auth` с `cnonce`/`nc`, поведение без qop сохраняется.

## 7. Сборка и поставка

- Локальная сборка: JDK 17 (`/opt/homebrew/opt/openjdk@17`), Android SDK
  (`platforms;android-35`, `build-tools;35.0.0`, `platform-tools`),
  Gradle 8.5 через wrapper, AGP 8.2.2.
- Выход v1: `app-debug.apk`/`app-release.apk` и `gsm2sip-magisk.zip`
  (модуль Magisk с APK, privapp-permissions, `tinymix`/`tinycap` под обе ABI,
  `sepolicy.rule`).
- Release подписывается тем же debug-ключом — намеренно, чтобы release-APK
  заменял debug-APK внутри модуля без конфликта подписи.

## 8. Проверка

Что проверяется на машине сборки:

- компиляция и юнит-тесты (`testDebugUnitTest`),
- сборка Magisk-модуля,
- статическая проверка PJSIP/dialplan-конфигов.

Что проверяется только на устройстве (вне нашей среды):

- выбор аудио-профиля и наличие цифрового пути,
- фактическое двустороннее аудио в сотовом вызове,
- регистрация в FreePBX и прохождение INVITE в обе стороны,
- поведение при выключенном экране и в фоне.

## 9. Вне объёма v1

- SMS over SIP (в upstream есть, отключаем).
- Linux-демон `gsm2sipd`.
- Поддержка Android 7–11 (minSdk 31).
- Push-пробуждение (используется foreground service).

## 10. Риски

| Риск | Влияние | Митигация |
|---|---|---|
| На MT6855V/MT6789 нет ни BGS, ни `incall_music` | Оба Cubot деградируют до акустики | `check-device-cubot.sh` до написания профиля; в UI видно активный профиль |
| VoWiFi включён оператором | Сотовой ноги нет — мост невозможен | Отключить WFC на SIM; проверять тип вызова по `logcat -b radio` |
| IMS/VoLTE-вызов идёт пакетным, HAL-путь иной | Захват/инъекция не работают | Проверка на реальном вызове до боевого включения |
| Замена IMEI ломает провижининг IMS | Нет голоса вообще | При отказе — SIM с «чистым» IMEI; прошивку не трогаем |
| Asterisk TLS-сертификат без нужного SAN | Не регистрируется | FQDN + SAN обязательны; заранее проверить на сервере |
| `X-GSM-*` не доходят на newer Asterisk | Звонок не маршрутизируется | Pre-dial-обработчик на целевом канале (не наследование) |
| `pm grant` не даёт `CAPTURE_AUDIO_OUTPUT` | Нет захвата сотовой ноги | Только priv-app + allowlist через Magisk-модуль (уже в upstream) |
| Приложение убито системой в фоне | Нет входящих SIP | Foreground service + `WAKE_LOCK` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `BootReceiver` |
