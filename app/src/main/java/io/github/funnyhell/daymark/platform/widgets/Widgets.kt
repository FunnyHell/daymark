package io.github.funnyhell.daymark.platform.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.ActionParameters.Key
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
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
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import io.github.funnyhell.daymark.MainActivity
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.container
import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.common.startOfWeek
import io.github.funnyhell.daymark.domain.habit.DayState
import io.github.funnyhell.daymark.domain.habit.HabitData
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.medication.ActionSource
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.stats.HabitStatistics
import io.github.funnyhell.daymark.platform.goAsyncWork
import io.github.funnyhell.daymark.ui.format.Formats
import java.time.DayOfWeek
import java.time.LocalDate

object WidgetUpdater {
    private val all: List<() -> GlanceAppWidget> = listOf(::HabitWidget, ::MedicationWidget, ::ProgressWidget, ::CalendarWidget, ::StreakWidget)

    suspend fun updateAll(context: Context) {
        for (create in all) {
            runCatching { create().updateAll(context) }.onFailure { Log.w("DaymarkWidgets", "Widget update failed", it) }
        }
    }

    /** Redraws one widget after its settings changed. */
    suspend fun update(context: Context, appWidgetId: Int) {
        val manager = GlanceAppWidgetManager(context)
        val glanceId = runCatching { manager.getGlanceIdBy(appWidgetId) }.getOrNull() ?: return
        val provider = AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)?.provider?.className ?: return
        val widget = when (provider) {
            MedicationWidgetReceiver::class.java.name -> MedicationWidget()
            ProgressWidgetReceiver::class.java.name -> ProgressWidget()
            CalendarWidgetReceiver::class.java.name -> CalendarWidget()
            StreakWidgetReceiver::class.java.name -> StreakWidget()
            else -> HabitWidget()
        }
        widget.update(context, glanceId)
    }
}

private val HabitIdKey = Key<String>("habit_id")
private val MedicationIdKey = Key<String>("medication_id")
private val OccurrenceKeyKey = Key<String>("occurrence_key")
private val DoseListKey = Key<String>("doses")

private suspend fun prefsFor(context: Context, id: GlanceId): Pair<Int, WidgetPrefs> {
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
    return appWidgetId to WidgetPrefsStore(context).get(appWidgetId)
}

