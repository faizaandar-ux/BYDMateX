# Плавающий виджет v2 — дизайн

**Дата:** 2026-04-22
**Версия приложения:** v2.3.9 (patch bump с v2.3.8)
**Статус:** дизайн согласован, ожидает implementation plan

## 1. Цель

Расширить текущий плавающий виджет (v2.3.3, `com.bydmate.app.ui.widget.*`) — добавить контекстные данные для водителя в реальном времени, сохранив компактность и неперекрытие навигатора на DiLink 5.0.

## 2. Scope

### Делаем
- Новый layout виджета: **190 × 64 dp** (вместо 220 × 78), 3 строки, 7 полей.
- Новое поле: **температура салона** (`insideTemp`, DiParsData param 25).
- Новое поле: **время в пути** (длительность активной поездки).
- Новое поле: **расход + тренд** с debounce-лагом, цвет по baseline-сравнению.
- Настройка **прозрачности** виджета (слайдер 30–100%, дефолт 100%).
- **Скрытие виджета в приложениях из blocklist** через `UsageStatsManager`. Пресет: YouTube + Настройки DiLink.
- Новые иконки для каждого поля (Material Icons Extended).

### НЕ делаем
- НЕ добавляем настройку «период расхода» — значение вычисляется автоматически (EMA → trip-avg).
- НЕ делаем sparkline или график на виджете — только число + стрелка тренда.
- НЕ трогаем логику drag/tap/drop-to-trash — они работают как в v2.3.3.
- НЕ меняем структуру `WidgetController` как singleton — остаётся object.
- НЕ меняем логику `BYDMateApp.WidgetLifecycleCallbacks` — по-прежнему прячем виджет когда BYDMate на переднем плане.

## 3. Layout виджета

**Размер:** 190 × 64 dp (3 строки, compact).
**Цвет рамки:** worst-wins из SOC (CRIT <15%, WARN <30%) + 12V (CRIT <12.0V, WARN <12.5V). Логика как в v2.3.3 (`widgetStatus()`).

### Структура (dp и sp примерные, финальные подкрутим по месту)

```
┌───────────────────────────────────────────┐
│  78%        421 км        ↓ 17.2          │  row 1 — энергия
│  (28sp)     (16sp)        (10sp)          │
│ ───────────────────────────────────────── │
│  ⏱ 24 мин            🚗 19°               │  row 2 — текущая поездка
│  (10sp)              (10sp)               │
│ ───────────────────────────────────────── │
│  🔋 12°              ⚡ 13.9 V            │  row 3 — служебное
│  (7sp)               (7sp)                │
└───────────────────────────────────────────┘
```

### Содержимое по строкам

| Строка | Поле | Размер | Цвет | Источник |
|--------|------|--------|------|----------|
| Row 1 | **SOC %** | Крупно | `socColor()` (Green/Yellow/Red) | `data.soc` |
| Row 1 | **Запас хода, км** | Среднекрупно | `TextPrimary` | `TrackingService.lastRangeKm` |
| Row 1 | **Расход + стрелка тренда** | Среднемелко | По тренду: Green/Grey/Yellow | `ConsumptionAggregator.state` |
| Row 2 | **Время в пути** | Средне | `TextPrimary`, icon `TextMuted` | `TrackingService.tripStartedAt` → minutes |
| Row 2 | **T° салона, °C** | Средне | `TextPrimary`, icon `TextMuted` | `data.insideTemp` |
| Row 3 | **T° батареи, °C** | Мелко | `TextMuted` | `data.avgBatTemp` |
| Row 3 | **12V, V** | Мелко | `TextMuted` | `data.voltage12v` |

### Иконки (Material Icons Extended, `Icons.Outlined.*`)

| Поле | Иконка |
|------|--------|
| Время в пути | `Schedule` |
| T° салона | `DirectionsCar` |
| T° батареи | `Battery6Bar` |
| 12V | `Bolt` |
| Тренд расхода | `TrendingDown` / `TrendingFlat` / `TrendingUp` |

### Цвета (из `ui.theme.Color.kt`)

