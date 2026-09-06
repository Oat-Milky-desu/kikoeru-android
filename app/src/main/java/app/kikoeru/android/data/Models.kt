package app.kikoeru.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Tags/circles use numeric IDs, while voice actors can use string IDs. */
object LabelIdSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("LabelId", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String =
        if (decoder is JsonDecoder) decoder.decodeJsonElement().jsonPrimitive.contentOrNull.orEmpty() else decoder.decodeString()
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

@Serializable
data class Label(@Serializable(with = LabelIdSerializer::class) val id: String = "", val name: String = "")

@Serializable
data class Work(
    val id: Long,
    val title: String = "未命名作品",
    val circle: Label? = null,
    val vas: List<Label> = emptyList(),
    val tags: List<Label> = emptyList(),
    val release: String? = null,
    val nsfw: Boolean = false,
    val rating: Int? = null,
    val description: String? = null,
)

@Serializable
data class WorkPage(val works: List<Work> = emptyList(), val pagination: Pagination = Pagination())
@Serializable
data class Pagination(val currentPage: Int = 1, val pageSize: Int = 12, val totalCount: Int = 0)
@Serializable
data class TrackNode(
    val type: String = "other",
    val title: String = "",
    val hash: String = "",
    val duration: Double? = null,
    val mediaStreamUrl: String = "",
    val children: List<TrackNode> = emptyList(),
    val subtitle: String? = null,
)

@Serializable
data class TrackRef(
    val scope: String,
    val workId: Long,
    val workTitle: String,
    val path: String,
    val title: String,
    val hash: String,
    val streamUrl: String,
    val coverUrl: String,
    val durationMs: Long = 0,
) {
    val identity: String get() = "$scope|$workId|$path"
    val directory: String get() = path.substringBeforeLast('/', "")
}

fun flattenTracks(nodes: List<TrackNode>, work: Work, scope: String, cover: String): List<TrackRef> {
    fun visit(items: List<TrackNode>, prefix: String): List<TrackRef> = items.flatMap { node ->
        // Linux servers may return a path in a title, Windows servers build nested children.
        val path = (prefix + node.title.replace('\\', '/')).trim('/')
        when (node.type) {
            "folder" -> visit(node.children, "$path/")
            "audio" -> listOf(TrackRef(scope, work.id, work.title,
                node.subtitle?.takeIf { it.isNotBlank() }?.let { (it.replace('\\', '/').trim('/') + "/" + node.title).trim('/') } ?: path, node.title,
                node.hash, node.mediaStreamUrl, cover,
                node.duration?.takeIf { it.isFinite() && it > 0 }?.times(1000)?.toLong() ?: 0))
            else -> emptyList()
        }
    }
    return visit(nodes, "")
}

/** Never fall back to an old server index: indices change after a rescan. */
fun matchTrack(saved: TrackRef, fresh: List<TrackRef>): TrackRef =
    fresh.filter { it.scope == saved.scope && it.workId == saved.workId && it.path == saved.path }
        .singleOrNull() ?: throw IllegalStateException("文件已变化或无法唯一匹配，请在作品目录中重新选择：${saved.title}")

@Serializable
data class Profile(val baseUrl: String, val username: String, val allowHttp: Boolean = false) {
    val scope: String get() = "$baseUrl|$username"
}

data class Session(val profile: Profile, val token: String)
@Serializable
data class User(val name: String = "", val group: String = "")
@Serializable
data class Me(val user: User = User(), val auth: Boolean = false)
@Serializable
data class LoginBody(val name: String, val password: String)
@Serializable
data class LoginResult(val token: String)

@Serializable
data class SavedQueue(
    val tracks: List<TrackRef> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val speed: Float = 1f,
    val repeatMode: Int = 0,
    val shuffle: Boolean = false,
)

data class BrowseFilter(val field: String, val id: String, val title: String)
data class BrowseQuery(val keyword: String = "", val order: String = "release", val sort: String = "desc", val filter: BrowseFilter? = null)
