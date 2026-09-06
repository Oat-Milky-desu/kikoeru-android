package app.kikoeru.android.data

import org.junit.Assert.*
import org.junit.Test

class TrackMatchingTest {
    private val work = Work(123, "测试作品")
    private fun audio(title: String, hash: String) = TrackNode("audio", title, hash, 12.5, "/api/media/stream/$hash")
    @Test fun flattensFoldersWithoutPuttingImagesIntoQueue() {
        val nodes = listOf(TrackNode(type = "folder", title = "日文", children = listOf(audio("01.mp3", "123/2"), TrackNode("image", "cover.jpg"))))
        val tracks = flattenTracks(nodes, work, "server|user", "cover")
        assertEquals(1, tracks.size)
        assertEquals("日文/01.mp3", tracks.single().path)
        assertEquals(12_500L, tracks.single().durationMs)
    }
    @Test fun rescannedIndexIsReplacedUsingPath() {
        val old = flattenTracks(listOf(audio("a.mp3", "123/1")), work, "scope", "").single()
        val fresh = flattenTracks(listOf(audio("0.mp3", "123/1"), audio("a.mp3", "123/2")), work, "scope", "")
        assertEquals("123/2", matchTrack(old, fresh).hash)
    }
    @Test fun missingFileNeverFallsBackToOldIndex() {
        val old = flattenTracks(listOf(audio("a.mp3", "123/1")), work, "scope", "").single()
        val fresh = flattenTracks(listOf(audio("b.mp3", "123/1")), work, "scope", "")
        assertThrows(IllegalStateException::class.java) { matchTrack(old, fresh) }
    }
    @Test fun ambiguousAndOtherAccountMatchesAreRejected() {
        val old = flattenTracks(listOf(audio("a.mp3", "123/1")), work, "scope", "").single()
        assertThrows(IllegalStateException::class.java) { matchTrack(old, listOf(old, old.copy(hash = "123/2"))) }
        assertThrows(IllegalStateException::class.java) { matchTrack(old, listOf(old.copy(scope = "other"))) }
    }
    @Test fun optionalMetadataAndNumericLabelIdDecode() {
        val json = """{"id":123,"title":"作品","circle":{"id":42,"name":"社团"},"tags":null,"vas":[],"unknown":true}"""
        val decoded = AppJson.decodeFromString<Work>(json)
        assertEquals("42", decoded.circle?.id)
        assertTrue(decoded.tags.isEmpty())
    }
    @Test fun serverSubtitlePreservesDirectoriesOnBothPlatforms() {
        val windows = flattenTracks(listOf(audio("01.mp3", "123/1").copy(subtitle = "音频\\本篇")), work, "scope", "").single()
        val linux = flattenTracks(listOf(audio("01.mp3", "123/2").copy(subtitle = "音频/本篇")), work, "scope", "").single()
        assertEquals("音频/本篇/01.mp3", windows.path)
        assertEquals(linux, matchTrack(windows, listOf(linux)))
    }
}
