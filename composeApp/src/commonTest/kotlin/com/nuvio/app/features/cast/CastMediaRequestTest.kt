package com.nuvio.app.features.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CastMediaRequestTest {

    @Test
    fun buildsTitleEpisodeLabelArtworkAndStartPosition() {
        val request = buildCastMediaRequest(
            url = "https://example.com/episode.mp4",
            streamType = null,
            title = "Show Name",
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = "Episode title",
            artworkUrl = "https://example.com/poster.jpg",
            startPositionMs = 351_000L,
        )

        assertEquals("https://example.com/episode.mp4", request.url)
        assertEquals("video/mp4", request.mimeType)
        assertEquals("Show Name", request.title)
        assertEquals("S1E2 · Episode title", request.subtitle)
        assertEquals("https://example.com/poster.jpg", request.artworkUrl)
        assertEquals(351_000L, request.startPositionMs)
    }

    @Test
    fun moviesHaveNoEpisodeLabel() {
        val request = buildCastMediaRequest(
            url = "https://example.com/movie.mp4",
            streamType = null,
            title = "Movie",
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            artworkUrl = null,
            startPositionMs = 0L,
        )

        assertNull(request.subtitle)
        assertNull(request.artworkUrl)
    }

    @Test
    fun episodeWithoutATitleKeepsTheEpisodeCode() {
        assertEquals(
            "S3E7",
            castEpisodeLabel(seasonNumber = 3, episodeNumber = 7, episodeTitle = "  "),
        )
    }

    @Test
    fun infersMimeTypeFromStreamTypeBeforeTheUrl() {
        assertEquals(
            "application/x-mpegURL",
            castMediaMimeType("https://example.com/master.m3u8", streamType = "hls"),
        )
        assertEquals(
            "application/dash+xml",
            castMediaMimeType("https://example.com/manifest", streamType = "dash"),
        )
        assertEquals(
            "video/x-matroska",
            castMediaMimeType("https://example.com/play", streamType = "mkv"),
        )
    }

    @Test
    fun infersMimeTypeFromTheUrlPathAndFallsBackToMp4() {
        assertEquals(
            "application/x-mpegURL",
            castMediaMimeType("https://example.com/master.m3u8?token=1", streamType = null),
        )
        assertEquals(
            "video/webm",
            castMediaMimeType("https://example.com/clip.webm", streamType = null),
        )
        assertEquals(
            "video/x-matroska",
            castMediaMimeType("https://example.com/stream?format=mkv", streamType = null),
        )
        assertEquals(
            "video/mp4",
            castMediaMimeType("https://example.com/play", streamType = null),
        )
    }
}
