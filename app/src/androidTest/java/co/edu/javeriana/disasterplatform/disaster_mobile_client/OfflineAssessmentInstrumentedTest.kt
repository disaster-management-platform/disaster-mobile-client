package co.edu.javeriana.disasterplatform.disaster_mobile_client

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.assessments.*
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterDatabase
import co.edu.javeriana.disasterplatform.disaster_mobile_client.debug.AssessmentActivity
import co.edu.javeriana.disasterplatform.disaster_mobile_client.di.NetworkModule
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OfflineAssessmentInstrumentedTest {
    @Test fun offlineAssessmentEvidenceAndSessionRecovery()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val arguments=InstrumentationRegistry.getArguments();val stage=arguments.getString("assessment-stage","complete")
        val activity=instrumentation.startActivitySync(Intent(context,AssessmentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AssessmentActivity
        val model=activity.model;val preferences=context.getSharedPreferences("offline-assessment-test",Context.MODE_PRIVATE)
        val connectivity=context.getSystemService(ConnectivityManager::class.java)
        fun await(message:String,condition:()->Boolean){val deadline=System.currentTimeMillis()+180_000;while(!condition()){check(System.currentTimeMillis()<deadline){message};Thread.sleep(200)}}
        fun network(enabled:Boolean){for(service in listOf("wifi","data"))instrumentation.uiAutomation.executeShellCommand("svc $service ${if(enabled)"enable" else "disable"}").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
        fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
        val database=Room.databaseBuilder(context,DisasterDatabase::class.java,"disasters.db").addMigrations(DisasterDatabase.MIGRATION_1_2,DisasterDatabase.MIGRATION_2_3,DisasterDatabase.MIGRATION_3_4,DisasterDatabase.MIGRATION_4_5).build()
        try {
            if(stage!="recover") {
                androidx.work.WorkManager.getInstance(context).cancelUniqueWork("sync-disasters").result.get(10,java.util.concurrent.TimeUnit.SECONDS)
                val jwt=checkNotNull(arguments.getString("assessment-token")){"Supply a signed external test identity"}
                main{model.importToken(jwt)}
                await("Session or catalogs not validated"){model.session.value!=null&&model.catalogs.value.any{it.id=="Bridge"}&&!model.busy.value}
                network(false);await("Connectivity not disabled"){connectivity.activeNetwork==null}
                fun draft(budget:Boolean)=JsonObject().apply{
                    addProperty("assetId",UUID.randomUUID().toString());addProperty("assetTypeId","Bridge");addProperty("damageCategoryId","Severe");add("disasterId",JsonNull.INSTANCE)
                    addProperty("evaluatedAt",java.time.Instant.now().toString());addProperty("location","Offline synthetic location");addProperty("description","Synthetic assessment; no personal data")
                    add("latitude",JsonNull.INSTANCE);add("longitude",JsonNull.INSTANCE);add("geometry",JsonNull.INSTANCE);add("evidence",JsonArray());if(budget)addProperty("budget",12.34)else add("budget",JsonNull.INSTANCE)
                }
                val body=draft(true);val asset=body["assetId"].asString
                main{model.save(body,null)}
                await("Offline row not saved"){model.assessments.value.any{AssessmentWire.json(it.draftJson)["assetId"].asString==asset}&&!model.busy.value}
                val row=model.assessments.value.single{AssessmentWire.json(it.draftJson)["assetId"].asString==asset}
                val source=File(context.cacheDir,"assessment-test.pdf").apply{writeBytes(pdf())}
                main{model.attach(row.localId,Uri.fromFile(source))}
                await("Private attachment not saved"){model.files.value.any{it.assessmentId==row.localId}&&!model.busy.value}
                val file=model.files.value.single{it.assessmentId==row.localId};assertTrue(File(file.path).exists());source.delete()
                assertEquals("PENDING",row.state);assertNull(row.remoteId);assertEquals(0,AssessmentWire.json(row.creationPayload!!)["evidence"].asJsonArray.size())
                val incomplete=draft(false);main{model.save(incomplete,null)}
                await("Unknown budget not persisted"){model.assessments.value.any{it.creationPayload==null}&&!model.busy.value}
                check(preferences.edit().putString("owner",row.owner).putString("id",row.localId).putString("file",file.id).putString("original",row.creationPayload).commit())
                if(stage=="store")return@runBlocking
            }
            await("Encrypted session not restored"){model.session.value!=null}
            val owner=checkNotNull(preferences.getString("owner",null));val id=checkNotNull(preferences.getString("id",null));val fileId=checkNotNull(preferences.getString("file",null));val original=checkNotNull(preferences.getString("original",null))
            val before=checkNotNull(database.assessmentDao().get(owner,id));assertEquals(original,before.creationPayload)
            val preserved=database.assessmentDao().files(owner,id).single{it.id==fileId};assertTrue(File(preserved.path).exists())
            assertEquals(preserved.sha256,File(preserved.path).inputStream().use{stream->val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);while(true){val n=stream.read(buffer);if(n<0)break;digest.update(buffer,0,n)};digest.digest().joinToString(""){"%02x".format(it)}})
            network(true);await("Connectivity not restored"){connectivity.activeNetwork!=null}
            main{model.sync()}
            await("Assessment/evidence never synchronized"){runBlocking{database.assessmentDao().get(owner,id)?.state=="SYNCED"}}
            val synced=checkNotNull(database.assessmentDao().get(owner,id));val file=database.assessmentDao().files(owner,id).single{it.id==fileId}
            assertEquals("ASSOCIATED",file.state);assertNotNull(file.evidenceId);assertEquals("attachment:${file.evidenceId}",file.reference);assertTrue(File(file.path).exists());assertEquals(original,synced.creationPayload)
            await("Room changes did not reach reactive UI state"){model.assessments.value.any{it.localId==id&&it.state=="SYNCED"}}
            val service=NetworkModule.providesAssessmentService(dagger.hilt.android.EntryPointAccessors.fromApplication(context, DeploymentNetworkEntryPoint::class.java).retrofit());val session=checkNotNull(model.session.value)
            val remote=checkNotNull(service.get(session.authorization,synced.remoteId!!).body());assertTrue(remote["evidence"].asJsonArray.any{it.asString==file.reference})
            val replay=service.batch(session.authorization,JsonObject().apply{add("operations",JsonArray().apply{add(JsonObject().apply{addProperty("clientRequestId",id);add("evaluation",AssessmentWire.json(original))})})})
            assertEquals("AlreadyProcessed",replay.body()!!["results"].asJsonArray[0].asJsonObject["status"].asString)
            assertEquals(original,database.assessmentDao().get(owner,id)?.creationPayload)
            assertNotNull(database.assessmentDao().cursor(owner,session.scope,0))
            assertTrue(database.assessmentDao().observe(owner,session.disasters).first().any{it.creationPayload==null&&it.remoteId==null})
            network(false);await("Second offline phase not entered"){connectivity.activeNetwork==null}
            main{model.showPdf(file)};await("Local PDF cannot be read offline"){model.document.value!=null&&!model.busy.value}
            network(true);await("Network not restored for identity isolation"){connectivity.activeNetwork!=null}
            val other=checkNotNull(arguments.getString("assessment-other-token"));main{model.importToken(other)}
            await("Second identity not validated"){model.session.value?.owner!=owner&&model.session.value!=null&&!model.busy.value}
            await("Previous owner's rows leaked"){model.assessments.value.isEmpty()&&model.files.value.isEmpty()}
            assertNotNull(database.assessmentDao().get(owner,id));assertTrue(File(file.path).exists())
        } finally {database.close();main{activity.finish()}}
    }
    private fun pdf():ByteArray {
        val objects=listOf("<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>","<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R >>","<< /Length 0 >>\nstream\n\nendstream")
        val output=java.io.ByteArrayOutputStream();output.write("%PDF-1.4\n".toByteArray());val offsets=mutableListOf<Int>()
        objects.forEachIndexed{i,obj->offsets.add(output.size());output.write("${i+1} 0 obj\n$obj\nendobj\n".toByteArray())}
        val xref=output.size();output.write("xref\n0 5\n0000000000 65535 f \n".toByteArray());offsets.forEach{output.write("%010d 00000 n \n".format(it).toByteArray())}
        output.write("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n".toByteArray());return output.toByteArray()
    }
}
