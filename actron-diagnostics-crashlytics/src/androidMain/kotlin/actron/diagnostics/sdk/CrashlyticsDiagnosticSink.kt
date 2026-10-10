package actron.diagnostics.sdk

import com.google.firebase.crashlytics.CustomKeysAndValues
import com.google.firebase.crashlytics.FirebaseCrashlytics
import actron.diagnostics.*

/** Android adapter; caller supplies an initialized SDK. No automatic collection or user identifiers. */
class CrashlyticsDiagnosticSink(private val crashlytics: FirebaseCrashlytics) : DiagnosticSink {
    override fun emit(event: CausalTraceEvent) {
        crashlytics.log("Actron ${event.definition.value} ${event.inputKind} ${event.outcome}")
        event.failureType?.let { failure ->
            val keys = CustomKeysAndValues.Builder()
            event.attributes().forEach { (key, value) -> keys.putString(key, value) }
            // Per-report attributes avoid mixing global custom keys between concurrent Stores.
            crashlytics.recordException(BehaviouralDiagnosticException(failure), keys.build())
        }
    }
}
