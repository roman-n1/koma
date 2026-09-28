# Statechart-слой поверх Koma: roadmap

- 更新日: 2026-09-28

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

## Upstream

Сначала прототип в форке `roman-n1/koma`, потом модель, валидация, Mermaid,
работающий прототип. Только после этого — RFC issue в `koma-kt/koma`
(«Proposal: optional introspectable statechart model for Koma»), и затем PR
небольшими шагами.

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
