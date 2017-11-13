package io.ktor.client.backend.apache

import io.ktor.network.util.*
import io.ktor.util.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.io.*
import org.apache.http.*
import org.apache.http.nio.*
import org.apache.http.nio.client.methods.*
import org.apache.http.protocol.*
import java.nio.ByteBuffer

internal class ApacheByteConsumer(
        private val channel: ByteWriteChannel,
        private val block: (HttpResponse) -> Unit
) : AsyncByteConsumer<Unit>() {
    private var lastJob: Job? = null

    override fun buildResult(context: HttpContext) {
        lastJob?.invokeOnCompletion { channel.close() } ?: channel.close()
    }

    override fun onByteReceived(buffer: ByteBuffer, io: IOControl) {
        io.suspendInput()
        val content = buffer.copy()
        if (content.remaining() <= 0) return

        lastJob = launch(ioCoroutineDispatcher) {
            channel.writeFully(content)
            io.requestInput()
        }
    }

    override fun onResponseReceived(response: HttpResponse) = block(response)
}