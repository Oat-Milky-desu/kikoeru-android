package app.kikoeru.android.data

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NetworkTest {
    @Test fun sendsBearerOnAccountRequestsAndPreservesRange() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("audio"))
            val session = Session(Profile(server.url("/").toString(), "alice", true), "local-test-token")
            clientFor(session).newCall(Request.Builder().url(server.url("/api/media/stream/1/0")).header("Range", "bytes=123-").build()).execute().use { assertTrue(it.isSuccessful) }
            val request = server.takeRequest()
            assertEquals("Bearer local-test-token", request.getHeader("Authorization"))
            assertEquals("bytes=123-", request.getHeader("Range"))
        }
    }
    @Test fun stripsTokenOnCrossOriginRedirect() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { api -> MockWebServer().use { cdn ->
            api.useHttps(serverTls.sslSocketFactory(), false); cdn.useHttps(serverTls.sslSocketFactory(), false)
            val apiRoot = api.url("/").newBuilder().host("localhost").build()
            val cdnAudio = cdn.url("/audio.mp3").newBuilder().host("localhost").build()
            api.enqueue(MockResponse().setResponseCode(302).addHeader("Location", cdnAudio))
            cdn.enqueue(MockResponse().setBody("audio"))
            val session = Session(Profile(apiRoot.toString(), "alice"), "private-token")
            val client = clientFor(session).newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
            client.newCall(Request.Builder().url(apiRoot.resolve("api/media/stream/1/0")!!).build()).execute().close()
            assertEquals("Bearer private-token", api.takeRequest().getHeader("Authorization"))
            assertNull(cdn.takeRequest().getHeader("Authorization"))
        } }
    }
    @Test fun httpRedirectToDifferentPortIsRejected() {
        MockWebServer().use { first -> MockWebServer().use { other ->
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/media")))
            val client = clientFor(Session(Profile(first.url("/").toString(), "alice", true), "token"))
            assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(first.url("/api/media")).build()).execute().close() }
            assertEquals(0, other.requestCount)
        } }
    }
    @Test fun accountTokenNotAddedOutsideProxyPrefix() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val client = clientFor(Session(Profile(server.url("/kikoeru/").toString(), "alice", true), "token"))
            client.newCall(Request.Builder().url(server.url("/unrelated")).header("Authorization", "Bearer stale").build()).execute().close()
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun browseUsesSearchEndpointAndServerPagination() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"works":[{"id":1,"title":"雨音"}],"pagination":{"currentPage":2,"pageSize":12,"totalCount":13}}"""))
            val repo = ServerRepository(Session(Profile(server.url("/").toString(), "admin", true), ""))
            val response = repo.browse(BrowseQuery(keyword = "雨 音"), 2)
            assertEquals(13, response.pagination.totalCount)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/api/search", url.encodedPath)
            assertEquals("雨 音", url.queryParameter("keyword"))
            assertEquals("2", url.queryParameter("page"))
        }
    }
}
