package com.nuvio.app.features.cast

import kotlin.test.Test
import kotlin.test.assertEquals

class CastSourceEligibilityTest {

    @Test
    fun directHttpsSourceIsCastable() {
        assertEquals(
            CastSourceEligibility.Eligible,
            castSourceEligibility(CastSource(url = "https://example.com/movie.mp4")),
        )
    }

    @Test
    fun directHttpLanSourceIsCastable() {
        assertEquals(
            CastSourceEligibility.Eligible,
            castSourceEligibility(CastSource(url = "http://192.168.1.20:8080/movie.mkv?token=abc")),
        )
    }

    @Test
    fun torrentInfoHashIsUnsupported() {
        assertReason(
            CastIneligibilityReason.Torrent,
            CastSource(url = "https://example.com/movie.mp4", torrentInfoHash = "abc123"),
        )
    }

    @Test
    fun magnetAndTorrentUrlsAreUnsupported() {
        assertReason(CastIneligibilityReason.Torrent, CastSource(url = "magnet:?xt=urn:btih:abc123"))
        assertReason(CastIneligibilityReason.Torrent, CastSource(url = "torrent://abc123?index=1"))
    }

    @Test
    fun downloadedLocalFileIsUnsupported() {
        assertReason(CastIneligibilityReason.LocalFile, CastSource(url = "file:/data/user/0/app/files/downloads/movie.mp4"))
        assertReason(CastIneligibilityReason.LocalFile, CastSource(url = "content://com.nuvio.app.fileprovider/movie.mp4"))
    }

    @Test
    fun youtubeSourceIsUnsupported() {
        assertReason(
            CastIneligibilityReason.External,
            CastSource(url = "https://redirector.googlevideo.com/videoplayback?id=1", youTubeId = "dQw4w9WgXcQ"),
        )
    }

    @Test
    fun externalOnlySourceIsUnsupported() {
        assertReason(
            CastIneligibilityReason.External,
            CastSource(url = null, externalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
    }

    @Test
    fun nonHttpSchemeIsUnsupported() {
        assertReason(CastIneligibilityReason.UnsupportedProtocol, CastSource(url = "rtsp://example.com/live"))
        assertReason(CastIneligibilityReason.UnsupportedProtocol, CastSource(url = "ftp://example.com/movie.mp4"))
        assertReason(CastIneligibilityReason.UnsupportedProtocol, CastSource(url = "data:text/plain,hello"))
    }

    @Test
    fun loopbackSourceIsUnsupported() {
        assertReason(CastIneligibilityReason.Loopback, CastSource(url = "http://localhost:8080/movie.mp4"))
        assertReason(CastIneligibilityReason.Loopback, CastSource(url = "http://127.0.0.1:8080/movie.mp4"))
        assertReason(CastIneligibilityReason.Loopback, CastSource(url = "https://127.4.5.6/movie.mp4"))
        assertReason(CastIneligibilityReason.Loopback, CastSource(url = "http://[::1]:8080/movie.mp4"))
    }

    private fun assertReason(expected: CastIneligibilityReason, source: CastSource) {
        assertEquals(
            CastSourceEligibility.Ineligible(expected),
            castSourceEligibility(source),
        )
    }
}
