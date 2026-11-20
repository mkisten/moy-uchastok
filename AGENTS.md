# AGENTS.md — основной контекст проекта «Мой участок» (бывш. «Обход домов»)

Файл для ИИ-агентов: держит актуальный контекст, чтобы не перечитывать весь код.
Обновляй его при значимых изменениях архитектуры, сборки или статуса.

## Что это за проект

Android-приложение «Мой участок» — автономный учёт обходов жилых домов (многоквартирных
и частного сектора): участки → подъезды → этажи → квартиры/дома с визуальной схемой
подъезда (плитки с цветовыми статусами). Реализовано по ТЗ v3.0 (краткая версия — README.md).
Работает полностью офлайн, данные локально (Room) + опциональная синхронизация через
Яндекс Диск / Google Таблицу. minSdk 26, target/compile 35. Пакет `ru.obkhod.app`.
Репозиторий: https://github.com/mkisten/moy-uchastok (gh CLI залогинен как mkisten).

## Стек и ключевые версии

| Компонент | Версия |
|---|---|
| Kotlin | 2.1.20 |
| AGP | 8.7.2 |
| Gradle | 8.14.3 (wrapper `gradlew`) |
| KSP | 2.1.20-1.0.31 (⚠ 1.0.29 не существует на Maven Central) |
| Compose | BOM 2024.12.01 (фактически foundation 1.11.2) |
| Room | 2.6.1 (KSP-компилятор) |
| OkHttp | 4.12.0 (WebDAV-клиент) |
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
  OkHttp добавлен (WebDAV) — `HttpURLConnection` не поддерживает MKCOL.
- После КАЖДОЙ сборки релиза проверять версию: `aapt dump badging` (build-tools/35.0.0) —
  были случаи «тихо не сработавших» бампов версии с релизом старого бинаря.
- Ассеты релизов именовать с версией (`#MoyУchastok-vX.apk` в gh; rename через API PATCH,
  только ASCII — кириллица в `-f name=` ломается на Windows-консоли). Устаревшие ассеты
  старых релизов удалять через API. GitHub /releases/latest кэшируется на минуты.

Команды:
```bash
JAVA_HOME="C:/Users/mkisten/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2" ./gradlew assembleRelease
JAVA_HOME="C:/Users/mkisten/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2" ./gradlew testDebugUnitTest
```

## Архитектура (MVVM)

```
app/src/main/java/ru/obkhod/app/
├── MainActivity.kt          # Activity; SharedPreferences "nav_state" — возврат к последнему экрану (п.21 ТЗ)
├── data/
│   ├── db/                  # Room v7: сущности, DAO и контрольная точка team_sync
│   ├── remote/              # WebDavClient (OkHttp), YandexAuth (OAuth)
│   └── repo/                # AreaRepository, EntranceRepository, Models.kt, withTransactionCompat
├── export/                  # ExcelExporter, CsvExporter, ExportDataLoader
├── backup/                  # BackupManager (ZIP+JSON, проверка и восстановление)
├── sync/                    # TeamSync (журнал операций Apps Script), YandexSync (WebDAV)
├── update/                  # UpdateChecker (GitHub Releases) + UpdateInstaller (DownloadManager)
└── ui/
    ├── AppContainer.kt, AppNavHost.kt, ViewModelFactory.kt
    ├── theme/, areas/, create/, area/, entrance/, apartment/, deleted/, edit/, settings/,
    ├── cloud/               # TeamSyncScreen (Apps Script), YandexSyncScreen (Яндекс)
    └── theme/ThemePrefs.kt  # тема в DataStore
```

### Маршруты навигации (AppNavHost.Routes)
`areas` · `create` · `area/{areaId}` · `entrance/{entranceId}` · `deleted` ·
`settings?areaId={areaId}` · `addEntrance/{areaId}` · `editEntrance/{entranceId}` ·
`cloud` · `yandex` (аргументы — NavType.LongType).

### БД (Room, version 7)
`areas` → `entrances` (CASCADE) → `floors` (CASCADE) → `apartments` + `visit_history`.
Миграции: v2 `label` (текстовые номера «1А»), v3 `type` участка (MULTI|PRIVATE),
v4 `uuid` всем строкам + `updatedAt` подъездам/этажам, v5 `hasChild`, v6 hasChild=1 →
status='CHILD', v7 `team_sync` для курсора и ожидающего отправки пакета. Квартиры: `status`, `phone`, `comment`, `lastVisitAt`, `deletedAt`
(soft delete), `label`, `hasChild` (legacy, не используется UI — заменён статусом CHILD).
Индексы на все FK, status, apartmentNumber, deletedAt.

## Статусы (Status enum) и значки плиток

`NONE, REFUSED, NO_ONE_HOME, REPEAT, NON_RESIDENTIAL, ROUGH_REFUSED, CHILD`.

Цвета плиток: REFUSED — светло-красный, ROUGH_REFUSED — ярко-красный E53935,
NO_ONE_HOME — жёлтый, REPEAT — зелёный, NON_RESIDENTIAL — серый, CHILD — голубой
90CAF9, NONE — светло-серый.

Значки плитки (все неактивные, только индикация):
- 📞 кружок с трубкой слева сверху — телефон цифрами (≥5 цифр, без букв; пробелы/+/дефисы
  допустимы). Телефон текстом («д.5» и т.п.) — серая ПЕРЕЧЁРКНУТАЯ трубка;
- «i» справа сверху — только при заполненном комментарии;
- 🧒 ChildCare слева снизу (тёмно-синий) — статус CHILD;
- галочки «обработано» убраны по запросу пользователя (2026-11-18).

