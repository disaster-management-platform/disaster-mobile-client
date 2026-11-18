package co.edu.javeriana.disasterplatform.disaster_mobile_client.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.assessments.*
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AssessmentViewModel @Inject constructor(
    private val repository:AssessmentRepository, disasterDao:DisasterDao,
) : ViewModel() {
    val session=repository.sessions.session
    val assessments=repository.assessments.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val catalogs=repository.catalogs.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val files=repository.files.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val disasters=disasterDao.observeAll().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val feedback=MutableStateFlow<String?>(null);val message=feedback.asStateFlow()
    private val working=MutableStateFlow(false);val busy=working.asStateFlow()
    private val rendered=MutableStateFlow<android.graphics.Bitmap?>(null);val document=rendered.asStateFlow()
    init{
        viewModelScope.launch{repository.sessions.restore()}
        viewModelScope.launch{session.map{it?.scope to it?.owner}.distinctUntilChanged().collect{rendered.value=null}}
    }
    fun action(task:suspend()->Unit){if(working.value)return;working.value=true;viewModelScope.launch{try{task();feedback.value=null}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}catch(_:Exception){feedback.value="Operación pendiente o datos inválidos. Revise sesión, contenido y conectividad."}finally{working.value=false}}}
    private val cameraRequest=MutableStateFlow<android.net.Uri?>(null);val camera=cameraRequest.asStateFlow()
    private var captureId:String?=null
    fun save(input:com.google.gson.JsonObject,id:String?)=action{repository.saveDraft(input,id)}
    fun attach(id:String,uri:android.net.Uri)=action{repository.addFile(id,uri)}
    fun capture(id:String)=action{val prepared=repository.prepareCapture(id);captureId=prepared.first;cameraRequest.value=prepared.second}
    fun cameraLaunched(){cameraRequest.value=null}
    fun captured(success:Boolean){captureId?.let{id->if(success)recoverCapture(id)else action{repository.cancelCapture(id)}}}
    fun recoverCapture(id:String)=action{repository.finishCapture(id)}
    suspend fun image(file:AssessmentFile)=runCatching{repository.localFile(file)}.getOrNull()
    fun download(id:String,reference:String)=action{repository.downloadEvidence(id,reference)}
    fun showPdf(file:AssessmentFile)=action{
        val owner=session.value?.owner;val scope=session.value?.scope
        val bitmap=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
            val source=repository.localFile(file)
            android.os.ParcelFileDescriptor.open(source,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use{fd->
                android.graphics.pdf.PdfRenderer(fd).use{renderer->renderer.openPage(0).use{page->
                    val height=minOf(1200,(page.height.toDouble()/page.width*800).toInt().coerceAtLeast(1))
                    android.graphics.Bitmap.createBitmap(800,height,android.graphics.Bitmap.Config.ARGB_8888).also{page.render(it,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}
                }}
            }
        }
        if(session.value?.owner==owner&&session.value?.scope==scope)rendered.value=bitmap else bitmap.recycle()
    }
    fun importToken(value:String)=action{repository.sessions.accept(value);repository.requestSync()}
    fun logout()=action{repository.sessions.clear()}
    fun sync()=action{repository.requestSync()}
}
