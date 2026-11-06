package co.edu.javeriana.disasterplatform.disaster_mobile_client.data

import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.dto.DisasterReportDto

fun DisasterReportDto.validated(): DisasterReportDto {
    val normalized = copy(nombre = nombre.trim(), ubicacion = ubicacion.trim(), descripcion = descripcion.trim())
    require(normalized.nombre.length in 1..120) { "El nombre debe tener entre 1 y 120 caracteres." }
    require(normalized.ubicacion.length in 1..200) { "La ubicación debe tener entre 1 y 200 caracteres." }
    require(normalized.descripcion.length in 1..2000) { "La descripción debe tener entre 1 y 2000 caracteres." }
    require(severidad in 1..5) { "La severidad debe estar entre 1 y 5." }
    require(latitud.isFinite() && latitud in -90.0..90.0) { "La latitud debe estar entre -90 y 90." }
    require(longitud.isFinite() && longitud in -180.0..180.0) { "La longitud debe estar entre -180 y 180." }
    return normalized
}
