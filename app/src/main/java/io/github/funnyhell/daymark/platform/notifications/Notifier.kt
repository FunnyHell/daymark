package io.github.funnyhell.daymark.platform.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.funnyhell.daymark.MainActivity
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.data.repo.SettingsRepository
import io.github.funnyhell.daymark.domain.common.TimeSource
import io.github.funnyhell.daymark.domain.common.formatQuantity
import io.github.funnyhell.daymark.domain.habit.HabitData
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.medication.InventoryRules
import io.github.funnyhell.daymark.domain.medication.MedicationData
import io.github.funnyhell.daymark.domain.medication.Occurrence
import io.github.funnyhell.daymark.ui.format.Formats
import java.time.Instant
import java.time.LocalDate

/**
 * Builds and posts notifications. Medication reminders carry Taken / Skip / Snooze actions that are handled by
 * [NotificationActionReceiver] through the same repository methods the app uses.
 */
class Notifier(private val context: Context, private val settings: SettingsRepository) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        // Version 1 channels had no explicit sound/vibration; channel settings are immutable, so they are replaced.
        LEGACY_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        fun channel(id: String, name: Int, desc: Int, importance: Int) = NotificationChannel(id, context.getString(name), importance).apply {
            description = context.getString(desc)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            setSound(sound, audio)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 150, 250)
            enableLights(true)
        }
        nm.createNotificationChannel(channel(CHANNEL_MEDICATION, R.string.channel_medication, R.string.channel_medication_desc, NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(channel(CHANNEL_HABIT, R.string.channel_habit, R.string.channel_habit_desc, NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(channel(CHANNEL_STOCK, R.string.channel_stock, R.string.channel_stock_desc, NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notificationsEnabled(): Boolean = hasPermission() && manager.areNotificationsEnabled()

    suspend fun showMedication(data: MedicationData, occurrence: Occurrence) {
        if (!notificationsEnabled()) return
        val med = data.medication
        val hide = settings.current().hideNamesOnLockScreen
        val timeText = Formats.time(context, occurrence.time)
        val title = context.getString(R.string.notif_med_title, med.name)
        val text = context.getString(R.string.notif_med_text, occurrence.doseLabel.ifBlank { context.getString(R.string.dose_default) }, timeText)
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_MEDICATION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_med_public_title))
            .setContentText(context.getString(R.string.notif_public_text))
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_MEDICATION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(if (hide) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(publicVersion)
            .setContentIntent(openAppIntent())
            .setAutoCancel(false)
            .addAction(0, context.getString(R.string.action_taken), medAction(NotificationActionReceiver.ACTION_TAKEN, med.id, occurrence.key))
            .addAction(0, context.getString(R.string.action_skip), medAction(NotificationActionReceiver.ACTION_SKIP, med.id, occurrence.key))
            .addAction(0, context.getString(R.string.action_snooze), medAction(NotificationActionReceiver.ACTION_SNOOZE, med.id, occurrence.key))
        post(TAG_MED + occurrence.key, builder)
    }

    /** A sample medication-channel notification to check sound and delivery with the screen off. */
    fun showTest() {
        if (!notificationsEnabled()) return
        val builder = NotificationCompat.Builder(context, CHANNEL_MEDICATION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_test_title))
            .setContentText(context.getString(R.string.notif_test_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
        post(TAG_TEST, builder)
    }

    /**
     * One notification for several doses due at the same time, with "Taken all" and "Snooze all". Each dose is
     * still recorded separately and validated by the repository. When a dose is recorded elsewhere it is removed
     * from the notification (see [cancelMedication]).
     */
    suspend fun showMedicationGroup(items: List<Pair<MedicationData, Occurrence>>, at: Instant) {
        if (!notificationsEnabled() || items.isEmpty()) return
        val doses = items.map { (data, occ) -> "${data.medication.id}|${occ.key}" }
        val lines = items.map { (data, occ) -> "${data.medication.name} · ${occ.doseLabel.ifBlank { context.getString(R.string.dose_default) }}" }
        postMedicationGroup(TAG_MED_GROUP + at.epochSecond / 60, doses, lines, Formats.time(context, items.first().second.time))
    }

    private suspend fun postMedicationGroup(tag: String, doses: List<String>, lines: List<String>, timeText: String) {
        val hide = settings.current().hideNamesOnLockScreen
        val title = context.resources.getQuantityString(R.plurals.notif_med_group_title, lines.size, lines.size, timeText)
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_MEDICATION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_med_public_title))
            .setContentText(context.getString(R.string.notif_public_text))
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_MEDICATION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.joinToString(", "))
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Updating the list after one dose was recorded must not ring again.
            .setOnlyAlertOnce(true)
            .setVisibility(if (hide) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(publicVersion)
            .setContentIntent(openAppIntent())
            .setAutoCancel(false)
            .addExtras(
                Bundle().apply {
                    putStringArray(EXTRA_GROUP_ITEMS, doses.toTypedArray())
                    putStringArray(EXTRA_GROUP_LINES, lines.toTypedArray())
                    putString(EXTRA_GROUP_TIME, timeText)
                },
            )
            .addAction(0, context.getString(R.string.action_taken_all), groupAction(NotificationActionReceiver.ACTION_TAKE_ALL, tag, doses))
            .addAction(0, context.getString(R.string.action_snooze_all), groupAction(NotificationActionReceiver.ACTION_SNOOZE_ALL, tag, doses))
        post(tag, builder)
    }

    /** One notification for several habit reminders at the same time, with "Done all" for the ones that allow it. */
    suspend fun showHabitGroup(items: List<Pair<HabitData, LocalDate>>, at: Instant) {
        if (!notificationsEnabled() || items.isEmpty()) return
        val hide = settings.current().hideNamesOnLockScreen
        val tag = TAG_HABIT_GROUP + at.epochSecond / 60
        val names = items.map { it.first.habit.name }
        val completable = items.filter { (data, _) -> data.habit.type in setOf(HabitType.CHECK, HabitType.MULTI, HabitType.WEEKLY_GOAL) }
            .map { (data, date) -> "${data.habit.id}|$date" }
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_HABIT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_habit_public_title))
            .setContentText(context.getString(R.string.notif_public_text))
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_HABIT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notif_habit_group_title, names.size, names.size))
            .setContentText(names.joinToString(", "))
            .setStyle(NotificationCompat.InboxStyle().also { style -> names.forEach(style::addLine) })
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(if (hide) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(publicVersion)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .addExtras(Bundle().apply { putStringArray(EXTRA_GROUP_ITEMS, items.map { (d, date) -> "${d.habit.id}|$date" }.toTypedArray()) })
        if (completable.isNotEmpty()) {
            builder.addAction(0, context.getString(R.string.action_done_all), groupAction(NotificationActionReceiver.ACTION_HABITS_DONE, tag, completable))
        }
        post(tag, builder)
    }

    private fun groupAction(action: String, tag: String, items: List<String>): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_ITEMS, items.toTypedArray())
            .putExtra(NotificationActionReceiver.EXTRA_TAG, tag)
        return PendingIntent.getBroadcast(context, (action + tag).hashCode(), intent, PI_FLAGS)
    }

    fun cancelTag(tag: String) = manager.cancel(tag, NOTIFICATION_ID)

    suspend fun showHabit(data: HabitData, date: LocalDate) {
        if (!notificationsEnabled()) return
        val habit = data.habit
        val hide = settings.current().hideNamesOnLockScreen
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_HABIT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_habit_public_title))
            .setContentText(context.getString(R.string.notif_public_text))
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_HABIT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_habit_title, habit.name))
            .setContentText(context.getString(R.string.notif_habit_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(if (hide) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(publicVersion)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
        if (habit.type == HabitType.CHECK || habit.type == HabitType.MULTI || habit.type == HabitType.WEEKLY_GOAL) {
            val intent = Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_HABIT_DONE)
                .putExtra(NotificationActionReceiver.EXTRA_HABIT_ID, habit.id)
                .putExtra(NotificationActionReceiver.EXTRA_DATE, date.toString())
            val pi = PendingIntent.getBroadcast(context, ("habit" + habit.id + date).hashCode(), intent, PI_FLAGS)
            val label = if (habit.type == HabitType.CHECK) R.string.action_done else R.string.action_plus_one
            builder.addAction(0, context.getString(label), pi)
        }
        post(habitTag(habit.id, date), builder)
    }

    suspend fun showLowStock(data: MedicationData) {
        if (!notificationsEnabled()) return
        val med = data.medication
        val hide = settings.current().hideNamesOnLockScreen
        val stock = InventoryRules.stock(data.adjustments)
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_STOCK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_stock_public_title))
            .setContentText(context.getString(R.string.notif_public_text))
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_STOCK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_stock_title, med.name))
            .setContentText(context.getString(R.string.notif_stock_text, stock.formatQuantity(), med.inventoryUnit))
            .setVisibility(if (hide) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(publicVersion)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
        post(TAG_STOCK + med.id, builder)
    }

    suspend fun cancelMedication(occurrenceKey: String) {
        manager.cancel(TAG_MED + occurrenceKey, NOTIFICATION_ID)
        removeFromMedicationGroups(occurrenceKey)
    }

    /** A dose recorded elsewhere leaves its group notification; an emptied group disappears. */
    private suspend fun removeFromMedicationGroups(occurrenceKey: String) {
        for (n in activeNotifications()) {
            val tag = n.tag ?: continue
            if (!tag.startsWith(TAG_MED_GROUP)) continue
            val extras = n.notification.extras
            val doses = extras.getStringArray(EXTRA_GROUP_ITEMS)?.toList() ?: continue
            val lines = extras.getStringArray(EXTRA_GROUP_LINES)?.toList() ?: continue
            val index = doses.indexOfFirst { it.substringAfter('|') == occurrenceKey }
            if (index < 0 || doses.size != lines.size) continue
            val restDoses = doses.filterIndexed { i, _ -> i != index }
            val restLines = lines.filterIndexed { i, _ -> i != index }
            if (restDoses.isEmpty()) {
                manager.cancel(tag, NOTIFICATION_ID)
            } else {
                postMedicationGroup(tag, restDoses, restLines, extras.getString(EXTRA_GROUP_TIME).orEmpty())
            }
        }
    }

    private fun activeNotifications(): List<android.service.notification.StatusBarNotification> =
        runCatching { context.getSystemService(NotificationManager::class.java).activeNotifications.toList() }.getOrDefault(emptyList())

    fun cancelHabit(habitId: String, date: LocalDate) = manager.cancel(habitTag(habitId, date), NOTIFICATION_ID)

    /** Removes habit reminders that no longer apply (done, paused, archived or deleted habits). */
    fun cancelResolvedHabitReminders(habits: List<HabitData>, time: TimeSource) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val active = runCatching { nm.activeNotifications.toList() }.getOrDefault(emptyList())
        val byId = habits.associateBy { it.habit.id }
        for (n in active) {
            val tag = n.tag ?: continue
            if (!tag.startsWith(TAG_HABIT)) continue
            val (habitId, dateText) = tag.removePrefix(TAG_HABIT).split('@').takeIf { it.size == 2 } ?: continue
            val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: continue
            val data = byId[habitId]
            val stillNeeded = data != null && data.habit.status == io.github.funnyhell.daymark.domain.common.LifecycleStatus.ACTIVE &&
                HabitEvaluator(data).let { it.isScheduled(date) && it.progressOn(date) < it.targetOn(date) } &&
                !date.isBefore(time.today())
            if (!stillNeeded) manager.cancel(tag, NOTIFICATION_ID)
        }
        // A habit group disappears once none of its habits is still to do.
        for (n in active) {
            val tag = n.tag ?: continue
            if (!tag.startsWith(TAG_HABIT_GROUP)) continue
            val items = n.notification.extras.getStringArray(EXTRA_GROUP_ITEMS)?.toList() ?: continue
            val anyNeeded = items.any { item ->
                val data = byId[item.substringBefore('|')] ?: return@any false
                val date = runCatching { LocalDate.parse(item.substringAfter('|')) }.getOrNull() ?: return@any false
                data.habit.status == io.github.funnyhell.daymark.domain.common.LifecycleStatus.ACTIVE &&
                    HabitEvaluator(data).let { it.isScheduled(date) && it.progressOn(date) < it.targetOn(date) }
            }
            if (!anyNeeded) manager.cancel(tag, NOTIFICATION_ID)
        }
    }

    fun cancelAll() = manager.cancelAll()

    private fun post(tag: String, builder: NotificationCompat.Builder) {
        if (!hasPermission()) return
        try {
            manager.notify(tag, NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call; nothing to show.
        }
    }

    private fun medAction(action: String, medicationId: String, key: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_MEDICATION_ID, medicationId)
            .putExtra(NotificationActionReceiver.EXTRA_OCCURRENCE_KEY, key)
        return PendingIntent.getBroadcast(context, (action + key).hashCode(), intent, PI_FLAGS)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PI_FLAGS)
    }

    companion object {
        const val CHANNEL_MEDICATION = "medication_reminders_v2"
        const val CHANNEL_HABIT = "habit_reminders_v2"
        const val CHANNEL_STOCK = "low_stock_v2"
        private val LEGACY_CHANNELS = listOf("medication_reminders", "habit_reminders", "low_stock")
        const val NOTIFICATION_ID = 1
        private const val TAG_MED = "med:"
        private const val TAG_HABIT = "habit:"
        private const val TAG_STOCK = "stock:"
        private const val TAG_MED_GROUP = "medgroup:"
        private const val TAG_HABIT_GROUP = "habitgroup:"
        private const val EXTRA_GROUP_ITEMS = "io.github.funnyhell.daymark.group_items"
        private const val EXTRA_GROUP_LINES = "io.github.funnyhell.daymark.group_lines"
        private const val EXTRA_GROUP_TIME = "io.github.funnyhell.daymark.group_time"
        private const val TAG_TEST = "test"
        private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        fun habitTag(habitId: String, date: LocalDate) = "$TAG_HABIT$habitId@$date"
    }
}
