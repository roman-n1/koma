package actron.statechart.test

import actron.core.ExperimentalActronApi
import actron.statechart.machine.MachineClock
import actron.statechart.machine.MachineTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration.Companion.milliseconds

/**
 * A [MachineClock] on the virtual time of a [TestCoroutineScheduler]: `now()` is the scheduler's
 * current time past [originMillis] (zero by default), and a timer's `delayUntil` is a `delay` the
 * scheduler advances with `advanceTimeBy` and `advanceUntilIdle`. A machine under test fires its
 * timers when the test says so, not when the wall clock does. [startingNow] makes a clock at zero
 * from the scheduler's current time, so stores created one after another on one scheduler have
 * comparable times.
 */
@ExperimentalActronApi
class VirtualMachineClock(private val scheduler: TestCoroutineScheduler, private val originMillis: Long = 0) : MachineClock {
    override fun now(): MachineTime = MachineTime((scheduler.currentTime - originMillis).milliseconds)

    override suspend fun delayUntil(deadline: MachineTime) {
        val remaining = deadline - now()
        if (remaining.isPositive()) delay(remaining)
    }

    companion object {
        /** A clock whose zero is the scheduler's current time. */
        fun startingNow(scheduler: TestCoroutineScheduler): VirtualMachineClock = VirtualMachineClock(scheduler, scheduler.currentTime)
    }
}

/** The virtual clock of this test's scheduler. */
@ExperimentalActronApi
fun TestScope.machineClock(): VirtualMachineClock = VirtualMachineClock(testScheduler)
