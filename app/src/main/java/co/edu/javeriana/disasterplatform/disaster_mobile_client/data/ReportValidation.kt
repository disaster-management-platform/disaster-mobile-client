package co.edu.javeriana.disasterplatform.disaster_mobile_client.data

import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterReportDto
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterInfoDto
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

fun DisasterReportDto.validated(): DisasterReportDto {
    val normalized = copy(nombre = nombre.trim(), ubicacion = ubicacion.trim(), descripcion = descripcion.trim())
    require(normalized.nombre.length in 1..120) { "El nombre debe tener entre 1 y 120 caracteres." }
    require(normalized.ubicacion.length in 1..200) { "La ubicación debe tener entre 1 y 200 caracteres." }
    require(normalized.descripcion.length in 1..2000) { "La descripción debe tener entre 1 y 2000 caracteres." }
    require(severidad in 1..5) { "La severidad debe estar entre 1 y 5." }
    require(latitud.isFinite() && latitud in -90.0..90.0) { "La latitud debe estar entre -90 y 90." }
    require(longitud.isFinite() && longitud in -180.0..180.0) { "La longitud debe estar entre -180 y 180." }
    require(radio == null || (radio.isFinite() && radio > 0.0 && radio <= 1_000_000.0)) {
        "El radio debe ser mayor que 0 y máximo 1.000.000 metros."
    }
    require(disasterTypeId == null || disasterTypeId > 0) { "Seleccione un tipo válido." }
    require(attentionLevel == null || attentionLevel in 1..4) { "La atención requerida debe estar entre 1 y 4." }
    return normalized.copy(startedAt = normalizedUtc(startedAt))
}

fun normalizedUtc(value: String?): String? {
    if (value == null) return null
    val parts = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,6}))?(?:Z|\\+00:00)$").matchEntire(value)
    require(parts != null) { "La fecha debe ser UTC, con formato 2026-11-17T12:00:00Z." }
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val position = ParsePosition(0)
    require(format.parse(parts.groupValues[1], position) != null && position.index == parts.groupValues[1].length) { "Fecha inválida." }
    val fraction = parts.groupValues[2].trimEnd('0')
    return parts.groupValues[1] + (if (fraction.isEmpty()) "" else ".$fraction") + "Z"
}

fun DisasterInfoDto.validated(requireVersion: Boolean = false): DisasterInfoDto {
    require(id > 0 && !nombre.isNullOrBlank() && !ubicacion.isNullOrBlank() && !descripcion.isNullOrBlank()) { "Respuesta incompleta." }
    val normalized = DisasterReportDto(nombre, ubicacion, severidad, descripcion, latitud, longitud, radio,
        disasterTypeId, startedAt, attentionLevel).validated()
    require(version == null && !requireVersion || version != null && version > 0) { "Versión inválida o ausente." }
    require(phase == null && !requireVersion || phase in setOf("Detected", "Registered", "Assessment", "Response", "Recovery", "Reconstruction", "Closed")) { "Fase inválida o ausente." }
    try {
        for (reference in listOf(registeringOrganizationId, emergencyResponsibleId))
            require(reference == null || reference.isNotBlank() && reference.length <= 200) { "Referencia de organismo inválida." }
        require(organizations == null || organizations.size <= 50 && organizations.all { organization ->
            val id = organization?.get("organizationId")
            val responsibility = organization?.get("responsibility")
            id != null && id.isJsonPrimitive && id.asJsonPrimitive.isString && id.asString.isNotBlank() && id.asString.length <= 200
                && responsibility != null && responsibility.isJsonPrimitive && responsibility.asJsonPrimitive.isString
                && responsibility.asString.isNotBlank() && responsibility.asString.length <= 500
        }) { "Asociaciones inválidas." }
        geography?.let { area ->
            require(area.toString().toByteArray(Charsets.UTF_8).size <= 65536) { "Geografía demasiado grande." }
            for (name in listOf("municipalities", "neighborhoods", "riskZones")) {
                val names = area.get(name)
                require(names == null || names.isJsonNull || names.isJsonArray && names.asJsonArray.size() <= 100
                    && names.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank() && it.asString.length <= 200 }) { "Zonas inválidas." }
            }
            area.get("polygon")?.takeUnless { it.isJsonNull }?.let { polygon ->
                require(polygon.isJsonObject && polygon.asJsonObject.get("type")?.asString == "Polygon") { "Polígono inválido." }
                val rings = polygon.asJsonObject.getAsJsonArray("coordinates")
                require(rings != null && rings.size() in 1..50 && rings.sumOf { it.asJsonArray.size() } <= 2000) { "Coordenadas inválidas." }
                for (ring in rings) {
                    require(ring.isJsonArray && ring.asJsonArray.size() >= 4 && ring.asJsonArray.first() == ring.asJsonArray.last()) { "Anillo inválido." }
                    for (point in ring.asJsonArray) {
                        require(point.isJsonArray && point.asJsonArray.size() == 2) { "Coordenada inválida." }
                        require(point.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }) { "Coordenada no numérica." }
                        val x = point.asJsonArray[0].asDouble; val y = point.asJsonArray[1].asDouble
                        require(x.isFinite() && y.isFinite() && x in -180.0..180.0 && y in -90.0..90.0) { "Coordenada fuera de WGS84." }
                    }
                }
            }
        }
    } catch (error: RuntimeException) {
        throw IllegalArgumentException("Información geográfica o de organismos inválida.", error)
    }
    return copy(startedAt = normalized.startedAt, registeredAt = normalizedUtc(registeredAt), updatedAt = normalizedUtc(updatedAt))
}
