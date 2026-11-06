package co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.screens

import android.graphics.Color
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.theme.CommandBlue
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels.DisastersViewModel
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.google.maps.android.heatmaps.Gradient
import com.google.maps.android.heatmaps.HeatmapTileProvider
import com.google.maps.android.heatmaps.WeightedLatLng

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    navController: NavHostController,
    viewModel: DisastersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
                        Text("← Volver", color = ComposeColor.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CommandBlue,
                    titleContentColor = ComposeColor.White
                )
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
                val weightedLatLngs = uiState.disasters.map { disaster ->
                    WeightedLatLng(
                        LatLng(disaster.latitud, disaster.longitud),
                        disaster.severidad.toDouble()
                    )
                }

                if (weightedLatLngs.isNotEmpty()) {
                    val colors = intArrayOf(
                        Color.rgb(76, 175, 80),
                        Color.rgb(205, 220, 57),
                        Color.rgb(255, 235, 59),
                        Color.rgb(255, 152, 0),
                        Color.rgb(244, 67, 54)
                    )
                    val startPoints = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 1.0f)
                    val gradient = remember { Gradient(colors, startPoints) }

                    val provider = remember(weightedLatLngs) {
                        HeatmapTileProvider.Builder()
                            .weightedData(weightedLatLngs)
                            .gradient(gradient)
                            .build()
                    }

                    TileOverlay(tileProvider = provider)
                }

                uiState.disasters.forEach { disaster ->
                    key(disaster.clientRequestId) {
                        val position = LatLng(disaster.latitud, disaster.longitud)
                        val radiusMeters = disaster.radio ?: 500.0

                        val fillColor = when (disaster.severidad) {
                            1 -> ComposeColor(0x334CAF50)
                            2 -> ComposeColor(0x338BC34A)
                            3 -> ComposeColor(0x33FFEB3B)
                            4 -> ComposeColor(0x33FF9800)
                            else -> ComposeColor(0x33F44336)
                        }
                        val strokeColor = when (disaster.severidad) {
                            1 -> ComposeColor(0xFF4CAF50)
                            2 -> ComposeColor(0xFF8BC34A)
                            3 -> ComposeColor(0xFFFFEB3B)
                            4 -> ComposeColor(0xFFFF9800)
                            else -> ComposeColor(0xFFF44336)
                        }

                        Circle(
                            center = position,
                            radius = radiusMeters,
                            fillColor = fillColor,
                            strokeColor = strokeColor,
                            strokeWidth = 3f
                        )

                        Marker(
                            state = rememberMarkerState(position = position),
                            title = disaster.nombre,
                            snippet = "${disaster.ubicacion} | Radio: ${radiusMeters.toInt()}m (Severidad: ${disaster.severidad})"
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
