package com.nuvio.app.features.cast

/**
 * Converts sidecar to WebVTT for Cast Target.
 * We still want support for delay, so those are baked into the cue times.
 * If subtitles are ASS/SSA, return null.
 */
internal fun subtitleTextToWebVtt(text: String, delayMs: Int): String? {
    val normalized = text
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    if (isAssOrSsaSubtitle(normalized)) return null

    val cues = normalized
        .split(Regex("\n{2,}"))
        .mapNotNull { block -> parseSubtitleBlock(block, delayMs) }
    if (cues.isEmpty()) return null

    return buildString {
        append("WEBVTT\n\n")
        cues.forEachIndexed { index, cue ->
            if (index > 0) append("\n\n")
            append(cue)
        }
    }
}

internal fun isAssOrSsaSubtitle(text: String): Boolean {
    val trimmed = text.trimStart()
    return trimmed.startsWith("[Script Info]", ignoreCase = true) ||
        trimmed.startsWith("[Events]", ignoreCase = true) ||
        ASS_DIALOGUE_LINE.containsMatchIn(text)
}

private fun parseSubtitleBlock(block: String, delayMs: Int): String? {
    val lines = block.lines()
    val timestampIndex = lines.indexOfFirst { line ->
        line.trim().isNotEmpty() && parseTimestampLine(line) != null
    }
    if (timestampIndex < 0) return null

    val timestamp = parseTimestampLine(lines[timestampIndex]) ?: return null
    val textLines = lines.drop(timestampIndex + 1)
        .dropWhile { it.isEmpty() }
        .dropLastWhile { it.isEmpty() }
    if (textLines.isEmpty()) return null

    val shiftedStartMs = timestamp.startMs + delayMs
    val shiftedEndMs = timestamp.endMs + delayMs
    if (shiftedEndMs <= 0L || shiftedEndMs <= shiftedStartMs) return null

    val settings = timestamp.settings.trim()
    return buildString {
        append(formatTimestamp(shiftedStartMs.coerceAtLeast(0L)))
        append(" --> ")
        append(formatTimestamp(shiftedEndMs))
        if (settings.isNotEmpty()) {
            append(' ')
            append(settings)
        }
        append('\n')
        append(textLines.joinToString("\n"))
    }
}

private fun parseTimestampLine(line: String): ParsedTimestamp? {
    val match = TIMESTAMP_LINE.matchEntire(line.trim()) ?: return null
    val startMs = timestampMillis(
        hours = match.groupValues[1],
        minutes = match.groupValues[2],
        seconds = match.groupValues[3],
        fraction = match.groupValues[4],
    )
    val endMs = timestampMillis(
        hours = match.groupValues[5],
        minutes = match.groupValues[6],
        seconds = match.groupValues[7],
        fraction = match.groupValues[8],
    )
    return ParsedTimestamp(startMs, endMs, match.groupValues[9])
}

private fun timestampMillis(hours: String, minutes: String, seconds: String, fraction: String): Long {
    val fractionMs = fraction.padEnd(3, '0').take(3).toLong()
    return (hours.toLongOrNull() ?: 0L) * 3_600_000L +
        (minutes.toLongOrNull() ?: 0L) * 60_000L +
        (seconds.toLongOrNull() ?: 0L) * 1_000L +
        fractionMs
}

private fun formatTimestamp(timeMs: Long): String {
    val hours = timeMs / 3_600_000L
    val minutes = (timeMs % 3_600_000L) / 60_000L
    val seconds = (timeMs % 60_000L) / 1_000L
    val millis = timeMs % 1_000L
    return buildString {
        append(hours.toString().padStart(2, '0'))
        append(':')
        append(minutes.toString().padStart(2, '0'))
        append(':')
        append(seconds.toString().padStart(2, '0'))
        append('.')
        append(millis.toString().padStart(3, '0'))
    }
}

private data class ParsedTimestamp(
    val startMs: Long,
    val endMs: Long,
    val settings: String,
)

private val TIMESTAMP_LINE = Regex(
    "^(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})\\s*-->\\s*(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})(.*)$",
)
private val ASS_DIALOGUE_LINE = Regex("(?m)^Dialogue\\s*:")
