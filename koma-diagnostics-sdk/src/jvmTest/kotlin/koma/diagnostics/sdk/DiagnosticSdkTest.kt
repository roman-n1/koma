package koma.diagnostics.sdk

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.sentry.*
import koma.core.*
import koma.diagnostics.*
import koma.observability.StoreInstanceId
import koma.statechart.*
import koma.statechart.machine.*
import java.lang.reflect.Proxy
import kotlin.test.*

class DiagnosticSdkTest {
    private data object Go : Action
    private fun event(parent: TraceOrigin? = null, failure: String? = null): CausalTraceEvent {
        val origin = TraceOrigin(StoreInstanceId("s"), InputId(if (parent == null) 1 else 2), if (parent == null) 1 else 2)
        return CausalTraceEvent(TraceId("trace"), DefinitionId("workflow"), DefinitionVersion("1"), origin, parent ?: origin, parent,
            "dispatch", MachineTime.Zero, null, null, listOf(TransitionId(0)), listOf(CommandId(1)), emptyList(),
            DecisionExplanation(emptySet(), emptyList(), emptyList()), if (failure == null) "handled" else "failed", failure, false)
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
            assertEquals("trace", spans[1].attributes.get(AttributeKey.stringKey("koma.trace")))
            assertEquals(listOf("transition", "command.registered"), spans[0].events.map { it.name })
        } finally { provider.close() }
    }

    @Test fun otelMarksUnavailableParentInsteadOfInventingAnSdkParent() {
        val exporter = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        try {
            OpenTelemetryDiagnosticSink(provider.get("test")).emit(event(TraceOrigin(StoreInstanceId("missing"), InputId(99), 99)))
            assertTrue(exporter.finishedSpanItems.single().attributes.get(AttributeKey.booleanKey("koma.otel.parent.missing")) == true)
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
        assertEquals("workflow", reports.single().getExtra("koma.definition"))
        assertTrue(reports.single().throwable is BehaviouralDiagnosticException)
        assertEquals("Koma workflow failure: SafeType", reports.single().throwable?.message)
    }
}
