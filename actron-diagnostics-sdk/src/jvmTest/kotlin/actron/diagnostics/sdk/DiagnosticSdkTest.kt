package actron.diagnostics.sdk

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.sentry.*
import actron.core.*
import actron.diagnostics.*
import actron.observability.StoreInstanceId
import actron.statechart.*
import actron.statechart.machine.*
import java.lang.reflect.Proxy
import kotlin.test.*

class DiagnosticSdkTest {
    private data object Go : Action
    private fun event(parent: TraceOrigin? = null, failure: String? = null): CausalTraceEvent {
        val origin = TraceOrigin(StoreInstanceId("s"), InputId(if (parent == null) 1 else 2), if (parent == null) 1 else 2)
        val lineage = if (parent == null) TraceLineage.Root(origin, TraceId("trace")) else TraceLineage.Linked(TraceId("trace"), parent, parent, false)
        val faults = failure?.let { listOf(TraceFault.of(FailureSource.Decision, actron.observability.FailureDescriptor(it))) }.orEmpty()
        return CausalTraceEvent(DefinitionId("workflow"), DefinitionVersion("1"), origin, lineage,
            "dispatch", MachineTime.Zero, TraceProvenance.Local, listOf(TransitionId(0)), listOf(CommandId(1)), emptyList(),
            TraceAssessment.Decided(DecisionExplanation(emptySet(), emptyList(), emptyList()), if (failure == null) "handled" else "failed", faults))
    }

    @Test fun otelUsesExplicitParentForEndedSpansAndClosesEveryReceiptSpan() {
        val exporter = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        try {
            val sink = OpenTelemetryDiagnosticSink(provider.get("test"))
            val first = event(); sink.emit(first); sink.emit(event(first.origin, "RuleFailure"))
            val spans = exporter.finishedSpanItems
            assertEquals(2, spans.size)
            assertEquals(spans[0].spanId, spans[1].parentSpanId)
            assertEquals(spans[0].traceId, spans[1].traceId)
            assertEquals(StatusCode.ERROR, spans[1].status.statusCode)
            assertEquals("trace", spans[1].attributes.get(AttributeKey.stringKey("actron.trace")))
            assertEquals(listOf("transition", "command.registered"), spans[0].events.map { it.name })
        } finally { provider.close() }
    }

    @Test fun otelMarksUnavailableParentInsteadOfInventingAnSdkParent() {
        val exporter = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        try {
            OpenTelemetryDiagnosticSink(provider.get("test")).emit(event(TraceOrigin(StoreInstanceId("missing"), InputId(99), 99)))
            assertTrue(exporter.finishedSpanItems.single().attributes.get(AttributeKey.booleanKey("actron.otel.parent.missing")) == true)
        } finally { provider.close() }
    }

    @Test fun sentryUsesReportLocalMetadataAndDoesNotAlterGlobalScope() {
        val breadcrumbs = mutableListOf<Breadcrumb>(); val reports = mutableListOf<SentryEvent>()
        val scopes = Proxy.newProxyInstance(IScopes::class.java.classLoader, arrayOf(IScopes::class.java)) { _, method, args ->
            when (method.name) {
                "addBreadcrumb" -> { breadcrumbs += args!![0] as Breadcrumb; null }
                "captureEvent" -> { reports += args!![0] as SentryEvent; io.sentry.protocol.SentryId.EMPTY_ID }
                else -> error("Unexpected global SDK mutation ${method.name}")
            }
        } as IScopes
        val sink = SentryDiagnosticSink(scopes)
        sink.emit(event()); sink.emit(event(failure = "SafeType"))
        assertEquals(2, breadcrumbs.size)
        assertEquals("workflow", reports.single().getExtra("actron.definition"))
        assertTrue(reports.single().throwable is BehaviouralDiagnosticException)
        assertEquals("Actron workflow failure: SafeType", reports.single().throwable?.message)
    }
}
