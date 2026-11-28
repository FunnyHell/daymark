package io.github.funnyhell.daymark.data.backup

import androidx.room.withTransaction
import io.github.funnyhell.daymark.data.db.DaymarkDatabase
import io.github.funnyhell.daymark.data.repo.DataChange
import io.github.funnyhell.daymark.data.repo.DataChangeListener
import io.github.funnyhell.daymark.data.repo.Settings
import io.github.funnyhell.daymark.domain.common.TimeSource

sealed interface ImportResult {
    data class Imported(val records: Int) : ImportResult
    data class Invalid(val errors: List<String>) : ImportResult
    data class Failed(val message: String) : ImportResult
}

/**
 * Export writes every record. Import is "replace all": the file is validated completely first, then all
 * existing records are replaced inside a single transaction, so a failure leaves the database unchanged.
 * The UI requires explicit confirmation and offers an export of the current data before importing.
 */
class BackupRepository(
    private val db: DaymarkDatabase,
    private val time: TimeSource,
    private val appVersion: String,
    private val readSettings: suspend () -> Settings,
    private val applySettings: suspend (Settings) -> Unit,
    private val listener: DataChangeListener = DataChangeListener.None,
) {
    suspend fun snapshot(): Snapshot = db.withTransaction {
        val h = db.habitDao()
        val m = db.medicationDao()
        Snapshot(
            habits = h.habits(), habitSchedules = h.schedules(), habitEvents = h.events(), pauses = db.pauseDao().pauses(),
            medications = m.medications(), medicationSchedules = m.schedules(), doses = m.doses(),
            medicationEvents = m.events(), adjustments = m.adjustments(), deletions = db.deletedRecordDao().all(),
            habitSteps = h.steps(),
        )
    }

    suspend fun exportJson(): String = BackupCodec.encode(snapshot(), readSettings(), time.now(), appVersion)

    /** Validates without writing; used to show a summary before the user confirms. */
    fun preview(text: String): DecodeResult = BackupCodec.decode(text)

    suspend fun importJson(text: String): ImportResult = when (val decoded = BackupCodec.decode(text)) {
        is DecodeResult.Invalid -> ImportResult.Invalid(decoded.errors)
        is DecodeResult.Valid -> try {
            replaceAll(decoded.snapshot)
            applySettings(decoded.settings)
            listener.onDataChanged(DataChange.Everything)
            ImportResult.Imported(decoded.snapshot.recordCount)
        } catch (e: Exception) {
            ImportResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Replaces every record in one transaction. Any failure rolls back to the previous data. */
    suspend fun replaceAll(snapshot: Snapshot) {
        db.withTransaction {
            val h = db.habitDao()
            val m = db.medicationDao()
            db.pauseDao().deleteAll()
            h.deleteAllHabits()
            m.deleteAllMedications()
            h.insertHabits(snapshot.habits)
            h.insertSchedules(snapshot.habitSchedules)
            h.insertSteps(snapshot.habitSteps)
            h.insertEvents(snapshot.habitEvents)
            db.pauseDao().insertAll(snapshot.pauses)
            m.insertMedications(snapshot.medications)
            m.insertSchedules(snapshot.medicationSchedules)
            m.insertDoses(snapshot.doses)
            m.insertEvents(snapshot.medicationEvents)
            m.insertAdjustments(snapshot.adjustments)
            db.deletedRecordDao().deleteAll()
            db.deletedRecordDao().insertAll(snapshot.deletions)
        }
    }
}
