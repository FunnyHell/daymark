package io.github.funnyhell.daymark.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.funnyhell.daymark.data.db.DaymarkDatabase
import io.github.funnyhell.daymark.domain.common.TimeSource
import io.github.funnyhell.daymark.domain.medication.DoseDraft
import io.github.funnyhell.daymark.domain.medication.MedicationDraft
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class FakeTime(var current: Instant, private val zoneId: ZoneId = ZoneOffset.UTC) : TimeSource {
    override fun now(): Instant = current
    override fun zone(): ZoneId = zoneId

    fun set(date: LocalDate, hour: Int, minute: Int = 0) {
        current = date.atTime(hour, minute).atZone(zoneId).toInstant()
    }
}

/** 2024-03-04 is a Monday. */
val DAY1: LocalDate = LocalDate.of(2024, 3, 4)

fun inMemoryDb(): DaymarkDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return Room.inMemoryDatabaseBuilder(context, DaymarkDatabase::class.java).build()
}

fun medDraft(
    times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
    quantity: Double = 1.0,
    stock: Double? = 10.0,
    threshold: Double? = null,
    inventory: Boolean = true,
) = MedicationDraft(
    name = "Test med", strength = null, notes = null, colorArgb = 0xFF336699, days = DayOfWeek.entries.toSet(),
    doses = times.map { DoseDraft(it, "1 tablet", quantity) }, inventoryEnabled = inventory, inventoryUnit = "tablets",
    lowStockThreshold = threshold, lowStockAlerts = true, initialStock = stock,
)
