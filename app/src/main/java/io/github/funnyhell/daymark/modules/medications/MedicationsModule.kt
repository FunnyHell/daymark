package io.github.funnyhell.daymark.modules.medications

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.module.DaymarkModule
import io.github.funnyhell.daymark.module.HomeHost
import io.github.funnyhell.daymark.module.HomeSection
import io.github.funnyhell.daymark.module.ModuleFab
import io.github.funnyhell.daymark.module.ModuleHost
import io.github.funnyhell.daymark.module.ModuleIcon
import io.github.funnyhell.daymark.module.ModuleNavigator
import io.github.funnyhell.daymark.module.ModuleTab
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.MessageEffect
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.TrackingViewModel
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.medications.DoseItem
import io.github.funnyhell.daymark.ui.medications.DoseRow
import io.github.funnyhell.daymark.ui.medications.MedicationDetailScreen
import io.github.funnyhell.daymark.ui.medications.MedicationEditScreen
import io.github.funnyhell.daymark.ui.medications.MedicationPresentation
import io.github.funnyhell.daymark.ui.medications.MedicationsScreen
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Medications: the Medications tab, medication screens and the doses of the day on Home. Reminders, notifications
 * and widgets for doses are built in (ReminderEngine, Notifier, widgets).
 */
object MedicationsModule : DaymarkModule {
    const val ID = "medications"
    override val id = ID
    override val title = R.string.module_medications
    override val description = R.string.module_medications_desc
    override val icon = ModuleIcon.Drawable(R.drawable.ic_pill)

    object Routes {
        const val EDIT = "medication/edit?id={id}"
        const val DETAIL = "medication/{id}"
        fun edit(id: String? = null) = "medication/edit?id=${id.orEmpty()}"
        fun detail(id: String) = "medication/$id"
    }

    override val tab = object : ModuleTab {
        override val route = "medications"
        override val label = R.string.tab_medications
        override val fab = ModuleFab(R.string.new_medication) { it.navigate(Routes.edit()) }

        @Composable
        override fun Content(host: ModuleHost) {
            MedicationsScreen(host.contentPadding, onOpen = { host.navigator.navigate(Routes.detail(it)) }, onNew = { host.navigator.navigate(Routes.edit()) })
        }
    }

    override val homeSections: List<HomeSection> = listOf(MedicationsDaySection)

    override fun NavGraphBuilder.screens(nav: ModuleNavigator) {
        composable(Routes.EDIT, arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            MedicationEditScreen(onDone = { nav.back(entry) }, showMessage = nav::showMessage)
        }
        composable(Routes.DETAIL) { entry ->
            MedicationDetailScreen(onBack = { nav.back(entry) }, onEdit = { nav.navigate(Routes.edit(it)) }, showMessage = nav::showMessage)
        }
    }
}

data class LowStockItem(val medicationId: String, val text: String)

data class MedicationsDayState(
    val loading: Boolean = true,
    val doses: List<DoseItem> = emptyList(),
    val lowStock: List<LowStockItem> = emptyList(),
    val hasMedications: Boolean = false,
)

class MedicationsDayViewModel(application: Application) : TrackingViewModel(application) {
    private val day = MutableStateFlow<LocalDate?>(null)

    fun show(date: LocalDate) {
        day.value = date
    }

    /** Re-evaluates states once a minute (due → missed) without polling the database. */
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    val state: StateFlow<MedicationsDayState> = combine(container.medications.observeAll(), day, ticker) { meds, chosen, _ ->
        val now = container.time.now()
        val zone = container.time.zone()
        val today = container.time.today()
        val date = chosen ?: today
        val active = meds.filter { it.medication.status == LifecycleStatus.ACTIVE }
        MedicationsDayState(
            loading = false,
            doses = MedicationPresentation.dosesOn(app, active, date, now, zone),
            lowStock = active.takeIf { date == today }.orEmpty().mapNotNull { data ->
                MedicationPresentation.stockInfo(app, data, today)?.takeIf { it.low }?.let {
                    LowStockItem(data.medication.id, string(R.string.low_stock_banner, data.medication.name, it.text))
                }
            },
            hasMedications = meds.isNotEmpty(),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MedicationsDayState())

    companion object {
        val Factory = appViewModelFactory { app, _ -> MedicationsDayViewModel(app) }
    }
}

/** The doses of the shown day, grouped by time, with low-stock notes for today. */
object MedicationsDaySection : HomeSection {
    override val id = "medications.day"
    override val title = R.string.section_medications

    @Composable
    override fun rememberItems(host: HomeHost): LazyListScope.() -> Unit {
        val vm: MedicationsDayViewModel = viewModel(factory = MedicationsDayViewModel.Factory)
        LaunchedEffect(host.day) { vm.show(host.day) }
        val state by vm.state.collectAsStateWithLifecycle()
        MessageEffect(vm.messages) { host.navigator.showMessage(it) }
        val canSnooze = host.isToday
        return {
            if (!state.loading) {
                item(key = "meds.header") {
                    SectionHeader(stringResource(R.string.section_medications)) {
                        if (state.doses.isNotEmpty()) {
                            Text(
                                stringResource(R.string.today_progress, state.doses.count { it.resolved }, state.doses.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(state.lowStock, key = { "low" + it.medicationId }) { low ->
                    AppCard(onClick = { host.navigator.navigate(MedicationsModule.Routes.detail(low.medicationId)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(low.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (state.doses.isEmpty()) {
                    item(key = "meds.empty") {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                stringResource(if (state.hasMedications) R.string.home_no_doses else R.string.home_no_medications_yet),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(top = 12.dp),
                            )
                            if (!state.hasMedications) {
                                TextButton(onClick = { host.navigator.navigate(MedicationsModule.Routes.edit()) }) { Text(stringResource(R.string.new_medication)) }
                            }
                        }
                    }
                }
                state.doses.groupBy { it.time }.forEach { (time, group) ->
                    val open = group.filter { !it.resolved }
                    if (group.size > 1) {
                        item(key = "group$time") {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.dose_group, group.first().timeText, group.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                if (open.size > 1) {
                                    TextButton(onClick = { vm.takeAll(open) }) { Text(stringResource(R.string.take_all)) }
                                }
                            }
                        }
                    }
                    items(group, key = { "dose" + it.occurrenceKey }) { dose ->
                        DoseRow(
                            item = dose,
                            onTake = { vm.takeDose(dose.medicationId, dose.occurrenceKey, dose.name) },
                            onSkip = { vm.skipDose(dose.medicationId, dose.occurrenceKey, dose.name) },
                            onSnooze = if (canSnooze) ({ vm.snoozeDose(dose.medicationId, dose.occurrenceKey) }) else null,
                            onCorrect = { vm.undoMedicationEvent(it) },
                            onOpen = { host.navigator.navigate(MedicationsModule.Routes.detail(dose.medicationId)) },
                        )
                    }
                }
            }
        }
    }
}
