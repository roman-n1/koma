@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package consumer

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PublishedSmokeTest {
    @Test
    fun publishedStoreChartMachineAndMessageBusWorkTogether() = runTest {
        val counter = counter(this)
        val receiver = receiver(this)
        val chart = chart(this)
        val machine = runningMachine(this)
        try {
            listOf(counter, receiver, chart, machine).forEach { it.start() }
            runCurrent()
            counter.dispatch(Increment)
            chart.dispatch(Increment)
            machine.dispatch(Increment)
            runCurrent()
            assertEquals(1, counter.currentState.count)
            assertEquals(1, receiver.currentState.count, "message plugin was obtained from the published artifact")
            assertEquals(1, chart.currentState.context)
            assertEquals(1, machine.currentState.context)
        } finally {
            listOf(counter, receiver, chart, machine).forEach { it.close() }
        }
    }
}
