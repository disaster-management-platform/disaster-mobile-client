package co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterReportDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.repository.DisasterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface FormUiState {
    object Idle : FormUiState
    object Loading : FormUiState
    object Success : FormUiState
    data class Error(val message: String) : FormUiState
}

data class FormState(
    val nombre: String = "",
    val ubicacion: String = "",
    val severidad: Int = 1,
    val descripcion: String = "",
    val latitud: String = "",
    val longitud: String = ""
)

@HiltViewModel
class FormViewModel @Inject constructor(
    private val repository: DisasterRepository
) : ViewModel() {

    private val _formState = MutableStateFlow(FormState())
    val formState: StateFlow<FormState> = _formState.asStateFlow()

    private val _uiState = MutableStateFlow<FormUiState>(FormUiState.Idle)
    val uiState: StateFlow<FormUiState> = _uiState.asStateFlow()

    fun onNombreChanged(value: String) {
        _formState.value = _formState.value.copy(nombre = value)
    }

    fun onUbicacionChanged(value: String) {
        _formState.value = _formState.value.copy(ubicacion = value)
    }

    fun onSeveridadChanged(value: Int) {
        _formState.value = _formState.value.copy(severidad = value)
    }

    fun onDescripcionChanged(value: String) {
        _formState.value = _formState.value.copy(descripcion = value)
    }

    fun onLatitudChanged(value: String) {
        _formState.value = _formState.value.copy(latitud = value)
    }

    fun onLongitudChanged(value: String) {
        _formState.value = _formState.value.copy(longitud = value)
    }

    fun resetState() {
        _uiState.value = FormUiState.Idle
    }

    fun submitForm() {
        if (_uiState.value is FormUiState.Loading || _uiState.value is FormUiState.Success) return
        val current = _formState.value
        if (current.nombre.isBlank() || current.ubicacion.isBlank() || current.descripcion.isBlank()) {
            _uiState.value = FormUiState.Error("Por favor completa todos los campos obligatorios")
            return
        }

        val lat = current.latitud.toDoubleOrNull()
        val lng = current.longitud.toDoubleOrNull()

        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) {
            _uiState.value = FormUiState.Error("Por favor ingresa valores numéricos válidos para latitud y longitud")
            return
        }

        val reportDto = DisasterReportDto(
            nombre = current.nombre,
            ubicacion = current.ubicacion,
            severidad = current.severidad,
            descripcion = current.descripcion,
            latitud = lat,
            longitud = lng
        )

        _uiState.value = FormUiState.Loading
        viewModelScope.launch {
            repository.createDisaster(reportDto)
                .onSuccess {
                    _uiState.value = FormUiState.Success
                    _formState.value = FormState()
                }
                .onFailure { error ->
                    _uiState.value = FormUiState.Error(error.localizedMessage ?: "Error al registrar el desastre")
                }
        }
    }
}
