@file:OptIn(actron.core.ExperimentalActronApi::class)
package actron.timetravel.compose
import actron.observability.StoreInstanceId
import actron.timetravel.GroupMismatch
import actron.timetravel.inspect.TimelineItem
import actron.timetravel.inspect.Availability

internal val InspectorState.selected: Int? get() = (selection as? InspectorSelection.Position)?.index
internal val InspectorState.selectedItem: TimelineItem? get() { var result: TimelineItem? = null; withSelected { result = it }; return result }
internal val InspectorState.storeFilter: StoreInstanceId? get() = (filter as? StoreFilter.Single)?.store
internal val TimelineItem.store: StoreInstanceId? get() = subject as? StoreInstanceId
internal val BranchControls.problem: String? get() = (request as? BranchRequest.Refused)?.reason
internal val GroupTimeTravelControls.branch: BranchControls? get() = (travel as? GroupTravel.Experiment)?.branch
internal val GroupTimeTravelControls.branchPosition: Int? get() = (travel as? GroupTravel.Experiment)?.position
internal val GroupReplayControls.divergence: GroupMismatch? get() = (movement as? GroupReplayMovement.Diverged)?.mismatch
internal val GroupReplayControls.selected: GroupReplayPosition? get() = if (hasSelection) selectedPosition() else null
internal val GroupReplayControls.observedVerification: List<GroupMismatch>? get() = (verification as? GroupVerification.Checked)?.problems
internal val GroupReplayControls.forwardUnavailable: String? get() = (forwardAvailability as? Availability.Unavailable)?.reasons?.firstOrNull()
