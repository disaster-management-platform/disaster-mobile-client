package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.assessments

import androidx.room.*
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
abstract class AssessmentDao {
    @Query("SELECT * FROM assessments WHERE owner=:owner AND state!='SCOPE_REMOVED' AND (disasterId IS NULL OR disasterId IN (:disasters)) ORDER BY rowid DESC")
    abstract fun observe(owner: String, disasters: List<Int>): Flow<List<LocalAssessment>>
    @Query("SELECT * FROM assessment_catalogs WHERE owner=:owner ORDER BY name")
    abstract fun catalogs(owner: String): Flow<List<AssessmentCatalog>>
    @Query("SELECT f.* FROM assessment_files f JOIN assessments a ON a.localId=f.assessmentId WHERE f.owner=:owner AND a.owner=:owner AND a.state!='SCOPE_REMOVED' AND (a.disasterId IS NULL OR a.disasterId IN (:disasters)) ORDER BY f.rowid")
    abstract fun observeFiles(owner: String, disasters: List<Int>): Flow<List<AssessmentFile>>
    @Query("SELECT * FROM assessments WHERE owner=:owner AND localId=:id")
    abstract suspend fun get(owner: String, id: String): LocalAssessment?
    @Query("SELECT * FROM assessments WHERE owner=:owner AND remoteId=:id")
    abstract suspend fun remote(owner: String, id: String): LocalAssessment?
    @Query("SELECT * FROM assessments WHERE owner=:owner AND rowid>COALESCE((SELECT rowid FROM assessments WHERE localId=:after),0) AND (disasterId IS NULL OR disasterId IN (:disasters)) AND (state='UPLOADING_EVIDENCE' OR (state='PENDING' AND creationPayload IS NOT NULL)) ORDER BY rowid LIMIT 50")
    abstract suspend fun pending(owner: String, disasters: List<Int>, after: String?): List<LocalAssessment>
    @Query("SELECT * FROM assessment_files WHERE owner=:owner")
    abstract suspend fun allFiles(owner: String): List<AssessmentFile>
    @Upsert abstract suspend fun save(row: LocalAssessment)
    @Upsert abstract suspend fun saveFile(file: AssessmentFile)
    @Upsert abstract suspend fun saveCatalogs(rows: List<AssessmentCatalog>)
    @Query("SELECT * FROM assessment_files WHERE owner=:owner AND assessmentId=:id ORDER BY rowid")
    abstract suspend fun files(owner: String, id: String): List<AssessmentFile>
    @Query("SELECT cursor FROM assessment_cursors WHERE owner=:owner AND scope=:scope AND disaster=:disaster")
    abstract suspend fun cursor(owner: String, scope: String, disaster: Int): String?
    @Upsert protected abstract suspend fun saveCursor(cursor: AssessmentCursor)
    @Query("DELETE FROM assessment_cursors WHERE owner=:owner AND (scope!=:scope OR disaster=:disaster)")
    abstract suspend fun resetCursor(owner: String, scope: String, disaster: Int)
    @Query("DELETE FROM assessments WHERE localId=:id AND state='SYNCED' AND NOT EXISTS (SELECT 1 FROM assessment_files WHERE assessmentId=:id)")
    protected abstract suspend fun removeCached(id: String): Int

