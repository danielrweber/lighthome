package com.danielrweber.lighthome

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Talks only to the Home Assistant address the user saved.
 * The token is sent as an Authorization header and is never written to a log.
 * Redirects are off so that header stays on the saved host.
 */
internal class HaClient {
    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
                retryOnConnectionFailure(false)
            }
        }
    }

    suspend fun verify(endpoint: HaEndpoint, token: String): HaCallResult<Unit> {
        val response = get(endpoint, token, "/api/") ?: return transportOrUnreadable()
        return when (val failure = failureStatus(response.status.value)) {
            null -> {
                val body = response.safeBody() ?: return HaCallResult.Unreadable
                if (looksLikeHomeAssistant(body)) HaCallResult.Ok(Unit) else HaCallResult.NotHomeAssistant
            }
            else -> failure
        }
    }

    suspend fun states(endpoint: HaEndpoint, token: String): HaCallResult<List<HaState>> {
        val response = get(endpoint, token, "/api/states") ?: return transportOrUnreadable()
        return when (val failure = failureStatus(response.status.value)) {
            null -> decodeStates(response.safeBody())
            else -> failure
        }
    }

    suspend fun call(
        endpoint: HaEndpoint,
        token: String,
        domain: String,
        service: String,
        body: HaServiceBody,
    ): HaCallResult<List<HaState>> {
        val response = post(endpoint, token, "/api/services/$domain/$service", body) ?: return transportOrUnreadable()
        return when (val failure = failureStatus(response.status.value)) {
            null -> decodeStates(response.safeBody())
            else -> failure
        }
    }

    fun close() {
        http.close()
    }

    private suspend fun get(endpoint: HaEndpoint, token: String, path: String): HttpResponse? =
        runHa { http.get(haRequestUrl(endpoint, path)) { haHeaders(token) } }

    private suspend fun post(
        endpoint: HaEndpoint,
        token: String,
        path: String,
        body: HaServiceBody,
    ): HttpResponse? = runHa {
        http.post(haRequestUrl(endpoint, path)) {
            haHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(haJson.encodeToString(HaServiceBody.serializer(), body))
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.haHeaders(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        header(HttpHeaders.UserAgent, "LightHome")
    }

    private var lastTransport: HaCallResult<Nothing> = HaCallResult.Transport(TransportKind.Unreachable)

    private suspend fun runHa(block: suspend () -> HttpResponse): HttpResponse? =
        try {
            block()
        } catch (error: HttpRequestTimeoutException) {
            lastTransport = HaCallResult.Transport(TransportKind.Unreachable)
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastTransport = classify(error)
            null
        }

    private fun transportOrUnreadable(): HaCallResult<Nothing> = lastTransport

    private suspend fun HttpResponse.safeBody(): String? =
        try {
            bodyAsText()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private fun decodeStates(body: String?): HaCallResult<List<HaState>> {
        if (body.isNullOrBlank()) return HaCallResult.Unreadable
        val parsed = runCatching { haJson.decodeFromString(ListSerializer(HaState.serializer()), body) }.getOrNull()
            ?: return HaCallResult.Unreadable
        return HaCallResult.Ok(parsed)
    }

    private fun failureStatus(status: Int): HaCallResult<Nothing>? = when (status) {
        in 200..299 -> null
        301, 302, 303, 307, 308 -> HaCallResult.Redirect(status)
        else -> HaCallResult.Http(status)
    }

    private fun classify(error: Throwable): HaCallResult<Nothing> {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        return when {
            chain.any { it is SSLException } -> HaCallResult.Transport(TransportKind.Tls)
            chain.any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException || it is IOException } ->
                HaCallResult.Transport(TransportKind.Unreachable)
            else -> HaCallResult.Unreadable
        }
    }
}
