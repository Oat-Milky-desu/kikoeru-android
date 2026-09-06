package app.kikoeru.android.data

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import okhttp3.*
import java.io.IOException
import java.nio.charset.Charset
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun normalizedSubtitlePath(path: String) = path.replace('\\', '/').removePrefix("./").trim('/')
private val subtitleExtensions = setOf("lrc", "srt", "vtt", "ass", "ssa")

data class SubtitleSource(val id: String, val label: String, val extension: String,
    val url: String? = null, val taskId: Long? = null, val automatic: Boolean = false, val available: Boolean = true)
data class SubtitleCatalog(val sources: List<SubtitleSource>, val notices: List<String>)
@Serializable data class AiSubtitleTask(val id: Long, val work_id: Long, val audio_path: String, val status: Int)
@Serializable data class AiSubtitleTasks(val tasks: List<AiSubtitleTask> = emptyList())
@Serializable private data class AiSubtitleResult(val lrcContent: String)

fun localSubtitleSources(nodes: List<TrackNode>, track: TrackRef, baseUrl: String): List<SubtitleSource> {
    val audioPath = normalizedSubtitlePath(track.path)
    val audioStem = audioPath.substringBeforeLast('.')
    fun visit(nodes: List<TrackNode>, prefix: String): List<SubtitleSource> = nodes.flatMap { node ->
        val path = normalizedSubtitlePath((node.subtitle?.takeIf(String::isNotBlank)?.let { "$it/" } ?: prefix) + node.title)
        val ext = path.substringAfterLast('.', "").lowercase()
        when {
            node.type == "folder" -> visit(node.children, "$path/")
            node.type != "audio" && ext in subtitleExtensions && node.mediaStreamUrl.isNotBlank() -> {
                val stem = path.substringBeforeLast('.')
                listOf(SubtitleSource("local:$path", "本地 · $path", ext, ServerUrls.resolve(baseUrl, node.mediaStreamUrl),
                    automatic = stem == audioPath || stem == audioStem))
            }
            else -> emptyList()
        }
    }
    return visit(nodes, "").distinctBy { it.id }.sortedByDescending { it.automatic }
}

fun aiSubtitleSources(tasks: List<AiSubtitleTask>, track: TrackRef): List<SubtitleSource> = tasks
    .filter { it.work_id == track.workId && normalizedSubtitlePath(it.audio_path) == normalizedSubtitlePath(track.path) }
    .map { task ->
        val status = when (task.status) { 1 -> "等待生成"; 2 -> "正在生成"; 3 -> "已完成"; 4 -> "生成失败"; else -> "不可用" }
        SubtitleSource("ai:${task.id}", "AI 字幕 · $status · #${task.id}", "lrc", taskId = task.id,
            automatic = task.status == 3, available = task.status == 3)
    }

class SubtitleRepository(private val repo: ServerRepository) {
    suspend fun catalog(track: TrackRef): SubtitleCatalog = supervisorScope {
        require(track.scope == repo.session.profile.scope) { "字幕属于其他服务器或账号" }
        val local = async { capture { localSubtitleSources(repo.api.tracks(track.workId), track, repo.session.profile.baseUrl) } }
        val ai = async { capture {
            val url = repo.session.profile.baseUrl + "api/lyric/translate?page=-1&work_id=${track.workId}&status=%5B%5D"
            val tasks = AppJson.decodeFromString<AiSubtitleTasks>(get(url).text())
            aiSubtitleSources(tasks.tasks, track)
        } }
        val l = local.await(); val a = ai.await()
        SubtitleCatalog(l.getOrDefault(emptyList()) + a.getOrDefault(emptyList()), buildList {
            l.exceptionOrNull()?.let { add("本地字幕：${it.friendlyMessage()}") }
            a.exceptionOrNull()?.let { add(if (it is SubtitleHttpException && it.code == 404) "此服务器未提供 AI 字幕接口" else "AI 字幕：${it.friendlyMessage()}") }
        })
    }

    suspend fun load(source: SubtitleSource, durationMs: Long): SubtitleDocument {
        require(source.available) { "该字幕尚未生成完成" }
        val text = if (source.taskId != null) {
            val data = get(repo.session.profile.baseUrl + "api/lyric/translate/lrc?id=${source.taskId}").text()
            AppJson.decodeFromString<AiSubtitleResult>(data).lrcContent
        } else get(requireNotNull(source.url)).text()
        return withContext(Dispatchers.Default) { SubtitleParser.parse(text, source.extension, durationMs) }
    }

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try { Result.success(block()) }
        catch (e: Exception) { if (e is CancellationException) throw e; Result.failure(e) }

    private data class Payload(val bytes: ByteArray, val charset: Charset?) {
        fun text() = SubtitleParser.decode(bytes, charset)
    }
    private suspend fun get(url: String): Payload = suspendCancellableCoroutine { continuation ->
        val call = repo.client.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    if (!it.isSuccessful) throw SubtitleHttpException(it.code)
                    val body = it.body ?: error("服务器返回了空字幕")
                    val source = body.source()
                    source.request(2_000_001)
                    require(source.buffer.size <= 2_000_000) { "字幕文件过大（上限 2 MB）" }
                    Payload(source.readByteArray(), body.contentType()?.charset())
                } }
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }
}

class SubtitleHttpException(val code: Int) : Exception(when (code) {
    401 -> "登录已失效，请重新连接服务器"
    403 -> "当前账号无权读取字幕"
    404 -> "字幕不存在或尚未生成，请刷新列表"
    else -> "字幕请求失败（$code）"
})
