package app.kikoeru.android.data

import org.junit.Assert.*
import org.junit.Test

class ServerUrlsTest {
    @Test fun normalizesServerWithProxyPrefix() {
        assertEquals("https://example.com/kikoeru/", ServerUrls.normalize(" https://example.com/kikoeru/api/ ", false))
        assertEquals("https://example.com/", ServerUrls.normalize("https://example.com", false))
    }
    @Test fun preservesProxyPrefixForApiLinks() {
        assertEquals("https://example.com/kikoeru/api/media/stream/12/1", ServerUrls.resolve("https://example.com/kikoeru/", "/api/media/stream/12/1"))
    }
    @Test fun preservesAbsoluteOffloadAndRootPaths() {
        assertEquals("https://cdn.example.org/a.flac", ServerUrls.resolve("https://example.com/k/", "https://cdn.example.org/a.flac"))
        assertEquals("https://example.com/media/stream/a.flac", ServerUrls.resolve("https://example.com/k/", "/media/stream/a.flac"))
    }
    @Test fun encodesNonAsciiAndSpaces() {
        val url = ServerUrls.resolve("https://example.com/", "media/雨 音.mp3")
        assertFalse(url.contains(" "))
        assertTrue(url.endsWith("%E9%9B%A8%20%E9%9F%B3.mp3"))
    }
    @Test fun httpRequiresExplicitOptIn() {
        assertThrows(IllegalArgumentException::class.java) { ServerUrls.normalize("http://192.168.1.3:8888", false) }
        assertEquals("http://192.168.1.3:8888/", ServerUrls.normalize("http://192.168.1.3:8888", true))
    }
    @Test fun rejectsEmbeddedCredentialsAndQuery() {
        assertThrows(IllegalArgumentException::class.java) { ServerUrls.normalize("https://u:p@example.com", false) }
        assertThrows(IllegalArgumentException::class.java) { ServerUrls.normalize("https://example.com?token=secret", false) }
    }
}
