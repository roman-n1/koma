package koma.example.durable

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val directory = Path.of(args.firstOrNull() ?: "build/durable-effects")
    Files.createDirectories(directory)
    val intent = CreditIntent("order-2026-0001", 25)
    CreditSession(directory).use { session ->
        session.start()
        session.request(intent)
        if (session.saver.durable.value.pending != null) {
            session.store.mailbox.subscribe().take(1).collect { session.handle(it) }
        }
        println("balance=${session.ledger.read().balance}, receipts=${session.ledger.read().receipts}")
    }
}
