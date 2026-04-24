# Adaptive Range & Consumption — Design Spec

**Версия:** v2.5.0
**Дата:** 2026-04-24
**Статус:** draft, ждёт review

---

## 1. Почему это нужно

Сейчас на виджете и Dashboard три числа — SOC%, остаточный пробег (range), расход + стрелка тренда. Каждое считается на своём входе, из-за чего они расходятся между собой и с итогом закрытой поездки.

### 1.1 Симптомы

1. **Range «застревает».** Проехал 3–4 км, а цифра 203 км не меняется, затем скачок сразу на ~4 км вниз.

   Причина: `SOC` в DiPars — `int` с шагом 1%. Для Leopard 3 (72.9 кВт·ч) 1% SOC ≈ 0.73 кВт·ч ≈ 4 км пробега при расходе 18 кВт·ч/100км. Пока SOC не щёлкнет — формула `SOC × cap / ema × 100` возвращает константу.

2. **Скачок range на ignition-off/on.** Владелец заглушил машину с цифрой 203, сразу завёл — 191.

   Причина: в `TrackingService.onCreate` → `historyImporter.runSync()` подтягивается свежая поездка из energydata, `insertTrip` инвалидирует `cachedEmaConsumption` в `TripRepository`. Если поездка была тяжелее среднего (холодный старт, прогрев, короткая дистанция), EMA сдвигается вверх на 3–7%, range падает на 10–15 км.

3. **Расход на виджете не совпадает с итогом поездки.** Ехал 6 км, виджет показывал 22 кВт·ч/100км (rolling-5-km зелёным), список Поездок записал 33 кВт·ч/100км.

   Причина: `ConsumptionAggregator.displayValue` использует rolling 5-км по пробегу. На короткой поездке с 10-минутным прогревом (HVAC без движения) rolling «не видит» прогрев — он вне `Δmileage`. Cumulative trip видит всё → 33. Физически оба числа корректны, но отвечают на разные вопросы, а виджет подаёт rolling как главное число.

### 1.2 Что подтвердил ресерч

- **Tesla** разделяет два числа: «рядом с SOC» — EPA-rated прокси (не адаптивный, не прыгает) + «Energy app projected» — rolling 5/15/30 mile. Instant считается бесполезным.
- **BMW i3, Hyundai Ioniq 5** — GOM = SOC × average за ~20 миль **по одометру** (не по поездкам, не по сессиям). Адаптация медленная (~300 миль до полного переобучения).
- **Общий вывод:** правильный горизонт — **rolling по одометру** длиной 25–50 км. Короткий window (rolling 5 км) — слишком дёрганый и искажает короткие поездки с прогревами. Длинный (EMA 20 поездок) — дискретный и скачет на каждой новой записи.

---

## 2. Цели и не-цели

### Цели

- **Range плавно уменьшается** по мере пробега — как у штатки, но честно (не врёт вверх).
- **Range не скачет на ignition-on/off** — непрерывен через циклы питания.
- **Расход на виджете близок к итогу поездки** — не расходится на 30–50% как сейчас.
- **Стрелка тренда остаётся быстрой** — показывает «сейчас лучше/хуже среднего».
- **Один источник truth** для range/расхода/стрелки — меньше ручной синхронизации.
- **Работает одинаково на Leopard 3 и Song** — без костылей.

### Не-цели

- Не делаем навигационный прогноз «хватит ли доехать до точки с учётом рельефа/погоды». Это отдельная задача.
- Не считаем true SoH через OBD. Это всё ещё вне доступа.
- Не меняем UI виджета (7 полей остаются, layout v3 как есть).
- Не меняем ментальную модель пользователя «цифра + стрелка» — просто делаем её честнее.

---

## 3. Архитектура

### 3.1 Новый класс `OdometerConsumptionBuffer`

Central rolling-window calculator. **Один на всё приложение** (`@Singleton`).

**Ответственность:** собирать пары `(mileage_км, totalElec_кВт·ч)` через DiPars live-поток, держать их на последние **25 км пробега одометра**, выдавать `recent_avg_consumption` (кВт·ч/100км).

**Persistent:** буфер сохраняется в Room (новая таблица `odometer_samples`), чтобы переживать ignition-off, sys-kill и рестарт приложения. Непрерывный одометр = непрерывный буфер.

**API:**

