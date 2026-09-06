package app.kikoeru.android.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class SubtitleParserTest {
    @Test fun lrcMultipleStampsBilingualOffsetAndEmptyEndMarker() {
        val doc = SubtitleParser.parse("[offset:500]\n[00:01.25][00:04.000]原文\n[00:01.250]译文\n[00:06.0]\n", "lrc", 10_000)
        assertEquals(listOf(750L, 3500L), doc.cues.map { it.startMs })
        assertEquals("原文\n译文", doc.cues[0].text)
        assertEquals(5500L, doc.cues[1].endMs)
        assertEquals(emptyList<Int>(), doc.activeIndices(5500))
        assertEquals(listOf(0), doc.activeIndices(1000))
    }

    @Test fun timedSubtitlesHandleOverlapGapsAndBackwardSeek() {
        val doc = SubtitleParser.parse("1\n00:00:01,000 --> 00:00:04,000\n<i>第一句</i>\n第二行\n\n2\n00:00:02,500 --> 00:00:03,000\n重叠\n\n3\n00:00:05,000 --> 00:00:06,000\n结束", "srt")
        assertEquals(listOf(0, 1), doc.activeIndices(2500))
        assertEquals(listOf(0), doc.activeIndices(3500))
        assertEquals(emptyList<Int>(), doc.activeIndices(4500))
        assertEquals(listOf(2), doc.activeIndices(5000))
        assertEquals(listOf(0), doc.activeIndices(1000))
        assertEquals("第一句\n第二行", doc.cues[0].text)
    }

    @Test fun positiveUserDelayShowsLater() {
        val doc = SubtitleParser.parse("[00:01.00]字幕\n[00:02.00]", "lrc")
        assertTrue(doc.activeIndices(1100, 500).isEmpty())
        assertEquals(listOf(0), doc.activeIndices(1500, 500))
        assertEquals(listOf(0), doc.activeIndices(500, -500))
        assertTrue(doc.activeIndices(2500, 500).isEmpty())
    }

    @Test fun webVttIdentifiersSettingsCommentsAndEntities() {
        val doc = SubtitleParser.parse("\uFEFFWEBVTT\n\nNOTE ignore this\n\ncue-1\n00:01.200 --> 00:02.500 align:start\n<v Speaker>Hello &amp; world</v>", "vtt")
        assertEquals(1200L, doc.cues.single().startMs)
        assertEquals("Hello & world", doc.cues.single().text)
    }

    @Test fun assKeepsDialogueCommasAndLineBreaks() {
        val doc = SubtitleParser.parse("[Script Info]\nTitle: Example\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.25,0:00:03.00,Default,,0,0,0,,{\\i1}Hello, world\\N第二行", "ass")
        assertEquals(1250L, doc.cues.single().startMs)
        assertEquals("Hello, world\n第二行", doc.cues.single().text)
    }

    @Test fun honorsBomAndServerCharset() {
        val text = "字幕テスト"
        assertEquals(text, SubtitleParser.decode(("\uFEFF" + text).toByteArray(Charsets.UTF_16LE), Charsets.UTF_8))
        val shiftJis = Charset.forName("Shift_JIS")
        assertEquals(text, SubtitleParser.decode(text.toByteArray(shiftJis), shiftJis))
    }

    @Test(expected = IllegalStateException::class) fun invalidEncodingIsNotSilentlyGarbled() {
        SubtitleParser.decode(byteArrayOf(0xff.toByte(), 0x61))
    }

    @Test(expected = IllegalArgumentException::class) fun malformedTimesDoNotCreateCues() {
        SubtitleParser.parse("1\n00:99:01,000 --> 00:99:02,000\nwrong\n\n2\n00:00:05,000 --> 00:00:02,000\nreversed", "srt")
    }

    @Test fun rootAndNestedLocalPathsDoNotConfuseDuplicateNames() {
        val track = TrackRef("scope", 1, "work", "mp3/01.wav", "01.wav", "1/5", "", "")
        fun node(name: String) = TrackNode(type = "text", title = name, mediaStreamUrl = "/api/media/stream/1/6")
        val nodes = listOf(node("01.lrc"), TrackNode(type = "folder", title = "mp3", children = listOf(node("01.wav.SRT"), node("01.zh.vtt"))))
        val sources = localSubtitleSources(nodes, track, "https://example.test/prefix/")
        assertEquals(listOf("local:mp3/01.wav.SRT"), sources.filter { it.automatic }.map { it.id })
        assertEquals(3, sources.size)
        assertEquals("https://example.test/prefix/api/media/stream/1/6", sources.first().url)
    }

    @Test fun aiMatchesExactWorkAndNormalizedPathAndRequiresSuccess() {
        val track = TrackRef("scope", 1, "work", "mp3/01.wav", "01.wav", "1/5", "", "")
        val sources = aiSubtitleSources(listOf(
            AiSubtitleTask(1, 1, "mp3\\01.wav", 3), AiSubtitleTask(2, 1, "mp3/01.wav", 2),
            AiSubtitleTask(3, 2, "mp3/01.wav", 3), AiSubtitleTask(4, 1, "wav/01.wav", 3)), track)
        assertEquals(listOf("ai:1", "ai:2"), sources.map { it.id })
        assertTrue(sources[0].automatic)
        assertFalse(sources[1].available)
    }
}
