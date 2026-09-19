package com.nuvio.app.features.downloads

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadLocationMode {
    ANDROID_SAF,
    IOS_FOLDER,
}

@Serializable
data class DownloadLocationPref(
    val mode: DownloadLocationMode,
    val value: String,
)