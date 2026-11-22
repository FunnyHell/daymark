package io.github.funnyhell.daymark.ui.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.remember
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import io.github.funnyhell.daymark.platform.backup.AutoBackup
import io.github.funnyhell.daymark.platform.backup.BackupFile
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

/** A validated backup waiting for the user's choice (merge or replace). */
data class PendingImport(val text: String, val records: Int, val exportedAt: String?)

class SettingsViewModel(application: Application) : AppViewModel(application) {
    val settings: StateFlow<Settings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())
    var pendingImport by mutableStateOf<PendingImport?>(null)
    var importErrors by mutableStateOf<List<String>?>(null)
    var busy by mutableStateOf(false)
    var backups by mutableStateOf<List<BackupFile>>(emptyList())
        private set

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
    fun setMedRepeat(minutes: Int) = launchSafely {
        container.settings.setMedicationRepeatMinutes(minutes)
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

    /** The user picked a folder for automatic backups: keep access to it, back up once and start the daily job. */
    fun chooseBackupFolder(uri: Uri) = launchSafely {
        app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        container.settings.setBackupFolder(uri.toString())
        AutoBackup.schedule(app, enabled = true)
        backUpNow()
    }

    fun turnOffAutoBackup() = launchSafely {
        val folder = settings.value.backupFolder
        container.settings.setBackupFolder(null)
        AutoBackup.schedule(app, enabled = false)
        folder?.let { runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        backups = emptyList()
    }

    fun backUpNow() = launchSafely {
        busy = true
        try {
            if (AutoBackup.backUpNow(app)) say(string(R.string.msg_exported)) else say(string(R.string.error_file_open))
            refreshBackups()
        } finally {
            busy = false
        }
    }

    fun refreshBackups() = launchSafely {
        val folder = container.settings.current().backupFolder?.let(Uri::parse) ?: return@launchSafely
        backups = withContext(Dispatchers.IO) { runCatching { AutoBackup.list(app, folder) }.getOrDefault(emptyList()) }
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

    /**
     * Merge keeps everything on this phone and adds what the backup has (the same rules as device sync);
     * replace makes the data exactly the backup.
     */
    fun confirmImport(merge: Boolean) {
        val pending = pendingImport ?: return
        pendingImport = null
        launchSafely {
            busy = true
            try {
                if (merge) {
                    val changes = container.sync.applyRemote(pending.text.toByteArray(Charsets.UTF_8))
                    say(string(R.string.msg_merged, changes))
                } else {
                    when (val r = container.backup.importJson(pending.text)) {
                        is ImportResult.Imported -> say(string(R.string.msg_imported, r.records))
                        is ImportResult.Invalid -> importErrors = r.errors
                        is ImportResult.Failed -> say(string(R.string.msg_import_failed, r.message))
                    }
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
    onOpenReminders: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenModules: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    MessageEffect(vm.messages, showMessage)
    val context = LocalContext.current
    val permissions = rememberReminderPermissions()
    val remindersOk = permissions.notifications && permissions.exactAlarms && permissions.unrestrictedBattery

    Column(
        Modifier.verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        NavRow(
            stringResource(R.string.settings_reminders),
            stringResource(if (remindersOk) R.string.settings_reminders_ok else R.string.settings_reminders_check),
            attention = !remindersOk,
            onClick = onOpenReminders,
        )
        NavRow(
            stringResource(R.string.settings_backup),
            s.lastBackupAt?.let { stringResource(R.string.settings_backup_last, Formats.dateTime(context, it)) }
                ?: stringResource(R.string.settings_backup_desc),
            onClick = onOpenBackup,
        )
        NavRow(stringResource(R.string.settings_modules), stringResource(R.string.settings_modules_short), onClick = onOpenModules)
        NavRow(stringResource(R.string.settings_sync), stringResource(R.string.settings_sync_desc), onClick = onOpenSync)

        SectionHeader(stringResource(R.string.settings_appearance))
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.themeMode == ThemeMode.SYSTEM, { vm.setTheme(ThemeMode.SYSTEM) }, { Text(stringResource(R.string.theme_system)) })
            FilterChip(s.themeMode == ThemeMode.LIGHT, { vm.setTheme(ThemeMode.LIGHT) }, { Text(stringResource(R.string.theme_light)) })
            FilterChip(s.themeMode == ThemeMode.DARK, { vm.setTheme(ThemeMode.DARK) }, { Text(stringResource(R.string.theme_dark)) })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SwitchRow(stringResource(R.string.settings_dynamic_color), null, s.dynamicColor, vm::setDynamic)
        }
        Text(stringResource(R.string.settings_week_start), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY).forEach { d ->
                FilterChip(s.firstDayOfWeek == d, { vm.setWeekStart(d) }, { Text(d.getDisplayName(java.time.format.TextStyle.FULL, Formats.locale(context))) })
            }
        }
        SwitchRow(stringResource(R.string.settings_hide_names), stringResource(R.string.settings_hide_names_desc), s.hideNamesOnLockScreen, vm::setHideNames)

        SectionHeader(stringResource(R.string.settings_about))
        SmallText(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.APPLICATION_ID))
        SmallText(stringResource(R.string.about_privacy))
        SmallText(stringResource(R.string.medical_disclaimer))
    }
}

/** Reminder switches and everything that decides whether reminders arrive on time. */
@Composable
fun RemindersSettingsScreen(
    onBack: () -> Unit,
    showMessage: suspend (UiMessage) -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    MessageEffect(vm.messages, showMessage)
    val context = LocalContext.current
    val permissions = rememberReminderPermissions()
    val requestNotifications = rememberNotificationPermissionRequest()
    SubScreen(stringResource(R.string.settings_reminders), onBack) {
        SwitchRow(stringResource(R.string.settings_med_reminders), null, s.medicationReminders, vm::setMedReminders)
        var repeatDialog by remember { mutableStateOf(false) }
        NavRow(
            stringResource(R.string.settings_med_repeat),
            repeatText(s.medicationRepeatMinutes),
            enabled = s.medicationReminders,
        ) { repeatDialog = true }
        if (repeatDialog) {
            AlertDialog(
                onDismissRequest = { repeatDialog = false },
                title = { Text(stringResource(R.string.settings_med_repeat)) },
                text = {
                    Column(Modifier.selectableGroup()) {
                        Text(
                            stringResource(R.string.settings_med_repeat_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        REPEAT_CHOICES.forEach { minutes ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = s.medicationRepeatMinutes == minutes,
                                        role = Role.RadioButton,
                                        onClick = { vm.setMedRepeat(minutes); repeatDialog = false },
                                    )
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = s.medicationRepeatMinutes == minutes, onClick = null)
                                Text(repeatText(minutes), modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { repeatDialog = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        SwitchRow(stringResource(R.string.settings_habit_reminders), null, s.habitReminders, vm::setHabitReminders)
        SectionHeader(stringResource(R.string.settings_delivery))
        StatusRow(
            title = stringResource(R.string.settings_notifications),
            status = stringResource(if (permissions.notifications) R.string.status_allowed else R.string.status_not_allowed),
            action = stringResource(if (permissions.notifications) R.string.perm_open_settings else R.string.perm_allow),
            onAction = { if (permissions.notifications) SystemSettings.openNotificationSettings(context) else requestNotifications() },
        )
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            StatusRow(
                title = stringResource(R.string.settings_sound),
                status = stringResource(R.string.settings_sound_desc),
                action = stringResource(R.string.perm_open_settings),
                onAction = { SystemSettings.openChannelSettings(context, Notifier.CHANNEL_MEDICATION) },
            )
        }
        NavRow(stringResource(R.string.settings_test_reminder), stringResource(R.string.settings_test_reminder_desc), onClick = vm::sendTestReminder)
    }
}

/** Export and import, and automatic daily backups into a chosen folder. */
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    showMessage: suspend (UiMessage) -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    MessageEffect(vm.messages, showMessage)
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::export) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::pickedImport) }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::chooseBackupFolder) }
    LaunchedEffect(s.backupFolder) { vm.refreshBackups() }

    SubScreen(stringResource(R.string.settings_backup), onBack) {
        SectionHeader(stringResource(R.string.backup_auto))
        val folder = s.backupFolder
        if (folder == null) {
            NavRow(stringResource(R.string.backup_choose_folder), stringResource(R.string.backup_auto_desc), enabled = !vm.busy) { folderLauncher.launch(null) }
        } else {
            NavRow(
                stringResource(R.string.backup_folder, Uri.parse(folder).lastPathSegment?.substringAfterLast(':').orEmpty()),
                s.lastBackupAt?.let { stringResource(R.string.settings_backup_last, Formats.dateTime(context, it)) } ?: stringResource(R.string.backup_none_yet),
                enabled = !vm.busy,
            ) { folderLauncher.launch(null) }
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = vm::backUpNow, enabled = !vm.busy) { Text(stringResource(R.string.backup_now)) }
                TextButton(onClick = vm::turnOffAutoBackup, enabled = !vm.busy) { Text(stringResource(R.string.backup_turn_off)) }
            }
            vm.backups.forEach { file ->
                NavRow(
                    file.date?.let { Formats.dateFull(context, it) } ?: file.name,
                    stringResource(R.string.backup_restore),
                    enabled = !vm.busy,
                ) { vm.pickedImport(file.uri) }
            }
        }

        SectionHeader(stringResource(R.string.backup_manual))
        NavRow(stringResource(R.string.settings_export), stringResource(R.string.settings_export_desc), enabled = !vm.busy) {
            exportLauncher.launch(vm.suggestedFileName())
        }
        NavRow(stringResource(R.string.settings_import), stringResource(R.string.settings_import_desc), enabled = !vm.busy) {
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }

    vm.pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = { vm.pendingImport = null },
            title = { Text(stringResource(R.string.import_confirm_title)) },
            text = { Text(stringResource(R.string.import_confirm_body, pending.records, pending.exportedAt ?: "—")) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { vm.confirmImport(merge = true) }) { Text(stringResource(R.string.import_merge)) }
                    TextButton(onClick = { vm.confirmImport(merge = false) }) {
                        Text(stringResource(R.string.import_replace), color = MaterialTheme.colorScheme.error)
                    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp), content = content)
    }
}

@Composable
private fun SmallText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    )
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

/** A row that opens something: title, one line of detail and a chevron. */
@Composable
private fun NavRow(title: String, detail: String, enabled: Boolean = true, attention: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val REPEAT_CHOICES = listOf(0, 2, 5, 10, 15, 30)

@Composable
private fun repeatText(minutes: Int): String =
    if (minutes <= 0) stringResource(R.string.settings_med_repeat_off)
    else pluralStringResource(R.plurals.settings_med_repeat_every, minutes, minutes)
