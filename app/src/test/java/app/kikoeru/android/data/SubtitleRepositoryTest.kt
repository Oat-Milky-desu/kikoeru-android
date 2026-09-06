package app.kikoeru.android.data

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SubtitleRepositoryTest {
    @Test fun aiReadsAuthenticatedLrcEnvelope() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"lrcContent":"[00:01.00]AI 字幕"}"""))
            val repo = SubtitleRepository(ServerRepository(Session(Profile(server.url("/prefix/").toString(), "user", true), "secret")))
            val doc = repo.load(SubtitleSource("ai:3", "AI", "lrc", taskId = 3), 5000)
            assertEquals("AI 字幕", doc.cues.single().text)
            val request = server.takeRequest()
            assertEquals("/prefix/api/lyric/translate/lrc?id=3", request.path)
            assertEquals("Bearer secret", request.getHeader("Authorization"))
        }
    }

    @Test fun localDownloadHonorsCharsetAndLimitsBytes() = runBlocking {
        MockWebServer().use { server ->
            val charset = java.nio.charset.Charset.forName("Shift_JIS")
            server.enqueue(MockResponse().setHeader("Content-Type", "text/plain; charset=Shift_JIS")
                .setBody(okio.Buffer().write("[00:00.00]字幕テスト".toByteArray(charset))))
            server.enqueue(MockResponse().setBody("x".repeat(2_000_001)))
            val repo = SubtitleRepository(ServerRepository(Session(Profile(server.url("/").toString(), "user", true), "")))
            val source = SubtitleSource("local:a", "local", "lrc", url = server.url("/api/media/stream/1/2").toString())
            assertEquals("字幕テスト", repo.load(source, 1000).cues.single().text)
            try { repo.load(source, 1000); fail("Expected byte limit") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun missingAiEndpointDoesNotHideLocalSubtitles() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = if (request.path!!.contains("/tracks/"))
                    MockResponse().setHeader("Content-Type", "application/json").setBody("""[{"type":"text","title":"01.lrc","mediaStreamUrl":"/api/media/stream/1/2"}]""")
                else MockResponse().setResponseCode(404)
            }
            val session = Session(Profile(server.url("/").toString(), "user", true), "")
            val repo = SubtitleRepository(ServerRepository(session))
            val track = TrackRef(session.profile.scope, 1, "work", "01.wav", "01.wav", "1/0", "", "")
            val result = repo.catalog(track)
            assertTrue(result.sources.single().automatic)
            assertTrue(result.notices.single().contains("未提供 AI"))
        }
    }

    @Test fun cancelledDownloadDoesNotDeliverOldSubtitles() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[00:00.00]old").setBodyDelay(2, TimeUnit.SECONDS))
            val repo = SubtitleRepository(ServerRepository(Session(Profile(server.url("/").toString(), "user", true), "")))
            var delivered = false
            val job = launch(Dispatchers.Default) {
                repo.load(SubtitleSource("local:a", "local", "lrc", url = server.url("/old").toString()), 1000)
                delivered = true
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertFalse(delivered)
        }
    }
}
