package io.github.funnyhell.daymark.modules.intervals

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.funnyhell.daymark.MainActivity
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.container
import io.github.funnyhell.daymark.module.DaymarkModule
import io.github.funnyhell.daymark.module.HomeSection
import io.github.funnyhell.daymark.module.ModuleFab
import io.github.funnyhell.daymark.module.ModuleHost
import io.github.funnyhell.daymark.module.ModuleIcon
import io.github.funnyhell.daymark.module.ModuleNavigator
import io.github.funnyhell.daymark.module.ModuleReminder
import io.github.funnyhell.daymark.module.ModuleReminders
import io.github.funnyhell.daymark.module.ModuleTab
import io.github.funnyhell.daymark.platform.goAsyncWork
import java.time.Instant
import java.time.ZoneId

/**
 * Interval reminders: reminders that repeat every so often during active hours ("Stretch every hour from 9 to
 * 18 on weekdays"), each with "Done" and "Later". They use their own notification type (quieter than medication
 * reminders). A small, complete module that uses every part of the module contract. Off until the user turns it on.
 */
object IntervalsModule : DaymarkModule {
    /** The id of the first version of this module ("Movement breaks"); kept so the user's choice stays. */
    const val ID = "breaks"
    const val LIST_ROUTE = "intervals"
    const val EDIT_ROUTE = "intervals/edit?id={id}"
    fun editRoute(id: String? = null) = "intervals/edit?id=${id.orEmpty()}"

    const val CHANNEL = "interval_reminders"
    private const val LEGACY_CHANNEL = "movement_breaks"
    private const val TAG_PREFIX = "interval:"
    private const val NOTIFICATION_ID = 1
    internal const val EXTRA_ID = "interval_id"

    override val id = ID
    override val title = R.string.module_intervals
    override val description = R.string.module_intervals_desc
    override val icon = ModuleIcon.Vector(Icons.Filled.Refresh)
    override val defaultEnabled = false
    override val homeSections: List<HomeSection> = listOf(IntervalsDaySection)

    override val tab = object : ModuleTab {
        override val route = LIST_ROUTE
        override val label = R.string.tab_intervals
        override val fab = ModuleFab(R.string.intervals_new) { it.navigate(editRoute()) }

        @Composable
        override fun Content(host: ModuleHost) = IntervalListScreen(host)
    }

    override fun onAppStart(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(LEGACY_CHANNEL)
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_intervals), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.channel_intervals_desc)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audio)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 120)
        }
        nm.createNotificationChannel(channel)
    }

    override suspend fun onDisabled(context: Context) {
        IntervalStore(context).all().forEach { cancelNotification(context, it.id) }
    }

    override val reminders = object : ModuleReminders {
        override suspend fun plan(context: Context, now: Instant, zone: ZoneId): List<ModuleReminder> =
            IntervalStore(context).all().filter { it.enabled }.flatMap { r ->
                BreakRules.upcoming(r.plan, r.anchor, now, zone).map { ModuleReminder("${r.id}@${it.epochSecond}", it, group = r.id) }
            }

        override suspend fun show(context: Context, reminder: ModuleReminder) {
            val item = IntervalStore(context).get(reminder.group) ?: return
            if (item.enabled) showNotification(context, item)
        }
    }

    override fun NavGraphBuilder.screens(nav: ModuleNavigator) {
        composable(EDIT_ROUTE, arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            IntervalEditScreen(entry.arguments?.getString("id")?.takeIf { it.isNotBlank() }, onDone = { nav.back(entry) })
        }
    }

    private fun showNotification(context: Context, item: IntervalReminder) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(item.title)
            .setContentText(context.getString(R.string.intervals_notif_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.intervals_done), action(context, IntervalActionReceiver.ACTION_DONE, item.id))
            .addAction(0, context.getString(R.string.intervals_later), action(context, IntervalActionReceiver.ACTION_LATER, item.id))
        try {
            NotificationManagerCompat.from(context).notify(TAG_PREFIX + item.id, NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // Permission revoked in the meantime; nothing to show.
        }
    }

    internal fun cancelNotification(context: Context, id: String) = NotificationManagerCompat.from(context).cancel(TAG_PREFIX + id, NOTIFICATION_ID)

    fun notificationTag(id: String) = TAG_PREFIX + id

    private fun action(context: Context, action: String, id: String): PendingIntent = PendingIntent.getBroadcast(
        context, (action + id).hashCode(),
        Intent(context, IntervalActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Records "done" now (from the notification or the app) and counts the next reminder from now. */
    suspend fun done(context: Context, id: String) {
        val c = context.container
        IntervalStore(context).done(id, c.time.now(), c.time.today())
        cancelNotification(context, id)
        c.reminders.reschedule()
    }

    suspend fun later(context: Context, id: String) {
        IntervalStore(context).later(id, context.container.time.now())
        cancelNotification(context, id)
        context.container.reminders.reschedule()
    }
}

/** "Done" and "Later" on an interval reminder. */
class IntervalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(IntervalsModule.EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_DONE -> goAsyncWork { IntervalsModule.done(context, id) }
            ACTION_LATER -> goAsyncWork { IntervalsModule.later(context, id) }
        }
    }

    companion object {
        const val ACTION_DONE = "io.github.funnyhell.daymark.action.INTERVAL_DONE"
        const val ACTION_LATER = "io.github.funnyhell.daymark.action.INTERVAL_LATER"
    }
}