- Расход DOWN (экономно): `AccentGreen` (#4ADE80)
- Расход FLAT (обычно): `TextMuted` (#64748B)
- Расход UP (жирно): `SocYellow` (#FBBF24)
- SOC градиент: `SocGreen` / `SocYellow` / `SocRed` (без изменений)

## 4. Алгоритм расхода и тренда

### 4.1. Источники данных

- **Мгновенный sample:** каждые **3 сек** (`TrackingService.POLL_INTERVAL_MS`).
- **На каждый sample** берём из `DiParsData`:
  - `mileage` (odometer, км)
  - `totalElecConsumption` (кумулятивный расход с начала истории авто, кВт·ч)
- **Исторический baseline (недельный EMA):** экспоненциальное среднее расхода за последние 7 дней из `TripRepository`. Уже есть агрегация в `InsightsManager` — переиспользовать / вынести в `TripRepository.getWeeklyEmaConsumption()`. Пересчёт на service start и после каждого sync (как текущий `cachedEmaConsumption`).

### 4.2. Состояние поездки

- Поездка считается **активной**, когда `TripTracker.state == DRIVING`.
- Новый экспонент: `TripTracker.tripStartedAt: StateFlow<Long?>` — timestamp перехода `IDLE → DRIVING`, `null` когда IDLE.
- Прокидывается в companion `TrackingService.tripStartedAt: StateFlow<Long?>` для доступа из `WidgetController`.

### 4.3. Число на виджете

```
если !tripActive:
    показываем weeklyEma (без стрелки)
если tripActive и возраст поездки < 3 мин (prewarmup):
    показываем weeklyEma (без стрелки)
если tripActive и возраст поездки ≥ 3 мин и trip-avg рассчитан:
    показываем trip-avg
если trip-avg ещё не валиден (пробег < 100 м):
    показываем weeklyEma
если weeklyEma == 0.0 (история пустая):
    показываем "—"
```

**Где trip-avg:**
```
tripKm  = mileage_now - mileage_trip_start
tripKwh = totalElec_now - totalElec_trip_start
trip-avg = tripKwh / tripKm * 100   // кВт·ч/100км
```

Валидация: `tripKm > 0.1` (минимум 100 м). Иначе trip-avg = null.

### 4.4. Стрелка тренда (только после prewarmup 3 мин)

**Pre-check:** если число на виджете — не trip-avg (а EMA или "—"), стрелка **не отображается** (иконка скрыта, цвет не переключается).

**Когда стрелка отображается** (trip-avg валиден, возраст поездки ≥ 3 мин):

Сравниваем `trip-avg` с `weeklyEma`:

```
ratio = trip-avg / weeklyEma
```

**Пороги с гистерезисом:**

| Текущее | Кандидат | Условие перехода |
|---------|----------|-------------------|
| FLAT | DOWN | ratio < 0.95 |
| FLAT | UP | ratio > 1.05 |
| DOWN | FLAT | ratio ≥ 0.97 |
| UP | FLAT | ratio ≤ 1.03 |
| DOWN | UP | ratio > 1.05 |
| UP | DOWN | ratio < 0.95 |

Зона между 0.97–1.03 — удерживающая FLAT. Между 0.95–0.97 и 1.03–1.05 — удерживающие предыдущее состояние (DOWN/UP).

**Debounce 60 секунд:**

```
при изменении кандидата:
    запоминаем candidateTrend = new
    запоминаем candidateSince = now
при совпадении кандидата с предыдущим и (now - candidateSince) ≥ 60_000 ms:
    committed = candidateTrend
    стрелка обновляется
```

Эффект: стрелка переключается только когда кандидат держится непрерывно 60 сек. Случайные колебания ≤ 60 сек не влияют.

### 4.5. Состояния-переходы

| Сценарий | Что на виджете |
|----------|----------------|
| Нет поездки, EMA есть | `17.8` без стрелки (TextMuted) |
| Нет поездки, EMA == 0 | `—` |
| Поездка началась, 0 ≤ age < 3 мин | `17.8` без стрелки (EMA) |
| Поездка 3+ мин, ratio стабильно < 0.95 | `16.2 ↓` зелёная |
| Поездка 3+ мин, ratio дрожит | текущая стрелка держится, переключится только через 60 сек устойчивости |
| Поездка 3+ мин, пробег < 100 м (стоишь) | `17.8` без стрелки (fallback на EMA) |

## 5. Время в пути

### 5.1. Расчёт

`duration = now - tripStartedAt`. Форматируется как в `Components.kt::formatDuration`:
- `< 1 час` → `"24 мин"`
- `≥ 1 час` → `"1 ч 24 мин"`

Если нет активной поездки — `⏱ —`.

### 5.2. Обновление

Вычисляется внутри Compose-ячейки через `produceState` на основе `tripStartedAt` и внутреннего тикера (каждые 15 сек). Достаточно для минут — секунды не отображаются.

## 6. Настройки (секция «Плавающий виджет»)

### 6.1. Текущие (оставляем)
- Switch вкл/выкл
- Кнопка «Сбросить позицию»

### 6.2. Новые

1. **Слайдер прозрачности**, 30–100%, дефолт 100%.
   - `WidgetPreferences.KEY_ALPHA: Float = 1.0f`
   - Применяется через `ComposeView.alpha = value` или `WindowManager.LayoutParams.alpha`.
   - Меняется live — на drag не влияет, фон и рамка плавно прозрачнеют.

2. **Секция «Скрывать в приложениях»** (после прозрачности):
   - Текст-описание: «Виджет не будет показываться в выбранных приложениях (например, YouTube в полноэкранном режиме).»
   - Кнопка «Выбрать приложения» → открывает picker с установленными приложениями (уже есть UI для `app_launch` action — переиспользовать).
   - Список выбранных приложений с возможностью удаления.
   - `WidgetPreferences.KEY_BLOCKLIST: Set<String>` (пакеты).
   - Пресет при первой установке: `com.google.android.youtube`, `com.android.settings`.
   - Предупреждение если permission PACKAGE_USAGE_STATS не выдан: «Требуется разрешение в Системных → Специальный доступ → Доступ к истории использования».
   - Кнопка «Открыть настройки» — `Settings.ACTION_USAGE_ACCESS_SETTINGS`.

## 7. Механизм скрытия в blocklist

### 7.1. Новый класс `AppForegroundWatcher`

Singleton/object. Поллит `UsageStatsManager` раз в 500 мс через `queryEvents(lastTime, now)` — берёт последнее событие `MOVE_TO_FOREGROUND`.

Expose `currentForegroundPackage: StateFlow<String?>`.

### 7.2. Запуск

В `BYDMateApp.onCreate`: если permission есть и `WidgetPreferences.isEnabled()` — стартуем watcher. Если permission нет — не стартуем, виджет работает без blocklist.

### 7.3. Интеграция с WidgetController

В `WidgetController.startDataSubscription`:

```kotlin
combine(
    TrackingService.lastData,
    TrackingService.lastRangeKm,
    TrackingService.tripStartedAt,
    ConsumptionAggregator.state,
    AppForegroundWatcher.currentForegroundPackage,
) { data, range, tripStart, consumption, foregroundPkg ->
    val hidden = foregroundPkg in WidgetPreferences(appCtx).getBlocklist()
    ...
}.collect { ... }
```

Если `hidden == true` — `widgetView.visibility = GONE` или `removeView`; если стал false — `addView` обратно. Лучше toggle visibility (дешевле, чем re-create ComposeView).

### 7.4. Manifest

Добавить:
```xml
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" tools:ignore="ProtectedPermissions" />
```

Это разрешение — **не runtime**, выдаётся руками пользователем через системные настройки. Аналогично уже имеющемуся `SYSTEM_ALERT_WINDOW`.

## 8. Затрагиваемые файлы (по этапам)

### Этап 1 — данные и layout виджета (6 файлов)

**Новые:**
- `app/src/main/kotlin/com/bydmate/app/domain/calculator/ConsumptionAggregator.kt`

**Изменяемые:**
- `app/src/main/kotlin/com/bydmate/app/domain/tracker/TripTracker.kt` — добавить `tripStartedAt: StateFlow<Long?>`.
- `app/src/main/kotlin/com/bydmate/app/service/TrackingService.kt`:
  - companion `tripStartedAt: StateFlow<Long?>` (копия из TripTracker).
  - В pollingJob вызывать `ConsumptionAggregator.onSample(data, tripActive, weeklyEma)`.
  - Поле `cachedWeeklyEmaConsumption` + `refreshConsumptionCache` дополнить получением weekly EMA.
- `app/src/main/kotlin/com/bydmate/app/data/repository/TripRepository.kt` — добавить `getWeeklyEmaConsumption(): Double` (последние 7 дней).
- `app/src/main/kotlin/com/bydmate/app/ui/widget/FloatingWidgetView.kt` — полная переделка под v6 (3 строки, 7 полей, иконки, тренд-цвет).
- `app/src/main/kotlin/com/bydmate/app/ui/widget/WidgetController.kt` — новый combined flow, прокидка новых параметров в `FloatingWidgetView`.
- `app/src/main/kotlin/com/bydmate/app/ui/widget/WidgetPreferences.kt` — ключ `KEY_ALPHA` (для Этапа 2, но добавить уже сейчас с default 1.0f).

### Этап 2 — настройки прозрачности (3 файла)

- `app/src/main/kotlin/com/bydmate/app/ui/widget/WidgetPreferences.kt` — методы `getAlpha()/setAlpha()/alphaFlow()`.
- `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsViewModel.kt` — state для alpha.
- `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsScreen.kt` — слайдер в секции «Плавающий виджет».

`WidgetController` подписывается на `alphaFlow()` и применяет через `widgetView.alpha = value`.

### Этап 3 — скрытие в blocklist (5 файлов + 1 manifest)

**Новые:**
- `app/src/main/kotlin/com/bydmate/app/util/AppForegroundWatcher.kt`

**Изменяемые:**
- `app/src/main/kotlin/com/bydmate/app/ui/widget/WidgetPreferences.kt` — `getBlocklist()/setBlocklist()/blocklistFlow()` + дефолт-пресет `[com.google.android.youtube, com.android.settings]`.
- `app/src/main/kotlin/com/bydmate/app/BYDMateApp.kt` — старт `AppForegroundWatcher` при наличии permission.
- `app/src/main/kotlin/com/bydmate/app/ui/widget/WidgetController.kt` — подписка на `AppForegroundWatcher.currentForegroundPackage` + toggle visibility.
- `app/src/main/kotlin/com/bydmate/app/ui/settings/SettingsScreen.kt` — picker blocklist + кнопка «Открыть системные настройки».
- `app/src/main/AndroidManifest.xml` — permission PACKAGE_USAGE_STATS.

### Этап 4 — релиз v2.3.9

- `app/build.gradle.kts` — versionCode=239, versionName="2.3.9".
- `CLAUDE.md` — current version + changelog entry.
- Сборка, подпись, `gh release`.

## 9. Сохраняемые конвенции

- Compose-стиль виджета как в v2.3.3: `FontFamily.Monospace`, `FontWeight.Bold` для главного, `FontWeight.Medium` для вторичного.
- Цвета — только из `ui.theme.Color.kt` (не хардкод).
- `bydSwitchColors()` если нужен Switch (пока не нужен).
- Логи через `Log.d/i/w/e` с тегом `"ConsumptionAggregator"`, `"AppForegroundWatcher"` и т.д.
- Никаких эмодзи в коде (CLAUDE.md правило).
- Внутренние строки (кодовые) на английском, UI-строки на русском.

## 10. Edge cases

- **Permission ACCESS_USAGE_STATS не выдан** — AppForegroundWatcher не стартует, виджет работает как v2.3.3 (всегда видим). В настройках виден warning.
- **EMA пустой** (новая установка) — «—» на виджете, стрелки нет.
- **DiPlus отдаёт null** для mileage или totalElecConsumption — sample игнорируется в агрегаторе.
- **Poll-interval backoff** (DiPlus отвалилась) — агрегатор не получает samples, trip-avg не растёт. Ок.
- **Поездка закрылась через `forceEnd`** (shutdown) — агрегатор сбрасывает состояние при переходе TripTracker → IDLE.
- **Автопоездка без км (прогрев на парковке)** — trip-avg = null (tripKm ≤ 0.1), fallback на EMA.

## 11. Цвета тренда (финальная таблица)

| Состояние | Цвет стрелки | Цвет числа | Иконка |
|-----------|--------------|-------------|---------|
| DOWN | `AccentGreen` #4ADE80 | `AccentGreen` | `TrendingDown` |
| FLAT | `TextMuted` #64748B | `TextMuted` | `TrendingFlat` |
| UP | `SocYellow` #FBBF24 | `SocYellow` | `TrendingUp` |
| NO_DATA (EMA / "—") | — (скрыта) | `TextMuted` | — |

## 12. Критерии приёмки

- Виджет читается за <1 сек (HIG правило).
- После `Этапа 1`: число, стрелка, время, салон — всё видно на DiLink. Никакого мигания в тестовой поездке.
- После `Этапа 2`: прозрачность 30% — виджет читаем поверх навигатора.
- После `Этапа 3`: YouTube в полноэкране → виджет исчезает; возврат в главный экран DiLink → появляется.
- Релиз: APK подписан, changelog в CLAUDE.md, release notes на русском.
