package io.ktor.client.backend.jetty

import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.network.util.*
import io.ktor.util.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.future.*
import kotlinx.coroutines.experimental.io.*
import org.eclipse.jetty.http.*
import org.eclipse.jetty.http2.*
import org.eclipse.jetty.http2.api.*
import org.eclipse.jetty.http2.frames.*
import org.eclipse.jetty.util.*
import java.io.*
import java.nio.channels.*
import java.util.concurrent.*


data class StatusWithHeaders(val statusCode: HttpStatusCode, val headers: Headers)

internal class JettyResponseListener(private val channel: ByteWriteChannel) : Stream.Listener {
    private val headersBuilder: HeadersBuilder = HeadersBuilder(caseInsensitiveKey = true)
    private val onHeadersReceived: CompletableFuture<HttpStatusCode?> = CompletableFuture()

    override fun onPush(stream: Stream, frame: PushPromiseFrame): Stream.Listener {
        stream.reset(ResetFrame(frame.promisedStreamId, ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP)
        return Ignore
    }

    override fun onReset(stream: Stream, frame: ResetFrame) {
        when (frame.error) {
            0 -> channel.close()
            ErrorCode.CANCEL_STREAM_ERROR.code -> channel.close(ClosedChannelException())
            else -> {
                val code = ErrorCode.from(frame.error)
                channel.close(IOException("Connection reset ${code?.name ?: "with unknown error code ${frame.error}"}"))
            }
        }

        onHeadersReceived.complete(null)
    }

    override fun onData(stream: Stream, frame: DataFrame, callback: Callback) {
        val data = frame.data.copy()
        val last = frame.isEndStream
        launch(ioCoroutineDispatcher) {
            try {
                channel.writeFully(data)
                if (last) channel.close()

                callback.succeeded()
            } catch (t: Throwable) {
                callback.failed(t)
            }
        }
    }

    override fun onHeaders(stream: Stream, frame: HeadersFrame) {
        frame.metaData.fields.forEach { field ->
            headersBuilder.append(field.name, field.value)
        }

        if (frame.isEndStream) channel.close()

        onHeadersReceived.complete((frame.metaData as? MetaData.Response)?.let {
            val (status, reason) = it.status to it.reason
            reason?.let { HttpStatusCode(status, reason) } ?: HttpStatusCode.fromValue(status)
        })
    }

    suspend fun awaitHeaders(): StatusWithHeaders {
        onHeadersReceived.await()
        val statusCode = onHeadersReceived.get() ?: throw IOException("Connection reset")
        return StatusWithHeaders(statusCode, headersBuilder.build())
    }

    companion object {
        private val Ignore = Stream.Listener.Adapter()
    }
}
