package actron.test

/** Observes zero or one callbacks; nullable capture is confined to this test fixture. */
internal fun ActionMatchDiagnostics<*, *>.observedSelectedIndex(): Int? {
    var selected: Int? = null
    withSelectedHandler { handler ->
        check(selected == null) { "Selected handler was visited more than once" }
        selected = handler.index
    }
    return selected
}
