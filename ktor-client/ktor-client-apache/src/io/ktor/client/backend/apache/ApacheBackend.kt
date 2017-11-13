package io.ktor.client.backend.apache

import io.ktor.client.*
import io.ktor.client.backend.*
import io.ktor.client.request.HttpRequest
import io.ktor.client.response.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.http.HttpHeaders
import io.ktor.util.*
import kotlinx.coroutines.experimental.io.*
import org.apache.http.HttpResponse
import org.apache.http.client.config.*
import org.apache.http.client.methods.*
import org.apache.http.client.utils.*
import org.apache.http.concurrent.*
import org.apache.http.entity.*
import org.apache.http.impl.nio.client.*
import org.apache.http.nio.client.methods.*
import org.apache.http.nio.protocol.*
import java.lang.*
import java.util.*
import javax.net.ssl.*
import kotlin.coroutines.experimental.*


class ApacheBackend(sslContext: SSLContext?) : HttpClientBackend {
    private val DEFAULT_TIMEOUT: Int = 10_000
    private val backend: CloseableHttpAsyncClient = prepareClient(sslContext).apply { start() }

    suspend override fun makeRequest(request: HttpRequest): HttpResponseBuilder {
        val apacheRequest = convertRequest(request)

        val sendTime = Date()
        val responseChannel = ByteChannel()

        val apacheResponse = suspendRequest(responseChannel) { consumer, callback ->
            backend.execute(HttpAsyncMethods.create(apacheRequest), consumer, callback)
        }

        val receiveTime = Date()
        return convertResponse(apacheResponse).apply {
            requestTime = sendTime
            responseTime = receiveTime
            body = ByteReadChannelBody(responseChannel)
        }
    }

    override fun close() {
        backend.close()
    }

    companion object : HttpClientBackendFactory {
        override operator fun invoke(block: HttpClientBackendConfig.() -> Unit): HttpClientBackend {
            val config = HttpClientBackendConfig().apply(block)
            return ApacheBackend(config.sslContext)
        }
    }

    private fun prepareClient(sslContext: SSLContext?): CloseableHttpAsyncClient {
        val clientBuilder = HttpAsyncClients.custom()
        with(clientBuilder) {
            disableAuthCaching()
            disableConnectionState()
            disableCookieManagement()
        }

        sslContext?.let { clientBuilder.setSSLContext(it) }
        return clientBuilder.build()!!
    }

    private fun convertRequest(request: HttpRequest): HttpUriRequest {
        val builder = RequestBuilder.create(request.method.value)
        with(request) {
            builder.uri = URIBuilder().apply {
                scheme = url.scheme
                host = url.host
                port = url.port
                path = url.path

                // if we have `?` in tail of url we should initialise query parameters
                if (request.url.queryParameters?.isEmpty() == true) setParameters(listOf())
                url.queryParameters?.flattenEntries()?.forEach { (key, value) -> addParameter(key, value) }
            }.build()
        }

        request.headers.entries().forEach { (name, values) ->
            if (HttpHeaders.ContentLength == name) return@forEach
            values.forEach { value -> builder.addHeader(name, value) }
        }

        val body = request.body as HttpMessageBody
        val length = request.contentLength() ?: -1
        val chunked = request.headers[HttpHeaders.TransferEncoding] == "chunked"

        if (body !is EmptyBody) {
            val bodyStream = body.toByteReadChannel().toInputStream()
            builder.entity = InputStreamEntity(bodyStream, length.toLong()).apply { isChunked = chunked }
        }

        builder.config = RequestConfig.custom()
                .setRedirectsEnabled(request.followRedirects)
                .setSocketTimeout(DEFAULT_TIMEOUT)
                .setConnectTimeout(DEFAULT_TIMEOUT)
                .setConnectionRequestTimeout(DEFAULT_TIMEOUT)
                .build()

        return builder.build()
    }

    private fun convertResponse(response: HttpResponse): HttpResponseBuilder {
        val statusLine = response.statusLine
        val builder = HttpResponseBuilder()

        builder.apply {
            status = if (statusLine.reasonPhrase != null) {
                HttpStatusCode(statusLine.statusCode, statusLine.reasonPhrase)
            } else {
                HttpStatusCode.fromValue(statusLine.statusCode)
            }

            headers {
                response.allHeaders.forEach { headerLine ->
                    append(headerLine.name, headerLine.value)
                }
            }

            with(statusLine.protocolVersion) {
                version = HttpProtocolVersion(protocol, major, minor)
            }
        }

        return builder
    }
}

private suspend fun suspendRequest(
        channel: ByteWriteChannel,
        block: (HttpAsyncResponseConsumer<Unit>, FutureCallback<Unit>) -> Unit
): HttpResponse {
    return suspendCoroutine { continuation ->
        val callback = object : FutureCallback<Unit> {
            override fun failed(exception: Exception) {
                channel.close(exception)
                continuation.resumeWithException(exception)
            }

            override fun completed(result: Unit) {}

            override fun cancelled() {}
        }

        block(ApacheByteConsumer(channel) { continuation.resume(it) }, callback)
    }
}
