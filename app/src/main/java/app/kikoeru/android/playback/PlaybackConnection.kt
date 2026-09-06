package app.kikoeru.android.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.session.*
import app.kikoeru.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString

data class PlaybackState(
    val connected: Boolean = false,
    val tracks: List<TrackRef> = emptyList(), val index: Int = 0,
    val playing: Boolean = false, val buffering: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val seekable: Boolean = false,
    val speed: Float = 1f, val repeatMode: Int = 0, val shuffle: Boolean = false,
    val timerDeadline: Long = 0, val stopAtEnd: Boolean = false,
    val error: String? = null,
) { val current: TrackRef? get() = tracks.getOrNull(index) }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackConnection(context: Context, private val scope: CoroutineScope) {
    val state = MutableStateFlow(PlaybackState())
    private val executor = ContextCompat.getMainExecutor(context)
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onExtrasChanged(controller: MediaController, extras: Bundle) { update(controller) }
            override fun onDisconnected(controller: MediaController) {
                state.value = state.value.copy(connected = false, playing = false, error = "播放服务已断开，请重新打开应用")
            }
        }).buildAsync()
    private var controller: MediaController? = null

    init {
        future.addListener({
            runCatching { future.get() }.onSuccess {
                controller = it
                it.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { update(it) }
                })
                update(it)
            }.onFailure { state.value = state.value.copy(error = "无法连接播放服务") }
        }, executor)
        scope.launch { while (isActive) { controller?.let(::update); delay(500) } }
    }

    private fun update(c: MediaController) {
        val extras = c.sessionExtras
        state.value = PlaybackState(true,
            (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).track() },
            c.currentMediaItemIndex.coerceAtLeast(0), c.isPlaying, c.playbackState == Player.STATE_BUFFERING,
            c.currentPosition.coerceAtLeast(0), c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            c.isCurrentMediaItemSeekable, c.playbackParameters.speed, c.repeatMode, c.shuffleModeEnabled,
            extras.getLong("timerDeadline"), extras.getBoolean("stopAtEnd"),
            c.playerError?.let { error ->
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "媒体请求失败，请检查登录状态或文件是否存在"
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "此设备不支持该音频格式"
                    else -> "播放失败，请检查网络后点击播放重试（${error.errorCode}）"
                }
            } ?: extras.getString("error"))
    }

    fun toggle() { controller?.run { if (isPlaying || playWhenReady) pause() else { if (playbackState == Player.STATE_ENDED) seekTo(0); prepare(); play() } } }
    fun seek(value: Long) { controller?.seekTo(value.coerceAtLeast(0)) }
    fun back() { controller?.seekBack() }
    fun forward() { controller?.seekForward() }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun next() { controller?.seekToNextMediaItem() }
    fun speed(value: Float) { controller?.setPlaybackSpeed(value) }
    fun mode() { controller?.run {
        when {
            shuffleModeEnabled -> { shuffleModeEnabled = false; repeatMode = Player.REPEAT_MODE_OFF }
            repeatMode == Player.REPEAT_MODE_OFF -> repeatMode = Player.REPEAT_MODE_ALL
            repeatMode == Player.REPEAT_MODE_ALL -> repeatMode = Player.REPEAT_MODE_ONE
            else -> { repeatMode = Player.REPEAT_MODE_OFF; shuffleModeEnabled = true }
        }
    } }
    fun remove(index: Int) { controller?.removeMediaItem(index) }
    fun move(from: Int, to: Int) { controller?.moveMediaItem(from, to) }
    fun jump(index: Int) { controller?.run { seekToDefaultPosition(index); prepare(); play() } }
    fun stop() { controller?.run { pause(); stop(); clearMediaItems() } }
    fun timer(minutes: Int) { controller?.sendCustomCommand(SessionCommand(TIMER_COMMAND, Bundle.EMPTY), Bundle().apply { putInt("minutes", minutes) }) }
    fun queue(tracks: List<TrackRef>, index: Int = 0, position: Long = 0, mode: String = "replace", onResult: (String?) -> Unit) {
        val c = controller ?: return onResult("播放服务连接中，请稍后重试")
        val queueJson = AppJson.encodeToString(SavedQueue(tracks, index, position))
        if (queueJson.toByteArray().size > 400_000) return onResult("此目录过大，请选择较小的子目录播放")
        val result = c.sendCustomCommand(SessionCommand(QUEUE_COMMAND, Bundle.EMPTY), Bundle().apply {
            putString("queue", queueJson); putString("mode", mode)
        })
        result.addListener({
            onResult(runCatching { result.get() }.fold(
                onSuccess = { if (it.resultCode == SessionResult.RESULT_SUCCESS) null else it.extras.getString("error") ?: "播放请求失败" },
                onFailure = { "播放请求已取消，请重试" }))
        }, executor)
    }
    fun release() { MediaController.releaseFuture(future) }
}
