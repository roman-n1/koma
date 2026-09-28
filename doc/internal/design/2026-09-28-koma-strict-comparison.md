# koma-strict и koma-statechart: сравнение и мост

- 更新日: 2026-09-28
- Предмет: [TBSten/koma-strict](https://github.com/TBSten/koma-strict) — KSP-плагин поверх
  Koma (MIT, experimental), и наш модуль `koma-statechart` (форк `roman-n1/koma`).
- Вопрос Романа: пересекаются ли они, можно ли их использовать в одном мессенджере, и нужен ли
  мост между ними.

## Источник и что проверено

Исследование только на чтение. Материал — неглубокий клон репозитория koma-strict (коммит
`6a1d201`, 2026-08-05), сделанный координатором вне репозитория `koma`: README, `doc/internal/`,
исходники `koma-strict-runtime`, `koma-strict-diagram`, `koma-strict-ksp/shared`. В наш репозиторий
ничего не скопировано. Сборку koma-strict и сайт Maven Central я не открывал.

Метки в тексте:

- **[проверено]** — прочитано в исходниках или README клона;
- **[вывод]** — моё заключение из прочитанного, не проверенное запуском.

## Что такое koma-strict

- **[проверено]** KSP-плагин: пользователь пишет sealed-иерархию состояний с аннотациями
  `@StoreSpec(initial = [...])`, `@OnEnter(nextState, emit)`, `@OnExit(emit)`,
  `@OnAction<A>(nextState, emit)`, `@OnRecover<E>(nextState, emit)`, `Stay`; плагин генерирует
  типобезопасный DSL (`createLceStore(...)`, `states(...)`, `<State>.actions(...)`,
  `nextState.toXxx()`, `emitXxx(...)`). Результат — обычный `Store<S, A, E>` Koma.
- **[проверено]** Идея «strict»: необъявленный переход или событие нельзя написать (функции
  `toXxx`/`emitXxx` существуют только для объявленных), а обработку объявленного состояния и
  действия нельзя забыть (обязательные именованные параметры). Всё — ошибки компиляции.
- **[проверено]** Иерархия — это sealed-группы: `@OnAction` на промежуточном sealed-типе или корне
  — «общее действие», которое генератор раскладывает в блок `state<Leaf> {}` каждого листа
  (иерархический dispatch Koma не используется). Одно и то же действие на предке и потомке —
  ошибка KSP. Цели переходов — только конкретные листья.
- **[проверено]** `nextState = [Stay::class, X::class]` — обработчик может остаться или перейти в
  `X`; какой вариант — решает код обработчика во время выполнения. Guard'ов как сущности нет.
- **[проверено]** Модули и координаты: группа `me.tbsten.koma.strict`, версия `0.3.0` в
  `libs.versions.toml`; `koma-strict-runtime` (`api` на `io.github.koma-kt:koma-core`, в клоне
  `4.0.0-rc03`), `koma-strict-ksp`, `koma-strict-diagram` (чистый KMP, без KSP/Compose, таргеты
  android/jvm/js/wasmJs/iosArm64/iosSimulatorArm64), `koma-strict-diagram-compose`, IDE-плагин.
  Публикация через `publishToMavenCentral()`; README утверждает, что модули опубликованы, и
  показывает бейдж Maven Central. Сам артефакт на Central я не открывал.
- **[проверено]** Диаграммы: KSP-опция `koma.strict.generateDiagramModel` (по умолчанию `false`)
  генерирует `<Root>DiagramModel: StoreDiagramModel` и `<Root>.diagramStateId(): StateId`.
  Рисует их Compose-панель `StoreDiagramPanel`. Экспорт в Mermaid/PlantUML в README и
  `doc/internal/generate-state-diagrams.md` описан как «только дизайн, не реализовано».
- **[проверено]** `@FlowSpec` (именованные пути для подсветки на диаграмме) — пока только
  объявление: процессор его не читает, `flows` в сгенерированной модели всегда пуст.

### IR диаграммы (`koma-strict-diagram`, пакет `me.tbsten.koma.strict.diagram.model`)

**[проверено]** по исходникам:

- `StoreDiagramModel(root: RootState, initial: List<StateId>, reachableLeafIds: Set<StateId>, degraded, unresolved, error, packageName, flows)`;
- дерево `DiagramStateNode`: `RootState` / `GroupState(enter?)` / `LeafState(enter?)`, у каждого
  `actions: List<ActionTrigger>`, `recovers: List<RecoverTrigger>`, `exit: ExitInfo?`;
- `StateId(segments: List<String>)` — путь от корня (`StateId("Stable", "Idle")`), `dotted`;
- `ActionTrigger(actionName, targets: List<StateId>, stay, emits, unresolvedTargets, source, actionRef)`,
  где `actionName` — простое имя типа действия, `actionRef` — путь от пакета (`FeedAction.Retry`);
- `EnterTrigger(targets, stay, emits, ...)` (в сгенерированной модели только у листьев),
  `RecoverTrigger(exceptionName, targets, stay, emits, ..., exceptionRef)`, `ExitInfo(emits)`;
- `Reachability.compute(root, initial)` — достижимые листья; сгенерированная модель вычисляет их
  при инициализации.
- `diagramStateId()` генерируется как `when (this) { is X.Y -> StateId("Y", ...) }` без `else`, с
  именами-литералами. Это устойчиво к R8 **[вывод]**: имена вписаны строками при компиляции, а `is`
  переживает переименование классов.

## Пересечение

| Возможность | koma-strict | koma-statechart |
|---|---|---|
| Источник модели | аннотации на sealed-типах, KSP | данные `StateChartDefinition`, пишутся руками |
| Типобезопасный DSL переходов и событий | да, compile-time | нет: метки guard/effect — строки, проверка при сборке Store |
| Проверка полноты обработчиков | да, compile-time | нет (не нужна: runtime сам исполняет модель) |
| Иерархия | sealed-группы, общие действия раскладываются по листьям; exit/enter как в Koma (по классу листа) | compound-состояния, LCA, exit изнутри наружу, enter снаружи внутрь |
| Параллельные регионы | нет | да |
| History (shallow/deep) | нет | да |
| Таймеры | нет (руками через `launch`) | `Trigger.After`, запускает `StateChartStore` |
| Guard'ы | нет; ветвление внутри обработчика (`[Stay, X]`, несколько целей) | именованные guard'ы в модели |
| Recover/exit | `@OnRecover`, `@OnExit(emit)` | recover — через `store { recover {} }`; exit — хук `onExit` |
| Валидация | ошибки KSP (структура), недостижимые листья | `validate()`: 19 видов проблем, включая неоднозначные переходы и таймеры |
| Диаграммы | IR + Compose-панель с живой подсветкой; Mermaid/PlantUML не реализованы | `toMermaid()`; живой подсветки нет |
| Генерация путей, покрытие переходов | нет (`@FlowSpec` пока только объявление) | `shortestPathTo`, `transitionCoveragePaths` |
| Проверка работающего Store по модели | нет | `StateChartConformance` (Plugin) |
| IDE-плагин | да | нет |
| Где живёт состояние | пользовательский sealed-тип | `ChartState<C>` (конфигурация + контекст + таймеры) |

Коротко: **[вывод]** koma-strict делает обычную Koma-машину строгой на этапе компиляции и
показывает её; koma-statechart добавляет семантику, которой в Koma нет (регионы, history, таймеры,
LCA), и инструменты тестирования по модели. Пересекаются они только в «модель переходов как данные
+ картинка».

## Можно ли использовать оба в одном проекте

**[вывод]** Да, но не на одном и том же Store:

- `StateChartStore` держит состояние одним классом `ChartState<C>`, а koma-strict требует
  sealed-корень с `@StoreSpec`. Один Store может быть только одним из двух.
- Разумное разделение для мессенджера: простые экраны (LCE, формы) — koma-strict; сложные машины
  с регионами, history и таймерами (соединение + чат) — koma-statechart.
- **Зависимости.** Оба тянут `koma-core`: koma-strict — официальный `io.github.koma-kt:koma-core`
  (в клоне `4.0.0-rc03`), форк — свой `koma-core`. В форке сейчас у `koma-core` та же группа
  `io.github.koma-kt` (своя группа `io.github.roman-n1` только у `koma-statechart`), поэтому при
  composite build (`includeBuild`) Gradle подставит форковый `koma-core` и вместо транзитивной
  зависимости koma-strict — классы не задвоятся. Совместимость API `4.0.0-rc03` → `4.0.0` не
  проверял. Если форк когда-нибудь опубликует `koma-core` под `io.github.roman-n1`, понадобится
  правило `dependencySubstitution` (или capability), иначе в classpath окажутся два `koma-core` с
  одинаковыми классами.
- Имена: оба модуля объявляют `StateId` (`me.tbsten.koma.strict.diagram.model.StateId` и
  `koma.statechart.StateId`); в файле, где нужны оба, — `import ... as`.

## Мост: koma-strict IR → `StateChartDefinition`

Цель **[вывод]**: для Store, написанного на koma-strict, получить инструменты koma-statechart без
переписывания: `validate()`, `toMermaid()` (которого в koma-strict ещё нет), пути для model-based
тестов и `StateChartConformance` как плагин на этом Store.

### API

```kotlin
// отдельный модуль koma-statechart-strict: зависит от koma-statechart и koma-strict-diagram
@ExperimentalKomaApi
fun StoreDiagramModel.toStateChart(
    matchers: Map<String, ActionMatcher>,   // actionRef -> ActionMatcher.of<A>("Name")
): StrictChart

@ExperimentalKomaApi
class StrictChart(
    val definition: StateChartDefinition,
    val skipped: List<String>,               // что не перенесено и почему
) {
    fun stateIdOf(id: me.tbsten.koma.strict.diagram.model.StateId): StateId
}
```

Использование в тесте:

```kotlin
val chart = LceStateDiagramModel.toStateChart(mapOf(
    "LceAction.Reload" to ActionMatcher.of<LceAction.Reload>("Reload"),
    "LceAction.Retry" to ActionMatcher.of<LceAction.Retry>("Retry"),
))
val conformance = StateChartConformance<LceState, LceAction, LceEvent>(chart.definition) {
    chart.stateIdOf(it.diagramStateId())
}
```

### Отображение

| koma-strict IR | `StateChartDefinition` |
|---|---|
| `RootState` | неявный корень (узла нет) |
| `GroupState` | `CompoundState(id, initial = первый потомок-лист в порядке объявления)`. Initial ни на что не влияет: цели переходов koma-strict — всегда листья |
| `LeafState` | `AtomicState` |
| `StateId(segments)` | `StateId(segments.joinToString("."))`, то есть `dotted` — строка, R8 не трогает |
| `initial` (список) | первый элемент — `initial`; остальные только в `skipped` (в `StateChartDefinition` одно начальное состояние). Conformance всё равно принимает старт из любого объявленного листа |
| `ActionTrigger` на листе или группе | по переходу на каждую цель, `on = matchers[actionRef]`. Переход с группы — переход из compound: в koma-strict общее действие раскладывается по листьям, а одинаковое действие на предке и потомке запрещено, так что приоритет «внутренний раньше внешнего» ничего не меняет |
| несколько целей (`nextState = [X, Y]`) | несколько переходов с синтетическими guard'ами `"Source->X"`, `"Source->Y"` — иначе `validate()` сообщит `AmbiguousTransitions`. Guard'ы только метки: пути их игнорируют, conformance тоже |
| `stay = true` | перехода нет. В Koma `Stay` не меняет состояние, а self-loop в statechart — внешний (exit/enter), это разная семантика; запись в `skipped` |
| `EnterTrigger` | переход с синтетическим матчером `ActionMatcher("enter:<Leaf>", type = StrictEnter::class)`, где `StrictEnter` — внутренний маркер-`Action`, который никто не отправляет. Conformance засчитывает такие переходы как «автоматические» (изменение без неиспользованного action — правило из KDoc `StateChartConformance`). При воспроизведении путей шаг с таким матчером — «дать `enter` отработать», а не dispatch |
| `RecoverTrigger` | то же с маркером `StrictRecover` и именем `recover:<Exception>` |
| `ExitInfo` | не переносится (переходов нет; события в модели statechart не описываются) |
| `unresolvedTargets`, `degraded`, `unresolved` | модель не строится: `IllegalArgumentException` с причиной |
| `reachableLeafIds` | в тесте моста сверяется с `reachableStates()` |

Почему `matchers` обязателен: IR хранит действие только строками (`actionName`, `actionRef`).
`ActionMatcher(name)` без типа сравнивает `simpleName` во время выполнения, а R8 его переименует;
поэтому мост не создаёт матчеры сам, а требует `ActionMatcher.of<A>(name)` от вызывающего и падает
на отсутствующем ключе. **[вывод]** Если koma-strict когда-нибудь начнёт генерировать
`KClass`-ссылки в модели (у KSP они есть), карту можно будет генерировать.

### Чего мост не даст

- Параллельных регионов, history и таймеров в koma-strict нет — мост их и не породит; это
  инструменты для плоских и групповых машин.
- `StateChartStore` на такой модели запускать бессмысленно: поведение Store описано обработчиками
  koma-strict, а не guard'ами и effect'ами чарта.
- Guard'ы синтетические, поэтому пути — только «структурные»: какое действие приведёт к нужной
  ветке, решает тест (как и в `MessengerChartTest`).

### Решение: только дизайн

Мост требует зависимости на `me.tbsten.koma.strict:koma-strict-diagram`, которой в модуле нет, и
по правилам волны новые зависимости не добавляются. Поэтому код не написан. Если Роман захочет
его, место — **отдельный модуль** `koma-statechart-strict` (koma-statechart остаётся без внешних
зависимостей), около 150 строк плюс тесты на `SampleModels`-подобных фикстурах, и версия
`koma-strict-diagram` в version catalog. Риски: IR koma-strict помечен как experimental и может
меняться до 1.0; сгенерированная модель пока не содержит `flows`, `source` и `enter` у групп.

## Что можно взять друг у друга (идеи, не планы)

- **[вывод]** Из koma-strict для нас: живая подсветка текущего состояния на диаграмме (у нас есть
  `ChartState.activeLeaves`, нужен только рендерер) и snapshot/drift-check диаграммы в CI (у нас он
  уже есть в виде теста, сравнивающего `toMermaid()` с текстом в KDoc).
- **[вывод]** Для koma-strict от нас: `toMermaid()`-подобный экспорт, генерация путей и
  conformance-плагин. Это возможный повод для issue в koma-strict, если мост окажется полезным;
  ничего не отправлялось.