```kotlin
@Singleton
class OdometerConsumptionBuffer @Inject constructor(
    private val dao: OdometerSampleDao,
    private val fallbackEmaProvider: () -> Double  // TripRepository.getEmaConsumption
) {
    /** Update on every DiPars tick. Idempotent on duplicate mileage/elec. */
    suspend fun onSample(mileage: Double?, totalElec: Double?, isCharging: Boolean)

    /** kWh/100km. Returns fallback EMA if buffer has < MIN_BUFFER_KM. */
    suspend fun recentAvgConsumption(): Double

    /** Short-horizon (2 km) for trend arrow. Null if buffer too short. */
    suspend fun shortAvgConsumption(): Double?

    /** For debugging / summary logs. */
    suspend fun status(): BufferStatus
}
```

**Константы:**

- `WINDOW_KM = 25.0` — длина rolling-окна.
- `SHORT_WINDOW_KM = 2.0` — окно для стрелки тренда.
- `MIN_BUFFER_KM = 5.0` — ниже этого ориентируемся на fallback EMA.
- `MIN_MILEAGE_DELTA = 0.05` — игнорируем tick'и где пробег не вырос и нет простоя с HVAC (шум).
- `MAX_BUFFER_ROWS = 500` — hard cap на случай простоев с большим количеством tick'ов (trim по кол-ву, не по пробегу).

### 3.2 Новый класс `RangeCalculator`

**Ответственность:** считать `estimatedRangeKm` по одной формуле для Dashboard и виджета.

**API:**

```kotlin
@Singleton
class RangeCalculator @Inject constructor(
    private val buffer: OdometerConsumptionBuffer,
    private val settings: SettingsRepository,
    private val socInterpolator: SocInterpolator
) {
    /** Plain estimate — for Dashboard recomputes on every DiPars tick. */
    suspend fun estimate(soc: Int?, totalElec: Double?): Double?
}
```

**Формула:**

```
remaining_kwh = SOC × cap / 100 - socInterpolator.carryOver(totalElec, soc)
range_km      = remaining_kwh / recentAvgConsumption × 100
```

### 3.3 `SocInterpolator` — сглаживание между SOC-ступенями

**Ответственность:** убирать 4-км ступеньки SOC, считая «сколько уже съели после последнего SOC-шага».

**Логика:**

