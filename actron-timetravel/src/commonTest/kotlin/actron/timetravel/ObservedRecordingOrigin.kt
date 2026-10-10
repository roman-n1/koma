package actron.timetravel
internal val <C : Any, A : actron.core.Action, CMD : Any, E : actron.core.Event> MachineRecorder<C, A, CMD, E>.problem: String? get() = (origin as? RecordingOrigin.UnknownBeginning)?.reason
