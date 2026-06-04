package no.politiet.pit.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CoverageSampleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(sample: CoverageSampleEntity): Long

    @Query("SELECT COUNT(*) FROM coverage_samples")
    fun count(): Int

    @Query("SELECT COUNT(DISTINCT captured_date_utc) FROM coverage_samples")
    fun dayCount(): Int

    @Query("SELECT COALESCE(SUM(LENGTH(sample_json) + 1), 0) FROM coverage_samples")
    fun encodedSizeBytes(): Long

    @Query(
        """
        SELECT
            captured_date_utc AS dateUtc,
            COUNT(*) AS sampleCount,
            COALESCE(SUM(LENGTH(sample_json) + 1), 0) AS sizeBytes,
            MAX(captured_at_epoch_millis) AS modifiedAtMillis
        FROM coverage_samples
        GROUP BY captured_date_utc
        ORDER BY captured_date_utc DESC
        """,
    )
    fun recordedCoverageDays(): List<RecordedCoverageDay>

    @Query(
        """
        SELECT * FROM coverage_samples
        WHERE captured_date_utc = :dateUtc
        ORDER BY captured_at_epoch_millis ASC, id ASC
        """,
    )
    fun samplesForDate(dateUtc: String): List<CoverageSampleEntity>

    @Query(
        """
        SELECT * FROM coverage_samples
        ORDER BY id DESC
        LIMIT :limit
        """,
    )
    fun recentSamples(limit: Int): List<CoverageSampleEntity>

    @Query(
        """
        SELECT * FROM coverage_samples
        ORDER BY captured_at_epoch_millis ASC, id ASC
        """,
    )
    fun allSamples(): List<CoverageSampleEntity>

    @Query(
        """
        SELECT * FROM coverage_samples
        WHERE upload_status = :status
        ORDER BY captured_at_epoch_millis ASC, id ASC
        LIMIT :limit
        """,
    )
    fun pendingUploadSamples(
        status: String = CoverageSampleEntity.UploadStatusPending,
        limit: Int,
    ): List<CoverageSampleEntity>

    @Query(
        """
        SELECT * FROM coverage_samples
        WHERE upload_status = :status
        ORDER BY id ASC
        LIMIT :limit
        """,
    )
    fun oldestSamplesByUploadStatus(status: String, limit: Int): List<CoverageSampleEntity>

    @Query(
        """
        SELECT * FROM coverage_samples
        WHERE upload_batch_id = :batchId
        ORDER BY id ASC
        """,
    )
    fun samplesForUploadBatch(batchId: String): List<CoverageSampleEntity>

    @Query(
        """
        UPDATE coverage_samples
        SET upload_status = :status,
            upload_batch_id = :batchId,
            last_upload_error = NULL
        WHERE id IN (:sampleIds)
        """,
    )
    fun markClaimed(sampleIds: List<Long>, batchId: String, status: String): Int

    @Query(
        """
        UPDATE coverage_samples
        SET upload_status = :status,
            uploaded_at_epoch_millis = :uploadedAtEpochMillis,
            last_upload_error = NULL
        WHERE upload_batch_id = :batchId
        """,
    )
    fun markBatchUploaded(batchId: String, status: String, uploadedAtEpochMillis: Long): Int

    @Query(
        """
        UPDATE coverage_samples
        SET upload_status = :status,
            upload_attempt_count = upload_attempt_count + 1,
            last_upload_error = NULL
        WHERE upload_batch_id = :batchId
        """,
    )
    fun markBatchAttemptStarted(batchId: String, status: String): Int

    @Query(
        """
        UPDATE coverage_samples
        SET upload_status = :status,
            last_upload_error = :lastError
        WHERE upload_batch_id = :batchId
        """,
    )
    fun markBatchFailed(batchId: String, status: String, lastError: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM coverage_samples
        WHERE upload_status != :uploadedStatus
        """,
    )
    fun queuedUploadCount(uploadedStatus: String = CoverageSampleEntity.UploadStatusUploaded): Int

    @Query(
        """
        SELECT COUNT(*) FROM coverage_samples
        WHERE upload_status = :uploadedStatus
        """,
    )
    fun reportedCount(uploadedStatus: String = CoverageSampleEntity.UploadStatusUploaded): Int

    @Query(
        """
        DELETE FROM coverage_samples
        WHERE upload_status = :uploadedStatus
        """,
    )
    fun deleteReported(uploadedStatus: String = CoverageSampleEntity.UploadStatusUploaded): Int

    @Query("DELETE FROM coverage_samples")
    fun deleteAll(): Int
}

data class RecordedCoverageDay(
    val dateUtc: String,
    val sampleCount: Int,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)
