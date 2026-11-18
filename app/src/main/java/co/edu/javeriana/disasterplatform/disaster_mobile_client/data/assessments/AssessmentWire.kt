package co.edu.javeriana.disasterplatform.disaster_mobile_client.data.assessments

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

object AssessmentWire {
    fun uuid(value: String): String = UUID.fromString(value).also {
        require(it != UUID(0, 0) && it.toString().equals(value, ignoreCase = true))
    }.toString()
    fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
    fun text(value: JsonObject, name: String): String = value[name].let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString)
        it.asString.also { text -> require(text.isNotBlank()) }
    }
    fun number(value: JsonObject, name: String): Long = value[name].let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
        it.asBigDecimal.longValueExact()
    }
    fun nullableInt(value: JsonObject, name: String): Int? {
        require(value.has(name))
        return if (value[name].isJsonNull) null else number(value, name).also { require(it in 1..Int.MAX_VALUE) }.toInt()
    }
    fun snapshot(value: JsonObject): JsonObject {
        uuid(text(value, "evaluationId")); uuid(text(value, "clientRequestId"))
        nullableInt(value, "disasterId")
        for (name in listOf("assetId", "assetType", "assetTypeId", "damageCategoryId", "evaluator", "organization", "location", "description")) text(value, name)
        for (name in listOf("evaluatedAt", "createdAt", "updatedAt")) OffsetDateTime.parse(text(value, name))
        require(text(value, "status") in setOf("Draft", "Validated", "Rejected"))
        require(text(value, "damageLevel") in setOf("None", "Minor", "Moderate", "Severe", "Destroyed"))
        require(number(value, "revision") > 0 && number(value, "version") >= 0)
        val budget = value["budget"]
        require(budget != null && budget.isJsonPrimitive && budget.asJsonPrimitive.isNumber)
        require(budget.asBigDecimal >= BigDecimal.ZERO && budget.asBigDecimal.stripTrailingZeros().scale() <= 2)
        for ((name, bound) in listOf("latitude" to 90.0, "longitude" to 180.0)) {
            require(value.has(name))
            if (!value[name].isJsonNull) { require(value[name].isJsonPrimitive&&value[name].asJsonPrimitive.isNumber); require(value[name].asDouble.let { it.isFinite() && it in -bound..bound }) }
        }
        require(value.has("geometry") && (value["geometry"].isJsonNull || value["geometry"].isJsonObject))
        require(value["latitude"].isJsonNull == value["longitude"].isJsonNull)
        require(value["evidence"]?.isJsonArray == true && value["evidence"].asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank() })
        return value
    }
    fun matches(receipt: JsonObject, input: JsonObject): Boolean = runCatching {
        snapshot(receipt)
        for (name in listOf("assetId", "assetTypeId", "damageCategoryId", "location", "description")) require(text(receipt, name) == text(input, name))
        require(nullableInt(receipt, "disasterId") == nullableInt(input, "disasterId"))
        require(OffsetDateTime.parse(text(receipt, "evaluatedAt")).toInstant() == OffsetDateTime.parse(text(input, "evaluatedAt")).toInstant())
        require(receipt["budget"].asBigDecimal.compareTo(input["budget"].asBigDecimal) == 0)
        for (name in listOf("latitude", "longitude", "geometry")) require(receipt[name] == input[name])
        require(receipt["evidence"] == input["evidence"])
    }.isSuccess
    fun input(snapshot: JsonObject): JsonObject = JsonObject().apply {
        for (name in listOf("disasterId", "assetId", "assetTypeId", "damageCategoryId", "evaluatedAt", "location", "latitude", "longitude", "geometry", "description", "evidence", "budget")) add(name, snapshot[name]?.deepCopy())
    }
}
