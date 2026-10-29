package com.danielrweber.lighthome

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class HaEndpointTest {
    @Test
    fun acceptsBareHostnameAsHttps() {
        val parsed = parseHaBaseUrl("  Home.Example.com/ ")
        val ok = assertIs<HaUrlResult.Ok>(parsed)
        assertEquals("https://home.example.com", ok.endpoint.baseUrl)
        assertEquals("home.example.com", ok.endpoint.label)
    }

    @Test
    fun keepsNonDefaultPort() {
        val parsed = parseHaBaseUrl("https://home.example.com:8443")
        val ok = assertIs<HaUrlResult.Ok>(parsed)
        assertEquals("https://home.example.com:8443", ok.endpoint.baseUrl)
        assertEquals("home.example.com:8443", ok.endpoint.label)
    }

    @Test
    fun dropsDefaultHttpsPort() {
        val parsed = parseHaBaseUrl("https://home.example.com:443")
        val ok = assertIs<HaUrlResult.Ok>(parsed)
        assertEquals("https://home.example.com", ok.endpoint.baseUrl)
    }

    @Test
    fun rejectsCleartextIpLocalNamesAndExtraPath() {
        assertEquals(HaMessages.URL_HTTP, (parseHaBaseUrl("http://home.example.com") as HaUrlResult.Invalid).message)
        assertEquals(HaMessages.URL_IP, (parseHaBaseUrl("https://192.168.1.20") as HaUrlResult.Invalid).message)
        assertEquals(HaMessages.URL_IP, (parseHaBaseUrl("https://10.0.0.5") as HaUrlResult.Invalid).message)
        assertEquals(HaMessages.URL_IP, (parseHaBaseUrl("https://127.0.0.1") as HaUrlResult.Invalid).message)
        assertEquals(HaMessages.URL_LOCAL, (parseHaBaseUrl("https://ha.local") as HaUrlResult.Invalid).message)
        assertEquals(HaMessages.URL_LOCAL, (parseHaBaseUrl("https://localhost") as HaUrlResult.Invalid).message)
        assertEquals(HaMessages.URL_PATH, (parseHaBaseUrl("https://home.example.com/api/") as HaUrlResult.Invalid).message)
        assertEquals(HaMessages.URL_PATH, (parseHaBaseUrl("https://home.example.com?token=abc") as HaUrlResult.Invalid).message)
        assertEquals(HaMessages.URL_USERINFO, (parseHaBaseUrl("https://user:secret@home.example.com") as HaUrlResult.Invalid).message)
        assertEquals(HaMessages.URL_HTTPS, (parseHaBaseUrl("ftp://home.example.com") as HaUrlResult.Invalid).message)
        assertEquals(HaMessages.URL_EMPTY, (parseHaBaseUrl("   ") as HaUrlResult.Invalid).message)
    }

    @Test
    fun requestUrlStaysOnTheSavedHost() {
        val endpoint = HaEndpoint("https://home.example.com", "home.example.com")
        assertEquals("https://home.example.com/api/", haRequestUrl(endpoint, "/api/"))
        assertEquals("https://home.example.com/api/states", haRequestUrl(endpoint, "/api/states"))
    }

    @Test
    fun tokenNormalizationStripsBearerAndRejectsJunk() {
        val token = "example-token-value-0001"
        val parsed = normalizeToken("  Bearer $token  ")
        assertEquals(token, assertIs<TokenResult.Ok>(parsed).token)
        assertIs<TokenResult.Invalid>(normalizeToken("short"))
        assertIs<TokenResult.Invalid>(normalizeToken("example token value 0001"))
        assertIs<TokenResult.Invalid>(normalizeToken(""))
    }

    @Test
    fun scannedCodeIsTheRawHomeAssistantToken() {
        val token = "ab".repeat(64)
        assertEquals(128, token.length)
        assertEquals(token, assertIs<TokenResult.Ok>(tokenFromScan(token)).token)
        assertEquals(token, assertIs<TokenResult.Ok>(tokenFromScan("  $token\n")).token)
        assertEquals(
            HaMessages.SCAN_NOT_TOKEN,
            assertIs<TokenResult.Invalid>(tokenFromScan("https://home.example.com/profile")).message,
        )
        assertIs<TokenResult.Invalid>(tokenFromScan("short"))
    }
}

class HaCatalogTest {
    @Test
    fun keepsTheShortListAndReadsClimateAttributes() {
        val devices = haJson.decodeFromString(ListSerializer(HaState.serializer()), SAMPLE_STATES).toDevices()
        assertEquals(listOf("light.kitchen", "switch.porch", "scene.movie", "script.bedtime", "cover.shade", "climate.hall"), devices.map { it.entityId })
        val climate = devices.first { it.entityId == "climate.hall" }
        assertEquals("Hall", climate.name)
        assertEquals(21.0, climate.targetTemperature)
        assertEquals(20.5, climate.currentTemperature)
        assertEquals(listOf("off", "heat", "cool"), climate.hvacModes)
        assertEquals("Heat · 21°C", climate.statusLabel)
        assertEquals("Tap to run", devices.first { it.domain == "scene" }.statusLabel)
        assertEquals(listOf("Lights", "Switches", "Scenes", "Scripts", "Covers", "Climate"), sections(devices).map { it.title })
    }

