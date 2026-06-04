package no.politiet.pit.storage

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object CoverageDatabaseProvider {
    val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var instance: CoverageDatabase? = null

    fun database(context: Context): CoverageDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CoverageDatabase::class.java,
                "coverage.db",
            )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE coverage_samples ADD COLUMN privacy_reduced INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS reporting_batches (
                    id TEXT NOT NULL PRIMARY KEY,
                    status TEXT NOT NULL,
                    created_at_epoch_millis INTEGER NOT NULL,
                    first_sample_id INTEGER NOT NULL,
                    last_sample_id INTEGER NOT NULL,
                    sample_count INTEGER NOT NULL,
                    payload_bytes INTEGER NOT NULL,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    last_attempt_at_epoch_millis INTEGER,
                    next_attempt_at_epoch_millis INTEGER,
                    uploaded_at_epoch_millis INTEGER,
                    last_error TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_reporting_batches_status ON reporting_batches(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_reporting_batches_next_attempt_at_epoch_millis ON reporting_batches(next_attempt_at_epoch_millis)")
        }
    }
}
