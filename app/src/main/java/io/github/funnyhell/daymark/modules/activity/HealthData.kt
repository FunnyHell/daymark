package io.github.funnyhell.daymark.modules.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A workout read from Health Connect (for example one recorded by Samsung Health). */
data class Workout(
    val id: String,
    val type: Int,
    val title: String?,
    val start: Instant,
    val end: Instant,
    /** Package of the app that recorded it, such as Samsung Health. */
    val source: String,
)

/** What Health Connect can give right now. */
enum class HealthAccess { NOT_INSTALLED, NEEDS_UPDATE, NO_PERMISSION, GRANTED }

/**
 * Reads activity from Health Connect, Android's on-device health store that Samsung Health and other apps share
 * data with. Only reads; nothing is written back and nothing leaves the phone.
 */
class HealthData(private val context: Context) {
    companion object {
        const val PROVIDER = "com.google.android.apps.healthdata"
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
        )

        fun permissionContract() = PermissionController.createRequestPermissionResultContract()

        /** Opens the store page of Health Connect (for older Android versions, where it is an app). */
        fun installIntent(): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun status(): Int = HealthConnectClient.getSdkStatus(context, PROVIDER)

    private fun client(): HealthConnectClient? =
        if (status() == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(context) else null

    suspend fun access(): HealthAccess = when (status()) {
        HealthConnectClient.SDK_UNAVAILABLE -> HealthAccess.NOT_INSTALLED
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAccess.NEEDS_UPDATE
        else -> {
            val granted = runCatching { client()?.permissionController?.getGrantedPermissions() }.getOrNull().orEmpty()
            if (granted.containsAll(PERMISSIONS)) HealthAccess.GRANTED else HealthAccess.NO_PERMISSION
        }
    }

    /** Workouts that started between [from] and [until] (days in [zone]), newest first; empty without access. */
    suspend fun workouts(from: LocalDate, until: LocalDate, zone: ZoneId): List<Workout> {
        val client = client() ?: return emptyList()
        val range = TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), until.plusDays(1).atStartOfDay(zone).toInstant())
        val response = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, timeRangeFilter = range))
        return response.records.map {
            Workout(it.metadata.id, it.exerciseType, it.title, it.startTime, it.endTime, it.metadata.dataOrigin.packageName)
        }.sortedByDescending { it.start }
    }

    /** Steps on [date]; null without access. */
    suspend fun steps(date: LocalDate, zone: ZoneId): Long? {
        val client = client() ?: return null
        val range = TimeRangeFilter.between(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
        val result = client.aggregate(AggregateRequest(metrics = setOf(StepsRecord.COUNT_TOTAL), timeRangeFilter = range))
        return result[StepsRecord.COUNT_TOTAL] ?: 0L
    }
}
