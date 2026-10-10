package actron.diagnostics.sdk

import io.sentry.Breadcrumb
import io.sentry.IScopes
import io.sentry.SentryEvent
import actron.diagnostics.*

/** Caller owns Sentry initialization/consent/transport. Events use local extras, not global scope keys. */
class SentryDiagnosticSink(private val scopes: IScopes) : DiagnosticSink {
    override fun emit(event: CausalTraceEvent) {
        val attributes = event.attributes()
        val breadcrumb = Breadcrumb().apply {
            category = "actron"; type = "statechart"; message = event.outcome
            attributes.forEach { (key, value) -> setData(key, value) }
        }
        scopes.addBreadcrumb(breadcrumb)
        event.failureType?.let { failure ->
            val report = SentryEvent(BehaviouralDiagnosticException(failure))
            attributes.forEach { (key, value) -> report.setExtra(key, value) }
            scopes.captureEvent(report)
        }
    }
}
