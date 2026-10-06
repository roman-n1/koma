package koma.diagnostics

/** Sanitized synthetic report; does not retain the original Throwable, stack, message or payload. */
class BehaviouralDiagnosticException(type: String) : RuntimeException("Koma workflow failure: $type")
