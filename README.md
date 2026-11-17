# GSM ↔ SIP gateway (Android)

Рутованный Android-телефон (Cubot X90 / X100) регистрируется в FreePBX/Asterisk
как SIP-эндпоинт и мостит звонки между PBX и сотовной ногой (SIM) в обе стороны.

## Скачать

Готовые файлы — на вкладке **Releases** справа:
https://github.com/GenaLC/gsm2sip-gateway/releases

| Файл | Что это |
|---|---|
| `app-release.apk` | приложение-шлюз, ставится вручную на телефон |
| `gsm2sip-magisk.zip` | модуль Magisk (init.d + busybox) для root-окружения |

## Установка

1. Скачайте `app-release.apk` прямо на телефон.
2. Разрешите установку из этого источника: «Настройки → Приложения → Специальный
   доступ → Установка неизвестных приложений» → выберите браузер или файловый
   менеджер → **Разрешить**.
3. Установите APK, откройте приложение, вкладка **Settings**:
   SIP Server, SIP Port (6064), SIP Username, SIP Password, Own Number (SIM) —
   номер SIM в E.164 с плюсом. **Enable TLS** выключить, если сервер работает по UDP.
4. Выдайте приложению root (Magisk) и включите **Autoconnect on startup**.

APK подписан отладочным ключом — Android спросит разрешение на установку из
неизвестных источников. Для работы нужны root и SIM-карта в телефоне.

## Сборка

- Android Gradle Plugin 8.7.3, Gradle 8.9, compileSdk/targetSdk 35, minSdk 24 (Android 7–15)
- Транспорт: UDP (TLS — опционально), μ-law / A-law / G.722
- Настройка PBX: `docs/freepbx/SETUP.md` в репозитории проекта
