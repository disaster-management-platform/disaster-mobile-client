package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [DisasterEntity::class], version = 1, exportSchema = true)
abstract class DisasterDatabase : RoomDatabase() {
    abstract fun disasterDao(): DisasterDao
}
