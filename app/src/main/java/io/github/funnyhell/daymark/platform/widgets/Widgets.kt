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
import androidx.glance.appwidget.SizeMode
import androidx.glance.LocalSize
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
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt
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
private val DoneKey = Key<Boolean>("done")

/** The settings of one placed widget, read once for the first frame and then observed for changes. */
private class PrefsSource(val appWidgetId: Int, val initial: WidgetPrefs, val updates: Flow<WidgetPrefs>)

private suspend fun prefsSource(context: Context, id: GlanceId): PrefsSource {
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
    val store = WidgetPrefsStore(context)
    return PrefsSource(appWidgetId, store.get(appWidgetId), store.observe(appWidgetId))
}

@Composable
private fun PrefsSource.collect(): WidgetPrefs {
    val prefs by updates.collectAsState(initial)
    return prefs
}

private fun configIntent(context: Context, appWidgetId: Int): Intent =
    Intent(context, WidgetConfigActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

private fun frameModifier(colors: WidgetColors, padding: Int = 12): GlanceModifier =
    GlanceModifier.fillMaxSize().background(colors.background).cornerRadius(20.dp).padding(padding.dp)

/** Bitmap size in pixels for a size in dp, capped to keep widget bitmaps small. */
private fun px(context: Context, dp: Float, max: Int = 1000): Int =
    (dp * context.resources.displayMetrics.density).toInt().coerceIn(16, max)

// ---------------------------------------------------------------------------------------------------------------
// Habits list

/** Habits due today (marked "show on widget") with a compact tap-to-toggle check control. */
class HabitWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val source = prefsSource(context, id)
        val initialHabits = c.habits.all()
        val initialSettings = c.settings.current()
        val habitsFlow = c.habits.observeAll()
        provideContent {
            val prefs = source.collect()
            val colors = WidgetColors.from(context, prefs)
            val habits by habitsFlow.collectAsState(initialHabits)
            val settings by c.settings.settings.collectAsState(initialSettings)
            val rows = WidgetContent.habitRows(context, habits, c.time.today(), settings.firstDayOfWeek)
                .filter { !prefs.hideCompleted || !it.done }
            GlanceTheme {
                WidgetFrame(colors, context.getString(R.string.widget_habits_title), context.getString(R.string.widget_habits_empty), rows.isEmpty()) {
                    LazyColumn {
                        items(rows, itemId = { it.habitId.hashCode().toLong() }) { row ->
                            WidgetRow(
                                colors = colors,
                                title = row.name,
                                subtitle = row.progressText,
                                accent = row.colorArgb,
                                done = row.done,
                                check = habitCheck(context, prefs, row, row.name, row.habitId),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun habitCheck(context: Context, prefs: WidgetPrefs, row: HabitWidgetRow, name: String, habitId: String): CheckSpec? {
    val checked = row.checked
    return when {
        checked != null -> CheckSpec(
            style = prefs.checkStyle,
            done = checked,
            enabled = true,
            description = context.getString(if (checked) R.string.cd_unmark else R.string.widget_done_cd, name),
            action = actionRunCallback<CompleteHabitAction>(actionParametersOf(HabitIdKey to habitId, DoneKey to !checked)),
        )
        row.done -> CheckSpec(prefs.checkStyle, done = true, enabled = false, description = context.getString(R.string.state_done), action = null)
        else -> null
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Medications list, grouped by time

/** Today's doses grouped by time; each group with several open doses gets a "mark all taken" control. */
class MedicationWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val source = prefsSource(context, id)
        val initial = c.medications.all()
        val medsFlow = c.medications.observeAll()
        provideContent {
            val prefs = source.collect()
            val colors = WidgetColors.from(context, prefs)
            val meds by medsFlow.collectAsState(initial)
            val rows = WidgetContent.medicationRows(context, meds, c.time.today(), c.time.now(), c.time.zone())
                .filter { !prefs.hideCompleted || it.canTake }
            val groups = rows.groupBy { it.time }.toList()
            GlanceTheme {
                WidgetFrame(colors, context.getString(R.string.widget_meds_title), context.getString(R.string.widget_meds_empty), rows.isEmpty()) {
                    LazyColumn {
                        groups.forEach { (time, group) ->
                            val open = group.filter { it.canTake }
                            item(itemId = time.toSecondOfDay().toLong() * -1 - 1) {
                                GroupHeader(
                                    colors = colors,
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
                                    colors = colors,
                                    title = row.title,
                                    subtitle = row.subtitle,
                                    accent = row.colorArgb,
                                    done = !row.canTake,
                                    check = CheckSpec(
                                        style = prefs.checkStyle,
                                        done = !row.canTake,
                                        // Taken doses can be unmarked; skipped ones are corrected in the app.
                                        enabled = row.canTake || row.taken,
                                        description = context.getString(if (row.taken) R.string.cd_unmark else R.string.widget_take_cd, row.title),
                                        action = actionRunCallback<TakeDoseAction>(
                                            actionParametersOf(
                                                MedicationIdKey to row.medicationId,
                                                OccurrenceKeyKey to row.occurrenceKey,
                                                DoneKey to !row.taken,
                                            ),
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
 * month (average progress per habit, see [HabitStatistics.periodProgress]). The rings scale with the widget.
 */
class ProgressWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val source = prefsSource(context, id)
        val initialHabits = c.habits.all()
        val initialSettings = c.settings.current()
        val habitsFlow = c.habits.observeAll()
        provideContent {
            val prefs = source.collect()
            val colors = WidgetColors.from(context, prefs)
            val habits by habitsFlow.collectAsState(initialHabits)
            val settings by c.settings.settings.collectAsState(initialSettings)
            val size = LocalSize.current
            val today = c.time.today()
            val wk = settings.firstDayOfWeek
            val day = HabitStatistics.dayFractions(habits, today, wk)
            val dayValue = if (day.isEmpty()) null else day.average()
            val dayLabel = if (day.isEmpty()) {
                context.getString(R.string.widget_today)
            } else {
                context.getString(R.string.widget_today_count, day.count { it >= 1.0 }, day.size)
            }
            val labelSpace = 22f
            GlanceTheme {
                Column(
                    modifier = frameModifier(colors, padding = 8).clickable(actionStartActivity<MainActivity>()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (prefs.progressRange == ProgressRange.TODAY) {
                        val ring = minOf(size.width.value - 16f, size.height.value - 16f - labelSpace).coerceIn(36f, 220f)
                        RingWithLabel(colors, WidgetGraphics.sectorRing(day, px(context, ring), colors.palette), dayValue, dayLabel, ring)
                    } else {
                        val week = HabitStatistics.periodProgress(habits, today.startOfWeek(wk), today, wk)
                        val month = HabitStatistics.periodProgress(habits, today.withDayOfMonth(1), today, wk)
                        val ring = minOf((size.width.value - 16f) / 3f - 6f, size.height.value - 16f - labelSpace).coerceIn(28f, 150f)
                        val ringPx = px(context, ring)
                        Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                            RingWithLabel(colors, WidgetGraphics.sectorRing(day, ringPx, colors.palette), dayValue, dayLabel, ring, GlanceModifier.defaultWeight())
                            RingWithLabel(colors, WidgetGraphics.progressRing(week ?: 0.0, ringPx, colors.palette), week, context.getString(R.string.widget_week), ring, GlanceModifier.defaultWeight())
                            RingWithLabel(colors, WidgetGraphics.progressRing(month ?: 0.0, ringPx, colors.palette), month, context.getString(R.string.widget_month), ring, GlanceModifier.defaultWeight())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RingWithLabel(
    colors: WidgetColors,
    bitmap: android.graphics.Bitmap,
    fraction: Double?,
    label: String,
    sizeDp: Float,
    modifier: GlanceModifier = GlanceModifier,
) {
    val percent = fraction?.let { "${(it * 100).roundToInt()}%" } ?: "—"
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
                style = TextStyle(color = colors.onSurface, fontSize = (sizeDp / 4.6f).coerceIn(10f, 40f).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            )
        }
        Text(text = label, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center), maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Calendar of day rings

/**
 * This month with a sector ring around every day number: one sector per habit due that day, filled by its
 * progress, so the ring shows how many of the due habits were done. All habits, or one chosen habit.
 */
class CalendarWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val source = prefsSource(context, id)
        val initialHabits = c.habits.all()
        val initialSettings = c.settings.current()
        val habitsFlow = c.habits.observeAll()
        provideContent {
            val prefs = source.collect()
            val colors = WidgetColors.from(context, prefs)
            val habits by habitsFlow.collectAsState(initialHabits)
            val settings by c.settings.settings.collectAsState(initialSettings)
            val size = LocalSize.current
            val today = c.time.today()
            val wk = settings.firstDayOfWeek
            val first = today.withDayOfMonth(1)
            val selected = prefs.habitId?.let { hid -> habits.firstOrNull { it.habit.id == hid } }
            val subject = if (selected != null) listOf(selected) else habits
            val fractions = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }
                .associateWith { date -> HabitStatistics.dayFractions(subject, date, wk, today) }
            val todays = fractions[today].orEmpty()
            val title = listOfNotNull(Formats.monthYear(context, today), selected?.habit?.name).joinToString(" · ")
            val todayText = if (todays.isEmpty()) null else context.getString(R.string.widget_today_count, todays.count { it >= 1.0 }, todays.size)
            val gridWidth = size.width.value - 20f
            val gridHeight = size.height.value - 20f - 24f
            val bitmap = WidgetGraphics.monthRings(
                first, today, fractions, wk,
                px(context, gridWidth), px(context, gridHeight), colors.palette, Formats.locale(context),
            )
            GlanceTheme {
                Column(modifier = frameModifier(colors, padding = 10).clickable(actionStartActivity<MainActivity>())) {
                    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = TextStyle(color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        if (todayText != null) {
                            Text(todayText, style = TextStyle(color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                        }
                    }
                    Spacer(GlanceModifier.height(4.dp))
                    Image(
                        provider = ImageProvider(bitmap),
                        contentDescription = context.getString(R.string.widget_calendar_cd, listOfNotNull(title, todayText).joinToString(", ")),
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
        val source = prefsSource(context, id)
        val initialHabits = c.habits.all()
        val initialSettings = c.settings.current()
        val habitsFlow = c.habits.observeAll()
        provideContent {
            val prefs = source.collect()
            val colors = WidgetColors.from(context, prefs)
            val habits by habitsFlow.collectAsState(initialHabits)
            val settings by c.settings.settings.collectAsState(initialSettings)
            val data = prefs.habitId?.let { hid -> habits.firstOrNull { it.habit.id == hid } }
            GlanceTheme {
                val frame = frameModifier(colors)
                if (data == null) {
                    Box(modifier = frame.clickable(actionStartActivity(configIntent(context, source.appWidgetId))), contentAlignment = Alignment.Center) {
                        Text(
                            context.getString(R.string.widget_streak_choose),
                            style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center),
                        )
                    }
                } else {
                    StreakContent(context, colors, data, frame, prefs, settings.firstDayOfWeek, c.time.today())
                }
            }
        }
    }
}

@Composable
private fun StreakContent(
    context: Context,
    colors: WidgetColors,
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
    val color = Color(data.habit.colorArgb)
    Column(modifier = frame.clickable(actionStartActivity<MainActivity>())) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                data.habit.name,
                style = TextStyle(color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            if (row != null && data.habit.status == LifecycleStatus.ACTIVE) {
                habitCheck(context, prefs, row, data.habit.name, data.habit.id)?.let { CheckControl(colors, it) }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                streak.current.toString(),
                style = TextStyle(color = ColorProvider(color, color), fontSize = 44.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(unit, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 14.sp), modifier = GlanceModifier.padding(bottom = 8.dp))
        }
        Text(
            context.getString(R.string.widget_best, streak.best),
            style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp),
        )
        Spacer(GlanceModifier.height(6.dp))
        Row {
            last7.forEach { d ->
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
    val action: Action?,
)

@Composable
private fun CheckControl(colors: WidgetColors, spec: CheckSpec) {
    val icon = when (spec.style) {
        CheckStyle.CIRCLE -> if (spec.done) R.drawable.ic_widget_check_filled else R.drawable.ic_widget_circle
        CheckStyle.RADIO -> if (spec.done) R.drawable.ic_widget_radio_on else R.drawable.ic_widget_radio_off
    }
    var modifier = GlanceModifier.size(40.dp).padding(6.dp).semantics { contentDescription = spec.description }
    val action = spec.action
    if (spec.enabled && action != null) modifier = modifier.clickable(action)
    Image(
        provider = ImageProvider(icon),
        contentDescription = spec.description,
        colorFilter = ColorFilter.tint(if (spec.enabled) colors.accent else colors.onSurfaceVariant),
        modifier = modifier,
    )
}

@Composable
private fun WidgetFrame(colors: WidgetColors, title: String, empty: String, isEmpty: Boolean, content: @Composable () -> Unit) {
    Column(modifier = frameModifier(colors)) {
        Text(
            text = title,
            style = TextStyle(color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()).padding(bottom = 4.dp),
        )
        if (isEmpty) {
            Text(
                text = empty,
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp),
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            )
        } else {
            content()
        }
    }
}

@Composable
private fun GroupHeader(colors: WidgetColors, text: String, allAction: Action?, allDescription: String) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = TextStyle(color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.defaultWeight(),
        )
        if (allAction != null) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_done_all),
                contentDescription = allDescription,
                colorFilter = ColorFilter.tint(colors.accent),
                modifier = GlanceModifier.size(36.dp).padding(6.dp).clickable(allAction),
            )
        }
    }
}

@Composable
private fun WidgetRow(colors: WidgetColors, title: String, subtitle: String, accent: Long, done: Boolean, check: CheckSpec?) {
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
                    color = if (done) colors.onSurfaceVariant else colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
            )
            Text(text = subtitle, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
        }
        if (check != null) CheckControl(colors, check)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Actions and receivers

/**
 * Marks or unmarks today for a habit. The widget passes the state it wants ([DoneKey]), so a stale widget or a
 * repeated tap cannot flip the result back (see HabitRepository.setDone). Without the key it marks the habit done.
 */
class CompleteHabitAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val habitId = parameters[HabitIdKey] ?: return
        context.container.habits.setDone(habitId, done = parameters[DoneKey] ?: true)
    }
}

/**
 * Records TAKEN through the shared, validated and idempotent repository method, or removes a taken mark when
 * [DoneKey] is false (the stock use is reversed once).
 */
class TakeDoseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val medId = parameters[MedicationIdKey] ?: return
        val key = parameters[OccurrenceKeyKey] ?: return
        if (parameters[DoneKey] ?: true) {
            context.container.medications.recordDose(medId, key, MedicationEventType.TAKEN, ActionSource.WIDGET)
        } else {
            context.container.medications.undoTaken(medId, key)
        }
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
