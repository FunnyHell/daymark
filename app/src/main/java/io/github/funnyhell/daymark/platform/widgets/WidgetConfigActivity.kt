package io.github.funnyhell.daymark.platform.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.container
import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.habit.Habit
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.theme.DaymarkTheme
import kotlinx.coroutines.launch

private enum class WidgetKind { HABITS, MEDICATIONS, TODAY, PROGRESS, CALENDAR, STREAK }

/**
 * Settings for one placed widget. Opened when a widget is added (on Android 12+ it is optional and can be reopened
 * later from the widget's long-press menu) and from the streak widget when no habit is chosen yet.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Until the user saves, adding the widget counts as canceled.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val kind = when (AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className) {
            MedicationWidgetReceiver::class.java.name -> WidgetKind.MEDICATIONS
            TodayWidgetReceiver::class.java.name -> WidgetKind.TODAY
            ProgressWidgetReceiver::class.java.name -> WidgetKind.PROGRESS
            CalendarWidgetReceiver::class.java.name -> WidgetKind.CALENDAR
            StreakWidgetReceiver::class.java.name -> WidgetKind.STREAK
            else -> WidgetKind.HABITS
        }
        val store = WidgetPrefsStore(this)
        enableEdgeToEdge()
        setContent {
            DaymarkTheme {
                var prefs by remember { mutableStateOf<WidgetPrefs?>(null) }
                var habits by remember { mutableStateOf<List<Habit>>(emptyList()) }
                LaunchedEffect(Unit) {
                    prefs = store.get(appWidgetId)
                    habits = container.habits.all().map { it.habit }.filter { it.status != LifecycleStatus.ARCHIVED }
                }
                val current = prefs
                if (current != null) {
                    ConfigScreen(
                        kind = kind,
                        initial = current,
                        habits = habits,
                        onCancel = { finish() },
                        onSave = { chosen ->
                            lifecycleScope.launch {
                                store.set(appWidgetId, chosen)
                                WidgetUpdater.update(this@WidgetConfigActivity, appWidgetId)
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                                finish()
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(
    kind: WidgetKind,
    initial: WidgetPrefs,
    habits: List<Habit>,
    onCancel: () -> Unit,
    onSave: (WidgetPrefs) -> Unit,
) {
    var prefs by remember { mutableStateOf(initial) }
    val scope = rememberCoroutineScope()
    val needsHabit = kind == WidgetKind.STREAK && prefs.habitId == null
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.widget_settings_title)) },
                actions = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (kind == WidgetKind.HABITS || kind == WidgetKind.MEDICATIONS || kind == WidgetKind.STREAK) {
                SectionHeader(stringResource(R.string.widget_check_style))
                Column(Modifier.selectableGroup()) {
                    Choice(stringResource(R.string.widget_check_circle), prefs.checkStyle == CheckStyle.CIRCLE) { prefs = prefs.copy(checkStyle = CheckStyle.CIRCLE) }
                    Choice(stringResource(R.string.widget_check_radio), prefs.checkStyle == CheckStyle.RADIO) { prefs = prefs.copy(checkStyle = CheckStyle.RADIO) }
                }
            }
            if (kind == WidgetKind.HABITS || kind == WidgetKind.MEDICATIONS) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.widget_hide_completed), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = prefs.hideCompleted, onCheckedChange = { prefs = prefs.copy(hideCompleted = it) })
                }
                if (kind == WidgetKind.HABITS) {
                    Text(
                        stringResource(R.string.widget_habits_choice_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            if (kind == WidgetKind.CALENDAR || kind == WidgetKind.STREAK) {
                SectionHeader(stringResource(R.string.widget_habit))
                Column(Modifier.selectableGroup()) {
                    if (kind == WidgetKind.CALENDAR) {
                        Choice(stringResource(R.string.widget_all_habits), prefs.habitId == null) { prefs = prefs.copy(habitId = null) }
                    }
                    habits.forEach { h -> Choice(h.name, prefs.habitId == h.id) { prefs = prefs.copy(habitId = h.id) } }
                }
                if (habits.isEmpty()) {
                    Text(
                        stringResource(R.string.widget_no_habits),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            SectionHeader(stringResource(R.string.widget_color))
            val context = LocalContext.current
            val dark = isSystemInDarkTheme()
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WidgetAccent.entries.forEach { accent ->
                    val (day, night) = WidgetColors.accent(context, accent)
                    val selected = prefs.accent == accent
                    val label = stringResource(accentLabel(accent))
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(if (dark) night else day))
                            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .selectable(selected = selected, onClick = { prefs = prefs.copy(accent = accent) }, role = Role.RadioButton)
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color(if (dark) day else night))
                    }
                }
            }
            Text(
                stringResource(accentLabel(prefs.accent)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            SectionHeader(stringResource(R.string.widget_bg_opacity, prefs.backgroundAlpha))
            Slider(
                value = prefs.backgroundAlpha.toFloat(),
                onValueChange = { prefs = prefs.copy(backgroundAlpha = (it / 10f).roundToInt() * 10) },
                valueRange = 0f..100f,
                steps = 9,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Button(
                onClick = { scope.launch { onSave(prefs) } },
                enabled = !needsHabit,
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

private fun accentLabel(accent: WidgetAccent): Int = when (accent) {
    WidgetAccent.SYSTEM -> R.string.widget_color_system
    WidgetAccent.BLUE -> R.string.widget_color_blue
    WidgetAccent.GREEN -> R.string.widget_color_green
    WidgetAccent.ORANGE -> R.string.widget_color_orange
    WidgetAccent.PURPLE -> R.string.widget_color_purple
    WidgetAccent.RED -> R.string.widget_color_red
    WidgetAccent.MONO -> R.string.widget_color_mono
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
