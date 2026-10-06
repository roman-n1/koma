package koma.statechart.machine

import koma.core.Action
import koma.core.Event
import koma.statechart.isConsistent

/** A semantic snapshot migration, independent of file-format codecs. */
class SnapshotMigration<From, To>(
    val from: DefinitionVersion,
    val to: DefinitionVersion,
    val transform: (MachineSnapshot<From>) -> MachineSnapshot<To>,
) {
    init { require(from != to) { "[Koma] A migration must change the version" } }
}

/** Checks restored data before it can allocate work or accept new inputs. */
fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.validateSnapshot(snapshot: MachineSnapshot<C>): List<String> = buildList {
    if (snapshot.definition != id) add("definition differs")
    if (snapshot.version != version) add("version differs")
    if (snapshot.revision < 0) add("negative revision")
    if (snapshot.isStarted) {
        if (!chart.isConsistent(snapshot.configuration)) add("inconsistent configuration/history")
        if (snapshot.activations.keys != snapshot.configuration.active) add("activations do not match active nodes")
        if (snapshot.activations.values.toSet().size != snapshot.activations.size) add("duplicate activation ids")
    } else if (snapshot.configuration.active.isNotEmpty() || snapshot.configuration.history.isNotEmpty() || snapshot.activations.isNotEmpty() || snapshot.commands.isNotEmpty() || snapshot.timers.isNotEmpty()) {
        add("unstarted snapshot contains active work")
    }
    if (snapshot.activations.values.any { it.value <= 0 || it.value > snapshot.counters.activations }) add("invalid activation counters")
    if (snapshot.commands.any { (id, record) -> id.value <= 0 || id.value > snapshot.counters.commands || record.scope !in snapshot.activations.values }) add("invalid command ids/scopes")
    if (snapshot.timers.any { (id, record) ->
        val transition = chart.transitions.getOrNull(record.transition.index)
        id.value <= 0 || id.value > snapshot.counters.timers || transition?.isTimer != true || snapshot.activations[transition.source] != record.activation
    }) add("invalid timers")
    if (listOf(snapshot.counters.activations, snapshot.counters.commands, snapshot.counters.timers, snapshot.counters.effects).any { it < 0 }) add("negative counters")
    addAll(checkInvariantsForRestore(snapshot))
}

private fun <C, A : Action, CMD, E : Event> Machine<C, A, CMD, E>.checkInvariantsForRestore(snapshot: MachineSnapshot<C>): List<String> =
    if (snapshot.definition == id && snapshot.version == version && snapshot.isStarted) checkInvariants(snapshot).map { "invariant ${it.name} failed" } else emptyList()

/** Applies one typed migration and validates the target's complete configuration, work and invariants. */
fun <From, To, A : Action, CMD, E : Event> Machine<To, A, CMD, E>.migrateSnapshot(
    snapshot: MachineSnapshot<From>, migration: SnapshotMigration<From, To>,
): MachineSnapshot<To> {
    require(snapshot.definition == id && snapshot.version == migration.from && version == migration.to) { "[Koma] Migration endpoints do not match" }
    val migrated = migration.transform(snapshot)
    val issues = validateSnapshot(migrated)
    require(issues.isEmpty()) { "[Koma] Migrated snapshot is invalid: ${issues.joinToString()}" }
    return migrated
}

/** Version-chain migration for an unchanged context type; duplicate and cyclic routes are rejected. */
class SnapshotMigrations<C>(migrations: List<SnapshotMigration<C, C>>) {
    private val routes = migrations.associateBy { it.from }
    init {
        require(routes.size == migrations.size) { "[Koma] Ambiguous migration routes" }
        for (start in routes.keys) {
            val visited = mutableSetOf<DefinitionVersion>()
            var current = start
            while (current in routes) {
                require(visited.add(current)) { "[Koma] Cyclic migration routes" }
                current = routes.getValue(current).to
            }
        }
    }

    /** Migrates every edge in order; callers still validate the final snapshot with the target machine. */
    fun migrate(snapshot: MachineSnapshot<C>, to: DefinitionVersion): MachineSnapshot<C> {
        var current = snapshot
        while (current.version != to) {
            val route = requireNotNull(routes[current.version]) { "[Koma] No migration from ${current.version} to $to" }
            val next = route.transform(current)
            require(next.definition == current.definition && next.version == route.to) { "[Koma] Migration returned wrong identity/version" }
            current = next
        }
        return current
    }
}