    @Transaction
    open suspend fun creation(owner: String, id: String, receipt: JsonObject): Boolean {
        val row = get(owner, id) ?: return false
        val remoteId = AssessmentWire.text(receipt, "evaluationId")
        val cached = remote(owner, remoteId)
        if (cached != null && cached.localId != id && removeCached(cached.localId) != 1) {
            save(row.copy(state = "CONFLICT", error = "La identidad remota requiere revisión")); return false
        }
        val candidates = listOfNotNull(cached?.remoteJson, row.observedRemoteJson, receipt.toString())
        val latest = candidates.maxBy { AssessmentWire.number(AssessmentWire.json(it), "version") }
        val value = AssessmentWire.json(latest)
        save(row.copy(remoteId = remoteId, remoteJson = latest, version = AssessmentWire.number(if(files(owner,id).isEmpty())value else receipt, "version"), revision = AssessmentWire.number(if(files(owner,id).isEmpty())value else receipt, "revision"), state = if (files(owner, id).isEmpty()) "SYNCED" else "UPLOADING_EVIDENCE", error = null))
        return true
    }
    @Transaction
    open suspend fun page(session: AssessmentSession, disaster: Int, values: List<JsonObject>, next: String, removed: List<JsonObject> = emptyList()) {
        for (value in values) {
            val id = AssessmentWire.text(value, "evaluationId")
            val request = AssessmentWire.text(value, "clientRequestId")
            val current = remote(session.owner, id) ?: get(session.owner, request)
            if (current != null && current.state !in listOf("SYNCED","SCOPE_REMOVED")) {
                val observed = current.observedRemoteJson?.let(AssessmentWire::json)
                if (observed == null || AssessmentWire.number(value, "version") > AssessmentWire.number(observed, "version")) save(current.copy(observedRemoteJson = value.toString()))
                continue
            }
            if (current?.version != null && current.version > AssessmentWire.number(value, "version")) continue
            if (current?.state == "SCOPE_REMOVED" && files(session.owner,current.localId).any { it.state != "ASSOCIATED" }) {
                save(current.copy(disasterId=AssessmentWire.nullableInt(value,"disasterId"),state="CONFLICT",observedRemoteJson=value.toString(),error="El ámbito cambió; revise los cambios locales conservados"));continue
            }
            save(LocalAssessment(current?.localId ?: UUID.nameUUIDFromBytes((session.owner + "/" + id).toByteArray()).toString(), session.owner,
                AssessmentWire.text(value, "evaluator"), AssessmentWire.text(value, "organization"), AssessmentWire.nullableInt(value, "disasterId"),
                AssessmentWire.input(value).toString(), current?.creationPayload, id, AssessmentWire.number(value, "revision"), AssessmentWire.number(value, "version"), value.toString(), state = "SYNCED"))
        }
        for (value in removed) {
            val id=AssessmentWire.uuid(AssessmentWire.text(value,"evaluationId"))
            val version=AssessmentWire.number(value,"version");require(version>=0)
            val current=remote(session.owner,id) ?: continue
            if ((current.disasterId ?: 0)!=disaster || (current.version ?: -1)>version)continue
            save(current.copy(state="SCOPE_REMOVED",error="La evaluación salió del ámbito autorizado; datos locales protegidos para reconciliación"))
        }
        saveCursor(AssessmentCursor(session.owner, session.scope, disaster, next))
    }
    @Transaction
    open suspend fun complete(row: LocalAssessment): Boolean {
        val current=get(row.owner,row.localId) ?: return false
        if(current.state in listOf("ERROR","CONFLICT","SCOPE_REMOVED"))return false
        if((current.version ?: -1)>(row.version ?: -1))return false
        val confirmed=AssessmentWire.json(checkNotNull(row.remoteJson))
        val references=confirmed["evidence"].asJsonArray.map { it.asString }
        val latest=files(row.owner,row.localId)
        for(file in latest)if(file.state in listOf("VERIFIED","ASSOCIATED") && file.reference!=null && file.reference in references)
            saveFile(file.copy(state="ASSOCIATED",error=null))
        val complete=files(row.owner,row.localId).all { it.state=="ASSOCIATED" && it.reference in references }
        save(current.copy(remoteJson=row.remoteJson,version=row.version,revision=row.revision,
            state=if(complete)"SYNCED" else "UPLOADING_EVIDENCE",error=null,
            observedRemoteJson=current.observedRemoteJson?.takeIf { AssessmentWire.number(AssessmentWire.json(it),"version")>(row.version ?: -1) }))
        return complete
    }
    @Transaction
    open suspend fun completeCapture(owner: String, id: String, size: Long?, sha: String?): Boolean {
        val file=allFiles(owner).firstOrNull { it.id==id } ?: return false
        val parent=get(owner,file.assessmentId) ?: return false
        if (file.state!="CAPTURING" && !(size!=null && file.state=="ERROR" && file.sha256.isEmpty() && file.evidenceId==null)) return false
        val cancelled=size==null
        val message=if(cancelled) "Captura cancelada; archivo conservado para revisión" else null
        saveFile(if(cancelled) file.copy(state="ERROR",error=message) else file.copy(size=checkNotNull(size),sha256=checkNotNull(sha),state="PENDING",error=null))
        if(parent.state in listOf("SCOPE_REMOVED","CONFLICT")) return false
        save(parent.copy(state=if(cancelled) "ERROR" else if(parent.remoteId==null) "PENDING" else "UPLOADING_EVIDENCE",error=message))
        return !cancelled
    }
    @Transaction
    open suspend fun attach(row: LocalAssessment, file: AssessmentFile) {
        val current=get(row.owner,row.localId) ?: error("Evaluación inexistente")
        require(current.state !in listOf("ERROR","CONFLICT","SCOPE_REMOVED"))
        saveFile(file)
        save(current.copy(state=if(current.remoteId==null) "PENDING" else "UPLOADING_EVIDENCE"))
    }
}
