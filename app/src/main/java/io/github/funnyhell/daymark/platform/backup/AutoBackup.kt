package io.github.funnyhell.daymark.platform.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.funnyhell.daymark.container
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One backup file in the backup folder. */
data class BackupFile(val name: String, val uri: Uri, val date: LocalDate?)

/**
 * Daily backups into a folder the user picked (any folder the system file picker offers: phone storage, an SD
 * card, or a folder another app syncs). One file per day, the newest [KEEP] are kept. Nothing leaves the phone
 * unless the chosen folder does that itself.
 */
object AutoBackup {
    const val KEEP = 7
    private const val PREFIX = "daymark-backup-"
    private const val WORK = "daymark-auto-backup"
    private const val TAG = "DaymarkBackup"

    fun fileName(date: LocalDate) = "$PREFIX$date.json"

    /** Writes today's backup now (replacing an earlier one of today) and removes the oldest beyond [KEEP]. */
    suspend fun backUpNow(context: Context): Boolean = withContext(Dispatchers.IO) {
        val c = context.container
        val folder = c.settings.current().backupFolder?.let(Uri::parse) ?: return@withContext false
        val name = fileName(c.time.today())
        val bytes = c.backup.exportJson().toByteArray(Charsets.UTF_8)
        val existing = list(context, folder).firstOrNull { it.name == name }
        val target = existing?.uri
            ?: DocumentsContract.createDocument(context.contentResolver, folderDocument(folder), "application/json", name)
            ?: return@withContext false
        context.contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: return@withContext false
        list(context, folder).drop(KEEP).forEach { old ->
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, old.uri) }
        }
        c.settings.setLastBackupAt(c.time.now())
        true
    }

    /** Backups in [folder], newest first. */
    fun list(context: Context, folder: Uri): List<BackupFile> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val result = mutableListOf<BackupFile>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                if (!name.startsWith(PREFIX) || !name.endsWith(".json")) continue
                val date = runCatching { LocalDate.parse(name.removePrefix(PREFIX).removeSuffix(".json")) }.getOrNull()
                result += BackupFile(name, DocumentsContract.buildDocumentUriUsingTree(folder, id), date)
            }
        }
        return result.sortedByDescending { it.name }
    }

    private fun folderDocument(folder: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))

    /** Turns the daily job on or off to match the chosen folder. */
    fun schedule(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (enabled) {
            val request = PeriodicWorkRequestBuilder<Worker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            work.cancelUniqueWork(WORK)
        }
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = try {
            backUpNow(applicationContext)
            Result.success()
        } catch (e: Exception) {
            // For example the folder was removed or its permission revoked; the settings screen shows the last
            // successful backup date, so the failure is visible there.
            Log.w(TAG, "Automatic backup failed", e)
            Result.success()
        }
    }
}
