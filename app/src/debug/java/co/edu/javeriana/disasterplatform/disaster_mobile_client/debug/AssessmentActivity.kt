package co.edu.javeriana.disasterplatform.disaster_mobile_client.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.assessments.*
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant

@AndroidEntryPoint
class AssessmentActivity : ComponentActivity() {
    val model:AssessmentViewModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{MaterialTheme{AssessmentScreen(model)}}}
}

@Composable
private fun AssessmentScreen(model:AssessmentViewModel) {
    val session by model.session.collectAsStateWithLifecycle()
    val rows by model.assessments.collectAsStateWithLifecycle()
    val catalogs by model.catalogs.collectAsStateWithLifecycle()
    val files by model.files.collectAsStateWithLifecycle()
    val disasters by model.disasters.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val document by model.document.collectAsStateWithLifecycle()
    var token by remember{mutableStateOf("")}
    var selected by remember(session?.owner,session?.scope){mutableStateOf<String?>(null)}
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)selected?.let{id->model.attach(id,uri)}}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){success->model.captured(success)}
    val capture by model.camera.collectAsStateWithLifecycle()
    LaunchedEffect(capture){capture?.let{camera.launch(it);model.cameraLaunched()}}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item{Text("Evaluación de daños",style=MaterialTheme.typography.headlineSmall)}
        item{Text("Identidad de desarrollo: JWT firmado externo. No disponible en Release.")}
        item{OutlinedTextField(token,{token=it},label={Text("JWT de pruebas")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())}
        item{Row{Button(onClick={val value=token;token="";model.importToken(value)},enabled=!busy&&token.isNotBlank()){Text("Validar sesión")};TextButton(onClick={model.logout()},enabled=!busy){Text("Cerrar sesión")}}}
        message?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
        session?.let{s->
            item{Text("${s.actor} · ${s.organization}"+(if(s.isCurrent)"" else " · Sesión vencida: trabajo local disponible"))}
            item{Button(onClick={model.sync()},enabled=!busy&&s.isCurrent){Text("Sincronizar y actualizar catálogo")}}
            if(s.canWrite)item{
                val draft=rows.firstOrNull{it.localId==selected&&it.creationPayload==null&&it.remoteId==null}
                AssessmentForm(draft?.let{AssessmentWire.json(it.draftJson)},catalogs,
                    disasters.filter{it.serverId!=null&&s.allows(it.serverId)}.map{it.serverId!! to it.nombre}){body->model.save(body,draft?.localId)}
            }
            items(rows,key={it.localId}){row->
                val data=runCatching{AssessmentWire.json(row.remoteJson?:row.draftJson)}.getOrNull()
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(data?.get("assetId")?.asString?:row.localId)
                        Text("${row.state} · revisión ${row.revision?:"local"}")
                        row.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                        TextButton(onClick={selected=row.localId}){Text(if(row.creationPayload==null&&row.remoteId==null)"Completar / adjuntar" else "Seleccionar")}
                        Text(data?.get("description")?.asString?:"")
                        Text("Desastre: ${row.disasterId?:"preliminar"} · ${data?.get("location")?.asString?:""}")
                        Text("${data?.get("assetType")?.asString?:"Tipo pendiente"} · ${data?.get("damageLevel")?.asString?:"Categoría pendiente"}")
                        Text("Presupuesto COP: "+(data?.get("budget")?.takeUnless{it.isJsonNull}?.asString?:"desconocido"))
                        if(row.state=="SYNCED"&&s.isCurrent)data?.get("evidence")?.asJsonArray?.forEach{reference->
                            if(reference.asString.startsWith("attachment:")&&!files.any{it.assessmentId==row.localId&&it.reference==reference.asString})TextButton(onClick={model.download(row.localId,reference.asString)}){Text("Guardar evidencia para uso offline")}
                        }
                    }
                }
            }
            document?.let{bitmap->item{androidx.compose.foundation.Image(bitmap=bitmap.asImageBitmap(),contentDescription="Documento local",modifier=Modifier.fillMaxWidth().height(250.dp))}}
            selected?.let{id->
                item{Text("Evidencias de la evaluación seleccionada")}
                if(s.canWrite)item{Row{
                    Button(onClick={pick.launch(arrayOf("image/jpeg","image/png","application/pdf"))},enabled=!busy){Text("Adjuntar")}
                    TextButton(onClick={model.capture(id)},enabled=!busy){Text("Fotografía")}
                }}
                items(files.filter{it.assessmentId==id},key={it.id}){file->
                    Column {
                        Text("${file.originalFileName}: ${file.state}")
                        file.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                        if(file.state=="CAPTURING"||file.state=="ERROR"&&file.sha256.isEmpty())TextButton(onClick={model.recoverCapture(file.id)}){Text("Recuperar foto capturada")}
                        if(file.state!="CAPTURING"){
                            Text("${file.size} bytes · ${file.reference?:"Archivo privado pendiente"}")
                            if(file.contentType=="application/pdf")TextButton(onClick={model.showPdf(file)}){Text("Ver PDF local")}
                            if(file.contentType.startsWith("image/")){
                                val image by produceState<java.io.File?>(null,file.id,session?.scope){value=null;value=model.image(file)}
                                coil.compose.AsyncImage(model=image,contentDescription=file.originalFileName,modifier=Modifier.fillMaxWidth().height(180.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssessmentForm(draft:JsonObject?,catalogs:List<AssessmentCatalog>,disasters:List<Pair<Int,String>>,save:(JsonObject)->Unit) {
    val initial=draft?.toString()
    fun value(name:String)=draft?.get(name)?.takeUnless{it.isJsonNull}?.asString?:""
    var asset by remember(initial){mutableStateOf(value("assetId"))};var location by remember(initial){mutableStateOf(value("location"))}
    var description by remember(initial){mutableStateOf(value("description"))};var budget by remember(initial){mutableStateOf(value("budget"))}
    var latitude by remember(initial){mutableStateOf(value("latitude"))};var longitude by remember(initial){mutableStateOf(value("longitude"))}
    var evaluatedAt by remember(initial){mutableStateOf(value("evaluatedAt").ifBlank{Instant.now().toString()})}
    var type by remember(initial){mutableStateOf(value("assetTypeId"))};var category by remember(initial){mutableStateOf(value("damageCategoryId"))}
    var disaster by remember(initial){mutableStateOf(value("disasterId").toIntOrNull())}
    var error by remember{mutableStateOf<String?>(null)}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Borrador local: guarde primero y adjunte evidencias después")
        for((label,text,change) in listOf(Triple("Activo estable",asset,{v:String->asset=v}),Triple("Ubicación",location,{v:String->location=v}),Triple("Descripción",description,{v:String->description=v}),Triple("Evaluado UTC (ISO 8601)",evaluatedAt,{v:String->evaluatedAt=v}),Triple("Presupuesto COP (vacío: desconocido)",budget,{v:String->budget=v}),Triple("Latitud",latitude,{v:String->latitude=v}),Triple("Longitud",longitude,{v:String->longitude=v})))OutlinedTextField(text,change,label={Text(label)},modifier=Modifier.fillMaxWidth())
        Selector("Desastre",disaster?.toString(),listOf("" to "Preliminar")+disasters.map{it.first.toString() to it.second}){disaster=it.toIntOrNull()}
        Selector("Tipo de activo",type,catalogs.filter{it.kind=="asset"&&it.active}.map{it.id to it.name}){type=it;category=""}
        Selector("Categoría",category,catalogs.filter{it.kind=="category"&&it.active&&(it.assetTypeId==null||it.assetTypeId==type)}.map{it.id to it.name}){category=it}
        if(catalogs.isEmpty())Text("Catálogo pendiente. Puede guardar información incompleta sin inventar tipos.")
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        Button(onClick={try{
            val body=JsonObject().apply{
                addProperty("assetId",asset);addProperty("location",location);addProperty("description",description);addProperty("evaluatedAt",evaluatedAt)
                add("disasterId",disaster?.let{com.google.gson.JsonPrimitive(it)}?:JsonNull.INSTANCE)
                add("assetTypeId",type.takeIf{it.isNotBlank()}?.let{com.google.gson.JsonPrimitive(it)}?:JsonNull.INSTANCE)
                add("damageCategoryId",category.takeIf{it.isNotBlank()}?.let{com.google.gson.JsonPrimitive(it)}?:JsonNull.INSTANCE)
                add("budget",budget.takeIf{it.isNotBlank()}?.let{com.google.gson.JsonPrimitive(it.toBigDecimal())}?:JsonNull.INSTANCE)
                add("latitude",latitude.takeIf{it.isNotBlank()}?.let{com.google.gson.JsonPrimitive(it.toDouble().also{n->require(n.isFinite())})}?:JsonNull.INSTANCE)
                add("longitude",longitude.takeIf{it.isNotBlank()}?.let{com.google.gson.JsonPrimitive(it.toDouble().also{n->require(n.isFinite())})}?:JsonNull.INSTANCE)
                add("geometry",JsonNull.INSTANCE);add("evidence",JsonArray())
            };save(body);error=null
        }catch(_:IllegalArgumentException){error="Revise fechas, coordenadas y presupuesto"}}){Text(if(draft==null)"Guardar evaluación offline" else "Completar borrador")}
    }
}
@Composable
private fun Selector(label:String,value:String?,options:List<Pair<String,String>>,selected:(String)->Unit) {
    var open by remember{mutableStateOf(false)}
    Box{TextButton(onClick={open=true}){Text(label+": "+(options.firstOrNull{it.first==value}?.second?:"Pendiente"))};DropdownMenu(expanded=open,onDismissRequest={open=false}){options.forEach{(id,name)->DropdownMenuItem(text={Text(name)},onClick={open=false;selected(id)})}}}
}
