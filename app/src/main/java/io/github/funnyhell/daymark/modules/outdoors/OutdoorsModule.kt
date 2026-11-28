package io.github.funnyhell.daymark.modules.outdoors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.module.DaymarkModule
import io.github.funnyhell.daymark.module.HomeSection
import io.github.funnyhell.daymark.module.ModuleHost
import io.github.funnyhell.daymark.module.ModuleIcon
import io.github.funnyhell.daymark.module.ModuleNavigator
import io.github.funnyhell.daymark.module.ModuleTab
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Outdoor quests: one small trip a day to a public place outside (a park, a square, a viewpoint, a monument) within
 * the walking time the user gives, confirmed by location when the user taps "I'm here", or marked by hand.
 * Places come from OpenStreetMap and are limited to outdoor public kinds, so homes, shops, clinics and other
 * private or indoor places are never offered. Location is used only while the app is open; off by default.
 */
object OutdoorsModule : DaymarkModule {
    const val ID = "outdoors"
    const val ROUTE = "outdoors"
    const val SCREEN_ROUTE = "outdoors/screen"

    override val id = ID
    override val title = R.string.module_outdoors
    override val description = R.string.module_outdoors_desc
    override val icon = ModuleIcon.Vector(Icons.Filled.Place)
    override val defaultEnabled = false
    override val settingsRoute = SCREEN_ROUTE
    override val homeSections: List<HomeSection> = listOf(QuestDaySection)

    override val tab = object : ModuleTab {
        override val route = ROUTE
        override val label = R.string.tab_outdoors

        @Composable
        override fun Content(host: ModuleHost) = QuestScreen(host.contentPadding)
    }

    override fun NavGraphBuilder.screens(nav: ModuleNavigator) {
        composable(SCREEN_ROUTE) { entry -> QuestStandaloneScreen(onBack = { nav.back(entry) }) }
    }

    val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasLocationPermission(context: Context): Boolean = LOCATION_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * A position for searching: a recent one the phone already knows (up to 15 minutes old), else a fresh one from
     * the network (fast, rough) or GPS. Null when none arrives (location off, no signal).
     */
    @SuppressLint("MissingPermission")
    suspend fun searchLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val recent = manager.allProviders.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 15 * 60_000 }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else 1_000f }
        return recent ?: fresh(context, manager, LocationManager.NETWORK_PROVIDER, 15_000) ?: fresh(context, manager, LocationManager.GPS_PROVIDER, 30_000)
    }

    /** A fresh position for "I'm here": GPS first (precise), then the network. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return fresh(context, manager, LocationManager.GPS_PROVIDER, 30_000) ?: fresh(context, manager, LocationManager.NETWORK_PROVIDER, 15_000)
    }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(context: Context, manager: LocationManager, provider: String, timeoutMs: Long): Location? {
        if (!hasLocationPermission(context) || !runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) return null
        val executor = Executors.newSingleThreadExecutor()
        try {
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor) { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                }
            }
        } finally {
            executor.shutdown()
        }
    }
}
