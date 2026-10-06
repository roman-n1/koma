# Model-based testing and decision explanations

- Updated: 2026-10-06

The first two waves of the gap analysis are additive to the pure Machine API.

* An invariant is a named, pure predicate of a complete MachineSnapshot. Names are unique
  within a machine. Checks include context, configuration, commands and timers. They run on
  stable snapshots, including ignored decisions, never midway through exit/effect/entry.
* `checkInvariants` is shared by tests, replay and inspectors. Runtime enforcement is opt-in
  through `enforceInvariants()`: a violated invariant fails the decision and rolls back the
  complete step before any commands, timers or effects execute. Predicate exceptions are
  reported as violations. Unstarted snapshots are checked only when explicitly requested.
* Explanations capture guard results during the actual decision. They never decide twice or
  call an unvisited guard. Candidates distinguish a false guard, a failed guard, priority
  skipping and losing an exit-set conflict. Action handlers and stale inputs remain visible.
* Exploration executes bounded breadth-first input sequences through the pure machine. It
  does not deduplicate by configuration: equal nodes may have different context, history,
  timers and counters. The application supplies typed payloads/command answers; scheduled
  timers can be generated with their logical deadlines. No commands or external IO execute.
* Limits apply to depth and decisions. A report distinguishes a complete bounded search
  from truncation. Coverage counts actually visited state nodes, selected transitions and
  guard outcomes per transition, including timers; it never claims unexplored paths covered.
* Failures preserve the input prefix, snapshot and invariant name (or decision failure type).
  Shrinking replays from the same initial snapshot, removes chunks then single inputs, and
  preserves the same failure identity. It promises deletion-minimality after an unbounded
  successful pass, not the globally shortest sequence. Limited shrinking reports truncation.
* A fixed seed and deterministic generator reproduce random walks. Generator functions must
  be pure; callers supply payload generation explicitly rather than instantiate action types
  using reflection. Built-in timer generation never moves logical time backwards.

Completion/final/eventless/internal transitions and durable workflow changes belong to the
subsequent waves; they require their own semantics and persistence contracts.
