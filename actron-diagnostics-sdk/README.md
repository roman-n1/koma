# actron-diagnostics-sdk

OpenTelemetryDiagnosticSink and SentryDiagnosticSink adapt common CausalTraceEvents on JVM/Android.
Supply an application-owned Tracer or IScopes. The adapters do not initialize SDKs or configure
transports/collection. Metadata-only synthetic failures omit the original exception/payload.
OpenTelemetry receipt spans use explicit parent contexts and are always ended; unavailable parent
contexts are flagged. Sentry uses event-local extras and metadata breadcrumbs.

Use asynchronous SDK exporters for live Store callbacks. Caller owns consent, queues, SDK versions
and lifetime. Firebase is isolated in [actron-diagnostics-crashlytics](../actron-diagnostics-crashlytics).
See the [contract/design guide](../doc/internal/design/2026-10-06-competitive-roadmap.md).
