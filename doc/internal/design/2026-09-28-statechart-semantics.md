# Statechart-слой: семантика иерархии, регионов, history, таймеров и адаптера

- 更新日: 2026-09-28

Документ фиксирует семантику фаз 4–7 из
[roadmap](2026-09-28-statechart-roadmap.md) до кода. Всё живёт в модуле
`koma-statechart` и использует только публичный API `koma-core` (план B). Все
новые публичные типы помечены `@ExperimentalKomaApi`.

## Модель

Модель остаётся плоским списком узлов. Дерево задаётся ссылкой на родителя.
Так существующие определения (`AtomicState(id)`) продолжают работать без
изменений, а валидатор и экспорт обходят один список.

| Узел | Поля | Смысл |
|---|---|---|
| `AtomicState` | `id`, `parent: StateId? = null` | Лист |
| `CompoundState` | `id`, `initial: StateId`, `parent` | Ровно один активный ребёнок |
| `ParallelState` | `id`, `parent` | Активны все дети (регионы) одновременно |
| `HistoryState` | `id`, `parent`, `deep: Boolean`, `default: StateId?` | Псевдо-узел: переход в него восстанавливает прошлую конфигурацию родителя |

`StateChartDefinition.initial` указывает на корневой узел. Корней может быть
несколько: тогда корень чарта ведёт себя как неявный compound.

Триггер перехода — sealed `Trigger`:

- `Trigger.OnAction(matcher)` — приход action, как сейчас;
- `Trigger.After(delay: Duration)` — таймер, который запускается при входе в
  `source` и отменяется при выходе из него.

`Transition(source, target, on: ActionMatcher, guard)` остаётся вторичным
конструктором, поэтому существующий код не меняется. Свойство `on` возвращает
matcher для `OnAction` и `null` для таймера.

У перехода появляется необязательная метка `effect: String?`. Как и у guard, это
только метка. Реализацию даёт адаптер к Store.

## Конфигурация

`StateConfiguration` — неизменяемое значение:

- `active: Set<StateId>` — активные узлы, включая всех предков;
- `history: Map<StateId, Set<StateId>>` — запомненные дети/листья для узлов,
  у которых есть `HistoryState`.

Runtime остаётся чистой функцией. Конфигурацию хранит вызывающий: Koma Store в
поле состояния.

## Шаг (step)

Семантика упрощённого SCXML (microstep без eventless-переходов):

1. **Выбор переходов.** Активные листья обходятся в порядке объявления узлов.
   Для каждого листа ищется первый переход в порядке объявления: сначала у
   самого листа, потом у предков, изнутри наружу. Переход подходит, если
   сработал триггер и guard отсутствует или вернул `true`. Так внутренний
   переход имеет приоритет над внешним, как в Harel/SCXML.
2. **Конфликты.** Два выбранных перехода конфликтуют, если их exit-множества
   пересекаются. Тогда побеждает выбранный раньше. Поэтому переходы в разных
   регионах срабатывают вместе, а переход родителя над регионами выполняется
   один раз.
3. **Exit-множество** перехода — активные потомки LCCA(source, target).
   LCCA — ближайший общий compound-предок; для self-loop это родитель source,
   то есть переход внешний: source выходит и входит заново. Exit идёт
   изнутри наружу, потом по обратному порядку объявления. Перед выходом из
   узла с `HistoryState` запоминается shallow (активный ребёнок) или deep
   (активные листья).
4. **Enter-множество**: цепочка от LCCA до target, дальше рекурсивно.
   Compound входит в `initial`. Parallel входит во все регионы. `HistoryState`
   восстанавливает запомненное, а если ничего нет — `default` или `initial`
   родителя. Enter идёт снаружи внутрь, в порядке объявления.
5. **Результат**: `StepResult.Transitioned(transitions, exited, entered,
   configuration)` или `StepResult.Ignored`. Для таймеров результат содержит
   `timersToStart` (переходы `After` из вошедших узлов) и `timersToCancel`
   (из вышедших узлов).

`StateChartRuntime.initialConfiguration()` выполняет вход в `initial`.
`step(configuration, state, action)` обрабатывает action.
`fire(configuration, state, timer)` обрабатывает таймер: если его source ещё
активен, срабатывает этот переход, если guard пропускает.

## Валидация

К текущим проверкам добавляются:

- неизвестный родитель; цикл в цепочке родителей;
- `CompoundState.initial` не является его ребёнком;
- у `ParallelState` меньше двух регионов;
- `HistoryState` с детьми или вне compound/parallel; `default` вне родителя;
- переход, чей source — `HistoryState`;
- `Trigger.After` с неположительной задержкой.

## Mermaid

Вложенные `state X { ... }`, регионы через `--`. History выводится как
`[H]` / `[H*]` в виде подписанного узла. Таймер выводится как `after 5s`.

## Пути и conformance

- `shortestPathTo` и `transitionCoveragePaths` работают на графе
  конфигураций (BFS из начальной конфигурации через `step`). Guard'ы при этом
  считаются истинными, а таймеры — шагами.
- `StateChartConformance` принимает `stateIdOf`, возвращающий активный лист, и
  ищет переход у листа и его предков. Переход в compound считается верным,
  если новый лист входит в enter-множество.

## Адаптер к Store (фаза 4)

Без изменений ядра, через один catch-all обработчик:

```kotlin
data class ChartState<C>(val configuration: StateConfiguration, val context: C) : State

val store = StateChartStore<C, A, E>(
    definition = chart,
    context = initialContext,
    guards = mapOf("canRetry" to { s, _ -> s.context.attempts < 3 }),
    effects = mapOf("countAttempt" to { ctx, _ -> ctx.copy(attempts = ctx.attempts + 1) }),
    onEnter = mapOf(loading to { /* launch работу, emit event */ }),
)
```

- Класс Koma-state не меняется (`ChartState`), поэтому Koma не делает
  exit/enter. Время жизни работы под узлом держит адаптер: `launch` в
  `onEnter` получает `LaunchLane` узла, и при выходе из узла адаптер
  вызывает `cancelLaunch(lane)`.
- Таймеры запускаются как `launch { delay(d); transaction { … fire … } }` с
  lane перехода и отменяются так же.
- Порядок в одном шаге: exit-обработчики (изнутри наружу), затем effects
  переходов (в порядке выбора), затем enter-обработчики (снаружи внутрь). Всё
  выполняется одной транзакцией Koma, поэтому UI видит одно новое состояние.
- Store остаётся обычным `Store<ChartState<C>, A, E>`: `koma-test`,
  `Plugin`, `StateSaver` и Compose работают без изменений.

## Порядок волн

1. Иерархия: модель, валидация, Mermaid, runtime с конфигурацией, пути,
   conformance.
2. History.
3. Параллельные регионы.
4. Таймеры (`Trigger.After`).
5. Адаптер `StateChartStore` с effects, onEnter/onExit и таймерами.
6. Пример «мессенджер» в тестах и обновление roadmap.

Каждая волна — отдельная ветка и PR в форке, со своими property-тестами.
