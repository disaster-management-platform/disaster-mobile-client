package co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import co.edu.javeriana.disasterplatform.disaster_mobile_client.R
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.theme.CommandBlue
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels.FormUiState
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.viewmodels.FormViewModel
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

data class DamageCategoryOption(
    val title: String,
    val description: String
)

private val assetTypeOptions = listOf(
    "Viviendas",
    "Edificios públicos",
    "Hospitales",
    "Escuelas",
    "Puentes",
    "Carreteras",
    "Sistemas de agua",
    "Redes eléctricas",
    "Redes de telecomunicaciones",
    "Instalaciones industriales",
    "Infraestructura crítica"
)

private val damageCategoryOptions = listOf(
    DamageCategoryOption("Sin daño", "El activo no presenta daños significativos."),
    DamageCategoryOption("Daño leve", "Existen daños que no comprometen el funcionamiento principal."),
    DamageCategoryOption("Daño moderado", "Existen daños que requieren reparación y pueden afectar parcialmente la operación."),
    DamageCategoryOption("Daño grave", "El activo presenta daños importantes y su funcionamiento puede estar comprometido."),
    DamageCategoryOption("Destrucción total", "El activo no puede utilizarse y probablemente requiere reconstrucción.")
)

private val evaluationStatusOptions = listOf(
    "Preliminar",
    "En revisión",
    "Confirmado",
    "Finalizado"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreen(
    navController: NavHostController,
    viewModel: FormViewModel = hiltViewModel()
) {
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val availableDisasters by viewModel.availableDisasters.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var isFetchingLocation by remember { mutableStateOf(false) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isFetchingLocation = true
            locationError = null
            fetchDeviceLocation(context, fusedLocationClient, viewModel) { err ->
                locationError = err
                isFetchingLocation = false
            }
        } else {
            locationError = "Se requiere el permiso de ubicación precisa (ACCESS_FINE_LOCATION) para capturar la ubicación actual."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Evaluación de Desastre") },
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
                // Header card with ID
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Registro de Evaluación de Daños",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Identificador: ${formState.identificador}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CommandBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "* Campos obligatorios",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 1. Desastre Asociado Dropdown
                val disasterOptions = remember(availableDisasters) {
                    val list = mutableListOf("Sin desastre formal (Registro preliminar)")
                    list.addAll(availableDisasters.map { d ->
                        val idLabel = d.serverId?.let { "#$it" } ?: "Local"
                        "$idLabel - ${d.nombre} (${d.ubicacion})"
                    })
                    list
                }
                val selectedDisasterText = formState.desastreAsociado.ifBlank {
                    "Sin desastre formal (Registro preliminar)"
                }

                CustomStringDropdown(
                    label = "Desastre Asociado",
                    selectedText = selectedDisasterText,
                    options = disasterOptions,
                    onOptionSelected = { selected ->
                        if (selected == "Sin desastre formal (Registro preliminar)") {
                            viewModel.onDesastreAsociadoChanged("")
                        } else {
                            viewModel.onDesastreAsociadoChanged(selected)
                        }
                    },
                    supportingText = {
                        Text("Permite registro preliminar cuando aún no hay información suficiente.")
                    }
                )

                // 2. Fecha y hora
                OutlinedTextField(
                    value = formState.fechaHora,
                    onValueChange = { viewModel.onFechaHoraChanged(it) },
                    label = { Text("Fecha y Hora *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // 3. Evaluador y Organización
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = formState.evaluador,
                        onValueChange = { viewModel.onEvaluadorChanged(it) },
                        label = { Text("Evaluador *") },
                        placeholder = { Text("Ej. Juan Pérez") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = formState.organizacion,
                        onValueChange = { viewModel.onOrganizacionChanged(it) },
                        label = { Text("Organización *") },
                        placeholder = { Text("Ej. Cruz Roja") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                // 4. Ubicación geográfica
                OutlinedTextField(
                    value = formState.ubicacion,
                    onValueChange = { viewModel.onUbicacionChanged(it) },
                    label = { Text("Ubicación Geográfica / Dirección *") },
                    placeholder = { Text("Ej. Bogotá, Suba Sector 3") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // GPS Location capture button
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = CommandBlue.copy(alpha = 0.08f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                locationError = null
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                    isFetchingLocation = true
                                    fetchDeviceLocation(context, fusedLocationClient, viewModel) { err ->
                                        locationError = err
                                        isFetchingLocation = false
                                    }
                                } else {
                                    requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            },
                            enabled = !isFetchingLocation,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonColors(
                                containerColor = CommandBlue,
                                contentColor = Color.White,
                                disabledContainerColor = Color.LightGray,
                                disabledContentColor = Color.DarkGray
                            )
                        ) {
                            if (isFetchingLocation) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(end = 6.dp)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(id = R.drawable.add_location),
                                    contentDescription = "GPS",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isFetchingLocation) "Obteniendo coordenadas GPS..." else "Seleccionar ubicación actual (GPS fine location)")
                        }

                        locationError?.let { err ->
                            Text(
                                text = err,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

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

                // Radio de la zona afectada
                OutlinedTextField(
                    value = formState.radio,
                    onValueChange = { viewModel.onRadioChanged(it) },
                    label = { Text("Radio afectado (metros)") },
                    placeholder = { Text("Ej. 500") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                // 5. Tipo de Activo Afectado Dropdown
                CustomStringDropdown(
                    label = "Tipo de Activo Afectado *",
                    selectedText = formState.tipoActivo,
                    options = assetTypeOptions,
                    onOptionSelected = { viewModel.onTipoActivoChanged(it) }
                )

                // 6. Nivel de Daño Dropdown
                CustomDamageCategoryDropdown(
                    selectedCategoryTitle = formState.nivelDano,
                    options = damageCategoryOptions,
                    onOptionSelected = { category ->
                        viewModel.onNivelDanoChanged(category.title)
                    }
                )

                // Severidad (Slider 1 a 5)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Nivel de Severidad equivalente:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
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

                // 7. Descripción
                OutlinedTextField(
                    value = formState.descripcion,
                    onValueChange = { viewModel.onDescripcionChanged(it) },
                    label = { Text("Descripción del Daño / Evento *") },
                    placeholder = { Text("Detalles del estado del activo y afectación...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5
                )

                // 8. Evidencias
                OutlinedTextField(
                    value = formState.evidencias,
                    onValueChange = { viewModel.onEvidenciasChanged(it) },
                    label = { Text("Evidencias (Notas, URLs de fotos, adjuntos)") },
                    placeholder = { Text("Ej. Fotografía 1 de fachada, acta de inspección...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                // 9. Estado de la Evaluación Dropdown
                CustomStringDropdown(
                    label = "Estado de la Evaluación *",
                    selectedText = formState.estadoEvaluacion,
                    options = evaluationStatusOptions,
                    onOptionSelected = { viewModel.onEstadoEvaluacionChanged(it) }
                )

                // Nombre / Título personalizado (opcional)
                OutlinedTextField(
                    value = formState.nombre,
                    onValueChange = { viewModel.onNombreChanged(it) },
                    label = { Text("Nombre / Título del Evento (Opcional)") },
                    placeholder = { Text("Ej. Evaluación Hospital Central") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
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
                                text = "Evaluación guardada localmente y en cola de sincronización.",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { viewModel.resetState() }) {
                                    Text("Registrar otra")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = { navController.popBackStack() }) {
                                    Text("Ir al inicio")
                                }
                            }
                        }
                    }
                }

                // Action submit button
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
                        text = if (uiState is FormUiState.Loading) "Guardando..." else "Guardar Evaluación",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomStringDropdown(
    label: String,
    selectedText: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: @Composable (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            supportingText = supportingText,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = {
                        onOptionSelected(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDamageCategoryDropdown(
    selectedCategoryTitle: String,
    options: List<DamageCategoryOption>,
    onOptionSelected: (DamageCategoryOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.find { it.title == selectedCategoryTitle } ?: options.first()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = "${selectedOption.title}: ${selectedOption.description}",
            onValueChange = {},
            readOnly = true,
            label = { Text("Nivel de Daño *") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = option.title,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun fetchDeviceLocation(
    context: Context,
    fusedLocationClient: FusedLocationProviderClient,
    viewModel: FormViewModel,
    onError: (String) -> Unit
) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        viewModel.onLatitudChanged(location.latitude.toString())
                        viewModel.onLongitudChanged(location.longitude.toString())
                    } else {
                        fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc ->
                            if (lastLoc != null) {
                                viewModel.onLatitudChanged(lastLoc.latitude.toString())
                                viewModel.onLongitudChanged(lastLoc.longitude.toString())
                            } else {
                                onError("No se pudo obtener la ubicación GPS actual. Verifica que el GPS esté activado.")
                            }
                        }.addOnFailureListener {
                            onError("Error al obtener la última ubicación: ${it.localizedMessage}")
                        }
                    }
                }
                .addOnFailureListener { e ->
                    onError("Error al consultar la ubicación GPS: ${e.localizedMessage}")
                }
        } catch (e: SecurityException) {
            onError("Permiso de ubicación denegado: ${e.localizedMessage}")
        }
    } else {
        onError("El permiso de ubicación no ha sido otorgado.")
    }
}