private fun configIntent(context: Context, appWidgetId: Int): Intent =
    Intent(context, WidgetConfigActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

// ---------------------------------------------------------------------------------------------------------------
// Habits list

/** Habits due today (marked "show on widget") with a compact check control. */
class HabitWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val (_, prefs) = prefsFor(context, id)
        val initialHabits = c.habits.all()
        val initialSettings = c.settings.current()
        val habitsFlow = c.habits.observeAll()
        provideContent {
            val habits by habitsFlow.collectAsState(initialHabits)
            val settings by c.settings.settings.collectAsState(initialSettings)
            val rows = WidgetContent.habitRows(context, habits, c.time.today(), settings.firstDayOfWeek)
                .filter { !prefs.hideCompleted || !it.done }
            GlanceTheme {
                WidgetFrame(context.getString(R.string.widget_habits_title), context.getString(R.string.widget_habits_empty), rows.isEmpty()) {
                    LazyColumn {
                        items(rows, itemId = { it.habitId.hashCode().toLong() }) { row ->
                            WidgetRow(
                                title = row.name,
                                subtitle = row.progressText,
                                accent = row.colorArgb,
                                done = row.done,
                                check = if (row.canComplete || row.done) {
                                    CheckSpec(
                                        style = prefs.checkStyle,
                                        done = row.done,
                                        enabled = row.canComplete,
                                        description = context.getString(R.string.widget_done_cd, row.name),
                                        action = actionRunCallback<CompleteHabitAction>(actionParametersOf(HabitIdKey to row.habitId)),
                                    )
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Medications list, grouped by time

/** Today's doses grouped by time; each group with several open doses gets a "mark all taken" control. */
class MedicationWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val (_, prefs) = prefsFor(context, id)
        val initial = c.medications.all()
        val medsFlow = c.medications.observeAll()
        provideContent {
            val meds by medsFlow.collectAsState(initial)
            val rows = WidgetContent.medicationRows(context, meds, c.time.today(), c.time.now(), c.time.zone())
                .filter { !prefs.hideCompleted || it.canTake }
            val groups = rows.groupBy { it.time }.toList()
            GlanceTheme {
                WidgetFrame(context.getString(R.string.widget_meds_title), context.getString(R.string.widget_meds_empty), rows.isEmpty()) {
                    LazyColumn {
                        groups.forEach { (time, group) ->
                            val open = group.filter { it.canTake }
                            item(itemId = time.toSecondOfDay().toLong() * -1 - 1) {
                                GroupHeader(
                                    text = Formats.time(context, time),
                                    allAction = if (open.size > 1) {
                                        actionRunCallback<TakeAllDosesAction>(
                                            actionParametersOf(DoseListKey to open.joinToString(";") { "${it.medicationId}|${it.occurrenceKey}" }),
                                        )
                                    } else {
                                        null
                                    },
                                    allDescription = context.getString(R.string.widget_take_all_cd, Formats.time(context, time)),
                                )
                            }
                            items(group, itemId = { it.occurrenceKey.hashCode().toLong() }) { row ->
                                WidgetRow(
                                    title = row.title,
                                    subtitle = row.subtitle,
                                    accent = row.colorArgb,
                                    done = !row.canTake,
                                    check = CheckSpec(
                                        style = prefs.checkStyle,
                                        done = !row.canTake,
                                        enabled = row.canTake,
                                        description = context.getString(R.string.widget_take_cd, row.title),
                                        action = actionRunCallback<TakeDoseAction>(
                                            actionParametersOf(MedicationIdKey to row.medicationId, OccurrenceKeyKey to row.occurrenceKey),
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Progress rings

/**
 * Rings: today (one sector per due habit, a full ring = everything due today is done), plus this week and this
 * month (share of scheduled habit days/weeks done).
 */
class ProgressWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val (_, prefs) = prefsFor(context, id)
        val initialHabits = c.habits.all()
        val initialSettings = c.settings.current()
        val habitsFlow = c.habits.observeAll()
        val palette = WidgetPalette.from(context)
        provideContent {
            val habits by habitsFlow.collectAsState(initialHabits)
            val settings by c.settings.settings.collectAsState(initialSettings)
            val today = c.time.today()
            val wk = settings.firstDayOfWeek
            val day = HabitStatistics.dayFractions(habits, today, wk)
            val week = HabitStatistics.summary(habits, today.startOfWeek(wk), today, today, wk).rate
            val month = HabitStatistics.summary(habits, today.withDayOfMonth(1), today, today, wk).rate
            val dayPercent = if (day.isEmpty()) null else day.average()
            GlanceTheme {
                Column(
                    modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp)
                        .padding(10.dp).clickable(actionStartActivity<MainActivity>()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (prefs.progressRange == ProgressRange.TODAY) {
                        RingWithLabel(WidgetGraphics.sectorRing(day, 360, palette), dayPercent, context.getString(R.string.widget_today), 96)
                    } else {
                        Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            RingWithLabel(WidgetGraphics.sectorRing(day, 240, palette), dayPercent, context.getString(R.string.widget_today), 64, GlanceModifier.defaultWeight())
                            RingWithLabel(WidgetGraphics.progressRing(week ?: 0.0, 240, palette), week, context.getString(R.string.widget_week), 64, GlanceModifier.defaultWeight())
                            RingWithLabel(WidgetGraphics.progressRing(month ?: 0.0, 240, palette), month, context.getString(R.string.widget_month), 64, GlanceModifier.defaultWeight())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RingWithLabel(bitmap: android.graphics.Bitmap, fraction: Double?, label: String, sizeDp: Int, modifier: GlanceModifier = GlanceModifier) {
    val percent = fraction?.let { "${(it * 100).toInt()}%" } ?: "—"
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = GlanceModifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = "$label: $percent",
                modifier = GlanceModifier.size(sizeDp.dp),
                contentScale = ContentScale.Fit,
            )
            Text(
                text = percent,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = (sizeDp / 5).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            )
        }
        Text(text = label, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Calendar of dots

/** This month as dots: the fuller the dot, the larger the share of due habits done that day. */
class CalendarWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val (_, prefs) = prefsFor(context, id)
        val initialHabits = c.habits.all()
        val initialSettings = c.settings.current()
        val habitsFlow = c.habits.observeAll()
        val palette = WidgetPalette.from(context)
        provideContent {
            val habits by habitsFlow.collectAsState(initialHabits)
            val settings by c.settings.settings.collectAsState(initialSettings)
            val today = c.time.today()
            val first = today.withDayOfMonth(1)
            val selected = prefs.habitId?.let { hid -> habits.firstOrNull { it.habit.id == hid } }
            val levels: Map<LocalDate, Double?> = if (selected != null) {
                val e = HabitEvaluator(selected, settings.firstDayOfWeek)
                e.days(first, today, today).associate { d ->
                    d.date to when (d.state) {
                        DayState.DONE -> 1.0
                        DayState.PARTIAL, DayState.PENDING -> d.fraction
                        DayState.MISSED, DayState.LAPSED -> 0.0
                        else -> null
                    }
                }
            } else {
                HabitStatistics.dailyActivity(habits, first, today, today, settings.firstDayOfWeek)
                    .mapValues { (_, a) -> if (a.due == 0) null else a.done.toDouble() / a.due }
            }
            val bitmap = WidgetGraphics.monthDots(first, today, levels, settings.firstDayOfWeek, 560, palette, Formats.locale(context))
            val title = listOfNotNull(Formats.monthYear(context, today), selected?.habit?.name).joinToString(" · ")
            GlanceTheme {
                Column(
                    modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp)
                        .padding(12.dp).clickable(actionStartActivity<MainActivity>()),
                ) {
                    Text(title, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    Spacer(GlanceModifier.height(6.dp))
                    Image(
                        provider = ImageProvider(bitmap),
                        contentDescription = context.getString(R.string.widget_calendar_cd, title),
                        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Streak of one habit

/** One habit's current streak in large type, its best streak, the last 7 days and a check control. */
class StreakWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val (appWidgetId, prefs) = prefsFor(context, id)
        val initialHabits = c.habits.all()
        val initialSettings = c.settings.current()
        val habitsFlow = c.habits.observeAll()
        provideContent {
            val habits by habitsFlow.collectAsState(initialHabits)
            val settings by c.settings.settings.collectAsState(initialSettings)
            val data = prefs.habitId?.let { hid -> habits.firstOrNull { it.habit.id == hid } }
            GlanceTheme {
                val frame = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp).padding(12.dp)
                if (data == null) {
                    Box(modifier = frame.clickable(actionStartActivity(configIntent(context, appWidgetId))), contentAlignment = Alignment.Center) {
                        Text(
                            context.getString(R.string.widget_streak_choose),
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center),
                        )
                    }
                } else {
                    StreakContent(context, data, frame, prefs, settings.firstDayOfWeek, c.time.today())
                }
            }
        }
    }
}

@Composable
private fun StreakContent(
    context: Context,
    data: HabitData,
    frame: GlanceModifier,
    prefs: WidgetPrefs,
    firstDayOfWeek: DayOfWeek,
    today: LocalDate,
) {
    val e = HabitEvaluator(data, firstDayOfWeek)
    val streak = e.streaks(today)
    val weeks = data.habit.type == HabitType.WEEKLY_GOAL
    val unit = context.resources.getQuantityString(if (weeks) R.plurals.unit_weeks_short else R.plurals.unit_days_short, streak.current)
    val row = WidgetContent.habitRows(context, listOf(data), today, firstDayOfWeek).firstOrNull()
    val last7 = e.days(today.minusDays(6), today, today)
    Column(modifier = frame.clickable(actionStartActivity<MainActivity>())) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                data.habit.name,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (row != null && data.habit.status == LifecycleStatus.ACTIVE && (row.canComplete || row.done)) {
                CheckControl(
                    CheckSpec(
                        style = prefs.checkStyle,
                        done = row.done,
                        enabled = row.canComplete,
                        description = context.getString(R.string.widget_done_cd, data.habit.name),
                        action = actionRunCallback<CompleteHabitAction>(actionParametersOf(HabitIdKey to data.habit.id)),
                    ),
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                streak.current.toString(),
                style = TextStyle(color = ColorProvider(Color(data.habit.colorArgb), Color(data.habit.colorArgb)), fontSize = 44.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(unit, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp), modifier = GlanceModifier.padding(bottom = 8.dp))
        }
        Text(
            context.getString(R.string.widget_best, streak.best),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
        )
        Spacer(GlanceModifier.height(6.dp))
        Row {
            last7.forEach { d ->
                val color = Color(data.habit.colorArgb)
                val filled = d.state == DayState.DONE
                val faint = d.state in setOf(DayState.NOT_SCHEDULED, DayState.PAUSED, DayState.BEFORE_START, DayState.SKIPPED)
                Box(
                    modifier = GlanceModifier.size(14.dp).cornerRadius(7.dp).background(
                        when {
                            filled -> ColorProvider(color, color)
                            faint -> ColorProvider(color.copy(alpha = 0.12f), color.copy(alpha = 0.18f))
                            else -> ColorProvider(color.copy(alpha = 0.3f), color.copy(alpha = 0.35f))
                        },
                    ),
                ) {}
                Spacer(GlanceModifier.width(4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Shared pieces

private class CheckSpec(
    val style: CheckStyle,
    val done: Boolean,
    val enabled: Boolean,
    val description: String,
    val action: Action,
)

@Composable
private fun CheckControl(spec: CheckSpec) {
    val icon = when (spec.style) {
        CheckStyle.CIRCLE -> if (spec.done) R.drawable.ic_widget_check_filled else R.drawable.ic_widget_circle
        CheckStyle.RADIO -> if (spec.done) R.drawable.ic_widget_radio_on else R.drawable.ic_widget_radio_off
    }
    var modifier = GlanceModifier.size(40.dp).padding(6.dp).semantics { contentDescription = spec.description }
    if (spec.enabled) modifier = modifier.clickable(spec.action)
    Image(
        provider = ImageProvider(icon),
        contentDescription = spec.description,
        colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
        modifier = modifier,
    )
}

@Composable
private fun WidgetFrame(title: String, empty: String, isEmpty: Boolean, content: @Composable () -> Unit) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(20.dp).padding(12.dp),
    ) {
        Text(
            text = title,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()).padding(bottom = 4.dp),
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
private fun GroupHeader(text: String, allAction: Action?, allDescription: String) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.defaultWeight(),
        )
        if (allAction != null) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_done_all),
                contentDescription = allDescription,
                colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                modifier = GlanceModifier.size(36.dp).padding(6.dp).clickable(allAction),
            )
        }
    }
}

@Composable
private fun WidgetRow(title: String, subtitle: String, accent: Long, done: Boolean, check: CheckSpec?) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = GlanceModifier.size(width = 4.dp, height = 30.dp).cornerRadius(2.dp).background(ColorProvider(Color(accent), Color(accent)))) {}
        Spacer(GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = title,
                style = TextStyle(
                    color = if (done) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Text(text = subtitle, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        }
        if (check != null) CheckControl(check)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Actions and receivers

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

/** Records TAKEN for every open dose of one time group; each dose is validated separately. */
class TakeAllDosesAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val list = parameters[DoseListKey] ?: return
        list.split(';').mapNotNull { pair -> pair.split('|').takeIf { it.size == 2 } }.forEach { (medId, key) ->
            context.container.medications.recordDose(medId, key, MedicationEventType.TAKEN, ActionSource.WIDGET)
        }
    }
}

/** Removes the per-widget settings when widgets are deleted from the home screen. */
abstract class DaymarkWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        goAsyncWork { WidgetPrefsStore(context).delete(appWidgetIds) }
    }
}

class HabitWidgetReceiver : DaymarkWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitWidget()
}

class MedicationWidgetReceiver : DaymarkWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MedicationWidget()
}

class ProgressWidgetReceiver : DaymarkWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProgressWidget()
}

class CalendarWidgetReceiver : DaymarkWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CalendarWidget()
}

class StreakWidgetReceiver : DaymarkWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StreakWidget()
}
