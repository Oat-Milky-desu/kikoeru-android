package app.kikoeru.android.data

import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.QueryMap
import java.util.concurrent.TimeUnit

val AppJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

object ServerUrls {
    fun normalize(input: String, allowHttp: Boolean): String {
        val url = input.trim().toHttpUrlOrNull() ?: error("请输入完整的 http:// 或 https:// 服务器地址")
        require(url.username.isEmpty() && url.password.isEmpty()) { "请在账号输入框填写凭据" }
        require(url.query == null && url.fragment == null) { "服务器地址不能带查询参数或片段" }
        require(url.isHttps || allowHttp) { "此地址使用 HTTP，请先开启“允许此服务器使用 HTTP”" }
        val clean = url.encodedPath.trimEnd('/').removeSuffix("/api")
        return url.newBuilder().encodedPath("$clean/").build().toString()
    }

    fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port

    fun resolve(base: String, raw: String): String {
        val root = base.toHttpUrl()
        val value = raw.replace('\\', '/')
        // Backend emits /api/... even when hosted under a reverse-proxy prefix.
        val relative = if (value.startsWith("/api/")) value.removePrefix("/") else value
        val resolved = root.resolve(relative) ?: error("无效的媒体地址")
        require(resolved.username.isEmpty() && resolved.password.isEmpty()) { "不支持带内嵌凭据的媒体地址" }
        return resolved.toString()
    }
}

/** Network interceptor runs again on redirects, so credentials are never forwarded across origins. */
class SessionInterceptor(private val session: Session) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val base = session.profile.baseUrl.toHttpUrl()
        val url = chain.request().url
        if (!url.isHttps && !(session.profile.allowHttp && url.host == base.host && url.port == base.port)) {
            throw IOException("已阻止未经允许的 HTTP 媒体地址，请检查服务器代理设置")
        }
        val request = chain.request().newBuilder().removeHeader("Authorization")
        if (session.token.isNotBlank() && ServerUrls.sameOrigin(base, url) && url.encodedPath.startsWith(base.encodedPath)) {
            request.header("Authorization", "Bearer ${session.token}")
        }
        return chain.proceed(request.build())
    }
}

fun clientFor(session: Session): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .addNetworkInterceptor(SessionInterceptor(session)).build()

interface KikoeruApi {
    @GET("auth/me") suspend fun me(): Me
    @POST("auth/me") suspend fun login(@Body body: LoginBody): LoginResult
    @GET("works") suspend fun works(@QueryMap params: Map<String, String>): WorkPage
    @GET("search") suspend fun search(@QueryMap params: Map<String, String>): WorkPage
    @GET("{field}/{id}/works") suspend fun filtered(@Path("field") field: String, @Path("id") id: String, @QueryMap params: Map<String, String>): WorkPage
    @GET("work/{id}") suspend fun work(@Path("id") id: Long): Work
    @GET("tracks/{id}") suspend fun tracks(@Path("id") id: Long): List<TrackNode>
}

class ServerRepository(val session: Session) {
    val client = clientFor(session)
    val api: KikoeruApi = Retrofit.Builder().baseUrl(session.profile.baseUrl + "api/")
        .client(client).addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
        .build().create(KikoeruApi::class.java)

    fun cover(id: Long) = session.profile.baseUrl + "api/cover/$id?type=main"

    suspend fun browse(query: BrowseQuery, page: Int): WorkPage {
        val params = mapOf("page" to page.toString(), "order" to query.order, "sort" to query.sort)
        return when {
            query.keyword.isNotBlank() -> api.search(params + ("keyword" to query.keyword.trim()))
            query.filter != null -> api.filtered(query.filter.field, query.filter.id, params)
            else -> api.works(params)
        }
    }

    suspend fun audio(work: Work): List<TrackRef> = flattenTracks(api.tracks(work.id), work, session.profile.scope, cover(work.id))
        .map { it.copy(streamUrl = ServerUrls.resolve(session.profile.baseUrl, it.streamUrl)) }

    suspend fun refreshTracks(saved: List<TrackRef>): List<TrackRef> {
        require(saved.all { it.scope == session.profile.scope }) { "播放记录属于其他服务器或账号" }
        val fresh = saved.distinctBy { it.workId }.associate { it.workId to audio(api.work(it.workId)) }
        return saved.map { matchTrack(it, fresh.getValue(it.workId)) }
    }
}

fun Throwable.friendlyMessage(): String = when (this) {
    is CancellationException -> throw this
    is HttpException -> when (code()) {
        401 -> "登录已失效或需要账号认证，请在设置中重新连接"
        403 -> "当前账号没有访问权限"
        404 -> "找不到接口或文件，请检查服务器地址与版本"
        422 -> "账号信息不符合服务器要求，请检查输入"
        else -> "服务器请求失败（${code()}），请稍后重试"
    }
    is SocketTimeoutException -> "连接超时，请检查服务器和网络"
    is SSLException -> "HTTPS 证书验证失败，请检查服务器证书"
    is SerializationException -> "服务器返回格式不兼容，请确认地址指向 Kikoeru 服务"
    is IOException -> "网络连接失败，请检查地址、网络或媒体代理"
    else -> message ?: "操作失败，请重试"
}
