package io.github.funnyhell.daymark.data.repo

import androidx.room.withTransaction
import io.github.funnyhell.daymark.data.db.DaymarkDatabase
import io.github.funnyhell.daymark.data.db.InventoryAdjustmentEntity
import io.github.funnyhell.daymark.data.db.MedicationEntity
import io.github.funnyhell.daymark.data.db.MedicationEventEntity
import io.github.funnyhell.daymark.data.db.MedicationScheduleEntity
import io.github.funnyhell.daymark.data.db.OWNER_MEDICATION
import io.github.funnyhell.daymark.data.db.PauseEntity
import io.github.funnyhell.daymark.data.db.ScheduledDoseEntity
import io.github.funnyhell.daymark.data.db.Weekdays
import io.github.funnyhell.daymark.data.db.assembleMedications
import io.github.funnyhell.daymark.domain.common.IdGenerator
import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.common.TimeSource
import io.github.funnyhell.daymark.domain.medication.ActionDecision
import io.github.funnyhell.daymark.domain.medication.ActionRules
import io.github.funnyhell.daymark.domain.medication.ActionSource
import io.github.funnyhell.daymark.domain.medication.AdjustmentType
import io.github.funnyhell.daymark.domain.medication.DoseDraft
import io.github.funnyhell.daymark.domain.medication.InventoryRules
import io.github.funnyhell.daymark.domain.medication.MedicationData
import io.github.funnyhell.daymark.domain.medication.MedicationDraft
import io.github.funnyhell.daymark.domain.medication.MedicationDraftError
import io.github.funnyhell.daymark.domain.medication.MedicationDraftValidator
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import io.github.funnyhell.daymark.domain.medication.RejectReason
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

sealed interface MedicationActionResult {
    data class Recorded(
        val eventId: String,
        /** Stock did not cover the full dose; the user should update the stock count. */
        val insufficientStock: Boolean = false,
        /** Stock reached or fell below the low-stock threshold with this action. */
        val becameLow: Boolean = false,
    ) : MedicationActionResult

    /** Duplicate or stale action: the occurrence already has an active TAKEN/SKIPPED. Nothing was written. */
    data class AlreadyResolved(val existingType: MedicationEventType) : MedicationActionResult

    /** A matching snooze was recorded moments ago (for example, a duplicated notification action). */
    data object AlreadySnoozed : MedicationActionResult

    data class Rejected(val reason: RejectReason) : MedicationActionResult

    data object NotFound : MedicationActionResult
}

class MedicationDraftException(val errors: List<MedicationDraftError>) : IllegalArgumentException(errors.joinToString())

