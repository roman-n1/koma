package actron.diagnostics

import actron.core.InputAttribution
import actron.core.InputId
import actron.observability.FailureDescriptor
import actron.observability.FailureDetails
import actron.observability.StoreInstanceId
import actron.statechart.machine.*

sealed interface TraceParent {
    val linked: Boolean
    fun withParent(accept: (TraceOrigin) -> Unit)
    data object Root : TraceParent {
        override val linked: Boolean = false
        override fun withParent(accept: (TraceOrigin) -> Unit) = Unit
    }
}
data class TraceOrigin(val store: StoreInstanceId, val input: InputAttribution, val sequence: Long) : TraceParent {
    override val linked: Boolean = true
    override fun withParent(accept: (TraceOrigin) -> Unit) = accept(this)
}
data class TraceId(val value: String)
enum class FailureSource { Decision, Command }

/** Input metadata needed to resolve a registering decision; payloads are excluded. */
sealed interface TraceProvenance {
    fun attributes(values: MutableMap<String, String>)
    data object Local : TraceProvenance {
        override fun attributes(values: MutableMap<String, String>) = Unit
    }
    data class Command(val id: CommandId) : TraceProvenance {
        override fun attributes(values: MutableMap<String, String>) { values["actron.command"] = id.value.toString() }
    }
    data class Timer(val id: TimerId) : TraceProvenance {
        override fun attributes(values: MutableMap<String, String>) { values["actron.timer"] = id.value.toString() }
    }
    data class Bridge(val message: MessageId) : TraceProvenance {
        override fun attributes(values: MutableMap<String, String>) = Unit
    }
}

/** Copies parent metadata without retaining recursive event graphs beyond the history budget. */
sealed interface TraceLineage {
    val trace: TraceId
    val root: TraceOrigin
    val parent: TraceParent
    val incomplete: Boolean
    data class Root(override val root: TraceOrigin, override val trace: TraceId = TraceId(root.traceIdentity())) : TraceLineage {
        override val parent: TraceParent = TraceParent.Root
        override val incomplete: Boolean = false
    }
    data class Unresolved(override val root: TraceOrigin) : TraceLineage {
        override val trace: TraceId get() = TraceId(root.traceIdentity())
        override val parent: TraceParent = TraceParent.Root
        override val incomplete: Boolean = true
    }
    data class Linked(override val trace: TraceId, override val root: TraceOrigin,
        override val parent: TraceOrigin, override val incomplete: Boolean) : TraceLineage
}

/** Type metadata of an actual failure; messages and Throwable instances are discarded. */
class TraceFault private constructor(val source: FailureSource, private val details: FailureDetails) {
    override fun toString(): String = "TraceFault(source=$source, details=$details)"
    fun withType(accept: (String) -> Unit) {
        if ("type" in details.attributes) accept(details.attributes.getValue("type"))
    }
    companion object {
        fun of(source: FailureSource, failure: FailureDescriptor): TraceFault {
            var details: FailureDetails = FailureDetails.Redacted
            failure.withType { details = FailureDetails.MetadataOnly(it) }
            return TraceFault(source, details)
        }
    }
}

/** A decision observes executable selection; a persisted/exported receipt cannot invent it. */
sealed interface TraceAssessment {
    val outcome: String
    val faults: List<TraceFault>
    fun withSelection(accept: (DecisionExplanation) -> Unit)
    data class Receipt(override val outcome: String, override val faults: List<TraceFault> = emptyList()) : TraceAssessment {
        override fun withSelection(accept: (DecisionExplanation) -> Unit) = Unit
    }
    data class Decided(val explanation: DecisionExplanation, override val outcome: String,
        override val faults: List<TraceFault> = emptyList()) : TraceAssessment {
        override fun withSelection(accept: (DecisionExplanation) -> Unit) = accept(explanation)
    }
}

private fun TraceOrigin.traceIdentity(): String = when (val attribution = input) {
    is InputId -> "${store.value}/${attribution.value}"
    else -> "${store.value}/unknown-$sequence"
}
