package app.kikoeru.android.playback

import app.kikoeru.android.AppContainer
import app.kikoeru.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SubtitleState(
    val trackIdentity: String? = null,
    val enabled: Boolean = true,
    val discovering: Boolean = false,
    val loading: Boolean = false,
    val sources: List<SubtitleSource> = emptyList(),
    val selectedId: String? = null,
    val document: SubtitleDocument? = null,
    val delayMs: Long = 0,
    val notices: List<String> = emptyList(),
    val error: String? = null,
)

/** Cancels discovery and downloads on a track/account change, never showing stale lyrics. */
class SubtitleController(private val container: AppContainer, private val playback: PlaybackConnection, private val scope: CoroutineScope) {
    val state = MutableStateFlow(SubtitleState())
    private var track: TrackRef? = null
    private var discovery: Job? = null
    private var download: Job? = null
    private val choices = mutableMapOf<String, String>()
    private val delays = mutableMapOf<String, Long>()

    init {
        scope.launch { container.settings.subtitlesEnabled.collect { enabled -> state.update { it.copy(enabled = enabled) } } }
        scope.launch {
            container.ready.await()
            combine(playback.state.map { it.current }.distinctUntilChangedBy { it?.identity }, container.session) { current, session ->
                current?.takeIf { it.scope == session?.profile?.scope }
            }.distinctUntilChangedBy { it?.identity }.collect { current ->
                track = current
                refresh()
            }
        }
    }

    fun enabled(value: Boolean) {
        state.update { it.copy(enabled = value) }
        scope.launch { container.settings.subtitles(value) }
    }

    fun refresh() {
        discovery?.cancel(); download?.cancel()
        val current = track
        state.value = SubtitleState(trackIdentity = current?.identity, enabled = state.value.enabled, discovering = current != null)
        val repo = container.repository ?: return
        if (current == null) return
        discovery = scope.launch {
            try {
                val catalog = SubtitleRepository(repo).catalog(current)
                ensureActive()
                if (repo !== container.repository || track?.identity != current.identity) return@launch
                state.update { it.copy(discovering = false, sources = catalog.sources, notices = catalog.notices) }
                val selected = catalog.sources.firstOrNull { it.id == choices[current.identity] && it.available }
                    ?: catalog.sources.firstOrNull { it.automatic && it.available }
                selected?.let { select(it.id) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.update { it.copy(discovering = false, error = e.friendlyMessage()) }
            }
        }
    }

    fun select(id: String) {
        val current = track ?: return
        val repo = container.repository ?: return
        val source = state.value.sources.firstOrNull { it.id == id && it.available } ?: return
        download?.cancel()
        choices[current.identity] = id
        state.update { it.copy(selectedId = id, document = null, loading = true, error = null, delayMs = delays["${current.identity}|$id"] ?: 0) }
        download = scope.launch {
            try {
                val duration = playback.state.value.duration.takeIf { it > 0 } ?: current.durationMs
                val document = SubtitleRepository(repo).load(source, duration)
                ensureActive()
                if (repo === container.repository && current.identity == track?.identity) {
                    state.update { it.copy(document = document, loading = false) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.update { it.copy(loading = false, error = e.friendlyMessage()) }
            }
        }
    }

    fun delay(value: Long) {
        val current = track ?: return
        val id = state.value.selectedId ?: return
        val clamped = value.coerceIn(-30_000, 30_000)
        delays["${current.identity}|$id"] = clamped
        state.update { it.copy(delayMs = clamped) }
    }
}
