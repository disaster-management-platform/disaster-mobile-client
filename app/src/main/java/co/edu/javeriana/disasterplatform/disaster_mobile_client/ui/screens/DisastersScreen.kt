package co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterEntity
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.SyncStatus
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels.DisastersViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisastersScreen(
    navController: NavHostController,
    viewModel: DisastersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lista de Desastres") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) {
                        Text("← Volver")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = { viewModel.fetchDisasters() },
                enabled = !uiState.isRefreshing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(if (uiState.isRefreshing) "Actualizando…" else "Actualizar desde el servidor")
            }

            uiState.message?.let { message ->
                Text(message, modifier = Modifier.padding(bottom = 12.dp))
            }
            if (uiState.disasters.isEmpty()) {
                Text("Todavía no hay reportes guardados.")
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(uiState.disasters, key = { it.clientRequestId }) { disaster ->
                        DisasterItemCard(disaster = disaster)
                    }
                }
            }
        }
    }
}

@Composable
fun DisasterItemCard(disaster: DisasterEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = disaster.serverId?.let { "#$it ${disaster.nombre}" } ?: disaster.nombre,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Severidad: ${disaster.severidad}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (disaster.syncStatus) {
                    SyncStatus.SYNCED -> "Sincronizado"
                    SyncStatus.CONFLICT -> "Conflicto: requiere revisión"
                    SyncStatus.ERROR -> "No sincronizado: requiere revisión"
                    else -> "Guardado localmente, pendiente de sincronización"
                },
                style = MaterialTheme.typography.labelMedium,
            )
            disaster.syncError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Text(
                text = "Ubicación: ${disaster.ubicacion}",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = disaster.descripcion,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Coordenadas: (${disaster.latitud}, ${disaster.longitud})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
