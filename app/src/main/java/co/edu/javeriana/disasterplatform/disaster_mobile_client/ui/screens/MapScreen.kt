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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.theme.CommandBlue
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels.DisastersUiState
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
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.fetchDisasters()
    }

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
                if (uiState is DisastersUiState.Success) {
                    val disasters = (uiState as DisastersUiState.Success).disasters

                    // Mapear cada desastre con su severidad (1 a 5) como peso (WeightedLatLng)
                    val weightedLatLngs = disasters.map { disaster ->
                        WeightedLatLng(
                            LatLng(disaster.latitud.toDouble(), disaster.longitud.toDouble()),
                            disaster.severidad.toDouble()
                        )
                    }

                    // Capa de Mapa de Calor (Heatmap) con 5 niveles de intensidad y color
                    if (weightedLatLngs.isNotEmpty()) {
                        val colors = intArrayOf(
                            Color.rgb(76, 175, 80),   // Nivel 1: Verde (Desastre leve)
                            Color.rgb(205, 220, 57),  // Nivel 2: Lima / Amarillo verdoso
                            Color.rgb(255, 235, 59),  // Nivel 3: Amarillo (Severidad media)
                            Color.rgb(255, 152, 0),   // Nivel 4: Naranja
                            Color.rgb(244, 67, 54)    // Nivel 5: Rojo (Desastre muy grave)
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

                    // Marcadores y Círculos del radio afectado de cada desastre
                    disasters.forEach { disaster ->
                        val position = LatLng(disaster.latitud.toDouble(), disaster.longitud.toDouble())
                        val radiusMeters = (disaster.radio ?: 500f).toDouble()

                        // Color del círculo de la zona afectada según severidad
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

                        // Círculo visual del radio afectado en metros
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

            when (uiState) {
                is DisastersUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is DisastersUiState.Error -> {
                    val errorMsg = (uiState as DisastersUiState.Error).message
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            text = "Error al cargar desastres: $errorMsg",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
                else -> {}
            }
        }
    }
}
