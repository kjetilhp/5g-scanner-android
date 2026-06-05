package no.politiet.pit.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReportingBatchDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(batch: ReportingBatchEntity)

    @Query(
        """
        SELECT * FROM reporting_batches
        WHERE status IN (:statuses)
        ORDER BY created_at_epoch_millis ASC
        LIMIT 1
        """,
    )
    fun oldestBatch(statuses: List<String>): ReportingBatchEntity?

    @Query(
        """
        UPDATE reporting_batches
        SET status = :status,
            attempt_count = attempt_count + 1,
            last_attempt_at_epoch_millis = :attemptedAtEpochMillis,
            next_attempt_at_epoch_millis = :nextAttemptAtEpochMillis,
            last_error = NULL
        WHERE id = :batchId AND status IN (:expectedStatuses)
        """,
    )
    fun markAttemptStartedIfStatus(
        batchId: String,
        expectedStatuses: List<String>,
        status: String,
        attemptedAtEpochMillis: Long,
        nextAttemptAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE reporting_batches
        SET status = :status,
            uploaded_at_epoch_millis = :uploadedAtEpochMillis,
            next_attempt_at_epoch_millis = NULL,
            last_error = NULL
        WHERE id = :batchId
        """,
    )
    fun markUploaded(batchId: String, status: String, uploadedAtEpochMillis: Long)

    @Query(
        """
        UPDATE reporting_batches
        SET status = :status,
            next_attempt_at_epoch_millis = :nextAttemptAtEpochMillis,
            last_error = :lastError
        WHERE id = :batchId
        """,
    )
    fun markFailed(batchId: String, status: String, nextAttemptAtEpochMillis: Long, lastError: String)

    @Query(
        """
        UPDATE reporting_batches
        SET status = :failedStatus,
            last_error = :lastError
        WHERE status = :inFlightStatus
            AND next_attempt_at_epoch_millis IS NOT NULL
            AND next_attempt_at_epoch_millis <= :nowEpochMillis
        """,
    )
    fun recoverStaleInFlight(
        nowEpochMillis: Long,
        lastError: String,
        inFlightStatus: String = ReportingBatchEntity.StatusInFlight,
        failedStatus: String = ReportingBatchEntity.StatusFailed,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM reporting_batches
        WHERE status = :status
        """,
    )
    fun countByStatus(status: String): Int

    @Query(
        """
        SELECT last_error FROM reporting_batches
        WHERE status = :status AND last_error IS NOT NULL
        ORDER BY last_attempt_at_epoch_millis DESC
        LIMIT 1
        """,
    )
    fun latestErrorForStatus(status: String): String?

    @Query("DELETE FROM reporting_batches")
    fun deleteAll(): Int

    @Query("DELETE FROM reporting_batches WHERE id = :batchId")
    fun deleteById(batchId: String): Int

    @Query(
        """
        DELETE FROM reporting_batches
        WHERE status = :status
        """,
    )
    fun deleteByStatus(status: String): Int
}
