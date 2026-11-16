# AGENTS.md — основной контекст проекта «Мой участок» (бывш. «Обход домов»)

Файл для ИИ-агентов: держит актуальный контекст, чтобы не перечитывать весь код.
Обновляй его при значимых изменениях архитектуры, сборки или статуса.

## Что это за проект

Android-приложение «Обход домов» — автономный учёт обходов жилых домов: участки → подъезды →
этажи → квартиры с визуальной схемой подъезда (плитки квартир с цветовыми статусами).
Реализовано по ТЗ v3.0 (полный ТЗ был в диалоге 2026-11-12; краткая версия требований — README.md).
Работает полностью офлайн, все данные локально (Room). minSdk 26, target/compile 35.
Пакет: `ru.obkhod.app`. Репозиторий: https://github.com/mkisten/moy-uchastok (gh CLI залогинен как mkisten).

## Обновления и релизы

- Проверка обновлений: `update/UpdateChecker.kt` — при открытии главного экрана (Dispatchers.IO)
  запрашивает GitHub API releases/latest, сравнивает tag_name (v1.2.3) с BuildConfig.VERSION_NAME
  через isNewer (SemVer); если новее — AlertDialog «Доступно обновление» с changelog и кнопкой
  «Обновить» (ACTION_VIEW на browser_download_url APK-ассета). INTERNET permission добавлен
  только для этого; ошибки сети тихо игнорируются.
- Процесс релиза: поднять versionCode/versionName в app/build.gradle.kts → assembleRelease →
  commit/push → `gh release create vX.Y.Z app/build/outputs/apk/release/app-release.apk
  --title ... --notes ...`. Подробно в README.md. Текущая версия: 1.0.1 (versionCode 2).

## Стек и ключевые версии

| Компонент | Версия |
|---|---|
| Kotlin | 2.1.20 |
| AGP | 8.7.2 |
| Gradle | 8.14.3 (wrapper `gradlew`) |
| KSP | 2.1.20-1.0.31 (⚠ 1.0.29 не существует на Maven Central) |
| Compose | BOM 2024.12.01 (фактически foundation 1.11.2) |
| Room | 2.6.1 (KSP-компилятор) |
| Apache POI | 5.2.5 (+ xmlbeans 5.2.0, exclude xmlbeans из poi-ooxml) |
| Навигация | navigation-compose 2.8.5 |

## Сборка на этой машине (важно!)

- Системного gradle на PATH нет — использовать `./gradlew` (Windows Git Bash) или кэш:
  `C:/Users/mkisten/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle`
- `JAVA_HOME` по умолчанию указывает на JDK 25 → AGP падает с загадочной ошибкой
  «What went wrong: 25». **Обязательно** переопределять:
  `JAVA_HOME="C:/Users/mkisten/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2"`
- Android SDK: `C:/Users/mkisten/AppData/Local/Android/Sdk` (прописан в `local.properties`).
- R8/POI: dontwarn-правила (org.osgi, java.awt, log4j и др.) уже в `app/proguard-rules.pro`;
  при новых Missing class — источник правил `app/build/outputs/mapping/release/missing_rules.txt`.
- Compose 1.11.2: `clickable(interactionSource, onLongClick)` удалён → использовать
  `combinedClickable` (ExperimentalFoundationApi). У POI — `setCellStyle()`, не property.

Команды:
```bash
JAVA_HOME="C:/Users/mkisten/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2" ./gradlew assembleRelease
JAVA_HOME="C:/Users/mkisten/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2" ./gradlew testDebugUnitTest
```

## Архитектура (MVVM)

```
app/src/main/java/ru/obkhod/app/
├── MainActivity.kt          # Activity; SharedPreferences "nav_state" — возврат к последнему экрану (п.21 ТЗ)
├── data/db/                 # Room v1: entities.kt (5 таблиц), daos.kt, AppDatabase.kt
├── data/repo/               # AreaRepository (создание участка транзакцией), EntranceRepository
│                            # (статусы/заметки/история/перемещение/soft-delete), Models.kt, withTransactionCompat
├── export/                  # ExcelExporter (XLSX, 3 листа), CsvExporter, ExportDataLoader
├── backup/                  # BackupManager: ZIP+backup.json (org.json), валидация, транзакционное восстановление
└── ui/
    ├── AppContainer.kt      # синглтон-DI (репозитории), AppNavHost.kt (маршруты), ViewModelFactory.kt
    ├── theme/, areas/, create/, area/, entrance/, apartment/, deleted/, edit/, settings/
```

