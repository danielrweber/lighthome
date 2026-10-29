package com.danielrweber.lighthome

import java.net.URI
import java.net.URISyntaxException

internal data class HaEndpoint(
    val baseUrl: String,
    val label: String,
)

internal sealed interface HaUrlResult {
    data class Ok(val endpoint: HaEndpoint) : HaUrlResult
    data class Invalid(val message: String) : HaUrlResult
}

internal sealed interface TokenResult {
    data class Ok(val token: String) : TokenResult
    data class Invalid(val message: String) : TokenResult
}

private val HOST_PATTERN = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
private val IPV4_PATTERN = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

internal fun parseHaBaseUrl(raw: String): HaUrlResult {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return HaUrlResult.Invalid(HaMessages.URL_EMPTY)

    val withScheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("http://", ignoreCase = true) -> return HaUrlResult.Invalid(HaMessages.URL_HTTP)
        "://" in trimmed -> return HaUrlResult.Invalid(HaMessages.URL_HTTPS)
        else -> "https://$trimmed"
    }

    val uri = try {
        URI(withScheme)
    } catch (_: URISyntaxException) {
        return HaUrlResult.Invalid(HaMessages.URL_BAD)
    }

    if (!uri.scheme.equals("https", ignoreCase = true)) {
        return HaUrlResult.Invalid(HaMessages.URL_HTTPS)
    }
    if (!uri.rawUserInfo.isNullOrEmpty()) {
        return HaUrlResult.Invalid(HaMessages.URL_USERINFO)
    }
    if (!uri.rawQuery.isNullOrEmpty() || uri.rawFragment != null) {
        return HaUrlResult.Invalid(HaMessages.URL_PATH)
    }
    val path = uri.rawPath ?: ""
    if (path.isNotEmpty() && path != "/") {
        return HaUrlResult.Invalid(HaMessages.URL_PATH)
    }

    val host = uri.host?.lowercase() ?: return HaUrlResult.Invalid(HaMessages.URL_BAD)
    if (host.isEmpty() || host.contains(':')) {
        return HaUrlResult.Invalid(HaMessages.URL_IP)
    }
    if (isIpAddress(host) || host.split('.').all { label -> label.isNotEmpty() && label.all { it.isDigit() } }) {
        return HaUrlResult.Invalid(HaMessages.URL_IP)
    }
    if (isLocalName(host)) return HaUrlResult.Invalid(HaMessages.URL_LOCAL)
    if (!host.contains('.') || !HOST_PATTERN.matches(host)) {
        return HaUrlResult.Invalid(HaMessages.URL_HOST)
    }

    val port = uri.port
    if (port == 0 || port > 65535) return HaUrlResult.Invalid(HaMessages.URL_BAD)
    val baseUrl = if (port == -1 || port == 443) "https://$host" else "https://$host:$port"
    val label = if (port == -1 || port == 443) host else "$host:$port"
    return HaUrlResult.Ok(HaEndpoint(baseUrl = baseUrl, label = label))
}

internal fun normalizeToken(raw: String): TokenResult {
    var token = raw.trim()
    if (token.startsWith("Bearer ", ignoreCase = true)) {
        token = token.substring("Bearer ".length).trim()
    }
    if (token.isEmpty()) return TokenResult.Invalid(HaMessages.TOKEN_MISSING)
    if (token.any { it.code !in 33..126 }) return TokenResult.Invalid(HaMessages.TOKEN_CHARS)
    if (token.length < 20) return TokenResult.Invalid(HaMessages.TOKEN_TOO_SHORT)
    if (token.length > 2000) return TokenResult.Invalid(HaMessages.TOKEN_TOO_LONG)
    return TokenResult.Ok(token)
}

internal fun haRequestUrl(endpoint: HaEndpoint, path: String): String {
    require(path.startsWith("/"))
    require("://" !in path && '?' !in path && '#' !in path)
    return endpoint.baseUrl + path
}

private fun isIpAddress(host: String): Boolean {
    val match = IPV4_PATTERN.matchEntire(host) ?: return host.all { it.isDigit() }
    return match.groupValues.drop(1).all { octet ->
        val value = octet.toIntOrNull() ?: return true
        value in 0..255
    }
}

private fun isLocalName(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".localhost")) return true
    val suffixes = listOf(".local", ".localdomain", ".lan", ".home", ".home.arpa", ".internal")
    return suffixes.any { host == it.removePrefix(".") || host.endsWith(it) }
}
