package com.nuvio.app.features.cast

internal data class CastSubtitleSource(
    val trackId: Long,
    val url: String,
    val language: String,
    val label: String,
    val headers: Map<String, String> = emptyMap(),
)

internal fun isCastSubtitleSupported(url: String): Boolean {
    val path = url.substringBefore('#').substringBefore('?')
    return when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "ass", "ssa" -> false
        else -> true
    }
}
