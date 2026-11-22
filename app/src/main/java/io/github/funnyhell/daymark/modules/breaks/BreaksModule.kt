package io.github.funnyhell.daymark.modules.breaks

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.funnyhell.daymark.MainActivity
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.container
import io.github.funnyhell.daymark.module.DaymarkModule
import io.github.funnyhell.daymark.module.HomeHost
import io.github.funnyhell.daymark.module.HomeSection
import io.github.funnyhell.daymark.module.ModuleIcon
import io.github.funnyhell.daymark.module.ModuleNavigator
import io.github.funnyhell.daymark.module.ModuleReminder
import io.github.funnyhell.daymark.module.ModuleReminders
import io.github.funnyhell.daymark.platform.goAsyncWork
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.format.Formats
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Movement breaks: a reminder to get up and move every so often during active hours. A small, complete module
 * that shows every part of the module contract: a Home section, a settings screen, its own storage, reminders
 * through the app's alarm and notification actions. Off until the user turns it on.
 */
object BreaksModule : DaymarkModule {
    const val ID = "breaks"
    const val SETTINGS_ROUTE = "breaks/settings"
    internal const val CHANNEL = "movement_breaks"
    private const val NOTIFICATION_TAG = "breaks"
    private const val NOTIFICATION_ID = 1

    override val id = ID
    override val title = R.string.module_breaks
    override val description = R.string.module_breaks_desc
    override val icon = ModuleIcon.Vector(Icons.Filled.Refresh)
    override val defaultEnabled = false
    override val settingsRoute = SETTINGS_ROUTE
    override val homeSections: List<HomeSection> = listOf(NextBreakSection)

    override fun onAppStart(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.breaks_channel), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = context.getString(R.string.breaks_channel_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override suspend fun onDisabled(context: Context) = cancelNotification(context)

    override val reminders = object : ModuleReminders {
        override suspend fun plan(context: Context, now: Instant, zone: ZoneId): List<ModuleReminder> {
            val state = BreaksStore(context).current()
            return BreakRules.upcoming(state.plan, state.anchor, now, zone).map { ModuleReminder("break@${it.epochSecond}", it) }
        }

        override suspend fun show(context: Context, reminder: ModuleReminder) = showNotification(context)
    }

    override fun NavGraphBuilder.screens(nav: ModuleNavigator) {
        composable(SETTINGS_ROUTE) { entry -> BreaksSettingsScreen(onBack = { nav.back(entry) }) }
    }

    private fun showNotification(context: Context) {
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
            .setContentTitle(context.getString(R.string.breaks_notif_title))
            .setContentText(context.getString(R.string.breaks_notif_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.breaks_done), action(context, BreakActionReceiver.ACTION_DONE))
            .addAction(0, context.getString(R.string.breaks_later), action(context, BreakActionReceiver.ACTION_LATER))
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // Permission revoked in the meantime; nothing to show.
        }
    }

    internal fun cancelNotification(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, NOTIFICATION_ID)

    private fun action(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(),
        Intent(context, BreakActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Records a break now (from the notification or the Home card) and moves the next one a full interval on. */
    internal suspend fun recordBreak(context: Context) {
        val c = context.container
        BreaksStore(context).done(c.time.now(), c.time.today())
        cancelNotification(context)
        c.reminders.reschedule()
    }

    internal suspend fun later(context: Context) {
        BreaksStore(context).later(context.container.time.now())
        cancelNotification(context)
        context.container.reminders.reschedule()
    }
}

/** "Done" and "Later" on the break notification. */
class BreakActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DONE -> goAsyncWork { BreaksModule.recordBreak(context) }
            ACTION_LATER -> goAsyncWork { BreaksModule.later(context) }
        }
    }

    companion object {
        const val ACTION_DONE = "io.github.funnyhell.daymark.action.BREAK_DONE"
        const val ACTION_LATER = "io.github.funnyhell.daymark.action.BREAK_LATER"
    }
}

data class NextBreakState(val loading: Boolean = true, val next: Instant? = null, val doneToday: Int = 0)

class NextBreakViewModel(application: Application) : AppViewModel(application) {
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    val state: StateFlow<NextBreakState> = combine(BreaksStore(application).state, ticker) { s, _ ->
        val now = container.time.now()
        NextBreakState(
            loading = false,
            next = BreakRules.upcoming(s.plan, s.anchor, now, container.time.zone(), days = 8).firstOrNull { it.isAfter(now) },
            doneToday = s.countOn(container.time.today()),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NextBreakState())

    fun done() = launchSafely { BreaksModule.recordBreak(app) }

    companion object {
        val Factory = appViewModelFactory { app, _ -> NextBreakViewModel(app) }
    }
}

/** The next break and today's count, with "I moved" to record a break now. */
object NextBreakSection : HomeSection {
    override val id = "breaks.next"
    override val title = R.string.module_breaks

    @Composable
    override fun rememberItems(host: HomeHost): LazyListScope.() -> Unit {
        val vm: NextBreakViewModel = viewModel(factory = NextBreakViewModel.Factory)
        val state by vm.state.collectAsStateWithLifecycle()
        val isToday = host.isToday
        return {
            if (!state.loading && isToday) {
                item(key = "breaks.card") {
                    val context = LocalContext.current
                    AppCard(onClick = { host.navigator.navigate(BreaksModule.SETTINGS_ROUTE) }) {
                        Text(stringResource(R.string.module_breaks), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val next = state.next
                        Text(
                            if (next == null) {
                                stringResource(R.string.breaks_none_planned)
                            } else {
                                val zone = context.container.time.zone()
                                val date = next.atZone(zone).toLocalDate()
                                val time = Formats.time(context, next, zone)
                                if (date == context.container.time.today()) stringResource(R.string.breaks_next_today, time)
                                else stringResource(R.string.breaks_next_on, Formats.dateMedium(context, date), time)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(onClick = vm::done) { Text(stringResource(R.string.breaks_done)) }
                            Text(
                                stringResource(R.string.breaks_done_today, state.doneToday),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
