package app.kikoeru.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.kikoeru.android.container
import app.kikoeru.android.data.*
import app.kikoeru.android.playback.PlaybackConnection
import app.kikoeru.android.playback.SubtitleController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import retrofit2.HttpException

data class AppState(
    val initialized: Boolean = false,
    val profile: Profile? = null,
    val connecting: Boolean = false,
    val authRequired: Boolean = false,
    val connectionError: String? = null,
    val query: BrowseQuery = BrowseQuery(),
    val works: List<Work> = emptyList(),
    val total: Int = 0, val page: Int = 0,
    val loading: Boolean = false,
    val browseError: String? = null,
    val detail: Work? = null,
    val tracks: List<TrackRef> = emptyList(),
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val history: List<HistoryEntry> = emptyList(),
    val message: String? = null,
    val preparing: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val container = app.container
    val state = MutableStateFlow(AppState())
    val playback = PlaybackConnection(app, viewModelScope)
    val subtitles = SubtitleController(container, playback, viewModelScope)
    val theme = container.settings.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "system")
    val dynamic = container.settings.dynamicColor.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val searches = container.settings.searches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var browseJob: Job? = null
    private var detailJob: Job? = null
    private var historyJob: Job? = null

    init {
        viewModelScope.launch {
            container.ready.await()
            container.session.collect { session ->
                browseJob?.cancel(); detailJob?.cancel(); historyJob?.cancel()
                state.value = AppState(initialized = true, profile = session?.profile)
                session?.let {
                    load()
                    historyJob = launch { container.dao.history(it.profile.scope).collect { history -> state.update { s -> s.copy(history = history) } } }
                }
            }
        }
    }

    fun connect(address: String, username: String, password: String, allowHttp: Boolean) {
        if (state.value.connecting) return
        viewModelScope.launch {
            state.update { it.copy(connecting = true, connectionError = null) }
            try {
                val base = ServerUrls.normalize(address, allowHttp)
                val anonymous = ServerRepository(Session(Profile(base, "admin", allowHttp), ""))
                val me = try { anonymous.api.me() } catch (e: HttpException) { if (e.code() == 401) null else throw e }
                val session = if (me != null && !me.auth) {
                    Session(Profile(base, me.user.name.ifBlank { "admin" }, allowHttp), "")
                } else {
                    if (username.isBlank() || password.isBlank()) {
                        state.update { it.copy(connecting = false, authRequired = true, connectionError = "服务器需要登录，请填写用户名和密码") }
                        return@launch
                    }
                    val token = anonymous.api.login(LoginBody(username.trim(), password)).token
                    require(token.isNotBlank()) { "服务器未返回有效登录凭据" }
                    val signed = Session(Profile(base, username.trim(), allowHttp), token)
                    val verified = ServerRepository(signed).api.me()
                    signed.copy(profile = signed.profile.copy(username = verified.user.name.ifBlank { username.trim() }))
                }
                // Verify a read endpoint before replacing a working connection.
                ServerRepository(session).api.works(mapOf("page" to "1"))
                playback.stop()
                container.updateSession(session)
                state.update { it.copy(connecting = false, connectionError = null, authRequired = false, message = "已连接服务器") }
            } catch (e: Exception) {
                state.update { it.copy(connecting = false, connectionError = e.friendlyMessage(), authRequired = it.authRequired || e is HttpException && e.code() == 401) }
            }
        }
    }

    fun disconnect() = viewModelScope.launch { playback.stop(); container.updateSession(null) }

    fun search(text: String) {
        state.update { it.copy(query = it.query.copy(keyword = text, filter = null)) }
        load(debounce = true)
    }
    fun rememberSearch() = viewModelScope.launch { container.settings.rememberSearch(state.value.query.keyword) }
    fun sort(order: String, direction: String) {
        state.update { it.copy(query = it.query.copy(order = order, sort = direction)) }; load()
    }
    fun filter(field: String, label: Label) {
        closeDetail()
        state.update { it.copy(query = BrowseQuery(filter = BrowseFilter(field, label.id, label.name))) }; load()
    }
    fun clearFilter() { state.update { it.copy(query = BrowseQuery()) }; load() }

    fun load(more: Boolean = false, debounce: Boolean = false) {
        val repo = container.repository ?: return
        if (more && (state.value.loading || state.value.works.size >= state.value.total)) return
        browseJob?.cancel()
        val query = state.value.query
        val page = if (more) state.value.page + 1 else 1
        state.update { it.copy(loading = true, browseError = null, works = if (more) it.works else emptyList(), page = if (more) it.page else 0) }
        browseJob = viewModelScope.launch {
            try {
                if (debounce) delay(350)
                val result = repo.browse(query, page)
                ensureActive()
                state.update { it.copy(loading = false, works = ((if (more) it.works else emptyList()) + result.works).distinctBy { work -> work.id }, total = result.pagination.totalCount, page = page) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.update { it.copy(loading = false, browseError = e.friendlyMessage()) }
            }
        }
    }

    fun openWork(work: Work) {
        val repo = container.repository ?: return
        detailJob?.cancel()
        state.update { it.copy(detail = work, tracks = emptyList(), detailLoading = true, detailError = null) }
        detailJob = viewModelScope.launch {
            try {
                val detail = async { repo.api.work(work.id) }
                val nodes = async { repo.api.tracks(work.id) }
                val loaded = detail.await()
                val tracks = flattenTracks(nodes.await(), loaded, repo.session.profile.scope, repo.cover(work.id))
                    .map { it.copy(streamUrl = ServerUrls.resolve(repo.session.profile.baseUrl, it.streamUrl)) }
                ensureActive()
                state.update { it.copy(detail = loaded, tracks = tracks, detailLoading = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.update { it.copy(detailLoading = false, detailError = e.friendlyMessage()) }
            }
        }
    }
    fun closeDetail() { detailJob?.cancel(); state.update { it.copy(detail = null, tracks = emptyList(), detailLoading = false) } }

    fun play(tracks: List<TrackRef>, index: Int = 0, position: Long = 0, mode: String = "replace") {
        if (tracks.isEmpty()) { message("此目录没有可播放的音频"); return }
        state.update { it.copy(preparing = true) }
        playback.queue(tracks, index, position, mode) { error ->
            state.update { it.copy(preparing = false, message = error ?: if (mode == "replace") null else "已加入播放队列") }
        }
    }
    fun playTrack(track: TrackRef) {
        val siblings = state.value.tracks.filter { it.directory == track.directory }
        play(siblings, siblings.indexOf(track).coerceAtLeast(0))
    }
    fun resume(entry: HistoryEntry) {
        val track = runCatching { AppJson.decodeFromString<TrackRef>(entry.trackJson) }.getOrNull()
            ?: return message("播放记录损坏，请重新选择作品")
        play(listOf(track), position = entry.positionMs)
    }
    fun message(value: String?) { state.update { it.copy(message = value) } }
    fun privacy(value: Boolean) = viewModelScope.launch { container.settings.privacy(value) }
    fun theme(value: String) = viewModelScope.launch { container.settings.theme(value) }
    fun dynamic(value: Boolean) = viewModelScope.launch { container.settings.dynamic(value) }
    override fun onCleared() { playback.release(); super.onCleared() }
}
