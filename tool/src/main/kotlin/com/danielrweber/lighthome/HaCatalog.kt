package com.danielrweber.lighthome

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

internal val haJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

internal val DOMAIN_ORDER = listOf("light", "switch", "scene", "script", "cover", "climate")

private val DOMAIN_TITLES = mapOf(
    "light" to "Lights",
    "switch" to "Switches",
    "scene" to "Scenes",
    "script" to "Scripts",
    "cover" to "Covers",
    "climate" to "Climate",
)

@Serializable
internal data class HaApiRoot(
    val message: String = "",
)

@Serializable
internal data class HaState(
    @SerialName("entity_id") val entityId: String = "",
    val state: String = "",
    val attributes: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class HaServiceBody(
    @SerialName("entity_id") val entityId: String,
    val temperature: Double? = null,
    @SerialName("hvac_mode") val hvacMode: String? = null,
)

internal data class HaDevice(
    val entityId: String,
    val domain: String,
    val name: String,
    val state: String,
    val statusLabel: String,
    val currentTemperature: Double? = null,
    val targetTemperature: Double? = null,
    val temperatureUnit: String? = null,
    val hvacModes: List<String> = emptyList(),
    val minTemp: Double? = null,
    val maxTemp: Double? = null,
    val tempStep: Double? = null,
)

internal data class HaSection(
    val title: String,
    val devices: List<HaDevice>,
)

internal fun List<HaState>.toDevices(): List<HaDevice> =
    mapNotNull { it.toDevice() }.associateBy { it.entityId }.values.toList()

internal fun domainTitle(domain: String): String = DOMAIN_TITLES[domain] ?: domain

internal fun sections(devices: List<HaDevice>): List<HaSection> =
    DOMAIN_ORDER.mapNotNull { domain ->
        val items = devices.filter { it.domain == domain }.sortedBy { it.name.lowercase() }
        if (items.isEmpty()) null else HaSection(domainTitle(domain), items)
    }

internal fun sanitizeCategory(raw: String?): String? {
    val value = raw?.trim()?.lowercase().orEmpty()
    if (value.isEmpty() || value == "all") return null
    return value.takeIf { it in DOMAIN_ORDER }
}

internal fun sanitizeChosenIds(ids: Set<String>): Set<String> =
    ids.map { it.trim() }.filter { isChosenEntityId(it) }.toSet()

internal fun visibleDevices(
    devices: List<HaDevice>,
    chosenIds: Set<String>,
    category: String?,
): List<HaDevice> {
    val chosen = devices.filter { it.entityId in chosenIds }
    val narrowed = if (category == null) chosen else chosen.filter { it.domain == category }
    return sections(narrowed).flatMap { it.devices }
}

internal fun filterChoices(): List<Pair<String?, String>> =
    listOf(null to "All") + DOMAIN_ORDER.map { domain -> domain to domainTitle(domain) }

internal fun isChosenEntityId(id: String): Boolean {
    if (id.length !in 3..200 || id.any { it.isWhitespace() }) return false
    val dot = id.indexOf('.')
    if (dot <= 0 || dot != id.lastIndexOf('.')) return false
    return id.substring(0, dot) in DOMAIN_ORDER
}

internal fun mergeDevices(existing: List<HaDevice>, updates: List<HaDevice>): List<HaDevice> {
    if (updates.isEmpty()) return existing
    val byId = updates.associateBy { it.entityId }
    val seen = mutableSetOf<String>()
    val merged = existing.map { device ->
        val updated = byId[device.entityId]
        if (updated != null) seen += device.entityId
        updated ?: device
    }
    val added = updates.filter { it.entityId !in seen }
    return merged + added
}

internal fun HaState.toDevice(): HaDevice? {
    val id = entityId.trim()
    val dot = id.indexOf('.')
    if (dot <= 0 || dot == id.lastIndex) return null
    val domain = id.substring(0, dot)
    if (domain !in DOMAIN_ORDER) return null
    val target = attributes.optDouble("temperature")
    val unit = attributes.optString("temperature_unit")
    val name = displayName(id, attributes.optString("friendly_name"))
    return HaDevice(
        entityId = id,
        domain = domain,
        name = name,
        state = state,
        statusLabel = statusLabel(domain, state, target, unit),
        currentTemperature = attributes.optDouble("current_temperature"),
        targetTemperature = target,
        temperatureUnit = unit,
        hvacModes = attributes.optStringList("hvac_modes"),
        minTemp = attributes.optDouble("min_temp"),
        maxTemp = attributes.optDouble("max_temp"),
        tempStep = attributes.optDouble("target_temp_step"),
    )
}

internal fun statusLabel(domain: String, state: String, target: Double?, unit: String?): String {
    if (state == "unavailable") return "Unavailable"
    return when (domain) {
        "scene", "script" -> "Tap to run"
        "climate" -> {
            val mode = wordLabel(state)
            val temp = target?.let { formatTemperature(it, unit) }
            if (temp != null && state != "off" && state != "unknown") "$mode · $temp" else mode
        }
        else -> wordLabel(state)
    }
}

internal fun wordLabel(state: String): String = when (state) {
    "on" -> "On"
    "off" -> "Off"
    "open" -> "Open"
    "closed" -> "Closed"
    "opening" -> "Opening"
    "closing" -> "Closing"
    "heat" -> "Heat"
    "cool" -> "Cool"
    "heat_cool" -> "Heat and cool"
    "auto" -> "Auto"
    "dry" -> "Dry"
    "fan_only" -> "Fan"
    "unavailable" -> "Unavailable"
    "unknown" -> "Unknown"
    "" -> "Unknown"
    else -> state.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

internal fun formatTemperature(value: Double?, unit: String?): String {
    if (value == null) return "Not set"
    val nearest = round(value * 10.0) / 10.0
    val text = if (abs(nearest - nearest.toInt()) < 0.05) {
        nearest.toInt().toString()
    } else {
        val tenths = round(nearest * 10.0).toInt()
        val sign = if (tenths < 0) "-" else ""
        val absolute = abs(tenths)
        "$sign${absolute / 10}.${absolute % 10}"
    }
    return text + temperatureSuffix(unit)
}

internal fun temperatureSuffix(unit: String?): String = when (unit?.trim()) {
    "°C", "C" -> "°C"
    "°F", "F" -> "°F"
    null, "" -> "°"
    else -> unit.trim()
}

internal fun nextTarget(device: HaDevice, direction: Int): Double? {
    if (direction == 0) return device.targetTemperature
    val step = device.tempStep?.takeIf { it > 0.0 } ?: 0.5
    val base = device.targetTemperature ?: device.currentTemperature ?: return null
    var next = base + (step * direction)
    device.minTemp?.let { next = max(next, it) }
    device.maxTemp?.let { next = min(next, it) }
    val snapped = round(next / step) * step
    return round(snapped * 1000.0) / 1000.0
}

internal fun looksLikeHomeAssistant(body: String): Boolean {
    val parsed = runCatching { haJson.decodeFromString(HaApiRoot.serializer(), body) }.getOrNull() ?: return false
    return parsed.message.contains("API running", ignoreCase = true)
}

private fun displayName(entityId: String, friendly: String?): String {
    val trimmed = friendly?.trim().orEmpty()
    if (trimmed.isNotEmpty()) return trimmed
    return entityId.substringAfter('.').replace('_', ' ')
}

private fun JsonObject.optString(key: String): String? {
    val primitive = this[key].asPrimitive() ?: return null
    if (!primitive.isString) return null
    return primitive.content
}

private fun JsonObject.optDouble(key: String): Double? {
    val primitive = this[key].asPrimitive() ?: return null
    return primitive.content.toDoubleOrNull()
}

private fun JsonObject.optStringList(key: String): List<String> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.mapNotNull { item ->
        item.asPrimitive()?.takeIf { it.isString }?.content
    }
}

private fun JsonElement?.asPrimitive(): JsonPrimitive? {
    val element = this ?: return null
    if (element is JsonNull || element !is JsonPrimitive) return null
    return element
}
