package co.edu.javeriana.disasterplatform.disaster_mobile_client

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.assessments.*
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterDatabase
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class OfflineAssessmentsTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun session(owner:String="owner",scope:String="scope")=AssessmentSession("", "actor","organization","issuer",listOf(12),listOf("damage.write"),Long.MAX_VALUE,owner,scope)
    private fun snapshot(id:String=UUID.randomUUID().toString(),version:Long=0)=JsonObject().apply {
        addProperty("evaluationId",id);addProperty("clientRequestId",UUID.nameUUIDFromBytes((id+"request").toByteArray()).toString());addProperty("disasterId",12)
        for(name in listOf("assetId","assetType","assetTypeId","damageCategoryId","location","description","evaluator"))addProperty(name,"known")
        addProperty("organization","organization");for(name in listOf("evaluatedAt","createdAt","updatedAt"))addProperty(name,"2026-11-18T00:00:00Z")
        addProperty("status","Draft");addProperty("damageLevel","Severe");addProperty("budget",12.34);addProperty("revision",1);addProperty("version",version)
        add("latitude",JsonNull.INSTANCE);add("longitude",JsonNull.INSTANCE);add("geometry",JsonNull.INSTANCE);add("evidence",JsonArray())
    }
    @Test fun concurrentAttachmentKeepsParentSynchronizable()=runTest {
        val db=Room.inMemoryDatabaseBuilder(context,DisasterDatabase::class.java).build()
        try {
            val dao=db.assessmentDao();val s=session();val value=snapshot();val id=value["evaluationId"].asString
            dao.page(s,12,listOf(value),"before")
            val stale=checkNotNull(dao.remote(s.owner,id)).copy(state="UPLOADING_EVIDENCE")
            dao.save(stale)
            val confirmed=value.deepCopy().apply { addProperty("version",1) }
            val file=AssessmentFile(UUID.randomUUID().toString(),stale.localId,s.owner,"private-file","test.pdf","application/pdf",5,"sha",UUID.randomUUID().toString())
            // HTTP finished with an earlier file list; a user attaches before the completion transaction.
            dao.attach(stale,file)
            assertFalse(dao.complete(stale.copy(remoteJson=confirmed.toString(),version=1)))
            val pending=checkNotNull(dao.get(s.owner,stale.localId))
            assertEquals("UPLOADING_EVIDENCE",pending.state);assertEquals(1L,pending.version)
            assertEquals(file,dao.files(s.owner,stale.localId).single())
            assertEquals(listOf(stale.localId),dao.pending(s.owner,s.disasters,null).map{it.localId})
            val reference="attachment:"+UUID.randomUUID()
            dao.saveFile(file.copy(reference=reference,state="VERIFIED"))
            confirmed["evidence"].asJsonArray.add(reference)
            assertTrue(dao.complete(pending.copy(remoteJson=confirmed.toString())))
            assertEquals("SYNCED",dao.get(s.owner,stale.localId)?.state)
            assertEquals("ASSOCIATED",dao.files(s.owner,stale.localId).single().state)
        }finally{db.close()}
    }
    @Test fun removedPreliminaryScopeIsAtomicAndProtectsLocalFiles()=runTest {
        val db=Room.inMemoryDatabaseBuilder(context,DisasterDatabase::class.java).build()
        try {
            val dao=db.assessmentDao();val s=session();val value=snapshot().apply{add("disasterId",JsonNull.INSTANCE)}
            dao.page(s,0,listOf(value),"initial")
            val row=checkNotNull(dao.remote(s.owner,value["evaluationId"].asString))
            val file=AssessmentFile(UUID.randomUUID().toString(),row.localId,s.owner,"private","test.pdf","application/pdf",5,"sha",UUID.randomUUID().toString())
            dao.attach(row,file)
            val removed=JsonObject().apply {addProperty("evaluationId",row.remoteId);addProperty("version",1)}
            try {dao.page(s,0,emptyList(),"invalid",listOf(removed,JsonObject()));fail("Partial invalidation committed")}catch(_:IllegalArgumentException){}
            assertEquals("initial",dao.cursor(s.owner,s.scope,0));assertEquals("UPLOADING_EVIDENCE",dao.get(s.owner,row.localId)?.state)
            val unauthorized=s.copy(disasters=emptyList())
            dao.page(unauthorized,0,emptyList(),"removed",listOf(removed))
            assertTrue(dao.observe(s.owner,emptyList()).first().isEmpty());assertTrue(dao.observeFiles(s.owner,emptyList()).first().isEmpty())
            assertEquals(file,dao.files(s.owner,row.localId).single());assertEquals(row.creationPayload,dao.get(s.owner,row.localId)?.creationPayload)
            assertEquals("removed",dao.cursor(s.owner,s.scope,0))
            dao.resetCursor(s.owner,s.scope,0)
            dao.page(unauthorized,0,emptyList(),"restarted",listOf(removed))
            assertTrue(dao.observe(s.owner,emptyList()).first().isEmpty())
            value.addProperty("disasterId",12);value.addProperty("version",1)
            dao.page(s,12,listOf(value),"destination")
            assertEquals("CONFLICT",dao.get(s.owner,row.localId)?.state)
            assertEquals(12,dao.get(s.owner,row.localId)?.disasterId)
            assertTrue(dao.observe(s.owner,emptyList()).first().isEmpty())
            assertEquals(1,dao.observe(s.owner,listOf(12)).first().size)
        }finally{db.close()}
    }
    @Test fun captureCompletionCannotRestoreRemovedScope()=runTest {
        val db=Room.inMemoryDatabaseBuilder(context,DisasterDatabase::class.java).build()
        try {
            val dao=db.assessmentDao();val s=session();val value=snapshot().apply{add("disasterId",JsonNull.INSTANCE)}
            dao.page(s,0,listOf(value),"initial")
            val row=checkNotNull(dao.remote(s.owner,value["evaluationId"].asString))
            val file=AssessmentFile(UUID.randomUUID().toString(),row.localId,s.owner,"private","photo.jpg","image/jpeg",0,"",UUID.randomUUID().toString(),state="CAPTURING")
            dao.attach(row,file)
            val removed=JsonObject().apply{addProperty("evaluationId",row.remoteId);addProperty("version",1)}
            dao.page(s,0,emptyList(),"removed",listOf(removed))
            assertFalse(dao.completeCapture(s.owner,file.id,10,"verified-hash"))
            assertEquals("SCOPE_REMOVED",dao.get(s.owner,row.localId)?.state)
            assertEquals("PENDING",dao.files(s.owner,row.localId).single().state)
            assertTrue(dao.observe(s.owner,emptyList()).first().isEmpty())
        }finally{db.close()}
    }
    @Test fun pagesAreAtomicAndPreservePendingCommandsAndNewerVersions()=runTest {
        val db=Room.inMemoryDatabaseBuilder(context,DisasterDatabase::class.java).build()
        try {
            val dao=db.assessmentDao();val s=session();val a=snapshot();val id=a["evaluationId"].asString
            dao.page(s,12,listOf(AssessmentWire.snapshot(a)),"cursor-1")
            val cached=checkNotNull(dao.remote(s.owner,id))
            dao.save(cached.copy(version=5,remoteJson=snapshot(id,5).toString()))
            dao.page(s,12,listOf(a),"cursor-2");assertEquals(5L,dao.remote(s.owner,id)?.version)
            val original="{\"immutable\":true}";dao.save(cached.copy(state="CONFLICT",creationPayload=original))
            dao.page(s,12,listOf(snapshot(id,6)),"cursor-3")
            val pending=checkNotNull(dao.remote(s.owner,id));assertEquals(original,pending.creationPayload);assertEquals("CONFLICT",pending.state);assertNotNull(pending.observedRemoteJson)
            val malformed=JsonObject()
            try{dao.page(s,12,listOf(snapshot(),malformed),"must-not-advance");fail("Invalid page accepted")}catch(_:IllegalArgumentException){}
            assertEquals("cursor-3",dao.cursor(s.owner,s.scope,12));assertEquals(1,dao.observe(s.owner,s.disasters).first().size)
            assertTrue(dao.observe("another-owner",s.disasters).first().isEmpty())
            assertTrue(dao.observe(s.owner,listOf(13)).first().isEmpty())
            assertNull(dao.cursor(s.owner,"different-scope",12))
        } finally {db.close()}
    }
    @Test fun incompleteOrMalformedRemoteObjectsAreNeverConfirmed() {
        val value=snapshot();AssessmentWire.snapshot(value)
        for(field in listOf("version","revision","evaluator","budget","evidence","latitude","disasterId")){
            val missing=value.deepCopy();missing.remove(field)
            assertTrue(runCatching{AssessmentWire.snapshot(missing)}.isFailure)
        }
        val older=value.deepCopy();older.addProperty("version",-1);assertTrue(runCatching{AssessmentWire.snapshot(older)}.isFailure)
        val bad=value.deepCopy();bad.addProperty("latitude",91);bad.addProperty("longitude",1);assertTrue(runCatching{AssessmentWire.snapshot(bad)}.isFailure)
    }
    @Test fun versionFourMigrationPreservesDisastersPendingIdentityAndCursor()=runTest {
        val name="assessment-migration-"+UUID.randomUUID()+".db"
        try {
            val schema=AssessmentWire.json(java.io.File("schemas/co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterDatabase/4.json").readText())["database"].asJsonObject
            val old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
            for(value in schema["entities"].asJsonArray){val entity=value.asJsonObject;val table=entity["tableName"].asString
                old.execSQL(entity["createSql"].asString.replace("\${TABLE_NAME}",table));entity["indices"]?.asJsonArray?.forEach{old.execSQL(it.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}",table))}}
            for(query in schema["setupQueries"].asJsonArray)old.execSQL(query.asString)
            old.execSQL("INSERT INTO disasters(clientRequestId,nombre,ubicacion,severidad,descripcion,latitud,longitud,radio,syncStatus,creationPayload) VALUES('stable-id','Preserve','Local',2,'Original',4.7,-74.0,123.45,'PENDING','immutable-payload')")
            old.execSQL("INSERT INTO disaster_sync_state(name,cursor) VALUES('disasters','stable-cursor')");old.version=4;old.close()
            val db=Room.databaseBuilder(context,DisasterDatabase::class.java,name).addMigrations(DisasterDatabase.MIGRATION_4_5).build()
            try{val row=checkNotNull(db.disasterDao().findByRequestId("stable-id"));assertEquals("immutable-payload",row.creationPayload);assertEquals(123.45,row.radio);assertEquals("PENDING",row.syncStatus);assertEquals("stable-cursor",db.disasterDao().getSyncCursor());assertTrue(db.assessmentDao().observe("owner",emptyList()).first().isEmpty())}finally{db.close()}
        }finally{context.deleteDatabase(name)}
    }
}