- При каждом tick'е получает `(soc, totalElec)`.
- Если `soc` изменился (сравнивая с предыдущим tick'ом того же сеанса приложения) → запоминает `totalElecAtSocChange = totalElec`.
- Возвращает `carryOver = totalElec - totalElecAtSocChange` (сколько кВт·ч потрачено после последнего щелчка SOC).
- При рестарте приложения помнит `totalElecAtSocChange` через SharedPreferences (достаточно одного значения).

**Почему это работает:** между SOC-шагами `carryOver` монотонно растёт от 0 до ~0.73 кВт·ч (на Leopard 3 с его 1% = 0.73 кВт·ч). В момент SOC-щелчка `SOC × cap/100` падает на 0.73, `carryOver` сбрасывается в 0 — `remaining_kwh` плавно, без скачка. Цифра range уменьшается непрерывно.

**Sanity-check:** если `carryOver` превысил `2 × (cap/100)` — значит пропустили SOC-change (например, после длительного сна приложения). Сбрасываем в 0.

### 3.4 Интеграция с `ConsumptionAggregator`

`ConsumptionAggregator` **остаётся** — но упрощается. Его новая роль: считать **только** `shortAvgConsumption` и `trend` на основе `OdometerConsumptionBuffer`. Старая session-based логика (ignition-on якорь, cumulative до 5 км, rolling после) — убирается. Session-концепция остаётся только для UI «время поездки» на виджете (`sessionStartedAt` в `TrackingService`), она не участвует в расчёте расхода.

**Новая цифра расхода на виджете** = `recentAvgConsumption` из буфера (а не session cumulative и не 5-км rolling).

**Стрелка** сравнивает `shortAvgConsumption` (последние 2 км) с `recentAvgConsumption` (25-км среднее):

- `ratio = short / recent`
- гистерезис: DOWN ≤ 0.90, UP ≥ 1.10 (шире, чем сейчас 0.95/1.05 — потому что short 2 км и так волатилен)
- debounce 30 сек (меньше, чем 60 — short уже сам по себе реагирует быстрее)

### 3.5 Data source: Leopard vs Song

**Ключевое:** rolling buffer живёт на **DiPars live-потоке** (`mileage` = param 3 `里程`, `totalElec` = param 32 `总电耗`). Он **не зависит** от `SettingsRepository.DataSource` toggle (energydata vs DiPlus TripInfo). Этот toggle влияет только на `HistoryImporter` и `TripRepository.getEmaConsumption()`.

**Fallback EMA** — читается через `TripRepository.getEmaConsumption()`, который уважает тогл. То есть на Song fallback считается по DiPlus TripInfo SOC-delta (менее точно, ~1 кВт·ч grain), но это только fallback — буфер быстро накопится за первые 25 км живого пробега и дальше работает одинаково.

**Feature detection для `totalElec` на Song:** в `DiParsClient.parse()` уже возвращается null если параметр отсутствует. Если первые ~3 минуты работы приложения `totalElec` всегда null или постоянно 0 — включаем **SOC-based режим** буфера: пара становится `(mileage, soc × cap / 100)`. Разрешение ухудшается (шаги 0.73 кВт·ч), но работает.

```kotlin
enum class BufferMode { TOTAL_ELEC, SOC_BASED }
```

Режим выбирается автоматически при первом заполнении буфера, сохраняется в SharedPreferences, пересматривается раз в сутки.

**Sanity-check согласованности.** Логируем раз в 5 минут: `Δ(SOC × cap/100) vs Δ(totalElec)` на одном и том же интервале пробега. Если систематическое отклонение > 20% — это сигнал, что `totalElec` у BYD считает не то, что мы думаем (например, только traction). В этом случае в дизайн-доке **зафиксировано**: переключаемся на SOC-based, эмитим warning в logcat.

### 3.6 Persistence-модель

**Новая Room-таблица `odometer_samples`:**

```kotlin
@Entity(tableName = "odometer_samples")
data class OdometerSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mileageKm: Double,
    val totalElecKwh: Double?,   // null в SOC_BASED режиме
    val socPercent: Int?,         // снимок SOC для калибровки
    val timestamp: Long
)
```

**DAO:**

```kotlin
@Dao
interface OdometerSampleDao {
    @Insert suspend fun insert(sample: OdometerSampleEntity): Long

    /** Last sample (by id, since id is monotonic). */
    @Query("SELECT * FROM odometer_samples ORDER BY id DESC LIMIT 1")
    suspend fun last(): OdometerSampleEntity?

    /** All samples where mileage >= (newest mileage - WINDOW_KM). */
    @Query("""
        SELECT * FROM odometer_samples
        WHERE mileageKm >= :minMileage
        ORDER BY mileageKm ASC
    """)
    suspend fun windowFrom(minMileage: Double): List<OdometerSampleEntity>

    @Query("DELETE FROM odometer_samples WHERE mileageKm < :cutoff")
    suspend fun trimBelow(cutoff: Double)

    @Query("SELECT COUNT(*) FROM odometer_samples") suspend fun count(): Int
}
```

**Миграция БД: 10 → 11.** Добавляется таблица `odometer_samples`. Старые данные не ломаются.

**Дополнительные SharedPreferences ключи** (`widget_prefs` или новая `range_prefs`):

- `soc_interpolator_last_soc` — последний виденный SOC
- `soc_interpolator_total_elec_at_change` — `totalElec` на момент последнего SOC-шага
- `buffer_mode` — `TOTAL_ELEC` или `SOC_BASED`
- `buffer_mode_locked_at` — timestamp для ежесуточного пересмотра

### 3.7 Граф зависимостей

```
DiParsClient.fetch()  ──►  TrackingService.pollingLoop
                               │
                               ├──► OdometerConsumptionBuffer.onSample
                               │         │
                               │         └──► OdometerSampleDao (Room)
                               │
                               ├──► SocInterpolator.onSample
                               │
                               ├──► RangeCalculator.estimate
                               │         ├──► OdometerConsumptionBuffer.recentAvgConsumption
                               │         └──► SocInterpolator.carryOver
                               │
                               └──► ConsumptionAggregator.onSample  (упрощён)
                                         └──► буфер.recentAvgConsumption + shortAvgConsumption

DashboardViewModel ────────►  RangeCalculator.estimate (на каждый tick DiPars)
WidgetController ─────────►   RangeCalculator.estimate (через TrackingService flow)
```

---

## 4. Edge cases

### 4.1 Зарядка

Во время активной зарядки `totalElec` может расти (регенерация, внутренние нужды) или падать (BYD считает заряд как negative consumption — непроверено). В любом случае семплы, снятые во время зарядки, **загрязняют** буфер.

**Решение:** при `chargeGunState > 0` или `chargingStatus == 2 (Started)` — **пропускаем** `onSample`. Буфер ставится на паузу.

### 4.2 Odometer regression

Если `mileage_now < mileage_previous` (glitch DiPars или одометр откатился после сервиса) — пропускаем tick.

Если разница > 100 км (нереальный скачок вперёд) — пропускаем и логируем warning.

### 4.3 Смена `batteryCapacity` в Settings

Пользователь поменял ёмкость (например, установил 82.56 по ошибке, потом исправил на 72.9). Буфер **не сбрасывается** — он хранит `(mileage, totalElec)` без привязки к cap. Меняется только `RangeCalculator.estimate` → пересчитывает `remaining_kwh` с новой cap на следующем tick'е.

### 4.4 Первая установка / после reset

Буфер пуст. `recentAvgConsumption` возвращает `fallbackEmaProvider()` (`TripRepository.getEmaConsumption`). При достижении `MIN_BUFFER_KM = 5` переключается на свой расчёт. Плавно, без скачка.

### 4.5 Долгий простой (сутки+) с HVAC выключенным

`totalElec` может немного вырасти (12V discharge, BMS overhead) без движения. При следующем включении первый tick даст `Δtotalelec > 0, Δmileage = 0`. Это попадёт в буфер как простойное потребление.

На 25-км горизонте это ~0.3–0.5 кВт·ч = +1–2 кВт·ч/100км к среднему. Некритично, но теоретически можно отфильтровать: если `(currentTimestamp - lastSampleTimestamp) > 6 часов` — вставляем «разделитель» (пропускаем, сбрасываем `totalElecAtSocChange`). Уточнить в реализации.

### 4.6 Sys-kill процесса

Буфер persist в Room → переживает. `SocInterpolator.totalElecAtSocChange` persist в SharedPreferences → переживает. На первом tick'е после рестарта — продолжаем с того же места.

### 4.7 SOC «прыгает» вверх (регенерация, быстрая зарядка, baseline drift)

Если `soc_now > soc_prev` и не заряжаемся — вероятно регенерация (короткий импульс). Игнорируем изменение `totalElecAtSocChange` (оставляем предыдущий), позволяем `carryOver` временно стать отрицательным в формуле. `remaining_kwh` может чуть подпрыгнуть — это честно, так и есть (вернулось немного энергии).

Если `soc_now - soc_prev > 2%` за один tick — вероятно zaряжались и пропустили. Обновляем `totalElecAtSocChange = totalElec_now` (сбрасываем carry).

### 4.8 Отсутствующие поля DiPars

- `soc == null`: `RangeCalculator.estimate` возвращает null (виджет/Dashboard показывают прочерк).
- `mileage == null`: буфер пропускает tick.
- `totalElec == null` в режиме `TOTAL_ELEC`: если 3+ tick'а подряд null — переключаем режим в `SOC_BASED`.

---

## 5. UI-изменения

### 5.1 Виджет (`FloatingWidgetView`)

**Что меняется:** цифра расхода (центральная ячейка, row 2) теперь `recentAvgConsumption` (25-км среднее) вместо rolling 5 км session.

**Что остаётся:** layout v3 (260×108), SOC% + km + расход с иконкой тренда, 3 строки, status colour для border + SOC + расход.

**Визуально** цифра расхода станет более стабильной, в районе 18–30 в обычной езде, без резких скачков. Range уменьшается плавно, не застревает.

### 5.2 Dashboard

**Что меняется:** `DashboardViewModel.calculateRange` заменяется вызовом `RangeCalculator.estimate`. Cache `recentAvgConsumption` в ViewModel убирается — теперь это ответственность `OdometerConsumptionBuffer` (и он сам оптимизирован по IO через in-memory cache + периодический flush в Room).

**UI не меняется.** Только цифра становится более плавной.

### 5.3 Notifications (foreground service)

`TrackingService.updateNotification` уже использует `estimateRangeKm` — меняется на `RangeCalculator.estimate`.

### 5.4 Settings

Ничего не добавляем. Buffer mode (TOTAL_ELEC/SOC_BASED) и прочее — hidden, авто-detectится.

---

## 6. Миграция старого кода

### 6.1 Что удаляется

- `DashboardViewModel.calculateRange` → заменяется на `RangeCalculator.estimate`.
- `DashboardViewModel.recentAvgConsumption` + `loadRecentAvgConsumption` — больше не нужно.
- `TrackingService.estimateRangeKm`, `cachedEmaConsumption`, `cachedBaselineEma`, `cachedBatteryCapacity`, `refreshConsumptionCache`, `doRefreshConsumptionCache`, `maybeRefreshConsumptionCache` — уезжают в `RangeCalculator` + `OdometerConsumptionBuffer`.
- `ConsumptionAggregator.SessionBaseline`, `pendingBaseline`, persist-логика session в `SessionPersistence` — session-концепция для расхода больше не нужна. **Но `sessionStartedAt` остаётся** (для времени поездки на виджете + `fireOncePerTrip` в automation — это другая роль, не связанная с расходом).
- Метод `TripRepository.getWeeklyEmaConsumption` — остаётся для AI Insights, но из widget-path убирается.
- `TripRepository.getRecentTripsEmaConsumption` — остаётся как source для `fallbackEmaProvider`.

### 6.2 Что модифицируется

- `ConsumptionAggregator.onSample` — теперь тонкая обёртка над `OdometerConsumptionBuffer.shortAvgConsumption` для стрелки. `displayValue` больше не вычисляет cumulative/rolling — просто берёт `recentAvgConsumption` из буфера.
- `SessionPersistence` — остаётся для sessionStartedAt-якоря automation, но `SessionBaseline` с mileage/elec убирается.
- `AppModule` (Hilt) — добавляются провайдеры `OdometerConsumptionBuffer`, `RangeCalculator`, `SocInterpolator`.

### 6.3 Что НЕ трогаем

- `HistoryImporter` — работает как раньше, включая `runSync()` на старте. Он не знает про буфер.
- `TripRepository.getEmaConsumption` — остаётся для fallback и для AI Insights.
- `SettingsRepository.DataSource` toggle — работает как раньше, влияет только на HistoryImporter.
- Layout виджета, Dashboard UI, Settings UI — без изменений.

---

## 7. Тесты

### 7.1 Unit-тесты

**`OdometerConsumptionBufferTest`:**

- empty buffer returns fallback EMA
- < MIN_BUFFER_KM returns fallback
- ≥ MIN_BUFFER_KM computes from buffer
- trim старых samples при росте mileage
- charging samples игнорируются
- odometer regression tick пропускается
- huge forward jump (>100 км) пропускается и warning'ится
- SOC_BASED fallback когда totalElec null 3+ tick'а
- short window (2 км) и recent window (25 км) считаются независимо
- persistent state выживает «рестарт» (DAO in-memory stub)

**`SocInterpolatorTest`:**

- carryOver монотонно растёт между SOC-шагами
- carryOver сбрасывается на SOC-щелчке (down)
- SOC up (регенерация малой) → carry может стать отрицательным, но ограничен
- SOC up (>2%) → баланс сбрасывается (charging missed)
- sanity cap: carryOver > 2× (cap/100) → сбрасывается

**`RangeCalculatorTest`:**

- null SOC → null
- SOC = 0 → 0 или null
- normal case: SOC=50, cap=72.9, avg=18 → ~202 км
- carryOver уменьшает range плавно
- смена cap в Settings сразу влияет на следующий вызов

**`ConsumptionAggregatorTest` (обновлённые 15 кейсов):**

- переписаны под новую роль: displayValue = buffer.recentAvg, shortAvg используется только для стрелки
- тренд debounce 30 сек, гистерезис 0.90/1.10
- старые кейсы про session baseline, prewarmup, rolling 5 km — удаляются (они больше не актуальны)

### 7.2 Integration-тесты (ручные)

1. **Ignition-on после холодной ночи:** range не отличается больше чем на 2 км от того, что было при ignition-off. Фиксируем в release-notes.
2. **Короткая поездка 6 км с прогревом:** расход в конце поездки на виджете ≈ итог поездки в списке (разница < 15%, было 30–50%).
3. **Длинная поездка 50+ км с разным стилем:** range плавно адаптируется, стрелка тренда корректно показывает «лучше/хуже среднего последних 25 км».
4. **Проезд 3–4 км в ровном режиме:** range уменьшается на ~3–4 км (не застревает, не прыгает).
5. **Смена batteryCapacity в Settings:** range мгновенно пересчитывается, буфер не теряется.
6. **Зарядка AC/DC:** буфер не заполняется во время зарядки, после завершения продолжает с предыдущей точки.
7. **Тест на Song (если есть тестовый доступ):** feature detection переключает в SOC_BASED, всё работает с худшим разрешением, но без ошибок.

### 7.3 Codex audit перед релизом

Как всегда — обязательный шаг до `gh release create`. Особое внимание:

- persistence race conditions (буфер + polling + onDestroy)
- precision loss в Float (используем Double везде, как в v2.4.4 fix)
- потенциальный leak Room коннекшенов
- буфер не читается синхронно на UI-потоке

---

## 8. План релиза

**Одним шагом в v2.5.0** (не разбиваем на v2.4.5 + v2.5.0 — пользователь предпочёл большой фикс сразу):

1. Room migration 10 → 11
2. `OdometerSampleDao` + entity
3. `OdometerConsumptionBuffer` + tests
4. `SocInterpolator` + tests
5. `RangeCalculator` + tests
6. `ConsumptionAggregator` refactor + updated tests
7. `TrackingService` integration (замена `refreshConsumptionCache` / `estimateRangeKm` / session baseline)
8. `DashboardViewModel` integration
9. `AppModule` Hilt bindings
10. Ручное тестирование (6 integration-кейсов выше)
11. Codex audit
12. Release notes на русском
13. Build, sign, release v2.5.0

**Ожидаемый объём:** ~15–20 файлов изменений, ~1500 строк кода + тестов. Это больше 3-файлового лимита из CLAUDE.md — именно поэтому идёт через spec → plan → execution с чекпоинтами.

---

## 9. Открытые вопросы для обсуждения

1. **Длина окна (25 км)** — правильный компромисс между инерцией и актуальностью? Tesla использует 5/15/30 miles (8/24/48 км), BMW ~20 миль (32 км), Hyundai похоже. 25 км — середина, можно поэкспериментировать. Готов сделать константой с TODO пересмотреть после 1-2 недель использования.

2. **Short window для стрелки (2 км)** — не слишком ли короткий? Если rolling 2 км окажется дёрганым в реальной езде (например, на городских пробках), увеличить до 3–5 км или временной window (последние 3 минуты движения).

3. **Filtering простоев > 6 часов** (п. 4.5) — делать или не париться? Простой эффект мал, усложнение буфера заметное. Моё мнение: **не делать** в v2.5.0, посмотреть на реальные данные из logcat, при необходимости добавить потом.

4. **Стратегия для стрелки на коротких поездках** — сейчас стрелка активируется с `cumKm ≥ 2` в рамках сессии. В новой модели «сессия» для расхода не существует. Предлагаю: стрелка активируется, когда **оба** окна (short и recent) достаточно заполнены — `short ≥ 2 km` и `recent ≥ 5 km`. Это естественно отсечёт первые километры поездки после долгого простоя (там ratio врёт из-за прогрева).

5. **Дефолт batteryCapacity на старте** — сейчас 38.0 в `DashboardViewModel`. Это рудимент старых BYD моделей. Предлагаю поднять до 72.9 (Leopard 3) — он в 95% случаев ближе к правде для пользователей этого приложения. Если владелец Song зайдёт — он всё равно сразу сходит в Settings и поправит.

---

## 10. Что пользователь увидит в v2.5.0 relase notes

> **v2.5.0 — честный остаточный пробег и расход**
>
> Переделан расчёт остаточного пробега и текущего расхода. Теперь цифры на виджете и Dashboard считаются из единого 25-км rolling-окна по одометру — как у Tesla и Hyundai.
>
> **Что изменится на виджете:**
> - Цифра остаточного пробега плавно уменьшается по мере поездки (раньше застревала до щелчка SOC и прыгала на ~4 км).
> - При выключении и повторном включении машины цифра не скачет (раньше теряла 10–15 км).
> - Расход в кВт·ч/100км отражает реальный расход последних 25 км пробега, а не только движения — прогревы, простои с кондиционером, короткие отрезки учитываются честно. Цифра будет ближе к тому, что ты увидишь в итоге поездки в списке Поездок.
> - Стрелка тренда активируется, когда в окне накопилось достаточно данных, сравнивает последние 2 км со средним 25 км.
>
> Данные буфера живут в БД и переживают перезапуски, выключения, принудительные остановки.

---

## 11. Что пользователь **не увидит**, но стоит знать

- Новая Room-таблица `odometer_samples`, пара сотен строк на старте, trim по достижению WINDOW_KM.
- Feature detection для `totalElec` на Song: в первые 3 минуты работы приложение определяет режим (TOTAL_ELEC или SOC_BASED) и запоминает.
- Раз в 5 минут в logcat пишется `BufferStatus` — размер окна, среднее, режим, последний SOC-шаг. Это для диагностики, UI не показывает.
- Session-концепция для расхода **убрана**. Для времени поездки и automation `fireOncePerTrip` она остаётся.
