package io.github.funnyhell.daymark.ui.settings

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.funnyhell.daymark.BuildConfig
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.data.backup.DecodeResult
import io.github.funnyhell.daymark.data.backup.ImportResult
import io.github.funnyhell.daymark.data.repo.Settings
import io.github.funnyhell.daymark.data.repo.ThemeMode
import io.github.funnyhell.daymark.platform.notifications.Notifier
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.MessageEffect
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.SystemSettings
import io.github.funnyhell.daymark.ui.common.UiMessage
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.common.rememberNotificationPermissionRequest
import io.github.funnyhell.daymark.ui.common.rememberReminderPermissions
import io.github.funnyhell.daymark.ui.format.Formats
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** A validated backup waiting for the user's confirmation. */
data class PendingImport(val text: String, val records: Int, val exportedAt: String?)

class SettingsViewModel(application: Application) : AppViewModel(application) {
    val settings: StateFlow<Settings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())
    var pendingImport by mutableStateOf<PendingImport?>(null)
    var importErrors by mutableStateOf<List<String>?>(null)
    var busy by mutableStateOf(false)

    fun setTheme(mode: ThemeMode) = launchSafely { container.settings.setThemeMode(mode) }
    fun setDynamic(on: Boolean) = launchSafely { container.settings.setDynamicColor(on) }
    fun setWeekStart(day: DayOfWeek) = launchSafely {
        container.settings.setFirstDayOfWeek(day)
        container.reminders.reschedule()
    }
    fun setMedReminders(on: Boolean) = launchSafely {
        container.settings.setMedicationReminders(on)
        container.reminders.reschedule()
    }
    fun setHabitReminders(on: Boolean) = launchSafely {
        container.settings.setHabitReminders(on)
        container.reminders.reschedule()
    }
    fun setHideNames(on: Boolean) = launchSafely { container.settings.setHideNamesOnLockScreen(on) }

    fun sendTestReminder() {
        container.reminders.scheduleTest()
        say(string(R.string.msg_test_scheduled))
    }

    fun suggestedFileName(): String = "daymark-backup-${LocalDate.now()}.json"

    fun export(uri: Uri) = launchSafely {
        busy = true
        try {
            val json = container.backup.exportJson()
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: error(string(R.string.error_file_open))
            }
            say(string(R.string.msg_exported))
        } finally {
            busy = false
        }
    }

    fun pickedImport(uri: Uri) = launchSafely {
        busy = true
        try {
            val text = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.size > MAX_BACKUP_BYTES) null else bytes.toString(Charsets.UTF_8)
                }
            }
            if (text == null) {
                importErrors = listOf(string(R.string.error_file_too_large))
                return@launchSafely
            }
            when (val result = withContext(Dispatchers.Default) { container.backup.preview(text) }) {
                is DecodeResult.Invalid -> importErrors = result.errors
                is DecodeResult.Valid -> pendingImport = PendingImport(
                    text, result.snapshot.recordCount, result.exportedAt?.let { Formats.dateTime(app, it) },
                )
            }
        } finally {
            busy = false
        }
    }

    fun confirmImport() {
        val pending = pendingImport ?: return
        pendingImport = null
        launchSafely {
            busy = true
            try {
                when (val r = container.backup.importJson(pending.text)) {
                    is ImportResult.Imported -> say(string(R.string.msg_imported, r.records))
                    is ImportResult.Invalid -> importErrors = r.errors
                    is ImportResult.Failed -> say(string(R.string.msg_import_failed, r.message))
                }
            } finally {
                busy = false
            }
        }
    }

    companion object {
        private const val MAX_BACKUP_BYTES = 50 * 1024 * 1024
        val Factory = appViewModelFactory { app, _ -> SettingsViewModel(app) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    showMessage: suspend (UiMessage) -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    MessageEffect(vm.messages, showMessage)
    val context = LocalContext.current
    val permissions = rememberReminderPermissions()
    val requestNotifications = rememberNotificationPermissionRequest()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::export) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::pickedImport) }

    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        SectionHeader(stringResource(R.string.settings_appearance))
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp))
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.themeMode == ThemeMode.SYSTEM, { vm.setTheme(ThemeMode.SYSTEM) }, { Text(stringResource(R.string.theme_system)) })
            FilterChip(s.themeMode == ThemeMode.LIGHT, { vm.setTheme(ThemeMode.LIGHT) }, { Text(stringResource(R.string.theme_light)) })
            FilterChip(s.themeMode == ThemeMode.DARK, { vm.setTheme(ThemeMode.DARK) }, { Text(stringResource(R.string.theme_dark)) })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SwitchRow(stringResource(R.string.settings_dynamic_color), stringResource(R.string.settings_dynamic_color_desc), s.dynamicColor, vm::setDynamic)
        }

        SectionHeader(stringResource(R.string.settings_calendar))
        Text(stringResource(R.string.settings_week_start), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp))
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY).forEach { d ->
                FilterChip(s.firstDayOfWeek == d, { vm.setWeekStart(d) }, { Text(d.getDisplayName(java.time.format.TextStyle.FULL, Formats.locale(context))) })
            }
        }

        SectionHeader(stringResource(R.string.settings_reminders))
        SwitchRow(stringResource(R.string.settings_med_reminders), null, s.medicationReminders, vm::setMedReminders)
        SwitchRow(stringResource(R.string.settings_habit_reminders), null, s.habitReminders, vm::setHabitReminders)
        StatusRow(
            title = stringResource(R.string.settings_notifications),
            status = stringResource(if (permissions.notifications) R.string.status_allowed else R.string.status_not_allowed),
            action = stringResource(if (permissions.notifications) R.string.perm_open_settings else R.string.perm_allow),
            onAction = { if (permissions.notifications) SystemSettings.openNotificationSettings(context) else requestNotifications() },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            StatusRow(
                title = stringResource(R.string.settings_sound),
                status = stringResource(R.string.settings_sound_desc),
                action = stringResource(R.string.perm_open_settings),
                onAction = { SystemSettings.openChannelSettings(context, Notifier.CHANNEL_MEDICATION) },
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            StatusRow(
                title = stringResource(R.string.settings_exact_alarms),
                status = stringResource(if (permissions.exactAlarms) R.string.status_allowed else R.string.status_not_allowed_exact),
                action = stringResource(R.string.perm_open_settings),
                onAction = { SystemSettings.openExactAlarmSettings(context) },
            )
        }
        StatusRow(
            title = stringResource(R.string.settings_battery),
            status = stringResource(if (permissions.unrestrictedBattery) R.string.status_unrestricted else R.string.status_optimized),
            action = stringResource(if (permissions.unrestrictedBattery) R.string.perm_open_settings else R.string.perm_allow),
            onAction = {
                if (permissions.unrestrictedBattery) SystemSettings.openAppDetails(context) else SystemSettings.requestUnrestrictedBattery(context)
            },
        )
        ActionRow(stringResource(R.string.settings_test_reminder), stringResource(R.string.settings_test_reminder_desc), enabled = true) {
            vm.sendTestReminder()
        }

        SectionHeader(stringResource(R.string.settings_privacy))
        SwitchRow(stringResource(R.string.settings_hide_names), stringResource(R.string.settings_hide_names_desc), s.hideNamesOnLockScreen, vm::setHideNames)

        SectionHeader(stringResource(R.string.settings_data))
        ActionRow(stringResource(R.string.settings_export), stringResource(R.string.settings_export_desc), enabled = !vm.busy) {
            exportLauncher.launch(vm.suggestedFileName())
        }
        ActionRow(stringResource(R.string.settings_import), stringResource(R.string.settings_import_desc), enabled = !vm.busy) {
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }

        SectionHeader(stringResource(R.string.settings_about))
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.APPLICATION_ID),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            stringResource(R.string.about_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Text(
            stringResource(R.string.medical_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }

    vm.pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.pendingImport = null },
            title = { Text(stringResource(R.string.import_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.import_confirm_body, pending.records, pending.exportedAt ?: "—"),
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmImport) { Text(stringResource(R.string.import_replace), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { exportLauncher.launch(vm.suggestedFileName()) }) { Text(stringResource(R.string.import_export_first)) }
                    TextButton(onClick = { vm.pendingImport = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
    vm.importErrors?.let { errors ->
        AlertDialog(
            onDismissRequest = { vm.importErrors = null },
            title = { Text(stringResource(R.string.import_invalid_title)) },
            text = { Text(stringResource(R.string.import_invalid_body) + "\n\n" + errors.joinToString("\n") { "• $it" }) },
            confirmButton = { TextButton(onClick = { vm.importErrors = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatusRow(title: String, status: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun ActionRow(title: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
