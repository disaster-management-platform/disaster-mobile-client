package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.assessments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import co.edu.javeriana.disasterplatform.disaster_mobile_client.BuildConfig
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.sync.DisasterSyncScheduler
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class AssessmentRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: AssessmentDao,
    private val api: AssessmentApi,
    val sessions: AssessmentSessionManager,
    private val scheduler: DisasterSyncScheduler,
) {
    private val synchronization = Mutex()
    val assessments = sessions.session.flatMapLatest { s -> s?.let { dao.observe(it.owner, it.disasters) } ?: flowOf(emptyList()) }
    val catalogs = sessions.session.flatMapLatest { s -> s?.let { dao.catalogs(it.owner) } ?: flowOf(emptyList()) }
    val files = sessions.session.flatMapLatest { s -> s?.let { dao.observeFiles(it.owner,it.disasters) } ?: flowOf(emptyList()) }
    private fun session(): AssessmentSession = checkNotNull(sessions.session.value).also { check(BuildConfig.DEBUG) }
    private fun active(s: AssessmentSession) = s.isCurrent && sessions.session.value?.let { it.owner == s.owner && it.scope == s.scope && it.token==s.token && it.isCurrent } == true
    suspend fun requestSync() {
        sessions.restore();val s=sessions.session.value
        if(s!=null&&s.isCurrent)try{catalog(s)}catch(cancelled:CancellationException){throw cancelled}catch(_:IOException){}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}
        scheduler.enqueue()
    }

    suspend fun saveDraft(input: JsonObject, localId: String? = null): String = withContext(Dispatchers.IO) {
        val s = session(); require(s.canWrite && s.allows(AssessmentWire.nullableInt(input, "disasterId")))
        for (name in listOf("assetId", "location", "description")) require(AssessmentWire.text(input, name).length <= if(name=="description") 2000 else 200)
        require(OffsetDateTime.parse(AssessmentWire.text(input, "evaluatedAt")).toInstant() <= java.time.Instant.now())
        for ((name,bound) in listOf("latitude" to 90.0,"longitude" to 180.0)) {
            require(input.has(name)); if(!input[name].isJsonNull)require(input[name].asDouble.let{it.isFinite()&&it in -bound..bound})
        }
        require(input["latitude"].isJsonNull == input["longitude"].isJsonNull)
        require(input["evidence"]?.isJsonArray==true && input["evidence"].asJsonArray.size()==0)
        val complete = listOf("assetTypeId","damageCategoryId","budget").all { input.has(it)&&!input[it].isJsonNull }
        if(!input["budget"].isJsonNull)require(input["budget"].asBigDecimal.let { it>=BigDecimal.ZERO && it.stripTrailingZeros().scale()<=2 && it.precision()<=19 })
        val existing = localId?.let { dao.get(s.owner,it) }
        require(existing==null || existing.creationPayload==null && existing.remoteId==null)
        val id=existing?.localId?:UUID.randomUUID().toString()
        dao.save(LocalAssessment(id,s.owner,s.actor,s.organization,AssessmentWire.nullableInt(input,"disasterId"),input.toString(),if(complete)input.toString() else null,error=if(complete)null else "Información pendiente: catálogo o presupuesto"))
        scheduler.enqueue(); id
    }
    suspend fun addFile(id: String, uri: Uri) = withContext(Dispatchers.IO) {
        val s=session();val row=checkNotNull(dao.get(s.owner,id));require(s.canWrite&&s.allows(row.disasterId)&&row.state!="SCOPE_REMOVED")
        if(row.remoteJson!=null)require(AssessmentWire.text(AssessmentWire.json(row.remoteJson),"status")=="Draft")
        val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor -> if(cursor.moveToFirst())cursor.getString(0) else null } ?: uri.lastPathSegment?.substringAfterLast('/') ?: error("Nombre de archivo desconocido")
        require(name.length in 1..200 && name.none { it=='/'||it=='\\'||it.isISOControl() })
        val lower=name.lowercase(java.util.Locale.ROOT)
        val mime=when { lower.endsWith(".pdf")->"application/pdf";lower.endsWith(".png")->"image/png";lower.endsWith(".jpg")||lower.endsWith(".jpeg")->"image/jpeg";else->error("Solo JPEG, PNG y PDF") }
        val fileId=UUID.randomUUID().toString();val directory=File(context.filesDir,"assessment-evidence/${s.owner}").apply { mkdirs() };val file=File(directory,fileId)
        val atomic=AtomicFile(file);val output=atomic.startWrite()
        try {
            val metadata=checkNotNull(context.contentResolver.openInputStream(uri)).use { content(it,mime,output) }
            atomic.finishWrite(output)
            dao.attach(row,AssessmentFile(fileId,id,s.owner,file.path,name,mime,metadata.first,metadata.second,UUID.randomUUID().toString()))
        } catch(failure:Exception) { atomic.failWrite(output);file.delete();throw failure }
        scheduler.enqueue()
    }
    private fun content(source:java.io.InputStream,mime:String,output:java.io.OutputStream?=null):Pair<Long,String> {
        val hash=MessageDigest.getInstance("SHA-256");var size=0L;val signature=java.io.ByteArrayOutputStream(8);val buffer=ByteArray(8192)
        while(true){val n=source.read(buffer);if(n<0)break;size+=n;require(size<=6291456){"Máximo 6 MiB"};if(signature.size()<8)signature.write(buffer,0,minOf(n,8-signature.size()));hash.update(buffer,0,n);output?.write(buffer,0,n)}
        val bytes=signature.toByteArray();require(size>0)
        require(when(mime){"application/pdf"->bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray());"image/png"->bytes.contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10));else->bytes.size>=3&&bytes[0]==255.toByte()&&bytes[1]==216.toByte()&&bytes[2]==255.toByte()}){"Firma de archivo inválida"}
        return size to hash.digest().joinToString(""){"%02x".format(it)}
    }
    suspend fun prepareCapture(id:String):Pair<String,Uri> = withContext(Dispatchers.IO) {
        val s=session();val row=checkNotNull(dao.get(s.owner,id));require(s.canWrite&&s.allows(row.disasterId)&&row.state!="SCOPE_REMOVED")
        val fileId=UUID.randomUUID().toString();val directory=File(context.filesDir,"assessment-evidence/${s.owner}").apply{mkdirs()};val file=File(directory,fileId)
        check(file.createNewFile())
        try{dao.attach(row,AssessmentFile(fileId,id,s.owner,file.path,"photo.jpg","image/jpeg",0,"",UUID.randomUUID().toString(),state="CAPTURING"))}catch(failure:Exception){file.delete();throw failure}
        fileId to androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".assessment-files",file)
    }
    suspend fun finishCapture(id:String) = withContext(Dispatchers.IO) {
        val s=session();val files=dao.allFiles(s.owner);val row=checkNotNull(files.firstOrNull{it.id==id});require(row.state=="CAPTURING"||row.state=="ERROR"&&row.sha256.isEmpty()&&row.evidenceId==null)
        val source=localFile(row)
        val uri=androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".assessment-files",source)
        context.revokeUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val metadata=source.inputStream().use{content(it,row.contentType)}
        if(dao.completeCapture(s.owner,id,metadata.first,metadata.second))scheduler.enqueue()
    }
    suspend fun cancelCapture(id:String)=withContext(Dispatchers.IO) {
        val s=session();val row=checkNotNull(dao.allFiles(s.owner).firstOrNull{it.id==id});require(row.state=="CAPTURING")
        val source=localFile(row);val uri=androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".assessment-files",source)
        context.revokeUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        dao.completeCapture(s.owner,id,null,null)
    }
    suspend fun localFile(file: AssessmentFile): File = withContext(Dispatchers.IO) {
        val s=session();require(s.owner==file.owner)
        val parent=checkNotNull(dao.get(s.owner,file.assessmentId));require(s.allows(parent.disasterId)&&parent.state!="SCOPE_REMOVED")
        val root=File(context.filesDir,"assessment-evidence/${file.owner}").canonicalFile
        File(file.path).canonicalFile.also { require(it.parentFile==root) }
    }
    suspend fun downloadEvidence(id:String,reference:String)=withContext(Dispatchers.IO) {
        val s=session();require(s.isCurrent);val row=checkNotNull(dao.get(s.owner,id));require(s.allows(row.disasterId)&&row.state=="SYNCED")
        val snapshot=AssessmentWire.snapshot(AssessmentWire.json(checkNotNull(row.remoteJson)))
        require(snapshot["evidence"].asJsonArray.any{it.asString==reference}&&reference.startsWith("attachment:"))
        val evidenceId=AssessmentWire.uuid(reference.substring(11));val remote=checkNotNull(row.remoteId)
        val response=api.evidence(s.authorization,remote,evidenceId);check(response.isSuccessful);val metadata=checkNotNull(response.body())
        require(AssessmentWire.text(metadata,"status")=="Verified"&&AssessmentWire.text(metadata,"evaluationId")==remote&&AssessmentWire.text(metadata,"organizationId")==s.organization)
        val mime=AssessmentWire.text(metadata,"contentType");require(mime in setOf("image/jpeg","image/png","application/pdf"))
        val signed=api.download(s.authorization,remote,evidenceId);check(signed.isSuccessful)
        val url=AssessmentWire.text(checkNotNull(signed.body()),"url");val target=java.net.URI(url)
        require(target.scheme=="https"&&target.host=="yhbwpetxhydxssernbrb.supabase.co"&&target.rawUserInfo==null&&target.port==-1&&target.path.startsWith("/storage/v1/object/sign/assessments-evidence/verified/$remote/$evidenceId"))
        val existing=dao.files(s.owner,id).firstOrNull{it.evidenceId==evidenceId}
        val fileId=existing?.id?:UUID.randomUUID().toString();val directory=File(context.filesDir,"assessment-evidence/${s.owner}").apply{mkdirs()};val file=File(directory,fileId)
        require(active(s));val data=api.readStorage(url);check(data.isSuccessful)
        val atomic=AtomicFile(file);val output=atomic.startWrite()
        try {
            val verified=checkNotNull(data.body()).use{body->body.byteStream().use{content(it,mime,output)}}
            require(verified.first==AssessmentWire.number(metadata,"size")&&verified.second==AssessmentWire.text(metadata,"sha256"))
            atomic.finishWrite(output)
            dao.saveFile(AssessmentFile(fileId,id,s.owner,file.path,AssessmentWire.text(metadata,"originalFileName"),mime,verified.first,verified.second,
                AssessmentWire.uuid(AssessmentWire.text(metadata,"clientRequestId")),evidenceId,AssessmentWire.number(metadata,"revision"),reference,"ASSOCIATED"))
        }catch(failure:Exception){atomic.failWrite(output);throw failure}
    }
    private suspend fun catalog(s: AssessmentSession): Boolean {
        val response=api.catalogs(s.authorization);if(!response.isSuccessful)return failure(response,s)
        val body=checkNotNull(response.body());val rows=mutableListOf<AssessmentCatalog>()
        for ((kind,name) in listOf("asset" to "assetTypes","category" to "damageCategories")) {
            require(body[name]?.isJsonArray==true)
            for(element in body[name].asJsonArray) {
                val value=element.asJsonObject;val id=AssessmentWire.text(value,"id");require(id.matches(Regex("[A-Za-z0-9_.:-]{1,120}")))
                val title=AssessmentWire.text(value,"name");require(title.length<=200)
                require(value["active"]?.isJsonPrimitive==true&&value["active"].asJsonPrimitive.isBoolean)
                val level=if(kind=="category")AssessmentWire.text(value,"damageLevel").also { require(it in setOf("None","Minor","Moderate","Severe","Destroyed")) } else null
                val type=value["assetTypeId"]?.takeUnless { it.isJsonNull }?.asString
                rows.add(AssessmentCatalog(s.owner,kind,id,title,value["active"].asBoolean,level,type))
            }
        }
        require(rows.distinctBy { it.kind to it.id }.size==rows.size)
        if(active(s))dao.saveCatalogs(rows)
        return true
    }
    private suspend fun failure(response: Response<*>, s: AssessmentSession): Boolean {
        if(response.code()==403)sessions.clear(s)
        if(response.code()==401)sessions.blockSynchronization(s)
        return response.code() in listOf(401,403)
    }
    private suspend fun uploadBatch(s: AssessmentSession, rows: List<LocalAssessment>): Boolean {
        if(rows.isEmpty()||!active(s))return true
        val operations=JsonArray();rows.forEach { row -> operations.add(JsonObject().apply { addProperty("clientRequestId",row.localId);add("evaluation",AssessmentWire.json(checkNotNull(row.creationPayload))) }) }
        val response=api.batch(s.authorization,JsonObject().apply { add("operations",operations) })
        if(!response.isSuccessful) {
            if(response.code() in listOf(400,413)&&rows.size>1) {
                val middle=rows.size/2;val first=uploadBatch(s,rows.take(middle));val second=uploadBatch(s,rows.drop(middle));return first&&second
            }
            if(response.code() in listOf(400,404,413,422))for(row in rows)dao.save(row.copy(state="ERROR",error="Batch rechazado (${response.code()}); conserve la operación para revisión"))
            return if(response.code() in listOf(400,404,413,422))true else failure(response,s)
        }
        val body=checkNotNull(response.body());require(body["results"]?.isJsonArray==true)
        val results=body["results"].asJsonArray.mapNotNull { runCatching{it.asJsonObject.also{value->AssessmentWire.uuid(AssessmentWire.text(value,"clientRequestId"))}}.getOrNull() }.groupBy { AssessmentWire.text(it,"clientRequestId") };var complete=true
        for(row in rows) {
            val result=results[row.localId]?.singleOrNull();if(result==null){complete=false;continue}
            val status=runCatching{AssessmentWire.text(result,"status")}.getOrNull();if(status==null){complete=false;continue}
            when(status) {
                "Created","AlreadyProcessed" -> {
                    val receipt=result["evaluation"]?.takeIf{it.isJsonObject}?.asJsonObject
                    if(receipt==null){complete=false;continue}
                    if(runCatching { AssessmentWire.snapshot(receipt) }.isFailure){complete=false;continue}
                    val valid=runCatching { require(AssessmentWire.text(receipt,"clientRequestId")==row.localId);require(AssessmentWire.text(receipt,"evaluator")==s.actor&&AssessmentWire.text(receipt,"organization")==s.organization);require(AssessmentWire.matches(receipt,AssessmentWire.json(row.creationPayload!!))) }.isSuccess
                    if(valid)dao.creation(s.owner,row.localId,receipt) else dao.save(row.copy(state="CONFLICT",error="La confirmación no coincide con el contenido original"))
                }
                "ValidationError"->dao.save(row.copy(state="ERROR",error="Evaluación rechazada; revise contenido y permisos"))
                "Conflict"->dao.save(row.copy(state="CONFLICT",error="UUID ya utilizado con otro contenido"))
                else->complete=false
            }
        }
        return complete
    }
    private fun body(response: Response<JsonObject>): JsonObject? = response.body()?:runCatching { response.errorBody()?.string()?.let(AssessmentWire::json) }.getOrNull()
    private suspend fun file(s: AssessmentSession,row: LocalAssessment,original: AssessmentFile): Boolean {
        var file=original;val remote=checkNotNull(row.remoteId)
        if(file.state=="CAPTURING")return false
        if(file.state in listOf("VERIFIED","ASSOCIATED"))return true
        if(file.state in listOf("ERROR","CONFLICT"))return true
        suspend fun confirm(): Pair<Response<JsonObject>,JsonObject?> {
            val response=api.confirm(s.authorization,remote,checkNotNull(file.evidenceId));return response to body(response)
        }
        fun verified(value: JsonObject): Boolean = runCatching {
            require(AssessmentWire.text(value,"status")=="Verified")
            require(AssessmentWire.uuid(AssessmentWire.text(value,"evidenceId"))==file.evidenceId)
            require(AssessmentWire.text(value,"evaluationId")==remote&&AssessmentWire.text(value,"organizationId")==s.organization&&AssessmentWire.text(value,"clientRequestId")==file.intentId)
            require(AssessmentWire.number(value,"size")==file.size&&AssessmentWire.text(value,"sha256")==file.sha256&&AssessmentWire.text(value,"contentType")==file.contentType)
            require(AssessmentWire.number(value,"revision")==file.revision)
            require(AssessmentWire.text(value,"reference")=="attachment:${file.evidenceId}")
        }.isSuccess
        fun metadataComplete(value: JsonObject): Boolean = runCatching {
            for(name in listOf("status","evidenceId","evaluationId","organizationId","sha256","contentType","clientRequestId"))AssessmentWire.text(value,name)
            AssessmentWire.number(value,"revision");AssessmentWire.number(value,"size")
            if(value["status"].asString=="Verified")AssessmentWire.text(value,"reference")
        }.isSuccess
        suspend fun persist(value: JsonObject): Boolean {
            if(!metadataComplete(value))return false
            if(!verified(value)){dao.saveFile(file.copy(state="CONFLICT",error="Confirmación de archivo incompatible"));return true}
            dao.saveFile(file.copy(reference=AssessmentWire.text(value,"reference"),state="VERIFIED",error=null));return true
        }
        if(file.evidenceId!=null) {
            val (response,value)=confirm()
            if(response.isSuccessful&&value!=null&&value["status"]?.asString=="Verified")return persist(value)
            if(response.code() in listOf(401,403))return failure(response,s)
            val detail=api.evidence(s.authorization,remote,file.evidenceId!!)
            if(!detail.isSuccessful)return failure(detail,s)
            val current=checkNotNull(detail.body());val state=AssessmentWire.text(current,"status")
            if(state=="Verified")return persist(current)
            if(state=="Rejected"){dao.saveFile(file.copy(state="ERROR",error="Storage rechazó el archivo"));return true}
            if(state=="Expired") {
                val previous=com.google.gson.JsonParser.parseString(file.previousIntents).asJsonArray
                require(previous.size()<3){"La autorización requiere revisión"}
                previous.add(JsonObject().apply { addProperty("intentId",file.intentId);addProperty("evidenceId",file.evidenceId);addProperty("status","Expired") })
                file=file.copy(intentId=UUID.randomUUID().toString(),evidenceId=null,revision=null,previousIntents=previous.toString());dao.saveFile(file)
            } else if(response.code()!=422) return false
        }
        if(!active(s))return false
        val metadata=JsonObject().apply { addProperty("originalFileName",file.originalFileName);addProperty("contentType",file.contentType);addProperty("size",file.size);addProperty("sha256",file.sha256) }
        val response=api.authorize(s.authorization,remote,file.intentId,metadata)
        if(!response.isSuccessful)return failure(response,s)
        val value=response.body()?:return false;if(!metadataComplete(value))return false
        if(AssessmentWire.text(value,"clientRequestId")!=file.intentId||AssessmentWire.text(value,"sha256")!=file.sha256||AssessmentWire.number(value,"size")!=file.size){dao.saveFile(file.copy(state="CONFLICT",error="Intención de subida incompatible"));return true}
        val id=AssessmentWire.uuid(AssessmentWire.text(value,"evidenceId"))
        require(AssessmentWire.text(value,"evaluationId")==remote&&AssessmentWire.text(value,"organizationId")==s.organization)
        file=file.copy(evidenceId=id,revision=AssessmentWire.number(value,"revision"));dao.saveFile(file)
        when(AssessmentWire.text(value,"status")) {
            "Verified"->return persist(value)
            "Rejected","Expired"->{dao.saveFile(file.copy(state="ERROR",error="La intención requiere revisión"));return true}
            "PendingUpload"->Unit
            else->return false
        }
        require(file.revision==row.revision){"La revisión remota cambió"}
        val url=AssessmentWire.text(value,"uploadUrl");val target=java.net.URI(url)
        require(target.scheme=="https"&&target.host=="yhbwpetxhydxssernbrb.supabase.co"&&target.port==-1&&target.rawUserInfo==null&&target.path.startsWith("/storage/v1/object/upload/sign/assessments-evidence/"))
        require(AssessmentWire.text(value,"method")=="PUT")
        require(OffsetDateTime.parse(AssessmentWire.text(value,"uploadExpiresAt")).toInstant()>java.time.Instant.now().plusSeconds(5))
        val headers=value["headers"].asJsonObject.entrySet().associate { it.key to it.value.asString }
        require(headers.keys.all { it.lowercase() in setOf("content-type","x-upsert") })
        require(headers["Content-Type"]==file.contentType&&headers["x-upsert"]=="false")
        if(!active(s))return false
        val source=localFile(file);require(source.length()==file.size)
        val digest=source.inputStream().use { stream -> val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);while(true){val n=stream.read(buffer);if(n<0)break;hash.update(buffer,0,n)};hash.digest().joinToString(""){"%02x".format(it)} }
        require(digest==file.sha256){"El archivo local cambió"}
        if(!active(s))return false
        val uploaded=api.upload(url,headers,source.asRequestBody(file.contentType.toMediaType()))
        uploaded.body()?.close();uploaded.errorBody()?.close()
        if(!active(s))return false
        val (confirmed,result)=confirm()
        if(confirmed.isSuccessful&&result!=null)return persist(result)
        if(result?.get("status")?.takeIf{it.isJsonPrimitive&&it.asJsonPrimitive.isString}?.asString=="Rejected"){dao.saveFile(file.copy(state="ERROR",error="Archivo rechazado durante verificación"));return true}
        return failure(confirmed,s)
    }
    private suspend fun evidence(s: AssessmentSession,initial: LocalAssessment): Boolean {
        var row=checkNotNull(dao.get(s.owner,initial.localId));if(row.remoteId==null)return true
        val currentResponse=api.get(s.authorization,row.remoteId!!)
        if(!currentResponse.isSuccessful)return failure(currentResponse,s)
        val currentSnapshot=AssessmentWire.snapshot(checkNotNull(currentResponse.body()))
        require(AssessmentWire.text(currentSnapshot,"evaluationId")==row.remoteId&&AssessmentWire.text(currentSnapshot,"organization")==s.organization)
        val localFiles=dao.files(s.owner,row.localId)
        val alreadyAssociated=localFiles.all{it.reference!=null&&currentSnapshot["evidence"].asJsonArray.any{ref->ref.asString==it.reference}}
        if(AssessmentWire.number(currentSnapshot,"revision")!=row.revision||AssessmentWire.number(currentSnapshot,"version")!=row.version&&!alreadyAssociated){
            dao.save(row.copy(state="CONFLICT",observedRemoteJson=currentSnapshot.toString(),error="La revisión o versión remota cambió"));return true
        }
        var complete=true
        for(file in localFiles) {
            if(!active(s))return false
            try { if(!file(s,row,file))complete=false }
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:IllegalArgumentException){dao.saveFile(file.copy(state="ERROR",error="Contenido o revisión incompatibles; conserve el archivo para revisión"))}
            catch(_:IOException){complete=false}
        }
        val files=dao.files(s.owner,row.localId)
        if(files.any{it.state in listOf("ERROR","CONFLICT")}) {dao.save(row.copy(state=if(files.any{it.state=="CONFLICT"})"CONFLICT" else "ERROR",error="Una evidencia requiere revisión"));return true}
        if(files.any{it.reference==null})return complete
        if(!active(s))return false
        val response=api.get(s.authorization,row.remoteId!!)
        if(!response.isSuccessful)return failure(response,s)
        val current=AssessmentWire.snapshot(checkNotNull(response.body()));val references=files.map{checkNotNull(it.reference)}
        val remoteReferences=current["evidence"].asJsonArray.map{it.asString}
        if(AssessmentWire.number(current,"revision")!=row.revision) {dao.save(row.copy(state="CONFLICT",observedRemoteJson=current.toString(),error="La revisión del servidor cambió"));return true}
        if(!remoteReferences.containsAll(references)) {
            if(AssessmentWire.text(current,"status")!="Draft"||AssessmentWire.number(current,"version")!=row.version){dao.save(row.copy(state="CONFLICT",observedRemoteJson=current.toString(),error="La evaluación cambió antes de asociar evidencias"));return true}
            val input=AssessmentWire.input(current).apply { addProperty("expectedVersion",row.version);add("evidence",JsonArray().apply{(remoteReferences+references).distinct().forEach{add(it)}}) }
            val update=api.edit(s.authorization,row.remoteId!!,input)
            if(update.code()==409){dao.save(row.copy(state="CONFLICT",error="Conflicto al asociar evidencias"));return true}
            if(!update.isSuccessful)return failure(update,s)
            val result=AssessmentWire.snapshot(checkNotNull(update.body()));require(result["evidence"].asJsonArray.map{it.asString}.containsAll(references))
            row=row.copy(remoteJson=result.toString(),version=AssessmentWire.number(result,"version"))
        } else row=row.copy(remoteJson=current.toString(),version=AssessmentWire.number(current,"version"))
        return dao.complete(row)&&complete
    }
    private suspend fun delta(s: AssessmentSession,disaster: Int?): Boolean {
        val key=disaster?:0;var cursor=dao.cursor(s.owner,s.scope,key)
        repeat(10) {
            if(!active(s))return false
            val response=api.delta(s.authorization,disaster,cursor)
            if(response.code()==409){dao.resetCursor(s.owner,s.scope,key);return false}
            if(!response.isSuccessful)return failure(response,s)
            val page=checkNotNull(response.body());require(page["evaluations"]?.isJsonArray==true)
            require(page["hasMore"]?.isJsonPrimitive==true&&page["hasMore"].asJsonPrimitive.isBoolean)
            val values=page["evaluations"].asJsonArray.map { AssessmentWire.snapshot(it.asJsonObject).also { row -> require(AssessmentWire.text(row,"organization")==s.organization&&AssessmentWire.nullableInt(row,"disasterId")==disaster) } }
            require(values.distinctBy{AssessmentWire.text(it,"evaluationId")}.size==values.size)
            val next=AssessmentWire.uuid(AssessmentWire.text(page,"nextCursor"));require(next!=cursor)
            if(!active(s))return false
            val removed=page["removed"]?.let { items -> require(items.isJsonArray);items.asJsonArray.map { item -> item.asJsonObject.also { require(it.keySet()==setOf("evaluationId","version"));AssessmentWire.uuid(AssessmentWire.text(it,"evaluationId"));require(AssessmentWire.number(it,"version")>=0) } } } ?: emptyList()
            require(removed.distinctBy{AssessmentWire.text(it,"evaluationId")}.size==removed.size)
            dao.page(s,key,values,next,removed);cursor=next
            if(!page["hasMore"].asBoolean)return true
        }
        return false
    }
    suspend fun synchronize(): Boolean = synchronization.withLock {
        if(!BuildConfig.DEBUG)return true
        sessions.restore();val s=sessions.session.value?:return true;if(!s.isCurrent)return true
        var complete=true
        suspend fun attempt(action:suspend()->Boolean) {try{if(!action())complete=false}catch(cancelled:CancellationException){throw cancelled}catch(_:IOException){complete=false}catch(_:IllegalArgumentException){complete=false}catch(_:IllegalStateException){complete=false}catch(_:UnsupportedOperationException){complete=false}catch(_:com.google.gson.JsonParseException){complete=false}}
        attempt{catalog(s)}
        var after:String?=null
        if(s.canWrite)repeat(4) {
            if(!active(s))return complete
            val rows=dao.pending(s.owner,s.disasters,after);if(rows.isEmpty())return@repeat
            attempt{uploadBatch(s,rows.filter{it.remoteId==null})}
            for(row in rows)if(active(s))attempt{evidence(s,row)}
            after=rows.last().localId
        }
        if(s.canWrite&&active(s)&&dao.pending(s.owner,s.disasters,after).isNotEmpty())complete=false
        for(disaster in listOf<Int?>(null)+s.disasters)if(active(s))attempt{delta(s,disaster)}
        return complete
    }
}
