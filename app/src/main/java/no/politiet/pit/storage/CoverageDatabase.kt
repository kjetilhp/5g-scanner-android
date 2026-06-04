package no.politiet.pit.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CoverageSampleEntity::class, ReportingBatchEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class CoverageDatabase : RoomDatabase() {
    abstract fun coverageSampleDao(): CoverageSampleDao
    abstract fun reportingBatchDao(): ReportingBatchDao
}
