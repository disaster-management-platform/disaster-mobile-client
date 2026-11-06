package co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels.DisastersViewModel
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    navController: NavHostController,
    viewModel: DisastersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Ubicación por defecto (Bogotá, Colombia)
    val defaultLocation = LatLng(4.6097, -74.0817)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(defaultLocation, 9f)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mapa de Desastres") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) {
                        Text("← Volver")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState
            ) {
                uiState.disasters.forEach { disaster ->
                    key(disaster.clientRequestId) {
                        val position = LatLng(disaster.latitud, disaster.longitud)
                        Marker(
                            state = rememberMarkerState(position = position),
                            title = disaster.nombre,
                            snippet = "${disaster.ubicacion} (Severidad: ${disaster.severidad})"
                        )
                    }
                }
            }
            if (uiState.isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.TopCenter))
            }
            uiState.message?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
