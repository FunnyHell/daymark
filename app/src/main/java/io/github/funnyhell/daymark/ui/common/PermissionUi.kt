package io.github.funnyhell.daymark.ui.common

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.container
import kotlinx.coroutines.flow.Flow

/** Current notification / exact-alarm status, refreshed whenever the screen resumes. */
data class ReminderPermissions(val notifications: Boolean, val exactAlarms: Boolean, val unrestrictedBattery: Boolean)

@Composable
fun rememberReminderPermissions(): ReminderPermissions {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    return remember(tick) {
        ReminderPermissions(
            notifications = context.container.notifier.notificationsEnabled(),
            exactAlarms = context.container.reminders.canScheduleExact(),
            unrestrictedBattery = SystemSettings.isIgnoringBatteryOptimizations(context),
        )
    }
}

/** Returns a function that asks for notification permission, or opens the system settings if it was refused. */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) SystemSettings.openNotificationSettings(context)
    }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.container.notifier.hasPermission()
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SystemSettings.openNotificationSettings(context)
        }
    }
}

/**
 * For implicit moments (turning on a reminder, adding a dose time): shows the system permission dialog only if
 * the permission is missing and Android still allows asking. It never opens the settings screen; a refused
 * permission is explained by [PermissionBanners] instead.
 */
@Composable
fun rememberQuietNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.container.notifier.hasPermission()) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Explains missing permissions only when reminders actually exist, with a direct way to fix them. */
@Composable
fun PermissionBanners(needed: Boolean) {
    if (!needed) return
    val permissions = rememberReminderPermissions()
    val context = LocalContext.current
    val requestNotifications = rememberNotificationPermissionRequest()
    if (!permissions.notifications) {
        Banner(
            title = stringResource(R.string.perm_notifications_title),
            body = stringResource(R.string.perm_notifications_body),
            action = stringResource(R.string.perm_allow),
            onAction = requestNotifications,
        )
    } else if (!permissions.exactAlarms) {
        Banner(
            title = stringResource(R.string.perm_exact_title),
            body = stringResource(R.string.perm_exact_body),
            action = stringResource(R.string.perm_open_settings),
            onAction = { SystemSettings.openExactAlarmSettings(context) },
        )
    } else if (!permissions.unrestrictedBattery) {
        Banner(
            title = stringResource(R.string.perm_battery_title),
            body = stringResource(R.string.perm_battery_body),
            action = stringResource(R.string.perm_allow),
            onAction = { SystemSettings.requestUnrestrictedBattery(context) },
        )
    }
}

@Composable
private fun Banner(title: String, body: String, action: String, onAction: () -> Unit) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

object SystemSettings {
    fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            appDetails(context)
        }
        start(context, intent)
    }

    fun openChannelSettings(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            start(
                context,
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
            )
        }
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            start(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
    }

    fun openAppDetails(context: Context) = start(context, appDetails(context))

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /** System dialog that lets the app run without battery optimization, so reminders are not held back. */
    @SuppressLint("BatteryLife")
    fun requestUnrestrictedBattery(context: Context) =
        start(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
fun MessageEffect(messages: Flow<UiMessage>, show: suspend (UiMessage) -> Unit) {
    LaunchedEffect(messages) { messages.collect { show(it) } }
}
