package com.nuvio.app.features.cast

internal data class CastSource(
    val url: String?,
    val torrentInfoHash: String? = null,
    val youTubeId: String? = null,
    val externalUrl: String? = null,
)

internal sealed interface CastSourceEligibility {
    data object Eligible : CastSourceEligibility
    data class Ineligible(val reason: CastIneligibilityReason) : CastSourceEligibility
}

internal enum class CastIneligibilityReason {
    Torrent,
    LocalFile,
    External,
    UnsupportedProtocol,
    Loopback,
}

/**
 * A Castable Source is a direct HTTP(S) URL that needs no request headers and is
 * reachable by a receiver on the local network.
 */
internal fun castSourceEligibility(source: CastSource): CastSourceEligibility {
    val url = source.url?.trim().orEmpty()
    val lowerUrl = url.lowercase()

    if (
        !source.torrentInfoHash.isNullOrBlank() ||
        lowerUrl.startsWith("magnet:") ||
        lowerUrl.startsWith("torrent:")
    ) {
        return CastSourceEligibility.Ineligible(CastIneligibilityReason.Torrent)
    }
    if (lowerUrl.startsWith("file:") || lowerUrl.startsWith("content:")) {
        return CastSourceEligibility.Ineligible(CastIneligibilityReason.LocalFile)
    }
    if (!source.youTubeId.isNullOrBlank()) {
        return CastSourceEligibility.Ineligible(CastIneligibilityReason.External)
    }
    if (url.isBlank()) {
        return CastSourceEligibility.Ineligible(
            if (!source.externalUrl.isNullOrBlank()) {
                CastIneligibilityReason.External
            } else {
                CastIneligibilityReason.UnsupportedProtocol
            },
        )
    }

    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") {
        return CastSourceEligibility.Ineligible(CastIneligibilityReason.UnsupportedProtocol)
    }

    val host = httpHost(url)
        ?: return CastSourceEligibility.Ineligible(CastIneligibilityReason.UnsupportedProtocol)
    if (isLoopbackHost(host)) {
        return CastSourceEligibility.Ineligible(CastIneligibilityReason.Loopback)
    }

    return CastSourceEligibility.Eligible
}

private fun httpHost(url: String): String? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return null
    val authority = url.substring(schemeEnd + 3)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    val hostAndPort = authority.substringAfterLast('@')
    if (hostAndPort.startsWith("[")) {
        val end = hostAndPort.indexOf(']')
        return if (end > 0) hostAndPort.substring(1, end) else null
    }
    return hostAndPort.substringBefore(':').trim().takeIf { it.isNotEmpty() }
}

private fun isLoopbackHost(host: String): Boolean {
    val normalized = host.trim().lowercase().removeSurrounding("[", "]")
    return normalized == "localhost" ||
        normalized == "::1" ||
        normalized == "0.0.0.0" ||
        normalized.startsWith("127.")
}
