# Actron design principles

- Updated: 2026-04-23

## Background

Notes on individual policies such as `PendingActionPolicy` and `MiddlewareExecutionPolicy` have been accumulating, but on their own they make it hard to read back later "why the specification goes in that direction".

Here we organize the design axes behind Actron's individual specifications.
This document exists to spell out the reasoning behind individual policy and API decisions as higher-level design principles.

## Policy

Actron's basic design policy is as follows.

- Actron is a state machine built around "what state are we in now, and what happens in that state" rather than "what do we do when this action arrives".
- An action is a trigger for a state transition or for starting processing; the lifetime of long-lived work belongs to the state.
- Store creation and the start of side effects are separated. A Store is first created as a declaration, and side effects run after start.
- Middleware is treated as an independent extension point on the outside, not as part of the Store's own pipeline.
- What may be changed through overrides is environment configuration, not the state transition structure itself.
- Business failures can be handled inside the state machine, but fatal failures and system failures are let out.

## Notes

- enter/exit, state scope switching and pending action handling take effect when `state::class` changes because a "change of state phase" is weighted more heavily than a "difference in values".
- `action { launch { ... } }` hangs off the state scope rather than the individual action because of the framing "the action is the trigger, and the state owns the work in progress".
- The Store starts lazily, and `currentState` and a restored state snapshot can be read before start, in order to separate "the Store as a declaration" from "the Store whose side effects have started running".
- Middleware defaults to concurrent execution so that ordering dependencies between middleware are not made a strong design assumption.
- `overrides` cannot touch state/action handlers and can only override configuration, following the line that "what state machine this Store is" is close to its identity and is not something to change for tests or debugging.
- `recover{}` and `exceptionHandler` are separate, and `Error` and cancellation are not used as material for state transitions, in order to separate business errors from failures of the execution system.

## Related

- [Rejection of the PendingActionPolicy extension proposal](../adr/2026-04-22-pending-action-policy.md)
- [Middleware execution policy defaults to concurrent](../adr/2026-04-23-middleware-execution-policy.md)
