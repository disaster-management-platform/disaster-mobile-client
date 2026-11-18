package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "disaster_types")
data class DisasterTypeEntity(@PrimaryKey val id: Int, val name: String, val isActive: Boolean)