class MedicationRepository(
    private val db: DaymarkDatabase,
    private val time: TimeSource,
    private val ids: IdGenerator = IdGenerator.Random,
    private val listener: DataChangeListener = DataChangeListener.None,
) {
    private val dao = db.medicationDao()
    private val pauseDao = db.pauseDao()

    fun observeAll(): Flow<List<MedicationData>> = combine(
        dao.observeMedications(), dao.observeSchedules(), dao.observeDoses(), pauseDao.observePauses(),
        combine(dao.observeEvents(), dao.observeAdjustments()) { e, a -> e to a },
    ) { m, s, d, p, ea -> assembleMedications(m, s, d, p, ea.first, ea.second) }

    fun observe(id: String): Flow<MedicationData?> = observeAll().map { list -> list.firstOrNull { it.medication.id == id } }

    suspend fun all(): List<MedicationData> =
        assembleMedications(dao.medications(), dao.schedules(), dao.doses(), pauseDao.pauses(), dao.events(), dao.adjustments())

    suspend fun get(id: String): MedicationData? {
        val med = dao.medication(id) ?: return null
        return assembleMedications(
            listOf(med), dao.schedulesFor(id), dao.dosesFor(id), pauseDao.pausesFor(id), dao.eventsFor(id), dao.adjustmentsFor(id),
        ).single()
    }

    suspend fun create(draft: MedicationDraft): String {
        val errors = MedicationDraftValidator.validate(draft)
        if (errors.isNotEmpty()) throw MedicationDraftException(errors)
        val now = time.now()
        val id = ids.newId()
        db.withTransaction {
            dao.insertMedication(
                MedicationEntity(
                    id = id, name = draft.name.trim(), strength = draft.strength?.trim()?.ifEmpty { null },
                    notes = draft.notes?.trim()?.ifEmpty { null }, colorArgb = draft.colorArgb,
                    status = LifecycleStatus.ACTIVE.name, inventoryEnabled = draft.inventoryEnabled,
                    inventoryUnit = draft.inventoryUnit.trim(), lowStockThreshold = draft.lowStockThreshold,
                    lowStockAlerts = draft.lowStockAlerts, createdAt = now, updatedAt = now,
                ),
            )
            insertScheduleVersion(id, time.today(), draft)
            if (draft.inventoryEnabled && draft.initialStock != null) {
                insertAdjustment(id, draft.initialStock, AdjustmentType.INITIAL, null, null)
            }
        }
        listener.onDataChanged(DataChange.Medications())
        return id
    }

    /**
     * Updates details and, if the doses or days changed, creates a new schedule version. The effective date
     * follows [OccurrenceRules.editEffectiveDate]; earlier occurrences and their events are left untouched.
     */
    suspend fun update(id: String, draft: MedicationDraft) {
        val errors = MedicationDraftValidator.validate(draft)
        if (errors.isNotEmpty()) throw MedicationDraftException(errors)
        val today = time.today()
        var cleared = emptyList<String>()
        db.withTransaction {
            val data = get(id) ?: return@withTransaction
            val existing = dao.medication(id) ?: return@withTransaction
            dao.updateMedication(
                existing.copy(
                    name = draft.name.trim(), strength = draft.strength?.trim()?.ifEmpty { null },
                    notes = draft.notes?.trim()?.ifEmpty { null }, colorArgb = draft.colorArgb,
                    inventoryEnabled = draft.inventoryEnabled, inventoryUnit = draft.inventoryUnit.trim(),
                    lowStockThreshold = draft.lowStockThreshold, lowStockAlerts = draft.lowStockAlerts, updatedAt = time.now(),
                ),
            )
            val current = OccurrenceRules.scheduleOn(data.schedules, today) ?: data.schedules.lastOrNull()
            val currentDoses = current?.doses.orEmpty().map { DoseDraft(it.time, it.doseLabel, it.quantity) }
            val changed = current == null || current.days != draft.days || currentDoses != normalizedDoses(draft)
            if (changed) {
                val effective = OccurrenceRules.editEffectiveDate(data, today)
                cleared = OccurrenceRules.occurrencesOn(data, today).map { it.key }
                // Versions that would start on or after the new effective date have not applied to any day yet.
                data.schedules.filter { !it.effectiveFrom.isBefore(effective) }.forEach { dao.deleteSchedule(it.id) }
                data.schedules.filter { it.effectiveFrom.isBefore(effective) && (it.effectiveUntil == null || it.effectiveUntil.isAfter(effective)) }
                    .forEach { dao.updateSchedule(MedicationScheduleEntity(it.id, it.medicationId, it.effectiveFrom, effective, Weekdays.toMask(it.days))) }
                insertScheduleVersion(id, effective, draft)
            }
            if (draft.inventoryEnabled && draft.initialStock != null) {
                if (data.adjustments.isEmpty()) {
                    insertAdjustment(id, draft.initialStock, AdjustmentType.INITIAL, null, null)
                } else {
                    val delta = InventoryRules.correctionDelta(InventoryRules.stock(data.adjustments), draft.initialStock)
                    if (delta != 0.0) insertAdjustment(id, delta, AdjustmentType.MANUAL_CORRECTION, null, null)
                }
            }
        }
        listener.onDataChanged(DataChange.Medications(clearedOccurrenceKeys = cleared))
    }

    private fun normalizedDoses(draft: MedicationDraft) =
        draft.doses.map { it.copy(time = it.time.withSecond(0).withNano(0), doseLabel = it.doseLabel.trim()) }.sortedBy { it.time }

    private suspend fun insertScheduleVersion(medicationId: String, from: LocalDate, draft: MedicationDraft) {
        val scheduleId = ids.newId()
        dao.insertSchedule(MedicationScheduleEntity(scheduleId, medicationId, from, null, Weekdays.toMask(draft.days)))
        dao.insertDoses(normalizedDoses(draft).map { ScheduledDoseEntity(ids.newId(), scheduleId, it.time, it.doseLabel, it.quantity) })
    }

    suspend fun pause(id: String) = setLifecycle(id, LifecycleStatus.PAUSED)

    suspend fun resume(id: String) = setLifecycle(id, LifecycleStatus.ACTIVE)

    suspend fun archive(id: String) = setLifecycle(id, LifecycleStatus.ARCHIVED)

    private suspend fun setLifecycle(id: String, target: LifecycleStatus) {
        val today = time.today()
        var cleared = emptyList<String>()
        db.withTransaction {
            val med = dao.medication(id) ?: return@withTransaction
            if (med.status == target.name) return@withTransaction
            val open = pauseDao.openPause(id)
            when (target) {
                LifecycleStatus.ACTIVE -> if (open != null) {
                    if (!open.start.isBefore(today)) pauseDao.delete(open.id) else pauseDao.close(open.id, today)
                }
                LifecycleStatus.PAUSED, LifecycleStatus.ARCHIVED -> if (open == null) {
                    get(id)?.let { data -> cleared = OccurrenceRules.occurrencesOn(data, today).map { it.key } }
                    pauseDao.insert(PauseEntity(ids.newId(), OWNER_MEDICATION, id, today, null, time.now()))
                }
            }
            dao.updateMedication(med.copy(status = target.name, updatedAt = time.now()))
        }
        listener.onDataChanged(DataChange.Medications(clearedOccurrenceKeys = cleared))
    }

    /** Permanently deletes a medication with its history and inventory. Only offered with explicit confirmation. */
    suspend fun delete(id: String) {
        val cleared = get(id)?.let { data -> OccurrenceRules.occurrencesOn(data, time.today()).map { it.key } }.orEmpty()
        db.withTransaction {
            pauseDao.deleteFor(id)
            dao.deleteMedication(id)
        }
        listener.onDataChanged(DataChange.Medications(clearedOccurrenceKeys = cleared))
    }

    /**
     * Records TAKEN or SKIPPED for an occurrence. Validation, the event and its inventory effect run in one
     * transaction; a repeated request for an already resolved occurrence returns [MedicationActionResult.AlreadyResolved]
     * and writes nothing. This is the single entry point used by the app UI, notifications and widgets.
     */
    suspend fun recordDose(
        medicationId: String,
        occurrenceKey: String,
        type: MedicationEventType,
        source: ActionSource,
    ): MedicationActionResult {
        require(type == MedicationEventType.TAKEN || type == MedicationEventType.SKIPPED) { "Use snooze() for SNOOZED" }
        var becameLow = false
        val result = db.withTransaction {
            val data = get(medicationId) ?: return@withTransaction MedicationActionResult.NotFound
            when (val decision = ActionRules.decide(data, occurrenceKey, time.today())) {
                is ActionDecision.AlreadyResolved -> MedicationActionResult.AlreadyResolved(decision.existing.type)
                is ActionDecision.Rejected -> MedicationActionResult.Rejected(decision.reason)
                is ActionDecision.Apply -> {
                    val occ = decision.occurrence
                    val eventId = ids.newId()
                    val quantity = if (type == MedicationEventType.TAKEN) occ.quantity else 0.0
                    dao.insertEvent(
                        MedicationEventEntity(
                            id = eventId, medicationId = medicationId, occurrenceKey = occ.key, doseId = occ.doseId,
                            scheduledDate = occ.date, scheduledTime = occ.time, doseLabel = occ.doseLabel, type = type.name,
                            quantity = quantity, snoozedUntil = null, source = source.name, recordedAt = time.now(), voidedAt = null,
                        ),
                    )
                    var insufficient = false
                    if (type == MedicationEventType.TAKEN && data.medication.inventoryEnabled) {
                        val before = InventoryRules.stock(data.adjustments)
                        val consumption = InventoryRules.consumption(before, quantity)
                        insufficient = consumption.insufficient
                        insertAdjustment(medicationId, consumption.delta, AdjustmentType.INTAKE, null, eventId)
                        becameLow = data.medication.lowStockAlerts &&
                            InventoryRules.crossedLowThreshold(before, before + consumption.delta, data.medication.lowStockThreshold)
                    }
                    MedicationActionResult.Recorded(eventId, insufficient, becameLow)
                }
            }
        }
        if (result is MedicationActionResult.Recorded || result is MedicationActionResult.AlreadyResolved) {
            listener.onDataChanged(
                DataChange.Medications(
                    clearedOccurrenceKeys = listOf(occurrenceKey),
                    lowStockMedicationId = medicationId.takeIf { becameLow },
                ),
            )
        }
        return result
    }

    /** Defers the reminder; never records intake and never touches inventory. */
    suspend fun snooze(
        medicationId: String,
        occurrenceKey: String,
        source: ActionSource,
        duration: Duration = SNOOZE_DURATION,
    ): MedicationActionResult {
        val result = db.withTransaction {
            val data = get(medicationId) ?: return@withTransaction MedicationActionResult.NotFound
            when (val decision = ActionRules.decide(data, occurrenceKey, time.today())) {
                is ActionDecision.AlreadyResolved -> MedicationActionResult.AlreadyResolved(decision.existing.type)
                is ActionDecision.Rejected -> MedicationActionResult.Rejected(decision.reason)
                is ActionDecision.Apply -> {
                    val now = time.now()
                    val recent = data.events.any {
                        it.occurrenceKey == occurrenceKey && it.isActive && it.type == MedicationEventType.SNOOZED &&
                            Duration.between(it.recordedAt, now).abs() < DUPLICATE_SNOOZE_WINDOW
                    }
                    if (recent) {
                        MedicationActionResult.AlreadySnoozed
                    } else {
                        val occ = decision.occurrence
                        val eventId = ids.newId()
                        dao.insertEvent(
                            MedicationEventEntity(
                                id = eventId, medicationId = medicationId, occurrenceKey = occ.key, doseId = occ.doseId,
                                scheduledDate = occ.date, scheduledTime = occ.time, doseLabel = occ.doseLabel,
                                type = MedicationEventType.SNOOZED.name, quantity = 0.0, snoozedUntil = now.plus(duration),
                                source = source.name, recordedAt = now, voidedAt = null,
                            ),
                        )
                        MedicationActionResult.Recorded(eventId)
                    }
                }
            }
        }
        if (result !is MedicationActionResult.NotFound) {
            listener.onDataChanged(DataChange.Medications(clearedOccurrenceKeys = listOf(occurrenceKey)))
        }
        return result
    }

    /**
     * Corrects (voids) an event. For a TAKEN event the INTAKE adjustment is reversed exactly once, so the stock
     * is reconciled with the corrected history. Repeating the correction changes nothing.
     */
    suspend fun voidEvent(eventId: String): Boolean {
        val changed = db.withTransaction {
            val event = dao.event(eventId) ?: return@withTransaction false
            if (dao.voidEvent(eventId, time.now()) == 0) return@withTransaction false
            if (event.type == MedicationEventType.TAKEN.name) {
                val intake = dao.adjustmentForEvent(eventId, AdjustmentType.INTAKE.name)
                val reversed = dao.adjustmentForEvent(eventId, AdjustmentType.INTAKE_REVERSAL.name)
                if (intake != null && reversed == null) {
                    insertAdjustment(event.medicationId, -intake.delta, AdjustmentType.INTAKE_REVERSAL, null, eventId)
                }
            }
            true
        }
        if (changed) listener.onDataChanged(DataChange.Medications())
        return changed
    }

    suspend fun addPackage(medicationId: String, quantity: Double, note: String?): Boolean {
        if (!MedicationDraftValidator.isValidQuantity(quantity, allowZero = false)) return false
        db.withTransaction {
            dao.medication(medicationId) ?: return@withTransaction
            insertAdjustment(medicationId, quantity, AdjustmentType.PACKAGE_ADDED, note, null)
        }
        listener.onDataChanged(DataChange.Medications())
        return true
    }

    /** Records a manual stock count. The difference is stored as a correction so the change stays explainable. */
    suspend fun setStock(medicationId: String, counted: Double, note: String?): Boolean {
        if (!MedicationDraftValidator.isValidQuantity(counted, allowZero = true)) return false
        var becameLow = false
        db.withTransaction {
            val data = get(medicationId) ?: return@withTransaction
            val before = InventoryRules.stock(data.adjustments)
            val delta = InventoryRules.correctionDelta(before, counted)
            if (delta == 0.0) return@withTransaction
            val type = if (data.adjustments.isEmpty()) AdjustmentType.INITIAL else AdjustmentType.MANUAL_CORRECTION
            insertAdjustment(medicationId, delta, type, note, null)
            becameLow = data.medication.lowStockAlerts &&
                InventoryRules.crossedLowThreshold(before, counted, data.medication.lowStockThreshold)
        }
        listener.onDataChanged(DataChange.Medications(lowStockMedicationId = medicationId.takeIf { becameLow }))
        return true
    }

    private suspend fun insertAdjustment(medicationId: String, delta: Double, type: AdjustmentType, note: String?, eventId: String?) {
        dao.insertAdjustment(
            InventoryAdjustmentEntity(ids.newId(), medicationId, delta, type.name, note?.trim()?.ifEmpty { null }, eventId, time.now()),
        )
    }

    companion object {
        val SNOOZE_DURATION: Duration = Duration.ofMinutes(30)

        /** A second snooze for the same occurrence inside this window is treated as a duplicate delivery. */
        val DUPLICATE_SNOOZE_WINDOW: Duration = Duration.ofSeconds(60)
    }
}
