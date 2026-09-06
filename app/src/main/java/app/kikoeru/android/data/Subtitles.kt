package app.kikoeru.android.data

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

class SubtitleDocument(val cues: List<SubtitleCue>) {
    private val maxEnds = LongArray(cues.size).also { ends ->
        cues.forEachIndexed { i, cue -> ends[i] = maxOf(cue.endMs, if (i > 0) ends[i - 1] else 0) }
    }

    fun activeIndices(positionMs: Long, delayMs: Long = 0): List<Int> {
        val time = positionMs - delayMs
        var low = 0; var high = cues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= time) low = mid + 1 else high = mid
        }
        val active = mutableListOf<Int>()
        var i = low - 1
        while (i >= 0 && maxEnds[i] > time) {
            if (cues[i].endMs > time) active.add(i)
            i--
        }
        return active.asReversed()
    }
}

object SubtitleParser {
    private val lrcTime = Regex("\\[(\\d+):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val cueTime = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})")
    private fun fraction(s: String) = s.padEnd(3, '0').take(3).toLong()

    fun decode(bytes: ByteArray, charset: Charset? = null): String {
        val encoding = when {
            bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> Charsets.UTF_8
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> charset ?: Charsets.UTF_8
        }
        return try {
            encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: java.nio.charset.CharacterCodingException) {
            error("字幕编码无法识别，请在服务器将字幕另存为 UTF-8 后重试")
        }
    }

    fun parse(content: String, extension: String, durationMs: Long = 0): SubtitleDocument {
        require(content.length <= 2_000_000) { "字幕文件过大（上限 2 MB）" }
        val text = content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = when (extension.lowercase().removePrefix(".")) {
            "lrc" -> lrc(text, durationMs)
            "srt", "vtt" -> timed(text)
            "ass", "ssa" -> ass(text)
            else -> error("暂不支持此字幕格式")
        }.filter { it.text.isNotBlank() && it.endMs > it.startMs }.sortedBy { it.startMs }
        require(cues.isNotEmpty()) { "字幕中没有可显示的时间轴，请检查文件格式" }
        require(cues.size <= 20_000) { "字幕条目过多（上限 20000 条）" }
        return SubtitleDocument(cues)
    }

    private fun lrc(text: String, durationMs: Long): List<SubtitleCue> {
        val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val entries = text.lineSequence().flatMap { line ->
            val stamps = lrcTime.findAll(line).toList()
            val words = clean(line.replace(lrcTime, "").replace(Regex("<\\d+:\\d+(?:[.:]\\d+)?>"), ""))
            stamps.asSequence().mapNotNull { match ->
                val (m, s, f) = match.destructured
                val minutes = m.toLongOrNull()?.takeIf { it < 1_000_000 } ?: return@mapNotNull null
                if (s.toInt() >= 60) return@mapNotNull null
                ((minutes * 60_000 + s.toLong() * 1000 + fraction(f) - offset).coerceAtLeast(0)) to words
            }
        }.toList().groupBy({ it.first }, { it.second }).toSortedMap().entries.toList()
        return entries.mapIndexed { i, entry ->
            SubtitleCue(entry.key, entries.getOrNull(i + 1)?.key ?: durationMs.takeIf { it > entry.key } ?: Long.MAX_VALUE,
                entry.value.filter(String::isNotBlank).distinct().joinToString("\n"))
        }
    }

    private fun timestamp(value: String): Long? {
        val m = cueTime.matchEntire(value.trim()) ?: return null
        val h = m.groupValues[1].ifEmpty { "0" }.toLongOrNull()?.takeIf { it < 1_000_000 } ?: return null
        val minutes = m.groupValues[2].toLong(); val seconds = m.groupValues[3].toLong()
        if (minutes >= 60 || seconds >= 60) return null
        return h * 3_600_000 + minutes * 60_000 + seconds * 1000 + fraction(m.groupValues[4])
    }

    private fun timed(text: String): List<SubtitleCue> = text.split(Regex("\n[ \t]*\n")).mapNotNull { block ->
        val lines = block.trim().lines()
        if (lines.firstOrNull()?.let { it.startsWith("NOTE") || it == "STYLE" || it == "REGION" } == true) return@mapNotNull null
        val index = lines.indexOfFirst { "-->" in it }
        if (index < 0) return@mapNotNull null
        val times = lines[index].split("-->", limit = 2)
        val start = timestamp(times[0]) ?: return@mapNotNull null
        val end = timestamp(times[1].trim().substringBefore(' ')) ?: return@mapNotNull null
        SubtitleCue(start, end, clean(lines.drop(index + 1).joinToString("\n")))
    }

    private fun ass(text: String): List<SubtitleCue> {
        var events = false
        var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        return text.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.startsWith("[")) events = line.equals("[Events]", true)
            if (!events) return@mapNotNull null
            if (line.startsWith("Format:", true)) fields = line.substringAfter(':').split(',').map { it.trim().lowercase() }
            if (!line.startsWith("Dialogue:", true)) return@mapNotNull null
            val values = line.substringAfter(':').trim().split(',', limit = fields.size)
            fun field(name: String) = values.getOrNull(fields.indexOf(name)).orEmpty()
            val start = timestamp(field("start")) ?: return@mapNotNull null
            val end = timestamp(field("end")) ?: return@mapNotNull null
            SubtitleCue(start, end, clean(field("text").replace(Regex("\\{[^}]*}"), "")
                .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")))
        }
    }

    private fun clean(text: String) = text.replace(Regex("<[^>]*>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&").trim()
}
