# The inspector branches from a group checkpoint and returns to the unchanged replay

- Updated: 2026-10-03
- Status: experimental debug tooling

The group inspector has one recording cursor, and `GroupReplaySession.branch` can already
create isolated member branches from that prefix. Previously, applications had to wire
their own navigation between these two views. The built-in workflow must make experiments
distinct from recorded history and retain the failure that prompted an experiment.

`GroupTimeTravelControls` owns the active `BranchControls` and its global origin. Branch
Here creates a branch from every member's executor checkpoint without moving or verifying
the replay. An active experiment is not replaced by repeated Branch Here calls. Return to
Replay discards it; branching again starts fresh. The original cursor, member checkpoints,
recording, divergence and verification results are preserved.

The additive `InspectorScreen(state, timeTravel = controls)` overload derives the displayed
mode from the workflow, hides replay navigation and the clickable recording timeline while
experimenting, and shows the origin plus an explicit return action. Existing screen and
ModeBar signatures remain available. The workflow does not mutate `InspectorState.mode`:
it controls its own view, while the diagnostic journal remains a view of the recorded run.

Branch inputs and route functions must be supplied by the application. Recorded route
metadata is descriptive and cannot reconstruct executable effect-to-action mappings.
Default empty routes mean no local bridge deliveries. Command results and external source
data remain scripts, timers run only on the branch's virtual clock, and no live runtime is
invoked. Callers must not independently move the replay session owned by this screen.

Validation covers all-member checkpoint isolation, branching at a divergence, preservation
of awaiting commands and recorded timers, a fresh experiment after returning, and a Compose
UI cycle from replay through a changed branch back to the unchanged recording position.
