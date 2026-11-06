package co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterEntity
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.repository.DisasterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DisastersUiState(
    val disasters: List<DisasterEntity> = emptyList(),
    val isRefreshing: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class DisastersViewModel @Inject constructor(
    private val repository: DisasterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DisastersUiState())
    val uiState: StateFlow<DisastersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeDisasters().collect { disasters ->
                _uiState.update { it.copy(disasters = disasters) }
            }
        }
        fetchDisasters()
    }

    fun fetchDisasters() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true, message = null) }
        viewModelScope.launch {
            try {
                repository.refreshDisasters()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(message = "No se pudo consultar el servidor. Se muestran los reportes guardados en este dispositivo.") }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }
}
