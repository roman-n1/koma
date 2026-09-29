# Runtime policy APIs keep using enum

- Updated: 2026-05-07

## Background

Runtime policies such as `PendingActionPolicy` and `PluginExecutionPolicy` are currently exposed as `enum`.

There is the point that moving these to `sealed interface` might improve future extensibility.
On the other hand, Koma's public policy APIs are designed not as strategy interfaces that allow users to implement their own, but as a small number of high-level modes interpreted by the Store.

## Decision

`PendingActionPolicy`, `PluginExecutionPolicy`, and similar runtime policies added in the future keep using `enum` in principle.

`sealed interface` is used only when each case should carry a payload, as with `LaunchControl`, or when the shape of the variants goes beyond simple named modes.

The representation is not forcibly unified across runtime policies.
A policy that chooses among a small number of fixed modes is an `enum`; a policy with payload-carrying variants or asymmetric input shapes is a `sealed interface`; the choice follows the shape of the concept.

## Notes

- `enum` has an intuitive meaning as a type representing "a closed, small set of modes", and its purpose is easy to read from the call site.
- Making it a `sealed interface` does not enable external users to implement their own policies. Since in Koma the library side gives meaning to policies, the extensibility of mere named modes is sufficient with `enum`.
- Changing from `enum` to `sealed interface` is a public API / ABI change and has a compatibility cost.
- `koma-core` has a JVM target and Java compilation support enabled, so the advantage of `enum` being easy to handle from Java is not discarded either.
- If, in the future, a policy needs payload-carrying variants such as `KeepUntil(...)` or different input shapes per case, conversion to `sealed interface` or introduction of a separate type will be reconsidered at that point.
