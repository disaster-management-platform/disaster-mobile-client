package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "disaster_sync_state")
data class DisasterSyncState(@PrimaryKey val name: String = "disasters", val cursor: String)
