package no.politiet.pit.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reporting_batches",
    indices = [
        Index("status"),
        Index("next_attempt_at_epoch_millis"),
        Index(value = ["status", "created_at_epoch_millis"]),
        Index(value = ["status", "next_attempt_at_epoch_millis"]),
        Index(value = ["status", "last_attempt_at_epoch_millis"]),
    ],
)
data class ReportingBatchEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "first_sample_id")
    val firstSampleId: Long,
    @ColumnInfo(name = "last_sample_id")
    val lastSampleId: Long,
    @ColumnInfo(name = "sample_count")
    val sampleCount: Int,
    @ColumnInfo(name = "payload_bytes")
    val payloadBytes: Long,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "last_attempt_at_epoch_millis")
    val lastAttemptAtEpochMillis: Long? = null,
    @ColumnInfo(name = "next_attempt_at_epoch_millis")
    val nextAttemptAtEpochMillis: Long? = null,
    @ColumnInfo(name = "uploaded_at_epoch_millis")
    val uploadedAtEpochMillis: Long? = null,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
) {
    companion object {
        const val StatusInFlight = "in_flight"
        const val StatusUploaded = "uploaded"
        const val StatusFailed = "failed"
    }
}