### Маршруты навигации (AppNavHost.Routes)
`areas` · `create` · `area/{areaId}` · `entrance/{entranceId}` · `deleted` ·
`settings?areaId={areaId}` · `addEntrance/{areaId}` · `editEntrance/{entranceId}`
(аргументы — NavType.LongType).

### БД (Room, version 3)
`areas` (type: MULTI|PRIVATE, v3: ALTER TABLE areas ADD COLUMN type TEXT NOT NULL DEFAULT 'MULTI',
MIGRATION_2_3) → `entrances` (CASCADE) → `floors` (CASCADE) → `apartments` + `visit_history`.
Квартиры: `status`, `phone`, `comment`, `lastVisitAt`, `deletedAt` (soft delete),
`label` (v2, MIGRATION_1_2) — текстовый отображаемый номер вида «1А»; числовая часть label
используется для сортировки. Индексы на все FK, status, apartmentNumber, deletedAt.
Уникальность номеров квартир в пределах участка проверяется в домен-логике (не constraint).

## Ключевые доменные решения (не менять без необходимости)

- **Удаление участка** — каскадное (всё вместе с историей). **Квартира** — мягкое
  (`deletedAt`), восстановление через экран «Удалённые» на прежний этаж.
- **«Без обхода»** = нет записей в `visit_history` (отличается от сброшенного статуса).
- **Статус** → запись истории (VISIT/RESET/CORRECTION) + `lastVisitAt = now`.
  Заметка/перемещение/просмотр НЕ меняют статус и дату. Сброс статуса сохраняет дату.
- **Перемещение** — только внутри подъезда, дубликат номера на этаже запрещён,
  подтверждение перед выполнением (можно отменить).
- **Редактирование квартиры** (2026-11-13, запрос пользователя; исправлено во 2-й итерации):
  «Изменить квартиру» в долгом нажатии — смена этажа и/или номера (номер может быть
  текстовым «1А», хранится в `label`, числовая часть — в `apartmentNumber` для сортировки).
  Перед сохранением вопрос «Пересчитать все номера подъезда?» — Да: перенумерация снизу
  вверх от минимального существующего номера подъезда (не с 1!); помеченные квартиры
  («29А») НЕ расходуют числовой номер — встают после своей числовой части, последующие
  числовые сдвигаются (29, 30→29А, 31 ⇒ 29, 29А, 30); Нет: меняется только эта квартира,
  при дубликате отображаемого номера на этаже пользователь получает ошибку.
- **Мастер создания** (уточнение 2026-11-13): номер подъезда задаётся вручную; этажность
  задаётся двумя полями «этажей × квартир на этаже» (плитки строятся одинаково по этажам),
  per-floor настройка из ТЗ убрана по запросу пользователя; все числовые поля —
  FreeIntField со свободным вводом (можно стереть цифру без ввода новой).
- **Добавление этажа** — нумерация квартир продолжается с последнего существующего номера
  подъезда (включая числовую часть label), а не с 1.
