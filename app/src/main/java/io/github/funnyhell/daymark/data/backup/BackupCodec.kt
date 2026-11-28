package io.github.funnyhell.daymark.data.backup

import io.github.funnyhell.daymark.data.db.DELETED_HABIT
import io.github.funnyhell.daymark.data.db.DELETED_MEDICATION
import io.github.funnyhell.daymark.data.db.DeletedRecordEntity
import io.github.funnyhell.daymark.data.db.HabitEntity
import io.github.funnyhell.daymark.data.db.HabitEventEntity
import io.github.funnyhell.daymark.data.db.HabitStepEntity
import io.github.funnyhell.daymark.data.db.HabitScheduleEntity
import io.github.funnyhell.daymark.data.db.InventoryAdjustmentEntity
import io.github.funnyhell.daymark.data.db.KIND_DAILY
import io.github.funnyhell.daymark.data.db.KIND_EVERY_N_DAYS
import io.github.funnyhell.daymark.data.db.KIND_WEEKDAYS
import io.github.funnyhell.daymark.data.db.MedicationEntity
import io.github.funnyhell.daymark.data.db.MedicationEventEntity
import io.github.funnyhell.daymark.data.db.MedicationScheduleEntity
import io.github.funnyhell.daymark.data.db.OWNER_HABIT
import io.github.funnyhell.daymark.data.db.OWNER_MEDICATION
import io.github.funnyhell.daymark.data.db.PauseEntity
import io.github.funnyhell.daymark.data.db.ScheduledDoseEntity
import io.github.funnyhell.daymark.data.db.Weekdays
import io.github.funnyhell.daymark.data.repo.Settings
import io.github.funnyhell.daymark.data.repo.ThemeMode
import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.habit.HabitEventType
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.medication.ActionSource
import io.github.funnyhell.daymark.domain.medication.AdjustmentType
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.json.Json

/** All persisted records, in database form. */
data class Snapshot(
    val habits: List<HabitEntity>,
    val habitSchedules: List<HabitScheduleEntity>,
    val habitEvents: List<HabitEventEntity>,
    val pauses: List<PauseEntity>,
    val medications: List<MedicationEntity>,
    val medicationSchedules: List<MedicationScheduleEntity>,
    val doses: List<ScheduledDoseEntity>,
    val medicationEvents: List<MedicationEventEntity>,
    val adjustments: List<InventoryAdjustmentEntity>,
    val deletions: List<DeletedRecordEntity> = emptyList(),
    val habitSteps: List<HabitStepEntity> = emptyList(),
) {
    val recordCount: Int
        get() = habits.size + habitSchedules.size + habitEvents.size + pauses.size + medications.size +
            medicationSchedules.size + doses.size + medicationEvents.size + adjustments.size + habitSteps.size
}

sealed interface DecodeResult {
    data class Valid(val snapshot: Snapshot, val settings: Settings, val exportedAt: Instant?) : DecodeResult
    data class Invalid(val errors: List<String>) : DecodeResult
}

object BackupCodec {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    fun encode(snapshot: Snapshot, settings: Settings, exportedAt: Instant, appVersion: String): String =
        json.encodeToString(BackupFile.serializer(), toBackup(snapshot, settings, exportedAt, appVersion))

    fun toBackup(s: Snapshot, settings: Settings, exportedAt: Instant, appVersion: String) = BackupFile(
        exportedAt = exportedAt.toString(),
        appVersion = appVersion,
        settings = BackupSettings(
            themeMode = settings.themeMode.name, dynamicColor = settings.dynamicColor,
            firstDayOfWeek = settings.firstDayOfWeek.name, medicationReminders = settings.medicationReminders,
            habitReminders = settings.habitReminders, hideNamesOnLockScreen = settings.hideNamesOnLockScreen,
        ),
        habits = s.habits.map {
            BackupHabit(
                it.id, it.name, it.description, it.colorArgb, it.type, it.status, it.unit, it.startDate.toString(),
                it.reminderTime?.toString(), it.showOnWidget, it.sortOrder, it.createdAt.toString(), it.updatedAt.toString(),
            )
        },
        habitSchedules = s.habitSchedules.map {
            BackupHabitSchedule(it.id, it.habitId, it.effectiveFrom.toString(), it.effectiveUntil?.toString(), it.recurrenceKind, it.weekdayMask, it.intervalDays, it.target)
        },
        habitEvents = s.habitEvents.map {
            BackupHabitEvent(it.id, it.habitId, it.date.toString(), it.type, it.amount, it.note, it.recordedAt.toString(), it.voidedAt?.toString(), it.stepId)
        },
        habitSteps = s.habitSteps.map { BackupHabitStep(it.id, it.habitId, it.title, it.target, it.sortOrder, it.addedOn.toString(), it.removedOn?.toString()) },
        pauses = s.pauses.map { BackupPause(it.id, it.ownerType, it.ownerId, it.start.toString(), it.end?.toString(), it.createdAt.toString()) },
        medications = s.medications.map {
            BackupMedication(
                it.id, it.name, it.strength, it.notes, it.colorArgb, it.status, it.inventoryEnabled, it.inventoryUnit,
                it.lowStockThreshold, it.lowStockAlerts, it.createdAt.toString(), it.updatedAt.toString(),
            )
        },
        medicationSchedules = s.medicationSchedules.map {
            BackupMedicationSchedule(it.id, it.medicationId, it.effectiveFrom.toString(), it.effectiveUntil?.toString(), it.weekdayMask)
        },
        scheduledDoses = s.doses.map { BackupDose(it.id, it.scheduleId, it.time.toString(), it.doseLabel, it.quantity) },
        medicationEvents = s.medicationEvents.map {
            BackupMedicationEvent(
                it.id, it.medicationId, it.occurrenceKey, it.doseId, it.scheduledDate?.toString(), it.scheduledTime?.toString(),
                it.doseLabel, it.type, it.quantity, it.snoozedUntil?.toString(), it.source, it.recordedAt.toString(), it.voidedAt?.toString(), it.note,
            )
        },
        inventoryAdjustments = s.adjustments.map {
            BackupAdjustment(it.id, it.medicationId, it.delta, it.type, it.note, it.eventId, it.recordedAt.toString())
        },
        deletedRecords = s.deletions.map { BackupDeletion(it.id, it.kind, it.deletedAt.toString()) },
    )

