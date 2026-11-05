package co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterInfoDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.repository.DisasterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DisastersUiState {
    object Idle : DisastersUiState
    object Loading : DisastersUiState
    data class Success(val disasters: List<DisasterInfoDto>) : DisastersUiState
    data class Error(val message: String) : DisastersUiState
}

@HiltViewModel
class DisastersViewModel @Inject constructor(
    private val repository: DisasterRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<DisastersUiState>(DisastersUiState.Idle)
    val uiState: StateFlow<DisastersUiState> = _uiState.asStateFlow()

    fun fetchDisasters() {
        viewModelScope.launch {
            _uiState.value = DisastersUiState.Loading
            repository.getDisasters()
                .onSuccess { disasters ->
                    _uiState.value = DisastersUiState.Success(disasters)
                }
                .onFailure { exception ->
                    _uiState.value = DisastersUiState.Error(
                        exception.localizedMessage ?: "Error al obtener los desastres"
                    )
                }
        }
    }
}
