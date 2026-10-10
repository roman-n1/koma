package actron.core

/**
 * Marker interface for state snapshots managed by a Actron [Store].
 */
interface State

/**
 * Marker interface for inputs dispatched to a Actron [Store], such as user intents or external signals.
 */
interface Action

/**
 * Marker interface for one-off outputs emitted from a Actron [Store].
 *
 * Unlike [State], events are not retained as the Store's current snapshot.
 */
interface Event
