package co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.theme.CommandBlue
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels.FormUiState
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels.FormViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreen(
    navController: NavHostController,
    viewModel: FormViewModel = hiltViewModel()
) {
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val types by viewModel.types.collectAsStateWithLifecycle()
    var typesExpanded by remember { mutableStateOf(false) }
    var attentionExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Registrar Desastre") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) {
                        Text("← Volver", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CommandBlue,
                    titleContentColor = Color.White
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Completa la información del reporte:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Box {
                    OutlinedButton(onClick = { typesExpanded = true }) {
                        Text(types.find { it.id == formState.disasterTypeId }?.let { it.name + if (it.isActive) "" else " (inactivo)" }
                            ?: "Tipo de desastre (opcional)")
                    }
                    DropdownMenu(expanded = typesExpanded, onDismissRequest = { typesExpanded = false }) {
                        DropdownMenuItem(text = { Text("Sin tipo") }, onClick = { viewModel.onTypeChanged(null); typesExpanded = false })
                        types.filter { it.isActive }.forEach { type ->
                            DropdownMenuItem(text = { Text(type.name) }, onClick = { viewModel.onTypeChanged(type.id); typesExpanded = false })
                        }
                    }
                }
                if (types.isEmpty()) Text("Catálogo pendiente de obtener. Puede registrar sin tipo y conservar el reporte offline.")
                OutlinedTextField(value = formState.startedAt, onValueChange = viewModel::onStartedAtChanged,
                    label = { Text("Inicio UTC (opcional)") }, placeholder = { Text("2026-11-17T12:00:00Z") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Box {
                    val labels = listOf("Bajo", "Medio", "Alto", "Crítico")
                    OutlinedButton(onClick = { attentionExpanded = true }) {
                        Text(formState.attentionLevel?.let { "Atención $it: ${labels[it - 1]}" } ?: "Atención requerida (opcional)")
                    }
                    DropdownMenu(expanded = attentionExpanded, onDismissRequest = { attentionExpanded = false }) {
                        DropdownMenuItem(text = { Text("Sin clasificar") }, onClick = { viewModel.onAttentionChanged(null); attentionExpanded = false })
                        labels.forEachIndexed { index, label ->
                            DropdownMenuItem(text = { Text("Nivel ${index + 1}: $label") },
                                onClick = { viewModel.onAttentionChanged(index + 1); attentionExpanded = false })
                        }
                    }
                }

                // Nombre
                OutlinedTextField(
                    value = formState.nombre,
                    onValueChange = { viewModel.onNombreChanged(it) },
                    label = { Text("Nombre del desastre *") },
                    placeholder = { Text("Ej. Inundación Sector Norte") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Ubicación
                OutlinedTextField(
                    value = formState.ubicacion,
                    onValueChange = { viewModel.onUbicacionChanged(it) },
                    label = { Text("Ubicación *") },
                    placeholder = { Text("Ej. Bogotá, Suba") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Severidad (Slider 1 a 5)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Severidad:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${formState.severidad} / 5",
                            style = MaterialTheme.typography.titleMedium,
                            color = CommandBlue,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = formState.severidad.toFloat(),
                        onValueChange = { viewModel.onSeveridadChanged(it.toInt()) },
                        valueRange = 1f..5f,
                        steps = 3,
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = CommandBlue,
                            activeTrackColor = CommandBlue,
                            inactiveTrackColor = Color.LightGray
                        )
                    )
                }

                // Descripción
                OutlinedTextField(
                    value = formState.descripcion,
                    onValueChange = { viewModel.onDescripcionChanged(it) },
                    label = { Text("Descripción *") },
                    placeholder = { Text("Detalles adicionales sobre el evento...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5
                )

                // Coordenadas: Latitud y Longitud
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = formState.latitud,
                        onValueChange = { viewModel.onLatitudChanged(it) },
                        label = { Text("Latitud *") },
                        placeholder = { Text("Ej. 4.6097") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    OutlinedTextField(
                        value = formState.longitud,
                        onValueChange = { viewModel.onLongitudChanged(it) },
                        label = { Text("Longitud *") },
                        placeholder = { Text("Ej. -74.0817") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                // Radio de la zona afectada (metros)
                OutlinedTextField(
                    value = formState.radio,
                    onValueChange = { viewModel.onRadioChanged(it) },
                    label = { Text("Radio afectado (metros, opcional)") },
                    placeholder = { Text("Desconocido") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Error alert message
                if (uiState is FormUiState.Error) {
                    val errorMessage = (uiState as FormUiState.Error).message
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                // Success alert message
                if (uiState is FormUiState.Success) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Guardado localmente, pendiente de sincronización.",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { viewModel.resetState() }) {
                                    Text("Registrar otro")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = { navController.popBackStack() }) {
                                    Text("Ir al inicio")
                                }
                            }
                        }
                    }
                }

                // Action button
                Button(
                    onClick = { viewModel.submitForm() },
                    enabled = uiState !is FormUiState.Loading && uiState !is FormUiState.Success,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonColors(
                        containerColor = CommandBlue,
                        contentColor = Color.White,
                        disabledContentColor = Color.Gray,
                        disabledContainerColor = Color.LightGray
                    )
                ) {
                    if (uiState is FormUiState.Loading) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    Text(
                        text = if (uiState is FormUiState.Loading) "Guardando..." else "Guardar desastre",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
