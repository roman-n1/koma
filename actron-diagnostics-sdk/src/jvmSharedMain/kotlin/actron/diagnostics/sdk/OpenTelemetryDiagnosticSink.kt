package actron.diagnostics.sdk

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import actron.diagnostics.*

/** Decision receipt spans, not command IO durations. No thread-local context or SDK initialization. */
class OpenTelemetryDiagnosticSink(private val tracer: Tracer, private val capacity: Int = 100) : DiagnosticSink {
    init { require(capacity > 0) }
    private val parents = linkedMapOf<TraceOrigin, SpanContext>()
    override fun emit(event: CausalTraceEvent) {
        val parentSetup = synchronized(parents) {
            var configure: (SpanBuilder) -> Unit = { it.setNoParent() }
            var missing = event.parent.linked
            event.parent.withParent { origin ->
                if (origin in parents) {
                    val context = parents.getValue(origin)
                    configure = { it.setParent(Context.root().with(Span.wrap(context))) }
                    missing = false
                }
            }
            configure to missing
        }
        val builder: SpanBuilder = tracer.spanBuilder("actron.${event.inputKind}")
        parentSetup.first(builder)
        val attributes: AttributesBuilder = Attributes.builder()
        event.attributes().forEach { (key, value) -> attributes.put(key, value) }
        attributes.put("actron.otel.parent.missing", parentSetup.second)
        val span: Span = builder.setAllAttributes(attributes.build()).startSpan()
        try {
            event.transitions.forEach { span.addEvent("transition", Attributes.builder().put("actron.transition", it.index.toLong()).build()) }
            event.commands.forEach { span.addEvent("command.registered", Attributes.builder().put("actron.command", it.value).build()) }
            event.withFailureType { span.setStatus(StatusCode.ERROR, it) }
            synchronized(parents) {
                parents[event.origin] = span.spanContext
                while (parents.size > capacity) parents.remove(parents.keys.first())
            }
        } finally { span.end() }
    }
}
