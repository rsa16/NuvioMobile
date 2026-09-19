package com.nuvio.app.features.downloads

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadLocationModelsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun locationPrefRoundTripsThroughSerialization() {
        val prefs = listOf(
            DownloadLocationPref(
                mode = DownloadLocationMode.ANDROID_SAF,
                value = "content://com.android.externalstorage.documents/tree/primary%3AMovies",
            ),
            DownloadLocationPref(
                mode = DownloadLocationMode.IOS_FOLDER,
                value = "/var/mobile/Documents/nuvio_downloads",
            ),
        )

        prefs.forEach { pref ->
            assertEquals(pref, json.decodeFromString<DownloadLocationPref>(json.encodeToString(pref)))
        }
    }

    @Test
    fun locationModeSerializesByName() {
        assertEquals("\"ANDROID_SAF\"", json.encodeToString(DownloadLocationMode.ANDROID_SAF))
        assertEquals("\"IOS_FOLDER\"", json.encodeToString(DownloadLocationMode.IOS_FOLDER))
    }
}
