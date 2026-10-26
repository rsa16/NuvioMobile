package com.nuvio.app.features.cast

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CastSubtitleServerTest {

    @Test
    fun servesOnlyTheSessionTokenPathAsWebVttWithCors() {
        val body = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello"
        withServer(mapOf(7L to body.toByteArray())) { server ->
            val connection = open(server.urlFor(7L))
            try {
                assertEquals(200, connection.responseCode)
                assertEquals("text/vtt; charset=utf-8", connection.contentType)
                assertEquals("*", connection.getHeaderField("Access-Control-Allow-Origin"))
                assertEquals(body, connection.inputStream.bufferedReader().readText())
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun answersOptionsWithTheDocumentedCorsHeaders() {
        withServer(mapOf(1L to "WEBVTT\n".toByteArray())) { server ->
            val connection = open(server.urlFor(1L)).apply { requestMethod = "OPTIONS" }
            try {
                assertEquals(204, connection.responseCode)
                assertEquals("*", connection.getHeaderField("Access-Control-Allow-Origin"))
                assertEquals("GET, OPTIONS", connection.getHeaderField("Access-Control-Allow-Methods"))
                val allowedHeaders = connection.getHeaderField("Access-Control-Allow-Headers").orEmpty()
                assertTrue(allowedHeaders.contains("Content-Type"), allowedHeaders)
                assertTrue(allowedHeaders.contains("Accept-Encoding"), allowedHeaders)
                assertTrue(allowedHeaders.contains("Range"), allowedHeaders)
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun returns404ForEverythingElseAndExposesNoFiles() {
        withServer(mapOf(1L to "WEBVTT\n".toByteArray())) { server ->
            val wrongToken = server.baseUrl.replace(server.token, "00000000000000000000000000000000")
            val paths = listOf(
                "$wrongToken/1.vtt",
                "${server.baseUrl}/2.vtt",
                "${server.baseUrl}/1.txt",
                "${server.baseUrl}/../etc/passwd",
                "http://${server.host}:${server.port}/etc/passwd",
                "http://${server.host}:${server.port}/",
            )

            paths.forEach { url ->
                val connection = open(url)
                try {
                    assertEquals(404, connection.responseCode, url)
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    @Test
    fun stopClosesTheListener() {
        val server = CastSubtitleServer(
            tracks = mapOf(1L to "WEBVTT\n".toByteArray()),
            bindAddress = InetAddress.getLoopbackAddress(),
            host = "127.0.0.1",
        )
        server.start()
        val url = URL(server.urlFor(1L))
        server.stop()

        assertFailsWith<IOException> { url.openConnection().connect() }
    }

    @Test
    fun everyServerUsesARandomToken() {
        val first = randomCastSubtitleToken()
        val second = randomCastSubtitleToken()

        assertEquals(32, first.length)
        assertTrue(first.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }, first)
        assertNotEquals(first, second)
    }

    private fun withServer(
        tracks: Map<Long, ByteArray>,
        block: (CastSubtitleServer) -> Unit,
    ) {
        val server = CastSubtitleServer(
            tracks = tracks,
            bindAddress = InetAddress.getLoopbackAddress(),
            host = "127.0.0.1",
        )
        server.start()
        try {
            block(server)
        } finally {
            server.stop()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_000
            readTimeout = 2_000
        }
}
