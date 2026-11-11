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
| `minSdk` | 31 (Android 12) | Выбрано пользователем. Отличается от исходной постановки «7–15»: Android 7–11 вне контура |
| `targetSdk` / `compileSdk` | 35 / 35 | Целевые устройства — Android 15 |
| Устройства | Cubot X90 (MediaTek Helio G99), Cubot X100 (Unisoc T606) | Роут (Magisk), кастомная прошивка, IMEI заменён. Прошивку не трогаем, Android не обновляем |
| X100 (Unisoc) | Исследовать цифровой путь; при неудаче — акустический фолбэк | В upstream профиля Unisoc нет |
| Объём v1 | Только голос в обе стороны + конфиг FreePBX | SMS-over-SIP и Linux-демон исключены из первой версии |
| Сигнализация | Прямой SIP over TLS + постоянный foreground service | Без своего сервера и без push |

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

### 5.2 Cubot X90 — MediaTek Helio G99

Upstream уже содержит профиль MediaTek MT6735/MT6753, работающий через
BGS-player модема: HAL-параметры `Set_BGS_UL_Mute` / `Set_BGS_DL_Mute` /
`Set_SpeechCall_UL_Mute` / `Set_SpeechCall_DL_Mute`, захват через
`AudioSource.VOICE_DOWNLINK`, воспроизведение с `USAGE_VOICE_COMMUNICATION`.

Требуется: подтвердить на Helio G99 (mt6768) наличие BGS-пути в HAL и
`incall_music` в `/vendor/etc/audio_policy_configuration.xml`, затем завести
профиль `mt6768()` на базе `mt6753()` с проверенными значениями.

### 5.3 Cubot X100 — Unisoc T606

Цифрового пути в upstream нет. План:

1. Проверить `tools/check-device.sh` на устройстве: наличие mix-порта инъекции
   в audio policy, HAL-контролов, поддержка `VOICE_DOWNLINK`.
2. Если путь есть — завести профиль `unisoc()`.
3. Если нет — акустический режим (общий микрофон/динамик, эхо, сниженное качество),
   с явной пометкой в логе и в UI, какой профиль активен.

Выбор между путями делается по факту проверки на устройстве, а не по предположению.

## 6. Сторона FreePBX

Сервер сейчас не трогаем — конфиг готовится как готовый к вставке.

### 6.1 PJSIP-эндпоинт

Требования: digest-аутентификация, `transport=tls`, один контакт, `context`
для входящих вызовов, `allow=!all,alaw,ulaw` (G.722 подключается при желании),
`message_context` не нужен (SMS вне объёма v1).

Критично: TLS-сертификат Asterisk должен содержать SAN с FQDN, совпадающим с
именем, по которому телефон подключается. Подключение по IP с валидным
сертификатом не пройдёт проверку имени — нужен FQDN.

### 6.2 Dialplan

- **Исходящий через шлюз** (PBX → SIM): `PJSIP_HEADER(add,X-GSM-From)` и
  `PJSIP_HEADER(add,X-GSM-To)` задаются на канале вызываемого, затем `Dial(PJSIP/<ext>,60)`.
  На Asterisk 22+ наследование заголовков стало строже, поэтому заголовки
  добавляются pre-dial-обработчиком на целевом канале, а не `Set` до `Dial`.
- **Входящий от SIM** (SIM → PBX): телефон сам ставит INVITE на фиксированное
  расширение; dialplan маршрутизирует его в PSTN-транк.
- В `Dial()` для GSM-замыкания нужно не меньше ~60 с: время установки сотовой
  ноги больше, чем SIP-транзакции.

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
| На Helio G99 нет BGS-пути | X90 деградирует до акустики | Проверка `check-device.sh` до записи профиля; в UI видно активный профиль |
| На Unisoc T606 нет цифрового пути | X100 деградирует до акустики | Явный фолбэк и предупреждение |
| Asterisk TLS-сертификат без нужного SAN | Не регистрируется | FQDN + SAN обязательны; заранее проверить на сервере |
| `X-GSM-*` не доходят на newer Asterisk | Звонок не маршрутизируется | Pre-dial-обработчик на целевом канале (не наследование) |
| IMEI-подмена на устройстве вызывает вопросы оператора | Звонки не проходят | Не трогаем прошивку; при отказе оператора — замена SIM с «чистым» IMEI |
| Приложение убито системой в фоне | Нет входящих SIP | Foreground service + `WAKE_LOCK` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `BootReceiver` |
