package no.politiet.pit.fivegscanner.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CoverageSampleEntity::class, ReportingBatchEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CoverageDatabase : RoomDatabase() {
    abstract fun coverageSampleDao(): CoverageSampleDao
    abstract fun reportingBatchDao(): ReportingBatchDao
}
