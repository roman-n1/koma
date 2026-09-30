# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

- Build all modules: `./gradlew build`
- Run all tests: `./gradlew allTests`
- Run single module tests: `./gradlew :koma-core:jvmTest` (or `:koma-core:allTests`)
- Compose UI tests of `koma-timetravel-compose` run on the JVM desktop runtime: `./gradlew :koma-timetravel-compose:jvmTest`
- Run specific test target: `./gradlew iosSimulatorArm64Test` (targets: jvm, iosArm64, iosSimulatorArm64, js, wasmJs, Android host)
- Debug tests with: `./gradlew jvmTest --info`
- Lint: `./gradlew lint`
- Public API/ABI check (CI runs it): `./gradlew apiCheck`; after a deliberate change of the public surface, refresh the dumps under `*/api/` with `./gradlew apiDump` in the same change

## Code Style Guidelines

### Architecture Pattern

- Follow the Koma state management pattern - one-way data flow
- State → Action → New State with optional Event emission

### Types and Interfaces

- Use sealed interfaces for State, Action, and Event hierarchies
- Implement proper marker interfaces (State, Action, Event)
- Use data classes/objects for concrete state implementations

### DSL Pattern

- Use the @KomaStoreDsl annotation for builder APIs
- Follow the state{} and action{} block pattern
- Handle recoverable exceptions in dedicated recover{} blocks

### Error Handling

- Use store.recover{} for business logic exceptions
- Use store.exceptionHandler() for system errors
- Prefer modeling errors as state transitions

### Documentation

- Include state transition diagrams in tests
- Document experimental APIs with @ExperimentalKomaApi
- Use KDoc comments for public APIs
- Put internal design/spec notes under `doc/internal/` so they stay separate from user-facing docs

## Fork

- This is the fork `roman-n1/koma` of `koma-kt/koma`, published as `io.github.roman-n1:*` (`koma.fork.version` in `gradle.properties`); `koma.upstream.base` names the upstream release it was last merged with
- Changes to the modules upstream owns are listed in the divergence inventory of `doc/internal/design/2026-09-28-statechart-roadmap.md`; keep it complete in the same PR as the change
- `upstream-pr/<topic>` branches are single commits on the upstream tag, prepared per `doc/internal/notes/2026-10-01-upstream-series.md`

