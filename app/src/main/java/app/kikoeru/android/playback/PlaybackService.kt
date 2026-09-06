package app.kikoeru.android.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import app.kikoeru.android.MainActivity
import app.kikoeru.android.container
import app.kikoeru.android.data.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.serialization.encodeToString

const val QUEUE_COMMAND = "app.kikoeru.QUEUE"
const val TIMER_COMMAND = "app.kikoeru.TIMER"
private const val TRACK_EXTRA = "track"
private data class PendingSave(val scope: String, val queue: SavedQueue? = null, val history: HistoryEntry? = null)

fun TrackRef.mediaItem(privacy: Boolean = false) = MediaItem.Builder().setMediaId(identity).setUri(streamUrl)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(if (privacy) "正在播放中" else title).setDisplayTitle(if (privacy) "正在播放中" else title)
        .setAlbumTitle(if (privacy) null else workTitle).setArtist(if (privacy) null else workTitle)
        .setArtworkUri(if (privacy) null else android.net.Uri.parse(coverUrl))
        .setExtras(Bundle().apply { putString(TRACK_EXTRA, AppJson.encodeToString(this@mediaItem)) }).build()).build()

fun MediaItem.track(): TrackRef? = mediaMetadata.extras?.getString(TRACK_EXTRA)?.let {
    runCatching { AppJson.decodeFromString<TrackRef>(it) }.getOrNull()
}

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private var operation = 0
    private var timerDeadline = 0L
    private var stopAtEnd = false
    private val saves = Channel<PendingSave>(Channel.UNLIMITED)
    private var lastScope: String? = null

    override fun onCreate() {
        super.onCreate()
        // The media data source picks an immutable account client at open time.
        val httpFactory = androidx.media3.datasource.DataSource.Factory {
            val repo = container.repository ?: error("请先连接服务器")
            OkHttpDataSource.Factory(repo.client).createDataSource()
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(15_000).setSeekForwardIncrementMs(30_000).build()
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val artworkLoader = androidx.media3.datasource.DataSourceBitmapLoader(
            androidx.media3.datasource.DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get(),
            DefaultDataSource.Factory(this, httpFactory), null, 512,
        )
        mediaSession = MediaSession.Builder(this, player).setBitmapLoader(artworkLoader)
            .setSessionActivity(activity).setCallback(callback).build()

        serviceScope.launch {
            container.privacyMode.collect { privacy ->
                val items = (0 until player.mediaItemCount).map { index ->
                    val item = player.getMediaItemAt(index)
                    item.track()?.mediaItem(privacy) ?: item
                }
                if (items.isNotEmpty()) player.replaceMediaItems(0, player.mediaItemCount, items)
            }
        }
        container.scope.launch(Dispatchers.IO) {
            for (pending in saves) {
                try {
                    pending.queue?.let { container.dao.queue(QueueRecord(pending.scope, AppJson.encodeToString(it))) }
                    pending.history?.let { container.dao.history(it) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // Disk errors must not terminate audio playback.
                    withContext(Dispatchers.Main) { publishExtras("播放记录未能保存，请检查设备剩余空间") }
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { save() }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // Preserve the final old-track position before the current item changes.
                val old = oldPosition.mediaItem?.track() ?: return
                saves.trySend(PendingSave(old.scope, history = HistoryEntry(old.scope, old.workId, AppJson.encodeToString(old), oldPosition.positionMs.coerceAtLeast(0), System.currentTimeMillis())))
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && stopAtEnd) setTimer(0)
            }
        })
        serviceScope.launch {
            container.ready.await()
            val repo = container.repository ?: return@launch
            val version = operation
            val record = withContext(Dispatchers.IO) { container.dao.queue(repo.session.profile.scope) }
            val saved = record?.let { runCatching { AppJson.decodeFromString<SavedQueue>(it.json) }.getOrNull() }
            if (saved != null && saved.tracks.isNotEmpty()) {
                try {
                    val fresh = repo.refreshTracks(saved.tracks)
                    if (version == operation && repo === container.repository) {
                        player.setMediaItems(fresh.map { it.mediaItem(container.privacyMode.value) }, saved.index.coerceIn(fresh.indices), saved.positionMs.coerceAtLeast(0))
                        player.setPlaybackSpeed(saved.speed.coerceIn(.5f, 2f))
                        player.repeatMode = saved.repeatMode.coerceIn(0, 2)
                        player.shuffleModeEnabled = saved.shuffle
                        // No network stream or autoplay until an explicit user action.
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    publishExtras(e.friendlyMessage())
                }
            }
        }
        serviceScope.launch {
            container.ready.await()
            container.session.drop(1).collect {
                operation++
                save()
                player.stop()
                player.clearMediaItems()
                setTimer(0)
            }
        }
        serviceScope.launch {
            var ticks = 0
            while (isActive) {
                delay(500)
                if (timerDeadline > 0 && SystemClock.elapsedRealtime() >= timerDeadline) {
                    player.pause(); setTimer(0)
                }
                if (++ticks % 20 == 0 && player.isPlaying) save()
            }
        }
    }

    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
            val own = controller.packageName == packageName
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply {
                if (own) { add(SessionCommand(QUEUE_COMMAND, Bundle.EMPTY)); add(SessionCommand(TIMER_COMMAND, Bundle.EMPTY)) }
            }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon().apply {
                    if (!own) remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                }.build()).build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            if (command.customAction == TIMER_COMMAND) {
                setTimer(args.getInt("minutes"))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (command.customAction != QUEUE_COMMAND) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            val future = SettableFuture.create<SessionResult>()
            val version = ++operation
            serviceScope.launch {
                try {
                    val repo = container.repository ?: error("请先连接服务器")
                    val requested = AppJson.decodeFromString<SavedQueue>(args.getString("queue") ?: error("播放队列为空"))
                    require(requested.tracks.isNotEmpty()) { "此目录没有音频文件" }
                    val fresh = repo.refreshTracks(requested.tracks)
                    if (version != operation || repo !== container.repository) error("播放请求已被替换，请重试")
                    when (args.getString("mode")) {
                        "next" -> player.addMediaItems((player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount), fresh.map { it.mediaItem(container.privacyMode.value) })
                        "append" -> player.addMediaItems(fresh.map { it.mediaItem(container.privacyMode.value) })
                        else -> {
                            save()
                            player.setMediaItems(fresh.map { it.mediaItem(container.privacyMode.value) }, requested.index.coerceIn(fresh.indices), requested.positionMs.coerceAtLeast(0))
                            player.prepare(); player.play()
                        }
                    }
                    publishExtras()
                    future.set(SessionResult(SessionResult.RESULT_SUCCESS))
                } catch (e: Exception) {
                    if (e is CancellationException) { future.cancel(false); throw e }
                    future.set(SessionResult(SessionError.ERROR_BAD_VALUE, Bundle().apply { putString("error", e.friendlyMessage()) }))
                }
            }
            return future
        }
    }

    private fun setTimer(minutes: Int) {
        stopAtEnd = minutes == -1
        player.pauseAtEndOfMediaItems = stopAtEnd
        timerDeadline = if (minutes > 0) SystemClock.elapsedRealtime() + minutes.coerceAtMost(720) * 60_000L else 0
        publishExtras()
    }

    private fun publishExtras(error: String? = null) {
        mediaSession?.setSessionExtras(Bundle().apply {
            putLong("timerDeadline", timerDeadline); putBoolean("stopAtEnd", stopAtEnd); putString("error", error)
        })
    }

    private fun save() {
        if (!::player.isInitialized) return
        if (player.mediaItemCount == 0) {
            lastScope?.let { saves.trySend(PendingSave(it, SavedQueue())) }
            lastScope = null
            return
        }
        val tracks = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).track() }
        if (tracks.size != player.mediaItemCount) return
        val queue = SavedQueue(tracks, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0), player.playbackParameters.speed, player.repeatMode, player.shuffleModeEnabled)
        val current = tracks.getOrNull(queue.index) ?: return
        lastScope = current.scope
        saves.trySend(PendingSave(current.scope, queue, HistoryEntry(current.scope, current.workId, AppJson.encodeToString(current), queue.positionMs, System.currentTimeMillis())))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onDestroy() {
        save(); saves.close(); serviceScope.cancel()
        mediaSession?.release(); player.release(); mediaSession = null
        super.onDestroy()
    }
}
