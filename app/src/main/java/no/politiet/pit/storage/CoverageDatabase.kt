package no.politiet.pit.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [CoverageSampleEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class CoverageDatabase : RoomDatabase() {
    abstract fun coverageSampleDao(): CoverageSampleDao
}
