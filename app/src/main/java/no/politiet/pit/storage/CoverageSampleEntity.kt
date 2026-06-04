package no.politiet.pit.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Entity(
    tableName = "coverage_samples",
    indices = [
        Index("captured_at_epoch_millis"),
        Index("captured_date_utc"),
        Index("upload_status"),
        Index("upload_batch_id"),
    ],
)
data class CoverageSampleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "captured_at_epoch_millis")
    val capturedAtEpochMillis: Long,
    @ColumnInfo(name = "captured_date_utc")
    val capturedDateUtc: String,
    @ColumnInfo(name = "sample_json")
    val sampleJson: String,
    @ColumnInfo(name = "mock_telemetry")
    val mockTelemetry: Boolean,
    @ColumnInfo(name = "privacy_reduced")
    val privacyReduced: Boolean = false,
    @ColumnInfo(name = "upload_status")
    val uploadStatus: String = UploadStatusPending,
    @ColumnInfo(name = "upload_batch_id")
    val uploadBatchId: String? = null,
    @ColumnInfo(name = "uploaded_at_epoch_millis")
    val uploadedAtEpochMillis: Long? = null,
    @ColumnInfo(name = "upload_attempt_count")
    val uploadAttemptCount: Int = 0,
    @ColumnInfo(name = "last_upload_error")
    val lastUploadError: String? = null,
) {
    companion object {
        const val UploadStatusPending = "pending"
        const val UploadStatusInFlight = "in_flight"
        const val UploadStatusUploaded = "uploaded"
        const val UploadStatusFailed = "failed"

        private val dayFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

        fun from(
            sampleJson: String,
            capturedAt: Instant,
            mockTelemetry: Boolean,
            privacyReduced: Boolean,
        ): CoverageSampleEntity =
            CoverageSampleEntity(
                capturedAtEpochMillis = capturedAt.toEpochMilli(),
                capturedDateUtc = dayFormatter.format(capturedAt),
                sampleJson = sampleJson,
                mockTelemetry = mockTelemetry,
                privacyReduced = privacyReduced,
            )
    }
}
