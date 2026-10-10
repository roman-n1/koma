package actron.core

/**
 * Handles non-fatal exceptions raised while a Store is running.
 *
 * This includes exceptions from DSL handlers, plugin hooks, launched coroutines, and state
 * persistence callbacks. A launched coroutine that fails after its state has already exited
 * (for example blocking work that finishes after cancellation and then throws), or whose state
 * exits before the failure is processed, is reported to this handler but not to `recover {}`:
 * its state is gone, so there is nothing to recover into.
 */
interface ExceptionHandler {
    /**
     * Handles the exception after Actron unwraps its internal bookkeeping errors.
     *
     * @param error The exception to handle
     */
    fun handle(error: Throwable)

    @Suppress("unused")
    companion object {
        /**
         * Ignores all handled exceptions.
         */
        val Ignore: ExceptionHandler = object : ExceptionHandler {
            override fun handle(error: Throwable) {}
        }

        /**
         * Deprecated alias for [Ignore].
         */
        @Deprecated(message = "Use Ignore", replaceWith = ReplaceWith("ExceptionHandler.Ignore"))
        val Noop: ExceptionHandler
            get() = Ignore

        /**
         * Prints the exception message and stack trace for debugging.
         */
        val Log: ExceptionHandler = object : ExceptionHandler {
            override fun handle(error: Throwable) {
                println("[Actron] An exception occurred in the Actron Framework: ${error.message ?: "Unknown error"}")
                error.printStackTrace()
            }
        }

        /**
         * Rethrows handled exceptions instead of swallowing them.
         */
        val Rethrow: ExceptionHandler = object : ExceptionHandler {
            override fun handle(error: Throwable) {
                throw error
            }
        }

        /**
         * Deprecated alias for [Rethrow].
         */
        @Deprecated(message = "Use Rethrow", replaceWith = ReplaceWith("ExceptionHandler.Rethrow"))
        val Unhandled: ExceptionHandler
            get() = Rethrow
    }
}

/**
 * Creates an [ExceptionHandler] from a single callback.
 */
fun ExceptionHandler(block: (error: Throwable) -> Unit) = object : ExceptionHandler {
    override fun handle(error: Throwable) {
        block.invoke(error)
    }
}
