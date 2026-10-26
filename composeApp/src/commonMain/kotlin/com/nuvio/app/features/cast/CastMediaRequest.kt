package com.nuvio.app.features.cast

internal data class CastMediaRequest(
    val url: String,
    val mimeType: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val startPositionMs: Long = 0L,
    val subtitleSources: List<CastSubtitleSource> = emptyList(),
    val activeSubtitleTrackId: Long? = null,
    val subtitleDelayMs: Int = 0,
)

// request factory
internal fun buildCastMediaRequest(
    url: String,
    streamType: String?,
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    artworkUrl: String?,
    startPositionMs: Long,
    subtitleSources: List<CastSubtitleSource> = emptyList(),
    activeSubtitleTrackId: Long? = null,
    subtitleDelayMs: Int = 0,
): CastMediaRequest = CastMediaRequest(
    url = url,
    mimeType = castMediaMimeType(url, streamType),
    title = title,
    subtitle = castEpisodeLabel(seasonNumber, episodeNumber, episodeTitle),
    artworkUrl = artworkUrl?.trim()?.takeIf { it.isNotEmpty() },
    startPositionMs = startPositionMs.coerceAtLeast(0L),
    subtitleSources = subtitleSources,
    activeSubtitleTrackId = activeSubtitleTrackId?.takeIf { id ->
        subtitleSources.any { it.trackId == id }
    },
    subtitleDelayMs = subtitleDelayMs,
)

// TBD: later maybe think about customizing this format?
internal fun castEpisodeLabel(
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
): String? {
    val code = when {
        seasonNumber != null && episodeNumber != null -> "S${seasonNumber}E${episodeNumber}"
        episodeNumber != null -> "E${episodeNumber}"
        else -> null
    } ?: return null
    val trimmedTitle = episodeTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return code
    return "$code · $trimmedTitle"
}

/**
 * MIME type for a Cast Media Request. Prefers the declared stream type, then
 * the URL path, and falls back to MP4 like the rest of the player.
 */
internal fun castMediaMimeType(url: String, streamType: String?): String {
    when (streamType?.trim()?.lowercase()) {
        "hls", "m3u8" -> return MIME_HLS
        "dash", "mpd" -> return MIME_DASH
        "smoothstreaming", "ss" -> return MIME_SMOOTH_STREAMING
        "webm" -> return "video/webm"
        "mkv" -> return "video/x-matroska"
        "ts", "m2ts", "mts" -> return "video/mp2t"
        "mp4", "m4v" -> return "video/mp4"
    }

    val path = url.substringBefore('#')
    val pathPart = path.substringBefore('?').lowercase()
    mimeTypeForExtension(pathPart.substringAfterLast('.', missingDelimiterValue = ""))?.let { return it }

    val query = path.substringAfter('?', missingDelimiterValue = "").lowercase()
    query.split('&').forEach { parameter ->
        val key = parameter.substringBefore('=', missingDelimiterValue = "")
        if (key !in MIME_QUERY_KEYS) return@forEach
        val value = parameter.substringAfter('=', missingDelimiterValue = "")
            .substringAfterLast('/')
            .substringAfterLast('.')
        mimeTypeForExtension(value)?.let { return it }
    }

    return "video/mp4"
}

private fun mimeTypeForExtension(extension: String): String? = when (extension) {
    "m3u8" -> MIME_HLS
    "mpd" -> MIME_DASH
    "ism", "isml" -> MIME_SMOOTH_STREAMING
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "ts", "m2ts", "mts" -> "video/mp2t"
    "mp4", "m4v", "mov" -> "video/mp4"
    else -> null
}

private val MIME_QUERY_KEYS = setOf("format", "mime", "mime_type", "contenttype", "content_type", "type", "ext", "extension", "output")

private const val MIME_HLS = "application/x-mpegURL"
private const val MIME_DASH = "application/dash+xml"
private const val MIME_SMOOTH_STREAMING = "application/vnd.ms-sstr+xml"