Меню короткого тапа (BottomSheet): «Информация» (статус, дата, редактируемые телефон и
комментарий с звонилкой при заполненном номере, кнопка «Сохранить», инлайн-история),
«Сброс», статусы «Отказ / Нет дома / Повтор / Не жилая», «Грубый отказ» (ярко-красный
full-width), «Ребенок один дома» (голубой, full-width). Меню долгого тапа: «Изменить
квартиру/дом», «Переместить на этаж» (multi), «Удалить».

## Ключевые доменные решения (не менять без необходимости)

- **Удаление участка** — каскадное. **Квартира/дом** — мягкое (`deletedAt`), восстановление
  через экран «Удалённые» на прежний этаж; snackbar «Отменить» после удаления.
- **«Без обхода»** = нет записей в `visit_history`.
- **Статус** → запись истории (VISIT/RESET/CORRECTION) + `lastVisitAt = now`. История
  показывает дату с днём недели (формат «EEE, dd.MM.yyyy HH:mm», Locale ru, детерминированный
  массив Пн…Вс). Заметка/перемещение/просмотр НЕ меняют статус и дату.
- **Редактирование квартиры**: «Изменить квартиру» (долгий тап) — смена этажа и/или номера
  (текстовый номер «1А» в `label`); вопрос «Пересчитать все номера подъезда?» — Да:
  перенумерация от минимального номера, CHILD-статусы не расходуют числовой слот; Нет:
  меняется только эта квартира, дубликат отображаемого номера — ошибка.
- **Мастер создания**: номер подъезда вручную; «этажей × квартир на этаже»; FreeIntField
  (свободный ввод). **Добавление этажа** — нумерация с последнего существующего номера
  подъезда (включая числовую часть label).
- **Частный сектор**: PRIVATE-участок = улица; дома в свободном порядке («Добавить дом»,
  номер с буквой), хранятся как квартиры на служебном подъезде № 1 / этаже 1, FlowRow по 4
  в ряд, по возрастанию. Все операции как у квартир; перемещение скрыто. Открытие — сразу
  на схему домов; старт всегда со списка участков (иначе «Назад» закрывал приложение).
  Терминология для PRIVATE — «дом»; «Информация» содержит редактируемые телефон/комментарий.
- **Трубка/«i»**: трубка — только числовые телефоны; «i» — только заполненный комментарий.
- **Информация (окно)**: поле телефона принимает номер или текстовую пометку («Нет
  телефона»). Звонилка доступна для распознанного номера. «Сохранить» закрывает
  окно после успешной записи телефона и комментария. При ошибке введённый текст
  остаётся в окне. История отображается в этом же окне.
- **Синхронизация Яндекс Диск** (основной, WebDAV/OkHttp): вход через Яндекс ID (OAuth,
  подтверждение по телефону, код вставляется в приложение) или пароль приложения; общая
  папка «Мой участок» (шаринг Яндекс Диска по email); слияние по uuid (история — union,
  поля — «последний выигрывает»); автосинхронизация при запуске.
- **Синхронизация команды**: Google Apps Script + закрытая Google Таблица с журналом,
  одноразовые приглашения, отдельный ключ устройства в `noBackupFilesDir`, обмен UUID
  и изменёнными полями. Предыдущий режим Google OAuth/AppAuth удалён. Развёртывание
  описано в README. При подключении команды Яндекс Диск и замена локальной базы недоступны.
- **Обновления**: UpdateChecker (GitHub Releases, SemVer) + UpdateInstaller (DownloadManager
  + автозапуск установщика + MANAGE_UNKNOWN_APP_SOURCES). Ассеты именуются с версией.
- **Яндекс OAuth «Приложение не установлено»**: из-за платформы Android в oauth.yandex.ru;
  НЕ добавлять её, оставить только Web-сервисы + verification_code redirect.
  401 после входа = не добавлен доступ «Яндекс Диск → Запись во всех папках и на диске»
  (или токен без новых прав — перевыпустить входом).
- Release APK подписан debug-ключом специально (для установки); для Google Play — свой ключ.

## Текущий статус (2026-11-20)

- Командная синхронизация через Apps Script проверена в BlueStacks на двух изолированных
  базах Room и локальном стенде, который исполняет `server/team-sync/Code.gs`. Проверены
  миграция 6→7, повтор пакета, отмена визита, конкурентная правка и восстановление квартиры.
  Развёртывание в реальной Google Таблице ещё требуется.
- После записи визита можно отменить последнюю отметку из уведомления. Отмена убирает
  эту запись из истории и возвращает прежний статус и дату обхода одной транзакцией.
- Корзина показывает адрес, подъезд и этаж. Восстановление проверяет отображаемый номер
  на исходном этаже, поэтому номера 25 и 25А считаются разными.

- Версия 1.5.2 / versionCode 30 содержит запись повторных визитов с тем же
  статусом, поиск по номеру дома или квартиры и видимый активный фильтр.
  ZIP и Яндекс Диск используют общий предпросмотр восстановления. После подтверждения
  приложение сохраняет предыдущую базу в `files/before_restore.zip`, затем заменяет данные
  в транзакции. Страховочная копия доступна из настроек.
- APK v1.5.2 для обновления: `MoyUchastok-v1.5.2.apk`. Ссылка на версию:
  https://github.com/mkisten/moy-uchastok/releases/tag/v1.5.2.
- Телефон (FEPV9XTC4DSOTOHU, MIUI): блокирует adb input — UI-тесты руками пользователя;
  BlueStacks (127.0.0.1:5555) — для автоматизации.
- ⚠ Ручная проверка: обновление по цепочке релизов на телефоне; XLSX в Excel/LibreOffice;
  ACTION_DIAL; восстановление бэкапа на другом устройстве.
- README.md — сборка/установка + процесс выпуска версий; AGENTS.md — этот файл.
