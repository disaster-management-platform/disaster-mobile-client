package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DisasterEntity::class, DisasterTypeEntity::class], version = 2, exportSchema = true)
abstract class DisasterDatabase : RoomDatabase() {
    abstract fun disasterDao(): DisasterDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE disasters ADD COLUMN disasterTypeId INTEGER")
                db.execSQL("ALTER TABLE disasters ADD COLUMN attentionLevel INTEGER")
                db.execSQL("ALTER TABLE disasters ADD COLUMN version INTEGER")
                for (column in listOf("startedAt", "registeredAt", "updatedAt", "phase", "creationPayload"))
                    db.execSQL("ALTER TABLE disasters ADD COLUMN $column TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS disaster_types (id INTEGER NOT NULL, name TEXT NOT NULL, isActive INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }
    }
}
