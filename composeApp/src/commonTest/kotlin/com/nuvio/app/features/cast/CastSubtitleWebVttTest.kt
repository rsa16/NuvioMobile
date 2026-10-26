package com.nuvio.app.features.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastSubtitleWebVttTest {

    @Test
    fun convertsSrtTimestampsAndDropsSequenceNumbers() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,500
            Hello <i>world</i>

            2
            00:01:03,250 --> 00:01:05,000
            Second line
            stays on two lines
        """.trimIndent()

        val vtt = subtitleTextToWebVtt(srt, delayMs = 0)

        assertEquals(
            """
                WEBVTT

                00:00:01.000 --> 00:00:02.500
                Hello <i>world</i>

                00:01:03.250 --> 00:01:05.000
                Second line
                stays on two lines
            """.trimIndent(),
            vtt,
        )
    }

    @Test
    fun appliesPositiveDelayToBothEdgesAndClampsAtZero() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,000
            Delayed
        """.trimIndent()

        val delayed = subtitleTextToWebVtt(srt, delayMs = 1_500)
        assertTrue(delayed!!.contains("00:00:02.500 --> 00:00:04.500"), delayed)

        val negative = """
            1
            00:00:01,000 --> 00:00:02,000
            Earlier
        """.trimIndent()
        val advanced = subtitleTextToWebVtt(negative, delayMs = -1_500)
        assertTrue(advanced!!.contains("00:00:00.000 --> 00:00:00.500"), advanced)
    }

    @Test
    fun stripsBomAndNormalizesWindowsLineEndings() {
        val srt = "\uFEFF1\r\n00:00:00,500 --> 00:00:01,000\r\nLine\r\n"

        val vtt = subtitleTextToWebVtt(srt, delayMs = 0)

        assertEquals(
            "WEBVTT\n\n00:00:00.500 --> 00:00:01.000\nLine",
            vtt,
        )
    }

    @Test
    fun alreadyVttUsesTheSamePathAndKeepsCueSettings() {
        val vtt = """
            WEBVTT

            intro
            00:00:02.000 --> 00:00:04.000 line:90% align:center
            Existing cue
        """.trimIndent()

        val converted = subtitleTextToWebVtt(vtt, delayMs = 500)

        assertEquals(
            """
                WEBVTT

                00:00:02.500 --> 00:00:04.500 line:90% align:center
                Existing cue
            """.trimIndent(),
            converted,
        )
    }

    @Test
    fun assAndSsaAreRejected() {
        assertNull(
            subtitleTextToWebVtt(
                """
                    [Script Info]
                    ScriptType: v4.00+

                    [Events]
                    Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello
                """.trimIndent(),
                delayMs = 0,
            ),
        )
        assertTrue(isAssOrSsaSubtitle("Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello"))
    }

    @Test
    fun skipsCuesWithoutATimestamp() {
        val srt = """
            1
            just text

            2
            00:00:05,000 --> 00:00:06,000
            Kept
        """.trimIndent()

        val vtt = subtitleTextToWebVtt(srt, delayMs = 0)

        assertEquals("WEBVTT\n\n00:00:05.000 --> 00:00:06.000\nKept", vtt)
    }
}
