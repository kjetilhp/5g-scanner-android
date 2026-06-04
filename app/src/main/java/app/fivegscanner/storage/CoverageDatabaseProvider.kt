package app.fivegscanner.storage

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
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
                .build()
                .also { instance = it }
        }
}