    @Test
    fun mergeReplacesMatchingEntities() {
        val original = haJson.decodeFromString(ListSerializer(HaState.serializer()), SAMPLE_STATES).toDevices()
        val update = HaState(
            entityId = "light.kitchen",
            state = "off",
            attributes = JsonObject(mapOf("friendly_name" to JsonPrimitive("Kitchen"))),
        ).toDevice()!!
        val merged = mergeDevices(original, listOf(update))
        assertEquals("Off", merged.first { it.entityId == "light.kitchen" }.statusLabel)
        assertEquals(original.size, merged.size)
    }

    @Test
    fun climateStepRespectsBounds() {
        val device = HaDevice(
            entityId = "climate.hall",
            domain = "climate",
            name = "Hall",
            state = "heat",
            statusLabel = "Heat",
            currentTemperature = 20.0,
            targetTemperature = 20.0,
            temperatureUnit = "°C",
            minTemp = 19.0,
            maxTemp = 21.0,
            tempStep = 0.5,
        )
        assertEquals(20.5, nextTarget(device, 1))
        assertEquals(19.5, nextTarget(device, -1))
        assertEquals(19.0, nextTarget(device.copy(targetTemperature = 19.0), -1))
        assertEquals(21.0, nextTarget(device.copy(targetTemperature = 21.0), 1))
        assertEquals(null, nextTarget(device.copy(targetTemperature = null, currentTemperature = null), 1))
    }

    @Test
    fun serviceBodyOmitsUnusedFields() {
        val toggle = haJson.encodeToString(HaServiceBody.serializer(), HaServiceBody(entityId = "light.kitchen"))
        assertEquals("""{"entity_id":"light.kitchen"}""", toggle)
        val temperature = haJson.encodeToString(HaServiceBody.serializer(), HaServiceBody(entityId = "climate.hall", temperature = 21.0))
        assertTrue("temperature" in temperature)
        assertTrue("hvac_mode" !in temperature)
        assertTrue("null" !in temperature)
        val mode = haJson.encodeToString(HaServiceBody.serializer(), HaServiceBody(entityId = "climate.hall", hvacMode = "heat"))
        assertTrue(""""hvac_mode":"heat"""" in mode)
        assertTrue("temperature" !in mode)
    }

    @Test
    fun recognizesTheApiRoot() {
        assertTrue(looksLikeHomeAssistant("""{"message":"API running."}"""))
        assertTrue(!looksLikeHomeAssistant("""{"ok":true}"""))
    }
}

class HaMessagesTest {
    @Test
    fun failuresTellTheUserWhatToDo() {
        assertEquals(HaMessages.TOKEN_REJECTED, messageFor(HaCallResult.Http(401)))
        assertEquals(HaMessages.TOKEN_REJECTED, messageFor(HaCallResult.Http(403)))
        assertEquals(HaMessages.NOT_FOUND, messageFor(HaCallResult.Http(404)))
        assertEquals(HaMessages.REDIRECT, messageFor(HaCallResult.Redirect(302)))
        assertEquals(HaMessages.UNREACHABLE, messageFor(HaCallResult.Transport(TransportKind.Unreachable)))
        assertEquals(HaMessages.TLS, messageFor(HaCallResult.Transport(TransportKind.Tls)))
        assertEquals(HaMessages.NOT_HA, messageFor(HaCallResult.NotHomeAssistant))
        assertTrue("401" in messageFor(HaCallResult.Http(500)).let { messageFor(HaCallResult.Http(500)) } || "500" in messageFor(HaCallResult.Http(500)))
    }

    @Test
    fun copyDoesNotContainACredential() {
        val copy = listOf(
            HaMessages.CALLS_ONLY,
            HaMessages.TOKEN_STAYS,
            HaMessages.TOKEN_REJECTED,
            HaMessages.UNREACHABLE,
            HaMessages.URL_USERINFO,
            HaMessages.ADDRESS_PLACEHOLDER,
        ).joinToString("\n")
        assertTrue("Bearer " !in copy)
        assertTrue("eyJ" !in copy)
        assertTrue("192.168." !in copy)
    }
}

private const val SAMPLE_STATES = """
[
  {"entity_id":"light.kitchen","state":"on","attributes":{"friendly_name":"Kitchen","brightness":80}},
  {"entity_id":"sensor.temperature","state":"20","attributes":{"friendly_name":"Temperature"}},
  {"entity_id":"switch.porch","state":"off","attributes":{"friendly_name":"Porch"}},
  {"entity_id":"scene.movie","state":"2020-01-01T00:00:00","attributes":{"friendly_name":"Movie"}},
  {"entity_id":"script.bedtime","state":"off","attributes":{"friendly_name":"Bedtime"}},
  {"entity_id":"cover.shade","state":"open","attributes":{"friendly_name":"Shade"}},
  {"entity_id":"climate.hall","state":"heat","attributes":{"friendly_name":"Hall","temperature":21,"current_temperature":20.5,"temperature_unit":"°C","hvac_modes":["off","heat","cool"],"min_temp":10,"max_temp":30,"target_temp_step":0.5,"extra":{"nested":true}}}
]
"""
