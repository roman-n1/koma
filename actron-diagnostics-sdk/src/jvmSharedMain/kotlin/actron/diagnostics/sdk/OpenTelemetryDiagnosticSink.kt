package actron.diagnostics.sdk

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import actron.diagnostics.*

/** Decision receipt spans, not command IO durations. No thread-local context or SDK initialization. */
class OpenTelemetryDiagnosticSink(private val tracer: Tracer, private val capacity: Int = 100) : DiagnosticSink {
    init { require(capacity > 0) }
    private val parents = linkedMapOf<TraceOrigin, SpanContext>()
    override fun emit(event: CausalTraceEvent) {
        val parent = synchronized(parents) { event.parent?.let(parents::get) }
        val builder = tracer.spanBuilder("actron.${event.inputKind}")
        if (parent != null) builder.setParent(Context.root().with(Span.wrap(parent))) else builder.setNoParent()
        val attributes = Attributes.builder()
        event.attributes().forEach { (key, value) -> attributes.put(key, value) }
        attributes.put("actron.otel.parent.missing", event.parent != null && parent == null)
        val span = builder.setAllAttributes(attributes.build()).startSpan()
        try {
            event.transitions.forEach { span.addEvent("transition", Attributes.builder().put("actron.transition", it.index.toLong()).build()) }
            event.commands.forEach { span.addEvent("command.registered", Attributes.builder().put("actron.command", it.value).build()) }
            event.failureType?.let { span.setStatus(StatusCode.ERROR, it) }
            synchronized(parents) {
                parents[event.origin] = span.spanContext
                while (parents.size > capacity) parents.remove(parents.keys.first())
            }
        } finally { span.end() }
    }
}
