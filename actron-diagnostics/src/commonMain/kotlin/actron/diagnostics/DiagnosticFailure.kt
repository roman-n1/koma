package actron.diagnostics

/** Sanitized synthetic report; does not retain the original Throwable, stack, message or payload. */
class BehaviouralDiagnosticException(type: String) : RuntimeException("Actron workflow failure: $type")
