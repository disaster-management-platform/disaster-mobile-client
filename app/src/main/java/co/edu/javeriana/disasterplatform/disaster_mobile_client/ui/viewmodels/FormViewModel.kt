package co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterReportDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterEntity
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.repository.DisasterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

sealed interface FormUiState {
    object Idle : FormUiState
    object Loading : FormUiState
    object Success : FormUiState
    data class Error(val message: String) : FormUiState
}

data class FormState(
    val identificador: String = UUID.randomUUID().toString().take(8).uppercase(),
    val desastreAsociado: String = "", // Vacio = Sin desastre formal (Registro preliminar)
    val fechaHora: String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
    val evaluador: String = "",
    val organizacion: String = "",
    val ubicacion: String = "",
    val latitud: String = "",
    val longitud: String = "",
    val radio: String = "500",
    val tipoActivo: String = "Viviendas",
    val nivelDano: String = "Sin daño",
    val descripcion: String = "",
    val evidencias: String = "",
    val estadoEvaluacion: String = "Preliminar",
    val nombre: String = "",
    val severidad: Int = 1,
)

@HiltViewModel
class FormViewModel @Inject constructor(
    private val repository: DisasterRepository
) : ViewModel() {

    private val _formState = MutableStateFlow(FormState())
    val formState: StateFlow<FormState> = _formState.asStateFlow()

    private val _uiState = MutableStateFlow<FormUiState>(FormUiState.Idle)
    val uiState: StateFlow<FormUiState> = _uiState.asStateFlow()

    val availableDisasters: StateFlow<List<DisasterEntity>> = repository.observeDisasters()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun onDesastreAsociadoChanged(value: String) {
        _formState.value = _formState.value.copy(desastreAsociado = value)
    }

    fun onFechaHoraChanged(value: String) {
        _formState.value = _formState.value.copy(fechaHora = value)
    }

    fun onEvaluadorChanged(value: String) {
        _formState.value = _formState.value.copy(evaluador = value)
    }

    fun onOrganizacionChanged(value: String) {
        _formState.value = _formState.value.copy(organizacion = value)
    }

    fun onUbicacionChanged(value: String) {
        _formState.value = _formState.value.copy(ubicacion = value)
    }

    fun onTipoActivoChanged(value: String) {
        _formState.value = _formState.value.copy(tipoActivo = value)
    }

    fun onNivelDanoChanged(value: String) {
        val mappedSeveridad = when (value) {
            "Sin daño" -> 1
            "Daño leve" -> 2
            "Daño moderado" -> 3
            "Daño grave" -> 4
            "Destrucción total" -> 5
            else -> _formState.value.severidad
        }
        _formState.value = _formState.value.copy(nivelDano = value, severidad = mappedSeveridad)
    }

    fun onDescripcionChanged(value: String) {
        _formState.value = _formState.value.copy(descripcion = value)
    }

    fun onEvidenciasChanged(value: String) {
        _formState.value = _formState.value.copy(evidencias = value)
    }

    fun onEstadoEvaluacionChanged(value: String) {
        _formState.value = _formState.value.copy(estadoEvaluacion = value)
    }

    fun onNombreChanged(value: String) {
        _formState.value = _formState.value.copy(nombre = value)
    }

    fun onSeveridadChanged(value: Int) {
        _formState.value = _formState.value.copy(severidad = value)
    }

    fun onLatitudChanged(value: String) {
        _formState.value = _formState.value.copy(latitud = value)
    }

    fun onLongitudChanged(value: String) {
        _formState.value = _formState.value.copy(longitud = value)
    }

    fun onRadioChanged(value: String) {
        _formState.value = _formState.value.copy(radio = value)
    }

    fun resetState() {
        _uiState.value = FormUiState.Idle
        _formState.value = FormState()
    }

    fun submitForm() {
        val state = _uiState.value
        if (state is FormUiState.Loading || state is FormUiState.Success) return
        val current = _formState.value

        if (current.evaluador.isBlank() || current.organizacion.isBlank() || current.ubicacion.isBlank() || current.descripcion.isBlank()) {
            _uiState.value = FormUiState.Error("Por favor completa los campos obligatorios (*): Evaluador, Organización, Ubicación y Descripción")
            return
        }

        val lat = current.latitud.toDoubleOrNull()
        val lng = current.longitud.toDoubleOrNull()
        val rad = current.radio.toDoubleOrNull() ?: 500.0

        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) {
            _uiState.value = FormUiState.Error("Por favor ingresa o captura valores numéricos válidos para Latitud y Longitud")
            return
        }
        if (!rad.isFinite() || rad <= 0.0) {
            _uiState.value = FormUiState.Error("Por favor ingresa un radio válido mayor que cero")
            return
        }

        val finalNombre = current.nombre.ifBlank {
            "Evaluación de ${current.tipoActivo} en ${current.ubicacion}"
        }

        val reportDto = DisasterReportDto(
            nombre = finalNombre.take(120),
            ubicacion = current.ubicacion,
            severidad = current.severidad.coerceIn(1, 5),
            descripcion = current.descripcion,
            latitud = lat,
            longitud = lng,
            radio = rad,
            desastreAsociado = current.desastreAsociado.ifBlank { "Preliminar / Sin desastre formal" },
            fechaHora = current.fechaHora,
            evaluador = current.evaluador,
            organizacion = current.organizacion,
            tipoActivo = current.tipoActivo,
            nivelDano = current.nivelDano,
            evidencias = current.evidencias,
            estadoEvaluacion = current.estadoEvaluacion,
        )

        _uiState.value = FormUiState.Loading
        viewModelScope.launch {
            repository.createDisaster(reportDto)
                .onSuccess {
                    _uiState.value = FormUiState.Success
                }
                .onFailure { error ->
                    _uiState.value = FormUiState.Error(error.localizedMessage ?: "Error al registrar la evaluación")
                }
        }
    }
}
