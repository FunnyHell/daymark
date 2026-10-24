package io.github.funnyhell.daymark.platform.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.funnyhell.daymark.MainActivity
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.container
import io.github.funnyhell.daymark.domain.medication.ActionSource
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import android.util.Log
import androidx.glance.action.ActionParameters.Key
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics

object WidgetUpdater {
    suspend fun updateAll(context: Context) {
        runCatching {
            HabitWidget().updateAll(context)
            MedicationWidget().updateAll(context)
        }.onFailure { Log.w("DaymarkWidgets", "Widget update failed", it) }
    }
}

private val HabitIdKey = Key<String>("habit_id")
private val MedicationIdKey = Key<String>("medication_id")
private val OccurrenceKeyKey = Key<String>("occurrence_key")

/** Habit widget: today's habits marked "show on widget", with a direct completion button where safe. */
class HabitWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val initialHabits = c.habits.all()
        val initialSettings = c.settings.current()
        val habitsFlow = c.habits.observeAll()
        provideContent {
            val habits by habitsFlow.collectAsState(initialHabits)
            val settings by c.settings.settings.collectAsState(initialSettings)
            val rows = WidgetContent.habitRows(context, habits, c.time.today(), settings.firstDayOfWeek)
            GlanceTheme {
                WidgetFrame(title = context.getString(R.string.widget_habits_title), empty = context.getString(R.string.widget_habits_empty), isEmpty = rows.isEmpty()) {
                    LazyColumn {
                        items(rows, itemId = { it.habitId.hashCode().toLong() }) { row ->
                            WidgetRow(
                                title = row.name,
                                subtitle = row.progressText,
                                accent = row.colorArgb,
                                buttonText = if (row.canComplete) context.getString(R.string.widget_done) else null,
                                buttonDescription = context.getString(R.string.widget_done_cd, row.name),
                                onButton = actionRunCallback<CompleteHabitAction>(actionParametersOf(HabitIdKey to row.habitId)),
                                done = row.done,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Medication widget: today's doses and their states, with a direct "Taken" button. */
class MedicationWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val initial = c.medications.all()
        val medsFlow = c.medications.observeAll()
        provideContent {
            val meds by medsFlow.collectAsState(initial)
            val rows = WidgetContent.medicationRows(context, meds, c.time.today(), c.time.now(), c.time.zone())
            GlanceTheme {
                WidgetFrame(title = context.getString(R.string.widget_meds_title), empty = context.getString(R.string.widget_meds_empty), isEmpty = rows.isEmpty()) {
                    LazyColumn {
                        items(rows, itemId = { it.occurrenceKey.hashCode().toLong() }) { row ->
                            WidgetRow(
                                title = row.title,
                                subtitle = row.subtitle,
                                accent = row.colorArgb,
                                buttonText = if (row.canTake) context.getString(R.string.action_taken) else null,
                                buttonDescription = context.getString(R.string.widget_take_cd, row.title),
                                onButton = actionRunCallback<TakeDoseAction>(
                                    actionParametersOf(MedicationIdKey to row.medicationId, OccurrenceKeyKey to row.occurrenceKey),
                                ),
                                done = !row.canTake,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetFrame(title: String, empty: String, isEmpty: Boolean, content: @Composable () -> Unit) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp).padding(12.dp),
    ) {
        Text(
            text = title,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()).padding(bottom = 6.dp),
        )
        if (isEmpty) {
            Text(
                text = empty,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
        } else {
            content()
        }
    }
}

@Composable
private fun WidgetRow(
    title: String,
    subtitle: String,
    accent: Long,
    buttonText: String?,
    buttonDescription: String,
    onButton: androidx.glance.action.Action,
    done: Boolean,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = GlanceModifier.size(width = 4.dp, height = 32.dp).cornerRadius(2.dp).background(ColorProvider(Color(accent), Color(accent)))) {}
        Spacer(GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = (if (done) "✓ " else "") + title,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Text(text = subtitle, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        }
        if (buttonText != null) {
            Spacer(GlanceModifier.width(6.dp))
            Button(
                text = buttonText,
                onClick = onButton,
                colors = ButtonDefaults.buttonColors(backgroundColor = GlanceTheme.colors.primary, contentColor = GlanceTheme.colors.onPrimary),
                modifier = GlanceModifier.height(36.dp).semantics { contentDescription = buttonDescription },
            )
        }
    }
}

/** Records a completion through the same idempotent repository method used by notifications. */
class CompleteHabitAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val habitId = parameters[HabitIdKey] ?: return
        context.container.habits.completeIfIncomplete(habitId)
    }
}

/** Records TAKEN through the shared, validated and idempotent repository method. */
class TakeDoseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val medId = parameters[MedicationIdKey] ?: return
        val key = parameters[OccurrenceKeyKey] ?: return
        context.container.medications.recordDose(medId, key, MedicationEventType.TAKEN, ActionSource.WIDGET)
    }
}

class HabitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitWidget()
}

class MedicationWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MedicationWidget()
}