- **Частный сектор** (2026-11-16, запрос пользователя): при создании участка выбирается тип
  (Многоквартирный дом / Частный сектор). PRIVATE-участок = улица; внутри в свободном порядке
  добавляются дома («Добавить дом», номер с буквой можно), хранятся как квартиры на служебном
  подъезде № 1 / этаже 1, отображаются FlowRow по 4 в ряд, сортировка по возрастанию (меньшие
  сверху). Все операции — как у квартир (статусы, заметки, история, фильтры, удаление,
  восстановление, экспорт, бэкап); перемещение между этажами скрыто. Открытие PRIVATE-участка
  идёт сразу на схему домов, а экран участка автоматически перенаправляет на неё (popUpTo
  inclusive — «Назад» ведёт к списку участков); кнопка «+ Подъезд» для PRIVATE скрыта.
  Служебный подъезд № 1 существует только в БД и нигде не виден пользователю. Вся терминология
  в UI для PRIVATE — «дом» (диалоги, меню, инфо-окно, история, заметка). «Изменить дом» —
  только номер, без выбора этажа и без вопроса о пересчёте (номер меняется сразу).
  На карточке главного экрана — бейдж категории «Частный сектор»/«Многоквартирный дом».
  ⚠ ВАЖНО (баг-фиксы 2026-11-16, проверено на BlueStacks): восстановление последнего экрана
  (nav_state) для PRIVATE-участка ведёт к СПИСКУ УЧАСТКОВ, а не на схему домов — иначе
  «Назад» со схемы закрывал приложение (у схемы нет родительского экрана). Тип участка
  определяется ТОЛЬКО по lastEntranceId через areaOfEntrance() — lastAreaId мог не сохраниться
  (переход в частный сектор идёт напрямую на схему, мимо экрана участка). Авто-перенаправление
  на экране участка для PRIVATE убрано (второй источник того же тупика). См. MainActivity.
  1.0.3: радикальное решение — старт ВСЕГДА с AREAS; восстановленный подъезд открывается
  в LaunchedEffect AREAS-композибла (AreasViewModel.loaded + restoreApplied rememberSaveable):
  navigate(area) → navigate(entrance) для MULTI, только entrance для PRIVATE. Back-stack
  полный: подъезд → участок → список. runBlocking в onCreate удалён. MIUI-телефон
  (FEPV9XTC4DSOTOHU) блокирует adb input injection — UI-тесты на телефоне делать руками
  пользователя, автоматизация только на BlueStacks (127.0.0.1:5555).
  Текущая версия 1.3.0 (versionCode 10), релиз v1.3.0 на GitHub.
  ⚠ ВАЖНО (урок 2026-11-16): версии в gradle не обновлялись с 1.1.0 (python-заменки без assert
  молча не срабатывали) — релизы v1.2.0–v1.2.2 содержали бинарь 1.1.0. Теперь после каждой
  сборки проверять версию через aapt dump badging. Ассеты релизов именуются с версией
  (МойУчасток-vX.apk, #label в gh release create/upload; rename через API PATCH assets).
  Устаревшие (не соответствующие версии) APK-ассеты из старых релизов удалять через API.
  GitHub /releases/latest кэшируется на минуты — не ждать его для тестов.
  Яндекс OAuth «Приложение не установлено»: причина — зарегистрированная платформа Android
  в oauth.yandex.ru переключает страницу в мобильный режим (попытка вернуть в приложение);
  для ручного код-флоу платформу Android нужно УДАЛИТЬ, оставить только Web-сервисы +
  redirect https://oauth.yandex.ru/verification_code. Направлено пользователю.

## Яндекс OAuth (вход через Яндекс ID, v1.2.2)

- Ключи Yandex OAuth: local.properties (yandexClientId / yandexClientSecret, gitignored) →
  BuildConfig.YANDEX_CLIENT_ID / YANDEX_CLIENT_SECRET. Client ID пользователя:
  e55f06b094354186b9efe1973c4de627 (приложение «Мой участок», платформа Android
  ru.obkhod.app + SHA256 debug-ключа, доступы: email/логин/портрет + Яндекс Диск чтение и запись).
- Файл data/remote/YandexAuth.kt: authorizeUrl() = oauth.yandex.ru/authorize?response_type=code
  &client_id=…&force_confirm=yes (страница входа Яндекса с подтверждением по телефону);
  пользователь получает «код подтверждения» и вставляет в приложение; exchangeCode(code)
  POST oauth.yandex.ru/token (grant_type=authorization_code, client_id+client_secret) →
  access/refresh токены; fetchLogin через login.yandex.ru/info. accessToken() — refresh-поток.
- WebDavClient переписан: конструктор (server, authHeader) + фабрики basic(login,password) /
  oauth(token) — Яндекс WebDAV принимает Authorization: OAuth <token>.
- YandexSyncScreen: OAuth-вход основной («Войти через Яндекс» → браузер → вставить код →
  «Подтвердить код»), пароль приложения — запасной (togglePasswordForm). WebDAV-клиент
  предпочитает OAuth-токен. Иконка сервиса для oauth.yandex.ru — icon_export/moy_uchastok_*.png.

## Синхронизация (Яндекс Диск / WebDAV, v1.2.0 — ОСНОВНОЙ способ совместного доступа)

- Причина: Google OAuth Client ID требовал настройки консоли + пересборку — пользователь
  попросил проще. WebDAV-вариант: вход логин + «пароль приложения» Яндекса прямо в приложении,
  без регистраций и пересборок.
- Файлы: `data/remote/WebDavClient.kt` (минимальный WebDAV GET/PUT/MKCOL/exists с Basic-auth;
  пути с кириллицей кодируются посегментно; exists через GET т.к. PROPFIND капризен),
  `sync/YandexSync.kt` (общий файл /Мой участок/data.json — снапшот формата бэкапа;
  merge remote→local по uuid LWW, затем выгрузка объединённого снапшота;
  restoreFromCloud = полная замена через BackupManager.restoreFromJson),
  `ui/cloud/YandexSyncScreen.kt` + `YandexSyncViewModel.kt` (вход, sync now, restore, disconnect),
  маршрут `yandex`, кнопка в настройках ПЕРВЫЙ пунктом (Google Таблица — второй, outlined).
  BackupManager: JSON теперь содержит uuid всех строк; добавлены buildSnapshot() и
  restoreFromJson(json) (restoreFrom(uri) → restoreFromJson).
- Автосинхронизация при запуске: тихий LaunchedEffect в AREAS-композибле AppNavHost
  (если в prefs yandex_sync есть login → IO sync, ошибки молча).
- Участники: админ шарит папку «Мой участок» штатным шарингом Яндекс Диска по email;
  участник подключает свой Яндекс-аккаунт тем же экраном. Пароль приложения создаётся
  на id.yandex.ru → Безопасность → Пароли приложений.

## Синхронизация (Google Таблица, v1.1.0)

- WebDavClient переписан на OkHttp v1.3.1: HttpURLConnection блокирует метод MKCOL
  ("Expected one of [OPTIONS, GET, ...] but was MKCOL") — OkHttp поддерживает любые методы.
  Яндекс OAuth: 401 после входа = в приложении oauth.yandex.ru не добавлен доступ
  «Яндекс Диск → Запись во всех папках и на диске» (cloud_app:write); старый токен новых
  прав не получает — перевыпустить входом заново.
- Файлы: `data/remote/GoogleAuth.kt` (AppAuth OAuth, redirect com.googleusercontent.apps.<CLIENT_ID>:/oauthredirect
  — intent-filter в манифесте через manifestPlaceholder googleRedirectScheme; манифест-плейсхолдер appAuthRedirectScheme
  нужен библиотеке AppAuth), `data/remote/GoogleRest.kt` (HttpURLConnection + retry при 401),
  `data/remote/TeamApi.kt` (создать/присоединить команду, permissions участников, чтение/запись листов),
  `sync/SheetSync.kt` (движок), `ui/cloud/CloudSyncScreen.kt` + `CloudSyncViewModel.kt`, маршрут `cloud`.
- CLIENT ID: `googleClientId` в gradle.properties (НЕ в git) → BuildConfig.GOOGLE_CLIENT_ID +
  manifestPlaceholders. Пока не задан — экран «Синхронизация» показывает подсказку (isConfigured()).
  Инструкция настройки OAuth в README. OAuth Client создаётся типа iOS, Bundle ID ru.obkhod.app.
- Слияние: ключ uuid у всех строк (Room v4, MIGRATION_3_4: ALTER + backfill lower(hex(randomblob(16))));
  newUuid() в data/repo ( top-level fun в AreaRepository.kt). Pull: remote.updated_at > local.updated_at →
  применить; Push: local.updated_at > lastPush (prefs cloud_sync) → appendRows. History — union по uuid.
  Удаление подъездов/этажей НЕ синхронизируется; удаление квартир — флаг deleted.
  Structure-лист: kind(area/entrance/floor), uuid, parent_uuid, num1, num2, name, deleted, updated_at, updated_by.
  Area type кодируется num1 (1=MULTI, 2=PRIVATE).
- На здоро́вом уме: не менять местами freshEntranceById (ключ id) и ByUuid-мапы; h.apartmentId → Long.
- **Название приложения** — «Мой участок» (strings.xml app_name + заголовок главного экрана),
  сменено с «Обход домов» по запросу пользователя 2026-11-16.
- **Плитки квартир** (2026-11-16): уменьшены (48×44dp, колонка этажа 32dp, кнопка удаления
  этажа убрана со строки — удаление этажа через «Редактировать подъезд»), чтобы 6 плиток
  помещались в ряд без прокрутки.
- **Восстановление бэкапа** — валидация версии/целостности → полная замена в одной транзакции.
  Backup JSON включает `label` и `type` (старые копии читаются: label=null, type=MULTI).
- Release APK подписан debug-ключом специально (для установки); для Google Play — свой ключ.
- **Дизайн-редизайн 2026-11-16** (приоритеты из рекомендаций, пп. 1–4): меню статусов и
  долгое нажатие — ModalBottomSheet с крупными статусными карточками (StatusCard/SheetAction);
  хаптик на долгом нажатии плитки; плитка — RoundedCornerShape(12dp), animateColorAsState,
  рамка только при drag; статистика+фильтры свёрнуты в одну строку «Обработано: X/Y» с
  раскрытием по иконке воронки (легенда цветов, счётчики на чипах фильтров); на карточке
  участка — кольцевой прогресс «visited/total» (CircularProgressIndicator) + цветные чипы
  статусов (StatusChip). Составляющие: EntranceScreen.kt, AreasScreen.kt.
  Вторая очередь (2026-11-16): динамический цвет Material You на Android 12+ (ObkhodTheme,
  dynamicColor=true), кастомная Typography (полужирные заголовки), «Добавить участок» —
  ExtendedFloatingActionButton, empty state с подсказкой на главном экране, snackbar
  «Отменить» после удаления квартиры/дома (EntranceViewModel.restore → restoreApartment).
  Темы (2026-11-16): настройки темы в DataStore (ui/theme/ThemePrefs.kt) — режим
  Системная/Светлая/Тёмная + переключатель «Цвет из обоев» (Material You), выбор в
  SettingsScreen, чтение в MainActivity.setContent; ObkhodTheme принимает darkTheme/dynamicColor.
  Шестерёнка настроек на главном экране (AreasScreen topbar → routes settings); раздел
  «О приложении» в SettingsScreen: «Мой участок», версия из BuildConfig.VERSION_NAME
  (buildConfig=true в gradle), разработчик Максим Кистень, maximkisten@gmail.com,
  +375 29 838 00 01.
  Третья очередь (2026-11-16): поиск дома по номеру в частном секторе (иконка лупы в тулбаре,
  фильтр по label + счётчик найденных); галочка «обработано» в углу плитки (по hasVisits —
  из истории, видна даже при сброшенном статусе); табулярные цифры (fontFeatureSettings="tnum")
  на плитках и в прогрессе карточек; skeleton-загрузка на главном экране (AreasViewModel.loaded,
  rememberInfiniteAlpha).

## Статус (2026-11-13, после доработок по замечаниям пользователя)

- ✅ Все модули ТЗ реализованы; assembleDebug/assembleRelease/testDebugUnitTest — SUCCESS.
- ✅ Доработки 2026-11-13: ручной № подъезда в мастере; «этажей × квартир на этаже»;
  свободный ввод чисел (FreeIntField); «Изменить квартиру» (этаж/номер «1А» + пересчёт);
  добавление этажа продолжает нумерацию с последнего номера подъезда; Room v2 + label.
- ✅ APK: debug `app/build/outputs/apk/debug/app-debug.apk`,
  release `app/build/outputs/apk/release/app-release.apk` (R8, debug-ключ).
- ✅ Unit-тесты: `app/src/test/.../LogicTest.kt`, 6/6 пройдены.
- ⚠ Осталось вручную на устройстве: сценарии п. 24 ТЗ (XLSX в Excel/LibreOffice,
  ACTION_DIAL, восстановление бэкапа на другом телефоне).
- README.md — инструкции по сборке/установке + таблица решений по ТЗ.