    /** Parses and fully validates a backup. Nothing is written; the caller applies a [DecodeResult.Valid] snapshot. */
    fun decode(text: String): DecodeResult {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            return DecodeResult.Invalid(listOf("Not a readable Daymark backup: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}"))
        }
        return validate(file)
    }

    fun validate(file: BackupFile): DecodeResult {
        val errors = mutableListOf<String>()
        if (file.format != BackupFile.FORMAT_ID) errors += "Unknown file format '${file.format}'"
        if (file.formatVersion != BackupFile.CURRENT_VERSION) {
            errors += "Unsupported backup version ${file.formatVersion} (this app reads version ${BackupFile.CURRENT_VERSION})"
        }
        if (errors.isNotEmpty()) return DecodeResult.Invalid(errors)

        val v = Validator(errors)
        val settings = Settings(
            themeMode = v.enumValue<ThemeMode>(file.settings.themeMode, "settings.themeMode") ?: ThemeMode.SYSTEM,
            dynamicColor = file.settings.dynamicColor,
            firstDayOfWeek = v.enumValue<DayOfWeek>(file.settings.firstDayOfWeek, "settings.firstDayOfWeek") ?: DayOfWeek.MONDAY,
            medicationReminders = file.settings.medicationReminders,
            habitReminders = file.settings.habitReminders,
            hideNamesOnLockScreen = file.settings.hideNamesOnLockScreen,
            onboardingDone = true,
        )

        v.unique(file.habits.map { it.id }, "habit")
        v.unique(file.habitSchedules.map { it.id }, "habit schedule")
        v.unique(file.habitEvents.map { it.id }, "habit event")
        v.unique(file.pauses.map { it.id }, "pause")
        v.unique(file.medications.map { it.id }, "medication")
        v.unique(file.medicationSchedules.map { it.id }, "medication schedule")
        v.unique(file.scheduledDoses.map { it.id }, "dose")
        v.unique(file.medicationEvents.map { it.id }, "medication event")
        v.unique(file.inventoryAdjustments.map { it.id }, "inventory adjustment")

        val habitIds = file.habits.map { it.id }.toSet()
        val medIds = file.medications.map { it.id }.toSet()
        val medScheduleIds = file.medicationSchedules.map { it.id }.toSet()
        val medEventIds = file.medicationEvents.map { it.id }.toSet()

        val habits = file.habits.mapNotNull { h ->
            val ctx = "habit ${h.id}"
            v.text(h.name, ctx)
            val type = v.enumValue<HabitType>(h.type, ctx)
            val status = v.enumValue<LifecycleStatus>(h.status, ctx)
            val start = v.date(h.startDate, ctx)
            val reminder = h.reminderTime?.let { v.time(it, ctx) }
            val created = v.instant(h.createdAt, ctx)
            val updated = v.instant(h.updatedAt, ctx)
            if (type == null || status == null || start == null || created == null || updated == null) null
            else HabitEntity(h.id, h.name, h.description, h.colorArgb, type.name, status.name, h.unit, start, reminder, h.showOnWidget, h.sortOrder, created, updated)
        }
        val habitSchedules = file.habitSchedules.mapNotNull { s ->
            val ctx = "habit schedule ${s.id}"
            v.reference(s.habitId in habitIds, ctx, "habit ${s.habitId}")
            val from = v.date(s.effectiveFrom, ctx)
            val until = s.effectiveUntil?.let { v.date(it, ctx) }
            if (s.recurrenceKind !in setOf(KIND_DAILY, KIND_WEEKDAYS, KIND_EVERY_N_DAYS)) v.fail(ctx, "unknown recurrence '${s.recurrenceKind}'")
            if (s.recurrenceKind == KIND_WEEKDAYS && (s.weekdayMask !in 1..Weekdays.ALL)) v.fail(ctx, "invalid weekdays")
            if (s.recurrenceKind == KIND_EVERY_N_DAYS && s.intervalDays !in 2..365) v.fail(ctx, "invalid interval")
            v.number(s.target, ctx, allowZero = true)
            if (from != null && until != null && !until.isAfter(from)) v.fail(ctx, "ends before it starts")
            if (from == null) null else HabitScheduleEntity(s.id, s.habitId, from, until, s.recurrenceKind, s.weekdayMask, s.intervalDays, s.target)
        }
        val habitEvents = file.habitEvents.mapNotNull { e ->
            val ctx = "habit event ${e.id}"
            v.reference(e.habitId in habitIds, ctx, "habit ${e.habitId}")
            val date = v.date(e.date, ctx)
            val type = v.enumValue<HabitEventType>(e.type, ctx)
            v.number(e.amount, ctx, allowZero = true)
            val recorded = v.instant(e.recordedAt, ctx)
            val voided = e.voidedAt?.let { v.instant(it, ctx) }
            if (date == null || type == null || recorded == null) null
            else HabitEventEntity(e.id, e.habitId, date, type.name, e.amount, e.note, recorded, voided, e.stepId)
        }
        v.unique(file.habitSteps.map { it.id }, "habit step")
        val habitSteps = file.habitSteps.mapNotNull { st ->
            val ctx = "habit step ${st.id}"
            v.reference(st.habitId in habitIds, ctx, "habit ${st.habitId}")
            if (st.target !in 1..1000) v.fail(ctx, "invalid target ${st.target}")
            val added = v.date(st.addedOn, ctx)
            val removed = st.removedOn?.let { v.date(it, ctx) }
            if (added == null) null else HabitStepEntity(st.id, st.habitId, st.title, st.target, st.sortOrder, added, removed)
        }
        val pauses = file.pauses.mapNotNull { p ->
            val ctx = "pause ${p.id}"
            when (p.ownerType) {
                OWNER_HABIT -> v.reference(p.ownerId in habitIds, ctx, "habit ${p.ownerId}")
                OWNER_MEDICATION -> v.reference(p.ownerId in medIds, ctx, "medication ${p.ownerId}")
                else -> v.fail(ctx, "unknown owner type '${p.ownerType}'")
            }
            val start = v.date(p.start, ctx)
            val end = p.end?.let { v.date(it, ctx) }
            val created = v.instant(p.createdAt, ctx)
            if (start == null || created == null) null else PauseEntity(p.id, p.ownerType, p.ownerId, start, end, created)
        }
        val medications = file.medications.mapNotNull { m ->
            val ctx = "medication ${m.id}"
            v.text(m.name, ctx)
            val status = v.enumValue<LifecycleStatus>(m.status, ctx)
            m.lowStockThreshold?.let { v.number(it, ctx, allowZero = true) }
            val created = v.instant(m.createdAt, ctx)
            val updated = v.instant(m.updatedAt, ctx)
            if (status == null || created == null || updated == null) null
            else MedicationEntity(m.id, m.name, m.strength, m.notes, m.colorArgb, status.name, m.inventoryEnabled, m.inventoryUnit, m.lowStockThreshold, m.lowStockAlerts, created, updated)
        }
        val medicationSchedules = file.medicationSchedules.mapNotNull { s ->
            val ctx = "medication schedule ${s.id}"
            v.reference(s.medicationId in medIds, ctx, "medication ${s.medicationId}")
            if (s.weekdayMask !in 0..Weekdays.ALL) v.fail(ctx, "invalid weekdays")
            val from = v.date(s.effectiveFrom, ctx)
            val until = s.effectiveUntil?.let { v.date(it, ctx) }
            if (from == null) null else MedicationScheduleEntity(s.id, s.medicationId, from, until, s.weekdayMask)
        }
        val doses = file.scheduledDoses.mapNotNull { d ->
            val ctx = "dose ${d.id}"
            v.reference(d.scheduleId in medScheduleIds, ctx, "medication schedule ${d.scheduleId}")
            val time = v.time(d.time, ctx)
            v.number(d.quantity, ctx, allowZero = true)
            if (time == null) null else ScheduledDoseEntity(d.id, d.scheduleId, time, d.doseLabel, d.quantity)
        }
        val medicationEvents = file.medicationEvents.mapNotNull { e ->
            val ctx = "medication event ${e.id}"
            v.reference(e.medicationId in medIds, ctx, "medication ${e.medicationId}")
            val type = v.enumValue<MedicationEventType>(e.type, ctx)
            val source = v.enumValue<ActionSource>(e.source, ctx)
            v.number(e.quantity, ctx, allowZero = true)
            val date = e.scheduledDate?.let { v.date(it, ctx) }
            val time = e.scheduledTime?.let { v.time(it, ctx) }
            val snoozed = e.snoozedUntil?.let { v.instant(it, ctx) }
            val recorded = v.instant(e.recordedAt, ctx)
            val voided = e.voidedAt?.let { v.instant(it, ctx) }
            if (type == null || source == null || recorded == null) null
            else MedicationEventEntity(e.id, e.medicationId, e.occurrenceKey, e.doseId, date, time, e.doseLabel, type.name, e.quantity, snoozed, source.name, recorded, voided, e.note)
        }
        // At most one active TAKEN/SKIPPED per occurrence, as the app itself guarantees.
        medicationEvents.filter { it.voidedAt == null && it.occurrenceKey != null && (it.type == "TAKEN" || it.type == "SKIPPED") }
            .groupBy { it.occurrenceKey }.filterValues { it.size > 1 }.keys
            .forEach { v.fail("occurrence $it", "has more than one active taken/skipped record") }
        val adjustments = file.inventoryAdjustments.mapNotNull { a ->
            val ctx = "inventory adjustment ${a.id}"
            v.reference(a.medicationId in medIds, ctx, "medication ${a.medicationId}")
            a.eventId?.let { v.reference(it in medEventIds, ctx, "medication event $it") }
            val type = v.enumValue<AdjustmentType>(a.type, ctx)
            if (!a.delta.isFinite()) v.fail(ctx, "invalid quantity")
            val recorded = v.instant(a.recordedAt, ctx)
            if (type == null || recorded == null) null
            else InventoryAdjustmentEntity(a.id, a.medicationId, a.delta, type.name, a.note, a.eventId, recorded)
        }
        adjustments.filter { it.eventId != null }.groupBy { it.eventId to it.type }.filterValues { it.size > 1 }.keys
            .forEach { v.fail("event ${it.first}", "has duplicate ${it.second} inventory records") }
        v.unique(file.deletedRecords.map { it.id }, "deleted record")
        val deletions = file.deletedRecords.mapNotNull { d ->
            val ctx = "deleted record ${d.id}"
            if (d.kind != DELETED_HABIT && d.kind != DELETED_MEDICATION) v.fail(ctx, "unknown kind '${d.kind}'")
            val at = v.instant(d.deletedAt, ctx)
            if (at == null) null else DeletedRecordEntity(d.id, d.kind, at)
        }

        if (errors.isNotEmpty()) return DecodeResult.Invalid(errors.take(MAX_REPORTED_ERRORS))
        val exportedAt = runCatching { Instant.parse(file.exportedAt) }.getOrNull()
        return DecodeResult.Valid(
            Snapshot(habits, habitSchedules, habitEvents, pauses, medications, medicationSchedules, doses, medicationEvents, adjustments, deletions, habitSteps),
            settings,
            exportedAt,
        )
    }

    private const val MAX_REPORTED_ERRORS = 20

    private class Validator(val errors: MutableList<String>) {
        fun fail(ctx: String, message: String) {
            errors += "$ctx: $message"
        }

        fun unique(ids: List<String>, what: String) {
            ids.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { fail("$what $it", "duplicate id") }
            if (ids.any { it.isBlank() }) fail(what, "blank id")
        }

        fun reference(ok: Boolean, ctx: String, target: String) {
            if (!ok) fail(ctx, "refers to missing $target")
        }

        fun text(value: String, ctx: String) {
            if (value.isBlank()) fail(ctx, "name is empty")
        }

        fun number(value: Double, ctx: String, allowZero: Boolean) {
            if (!value.isFinite() || value < 0 || (!allowZero && value == 0.0)) fail(ctx, "invalid number $value")
        }

        fun date(value: String, ctx: String): LocalDate? =
            runCatching { LocalDate.parse(value) }.getOrElse { fail(ctx, "invalid date '$value'"); null }

        fun time(value: String, ctx: String): LocalTime? =
            runCatching { LocalTime.parse(value) }.getOrElse { fail(ctx, "invalid time '$value'"); null }

        fun instant(value: String, ctx: String): Instant? =
            runCatching { Instant.parse(value) }.getOrElse { fail(ctx, "invalid timestamp '$value'"); null }

        inline fun <reified T : Enum<T>> enumValue(value: String, ctx: String): T? =
            enumValues<T>().firstOrNull { it.name == value } ?: run { fail(ctx, "unknown value '$value'"); null }
    }
}
