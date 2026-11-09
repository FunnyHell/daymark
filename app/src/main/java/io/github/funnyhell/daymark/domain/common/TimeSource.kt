package io.github.funnyhell.daymark.domain.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Current time and device zone. Injected so that rules and repositories can be tested at fixed times. */
interface TimeSource {
    fun now(): Instant
    fun zone(): ZoneId
    fun today(): LocalDate = now().atZone(zone()).toLocalDate()

    object System : TimeSource {
        override fun now(): Instant = Instant.now()
        override fun zone(): ZoneId = ZoneId.systemDefault()
    }
}

/** Generates stable random identifiers for new records. */
fun interface IdGenerator {
    fun newId(): String

    companion object {
        val Random = IdGenerator { java.util.UUID.randomUUID().toString() }
    }
}
