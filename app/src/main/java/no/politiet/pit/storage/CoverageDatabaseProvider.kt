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
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE coverage_samples ADD COLUMN privacy_reduced INTEGER NOT NULL DEFAULT 0")
        }
    }
}
