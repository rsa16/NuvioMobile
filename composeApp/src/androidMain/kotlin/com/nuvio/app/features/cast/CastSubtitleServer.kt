package com.nuvio.app.features.cast

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

// Chromecast requires subtitles to be served by an URL.
internal class CastSubtitleServer(
    private val tracks: Map<Long, ByteArray>,
    val token: String = randomCastSubtitleToken(),
    private val bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
    val host: String = localCastSubtitleHost(),
) {
    private val executor = Executors.newFixedThreadPool(MAX_WORKERS)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    var port: Int = 0
        private set

    val baseUrl: String
        get() = "http://$host:$port/subtitles/$token"

    fun urlFor(trackId: Long): String = "$baseUrl/$trackId.vtt"

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket(0, BACKLOG, bindAddress)
        serverSocket = socket
        port = socket.localPort
        running = true
        acceptThread = Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    if (!running) return@Thread
                    continue
                }
                runCatching { executor.execute { handle(client) } }
            }
        }, "nuvio-cast-subtitles").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor.shutdownNow()
        acceptThread = null
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = CLIENT_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            // consume all headers, we can't pass them to choromecast anyway
            // we COULD if we implement a custom Cast Reciever, but once again,
            // out of scope for this ticket.
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }

            when (method) {
                "GET", "HEAD" -> {
                    val body = trackBody(path)
                    if (body == null) {
                        writeResponse(output, status = "404 Not Found", body = ByteArray(0), method = method)
                    } else {
                        writeResponse(
                            output,
                            status = "200 OK",
                            body = body,
                            method = method,
                            contentType = "text/vtt; charset=utf-8",
                        )
                    }
                }

                "OPTIONS" -> writeResponse(
                    output,
                    status = "204 No Content",
                    body = ByteArray(0),
                    method = method,
                )

                else -> writeResponse(output, status = "404 Not Found", body = ByteArray(0), method = method)
            }
        }
    }

    private fun trackBody(path: String): ByteArray? {
        val prefix = "/subtitles/$token/"
        if (!path.startsWith(prefix)) return null
        val name = path.removePrefix(prefix)
        if (!name.endsWith(".vtt")) return null
        val idText = name.removeSuffix(".vtt")
        if (idText.isEmpty() || idText.any { it !in '0'..'9' }) return null
        return tracks[idText.toLongOrNull()]
    }

    private fun writeResponse(
        output: OutputStream,
        status: String,
        body: ByteArray,
        method: String,
        contentType: String? = null,
    ) {
        // CORS may or may not be required
        // I couldn't verify when I made the prototype,
        // so I'm adding them here in case.
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Accept-Encoding, Range\r\n")
            append("Access-Control-Max-Age: 86400\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.US_ASCII))
        if (method != "HEAD" && body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val value = input.read()
            if (value == -1) return line.takeIf { it.isNotEmpty() }?.toString()
            if (value == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(value.toChar())
            if (line.length > MAX_LINE_LENGTH) return null
        }
    }

    private companion object {
        const val BACKLOG = 16
        const val MAX_WORKERS = 4
        const val CLIENT_TIMEOUT_MS = 5_000
        const val MAX_LINE_LENGTH = 8_192
    }
}

internal fun randomCastSubtitleToken(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
}

internal fun localCastSubtitleHost(): String {
    return runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull() ?: InetAddress.getLoopbackAddress().hostAddress
}

internal data class PreparedCastSubtitles(
    val server: CastSubtitleServer,
    val sources: List<CastSubtitleSource>,
)

internal const val CAST_SUBTITLE_CONTENT_TYPE = "text/vtt"
