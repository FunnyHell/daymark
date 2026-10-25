package io.github.funnyhell.daymark.data.sync

import androidx.room.withTransaction
import io.github.funnyhell.daymark.data.backup.BackupCodec
import io.github.funnyhell.daymark.data.backup.BackupRepository
import io.github.funnyhell.daymark.data.backup.DecodeResult
import io.github.funnyhell.daymark.data.db.DaymarkDatabase
import io.github.funnyhell.daymark.data.repo.DataChange
import io.github.funnyhell.daymark.data.repo.DataChangeListener
import io.github.funnyhell.daymark.data.repo.Settings
import io.github.funnyhell.daymark.domain.common.TimeSource

/**
 * The data side of device sync: this device's records in the backup format, and merging the other device's
 * records into the database. Settings stay per device.
 */
class SyncRepository(
    private val db: DaymarkDatabase,
    private val backup: BackupRepository,
    private val time: TimeSource,
    private val appVersion: String,
    private val readSettings: suspend () -> Settings,
    private val listener: DataChangeListener = DataChangeListener.None,
) {
    suspend fun payload(): ByteArray =
        BackupCodec.encode(backup.snapshot(), readSettings(), time.now(), appVersion).toByteArray(Charsets.UTF_8)

    /**
     * Validates the other device's records completely, then merges them in one transaction (a failure leaves the
     * data unchanged). Returns the number of records that changed on this device.
     */
    suspend fun applyRemote(bytes: ByteArray): Int {
        val remote = when (val decoded = BackupCodec.decode(bytes.toString(Charsets.UTF_8))) {
            is DecodeResult.Valid -> decoded.snapshot
            is DecodeResult.Invalid -> throw SyncException(SyncException.Reason.INVALID_DATA, decoded.errors.joinToString("; "))
        }
        val changes = db.withTransaction {
            val local = backup.snapshot()
            val merged = SyncMerger.merge(local, remote)
            val count = SyncMerger.changes(local, merged)
            if (count > 0 || merged.deletions != local.deletions) backup.replaceAll(merged)
            count
        }
        if (changes > 0) {
            listener.onDataChanged(DataChange.Habits)
            listener.onDataChanged(DataChange.Medications())
        }
        return changes
    }
}
