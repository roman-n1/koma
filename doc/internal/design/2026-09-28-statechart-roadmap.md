# Statechart-слой поверх Koma: roadmap

- 更新日: 2026-09-28 (upstream-стратегия по шагам; использование форка и путь обратно на upstream)

## 背景

Koma 4.0 уже работает как плоский state machine на sealed-классах. В ней есть
`state<S2> { enter / action<A2> / exit / recover<T> }`, first-match по порядку
регистрации, эффекты с временем жизни состояния (`launch`, `LaunchControl`),
бизнес-ошибки как переходы (`recover {}`) и `Plugin` для наблюдения.

Чего нет для Harel statecharts:

- иерархии: смена фазы определяется только по `state::class != nextState::class`
  (`StoreImpl.onActionDispatched` / `onStateChanged`), поэтому переход между
  дочерними вариантами одного sealed-родителя делает exit/enter «целиком», без LCA;
- параллельных (ортогональных) регионов и history;
- guard как отдельной сущности (условие живёт в теле handler'а);
- **перехода как данных**. Реестр обработчиков в `StoreBuilder` — это приватные
  списки пар «лямбда-предикат + лямбда-обработчик». Типы `S2` / `A2` существуют
  только внутри reified-предиката `it is S2`, наружу не выходят.

Из-за последнего пункта сегодня невозможны introspection, валидация,
визуализация, transition coverage и model-based testing.

## 方針

### Не переписывать Koma

- Statechart — опциональный слой в отдельном модуле. Существующий API и
  семантика `Store` не меняются.
- `Koma State != statechart node`. Слой использует композицию: `Store`
  остаётся источником `StateFlow` для UI, statechart отвечает за структуру
  переходов.
- Statechart применяется только там, где есть `State + Event -> Transition`.
  Обычные данные (`toolbarTitle`, `scrollPosition`, значения полей ввода) в
  узлы не превращаются.

### DSL != Runtime

```text
Statechart DSL
      |
      v
StateChartDefinition (immutable data)
      |
      +--> Validator
      +--> Exporter (Mermaid / DOT)
      +--> Test path generator
      +--> Runtime --> Koma Store adapter
```

DSL строит неизменяемую модель, runtime её исполняет. Всё, что нужно
инструментам (валидация, диаграммы, coverage), берётся из модели без runtime
reflection.

### Семантика прежде API

Самая сложная часть — детерминированная семантика: порядок exit/enter по LCA,
приоритет переходов, порядок guard'ов, одновременные переходы в регионах, время
жизни корутин, исключения, persistence. Каждая фаза начинается с
semantics-first тестов, public API полируется последним. Не стремиться сразу к
`StateChartDefinition<S, A, E, ...>` со множеством generic-параметров.

Ожидаемые порядки:

```text
Connected / ChatOpened -> Disconnected
  exit ChatOpened, exit Connected, enter Disconnected

Connected / Idle -> Connected / ChatOpened
  exit Idle, enter ChatOpened
```

## Фазы

### Phase 0 — архитектурный контракт

Этот документ. Кода нет.

### Phase 1 — introspectable model

Новый KMP-модуль `koma-statechart` (по шаблону `koma-test`: android, iosArm64,
iosSimulatorArm64, jvm). На первой итерации — один модуль с пакетами `model`,
`validation`, `tooling`, `runtime`, `dsl` вместо шести отдельных артефактов.

Минимальная модель: `StateId` (value class), `StateNode` / `AtomicState`,
`Transition(source, target, on: ActionMatcher)`, `StateChartDefinition`.
`ActionMatcher` несёт `KClass<out Action>` и стабильное имя, потому что
`qualifiedName` доступен не на всех KMP-таргетах. Зависимость от `koma-core`
только на маркерные интерфейсы. Unit-тесты.

### Phase 2 — валидация и Mermaid

Дублирующиеся ID, переходы в несуществующие состояния, недостижимые состояния,
отсутствующий initial, конфликтующие переходы без guard'ов. Экспорт в Mermaid
`stateDiagram-v2`.

### Phase 3 — плоский runtime

Чистая функция `step(configuration, action) -> TransitionResult` с метаданными
сработавшего перехода. Без корутин и без Koma.

### Phase 4 — интеграция с Koma

Адаптер, который собирает `Store` из definition. Предпочтительный вариант без
изменений ядра: один catch-all `state<S> { action<A> { runtime.step(...) } }`.
Koma-state хранит активную конфигурацию как поле. Трасса переходов для
coverage снимается через `Plugin` и `koma-test` (`dispatchAndAwait`,
`StoreRecorder`).

### Phase 5 — иерархия

Compound-состояния, initial-переходы, LCA-алгоритм exit/enter. Если слой держит
Koma-state одним классом, Koma сама не делает exit/enter, и тогда временем
жизни корутин под-состояний управляет слой.

Если это упрётся в ограничения, минимальный хук в ядре — стратегия «ключа
фазы» `(S) -> Any` (по умолчанию `it::class`) вместо `state::class` в
`StoreImpl`, помеченная `@ExperimentalKomaApi`. Она затрагивает
`stateRuntimes`, `PendingActionPolicy` и `LaunchControl`, поэтому только через
отдельный ADR.

### Phase 6 — параллельные регионы

`ParallelState` / `Region`, конфигурация как набор активных листьев,
детерминированный порядок обработки регионов.

### Phase 7 — history

Shallow / deep history.

### Phase 8 — инструменты

Генерация путей для model-based testing, transition coverage, проверка
инвариантов, shrinking падающей последовательности, debug timeline.

## Definition of Done первого milestone (Phase 1–2)

- существующий API Koma не изменён;
- модель неизменяема, переходы — first-class объекты, ID состояний стабильны;
- модель можно обойти целиком;
- можно описать плоский граф и найти в нём недостижимые состояния;
- Mermaid-экспорт строится без runtime reflection;
- есть unit-тесты.

## Upstream-стратегия: маленькие шаги

Цель — чтобы автор Koma (`koma-kt/koma`) принимал изменения по одному. Для
этого каждый шаг:

- полезен Koma сам по себе, даже если statechart-слой не появится;
- опирается на то, что автор уже сам записал в `doc/internal/`;
- аддитивен и не меняет поведение существующих Store;
- начинается с feature request и только после согласия превращается в
  небольшой PR.

Порядок и размещение:

| # | Шаг | Где | Польза для Koma сама по себе | Что даёт statechart-слою |
|---|---|---|---|---|
| 1 | Хранить matcher-метаданные (`StateType(S2::class)` / `AnyState`, `ActionType(A2::class)` / `AnyAction`) рядом с предикатами в реестре `StoreBuilder`. Публичный API не меняется. | core, internal | Основа для диагностики роутинга, которую автор предлагает в `notes/2026-04-25-unhandled-action-behavior.md` (раздел про `build()`-проверки прямо называет это препятствием) | Первые данные о структуре: какие типы состояний и действий объявлены |
| 2 | Routing diagnostics в `:koma-test`: `diagnoseActionMatches`, assert числа совпадений при dispatch | `koma-test` | Ровно пункты 2–3 из той же записки автора | Проверка, что переход в модели и handler в Store совпадают |
| 3 | Read-only описание всех обработчиков (`describeHandlers()`); в ядре только `@InternalKomaApi`-метод, публичный API в `koma-test`, потому что автор предпочитает не расширять публичную поверхность `koma-core` | `koma-test` + core internal | Документация Store, handler coverage в тестах | Проверка покрытия и первая визуализация без собственного DSL |
| 4 | Причина изменения состояния для наблюдателей (action / enter / launch transaction / recover) | core, спорно | Debug timeline и trace (upstream #189), `receiveTransition` в тестовом драйвере (upstream #176) | Метаданные перехода для coverage и MBT |
| 5 | Модуль `koma-statechart`: модель, валидация, Mermaid, генерация путей | отдельный модуль (в форке) | — | Phase 1–2 и часть Phase 8 |
| 6 | Runtime и адаптер к Store через обычный DSL | отдельный модуль | — | Phase 3–4 |
| 7 | Opt-in иерархические state scope: `enter` / `exit` / `launch` у sealed-родителя переживают переходы между его дочерними вариантами (LCA по sealed-иерархии) | core, RFC + ADR | Сегодня `state<Parent> { enter {} }` перезапускается при каждой смене дочернего варианта, это неудобно и без statecharts. Оформляется как policy-enum, по образцу `doc/internal/adr/2026-05-07-runtime-policy-enum.md` | Phase 5 без собственного управления корутинами |
| 8 | Параллельные регионы, history | отдельный модуль | — | Phase 6–7, в ядро не предлагаем |
| 9 | RFC «optional introspectable statechart model for Koma»: модуль как companion или внешний артефакт | upstream issue | — | Официальный статус слоя |

Замечания по рискам:

- Шаг 4 противоречит текущей позиции автора: в
  `notes/2026-05-02-plugin-design.md` Plugin наблюдает только границы Store
  (вход и выход) и отдельного хука на смену типа нет. Поэтому шаг 4 идёт
  после 1–3 и предлагается как данные, а не как новый хук. Если автор
  откажет, слой коррелирует `onAction` → `onState` сам (под одним mutex) или
  отдаёт метаданные из собственного runtime.
- Шаг 7 меняет семантику scope, поэтому только opt-in и только после
  отдельного ADR.
- Шаги 5, 6 и 8 не требуют ничего от upstream и идут в форке параллельно
  с 1–4.
- Номера upstream-issues (#175, #176, #189) взяты из handoff и записок
  автора и в этой сессии не проверялись.

Черновик первого feature request (шаг 1):
[`notes/2026-09-28-upstream-fr-handler-matcher-metadata.md`](../notes/2026-09-28-upstream-fr-handler-matcher-metadata.md).

## Использование форка в мессенджере и путь обратно на upstream

Цель Романа: сейчас подключить форк `roman-n1/koma` в свой KMP-мессенджер,
а по мере того, как автор принимает запросы, вернуться на официальную Koma.
Отсюда следуют правила.

### Правила для форка

- **Минимум расхождений в ядре.** Изменения `koma-core` в форке —
  небольшие, аддитивные, по одному шагу на PR, каждый в своих коммитах. Их
  можно выбросить при переходе на upstream-версию, где такое же изменение уже
  есть.
- **Statechart-слой только в своём модуле.** `koma-statechart` зависит лишь
  от публичного API `koma-core` (см. план B) и должен собираться поверх
  официальной Koma без форка ядра.
- **Учёт расхождений.** Таблица ниже — единственный список изменений ядра в
  форке. Каждая строка удаляется, когда upstream выпускает то же самое или
  когда мы отказываемся от изменения.

| Шаг | Что меняет в ядре | Fork PR | Статус в upstream |
|---|---|---|---|
| 1 | Matcher-метаданные в реестре `StoreBuilder` (internal) | #2 | запрос готов, не отправлен |
| 2 | `StoreInternalApi.matchActionHandlers` (`@InternalKomaApi`) | #3 | в форке |
| 3 | `StoreInternalApi.handlerMetadata` (`@InternalKomaApi`) | #4 | в форке |

### Синхронизация с upstream

- `main` форка = upstream + наши влитые PR.
- При каждом релизе upstream: `git fetch upstream --tags`, затем merge тега
  релиза в `main` форка **merge-коммитом** (без rebase, чтобы не ломать
  ветки и клоны). Конфликты в ядре решаются в пользу upstream-версии, если она
  покрывает наш шаг; строка в таблице удаляется.
- После merge: CI форка зелёный на всех платформах, затем новая версия форка.

### Как мессенджер подключает форк

1. **Во время активной разработки — composite build.** Форк подключается
   git-сабмодулем, а в `settings.gradle.kts` мессенджера прописывается
   `includeBuild("koma")`. Gradle сам подставит модули форка вместо
   зависимостей `io.github.koma-kt:*`, публиковать ничего не нужно, правки в
   Koma видны сразу. Минус: CI мессенджера должен забирать сабмодуль.
2. **Когда версия стабилизировалась — GitHub Packages.** Публикация из GitHub
   Actions форка на macOS-раннере (иначе не собрать iOS-артефакты), в свой
   Maven-репозиторий форка. Для чтения нужен токен с `read:packages`.
   - Координаты: отдельная группа `io.github.roman-n1`, чтобы артефакты
     форка никогда не путались с официальными `io.github.koma-kt`.
   - Версия: базовая версия upstream + суффикс форка, например
     `4.0.0-sc.1`, `4.0.0-sc.2`; после синхронизации с 4.1.0 — `4.1.0-sc.1`.
   - Нельзя смешивать официальный `koma-core` и форковый в одном проекте:
     классы одинаковые, группы разные. Группа и версия Koma задаются в
     version catalog мессенджера в одном месте.
   - Текущий `.github/workflows/publish.yml` публикует в Maven Central по
     prerelease и в форке упадёт без секретов. Для форка нужен отдельный
     workflow публикации; сделать, когда дойдём до пункта 2.
3. **Переход на официальную Koma.** Когда upstream выпускает все шаги ядра,
   которые нужны мессенджеру: в version catalog мессенджера группа
   `koma-core`/`koma-compose`/`koma-test` меняется на `io.github.koma-kt`, а
   версия — на официальную. `koma-statechart` остаётся нашим артефактом (или
   переезжает в upstream через RFC, шаг 9) и работает поверх официального
   `koma-core`. Если какой-то шаг ядра upstream не принял, мессенджер
   остаётся на форке только ради него, либо слой обходится без него (план B).

## 補足

- Реестр handler'ов, first-match и порядок коммита (exit → `_state` + `StateSaver`
  → `Plugin.onState` → clear pending → enter) проверены по исходникам 4.0.0.
- `Plugin.onAction` вызывается до handler'а, `Plugin.onState(prev, next)` — после
  коммита. Какой action вызвал переход, Plugin не знает; слой отдаёт эту
  метаданную сам.

## 関連

- [Koma の設計原則](./2026-04-23-design-principles.md)
- [Store surface の設計メモ](./2026-04-29-store-api-design.md)
- [LaunchControl API のデザイン](../adr/2026-05-01-launch-control-case-naming.md)
