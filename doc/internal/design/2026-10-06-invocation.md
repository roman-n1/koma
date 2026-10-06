# Scoped child-machine composition

- Updated: 2026-10-06

MachineGroup remains the production coordinator for independently executing stores: admission,
per-member FIFO, ordered event routing, consistent cuts and per-member failure isolation already
have explicit contracts in Group.kt. A group broadcast is not a distributed transaction.

`InvokedMachine` adds a deterministic pure parent/child composition primitive. Its complete
snapshot is parent snapshot + child snapshot + owning parent ActivationId. A child lives while
that exact activation lives; exit and re-entry creates a new addressed instance, even for a
parent self-loop. Child inputs must carry that owner id, so a late input cannot hit its replacement.

One caller decides inputs sequentially. Parent decisions synchronize child lifecycle before
routing parent effects to it. Child effects map to parent inputs; mapped inputs drain in FIFO
order within the same composite decision. A bounded delivery loop detects feedback livelock.
Routing never reads another partially committed Store. Parent/child snapshots commit together
at the final stable point; any failed decision rolls the composite step back before intents execute.

Returned child decisions are addressed by owner activation. Cancelled owners/scopes are explicit;
`childCommands` and `childTimers` contain only work alive in the final snapshot. A temporary child
that starts and stops inside one composite step does not run commands. The executor must include
owner identity in its command/timer keys and commit the composite snapshot before executing.

This primitive does not erase application types into an actor registry or impose a distributed
scheduler. Use one typed invocation for a parent/child pair; deeper trees can compose these snapshots in
application rules or use MachineGroup for independently persisted and isolated members. Snapshot/outbox migrations must retain owner identity.
