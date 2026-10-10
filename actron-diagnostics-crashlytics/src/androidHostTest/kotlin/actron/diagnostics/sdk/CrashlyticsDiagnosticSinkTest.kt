package actron.diagnostics.sdk

import com.google.firebase.crashlytics.*
import actron.core.InputId
import actron.diagnostics.*
import actron.observability.StoreInstanceId
import actron.statechart.machine.*
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import kotlin.test.*

class CrashlyticsDiagnosticSinkTest {
    @Test fun sendsPerReportKeysWithoutGlobalCustomKeyMutationOrSdkInitialization() {
        val sdk = mock(FirebaseCrashlytics::class.java)
        val origin = TraceOrigin(StoreInstanceId("store"), InputId(1), 1)
        val event = CausalTraceEvent(TraceId("trace"), DefinitionId("workflow"), DefinitionVersion("1"), origin, origin, null,
            "dispatch", MachineTime.Zero, null, null, emptyList(), emptyList(), emptyList(),
            DecisionExplanation(emptySet(), emptyList(), emptyList()), "failed", "SafeType", false)
        CrashlyticsDiagnosticSink(sdk).emit(event)
        verify(sdk).log("Actron workflow dispatch failed")
        val failure = ArgumentCaptor.forClass(Throwable::class.java)
        verify(sdk).recordException(failure.capture(), any(CustomKeysAndValues::class.java))
        assertEquals("Actron workflow failure: SafeType", failure.value.message)
        verifyNoMoreInteractions(sdk)
    }
}
