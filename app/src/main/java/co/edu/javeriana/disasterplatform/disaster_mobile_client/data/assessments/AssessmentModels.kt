package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.assessments

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ForeignKey

@Entity(tableName = "assessments", indices = [Index(value = ["owner", "remoteId"], unique = true)])
data class LocalAssessment(
    @PrimaryKey val localId: String,
    val owner: String,
    val actor: String,
    val organization: String,
    val disasterId: Int?,
    val draftJson: String,
    val creationPayload: String?,
    val remoteId: String? = null,
    val revision: Long? = null,
    val version: Long? = null,
    val remoteJson: String? = null,
    val observedRemoteJson: String? = null,
    val state: String = "PENDING",
    val error: String? = null,
)

@Entity(tableName = "assessment_catalogs", primaryKeys = ["owner", "kind", "id"])
data class AssessmentCatalog(
    val owner: String, val kind: String, val id: String, val name: String,
    val active: Boolean, val damageLevel: String?, val assetTypeId: String?,
)

@Entity(tableName = "assessment_files", foreignKeys = [ForeignKey(
    entity = LocalAssessment::class, parentColumns = ["localId"], childColumns = ["assessmentId"], onDelete = ForeignKey.RESTRICT,
)], indices = [Index("assessmentId")])
data class AssessmentFile(
    @PrimaryKey val id: String,
    val assessmentId: String,
    val owner: String,
    val path: String,
    val originalFileName: String,
    val contentType: String,
    val size: Long,
    val sha256: String,
    val intentId: String,
    val evidenceId: String? = null,
    val revision: Long? = null,
    val reference: String? = null,
    val state: String = "PENDING",
    val error: String? = null,
    val previousIntents: String = "[]",
)

@Entity(tableName = "assessment_cursors", primaryKeys = ["owner", "scope", "disaster"])
data class AssessmentCursor(val owner: String, val scope: String, val disaster: Int, val cursor: String)
