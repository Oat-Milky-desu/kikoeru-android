package app.kikoeru.android

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.kikoeru.android.data.*
import coil.ImageLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class KikoeruApplication : Application() {
    val container by lazy { AppContainer(this) }
    override fun onCreate() { super.onCreate(); container }
}

class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val dao = Room.databaseBuilder(context, ListeningDatabase::class.java, "listening.db").build().listening()
    val session = MutableStateFlow<Session?>(null)
    val privacyMode = MutableStateFlow(true)
    val ready = CompletableDeferred<Unit>()
    var repository: ServerRepository? = null
        private set
    var imageLoader: ImageLoader = ImageLoader(context)
        private set
    private val mutex = Mutex()

    init {
        scope.launch {
            privacyMode.value = settings.privacyMode.first()
            launch { settings.privacyMode.collect { privacyMode.value = it } }
            val saved = withContext(Dispatchers.IO) { runCatching { settings.loadSession() }.getOrNull() }
            install(saved)
            ready.complete(Unit)
        }
    }

    private fun install(value: Session?) {
        repository?.client?.dispatcher?.cancelAll()
        imageLoader.shutdown()
        repository = value?.let(::ServerRepository)
        imageLoader = ImageLoader.Builder(context).apply {
            repository?.let { okHttpClient(it.client) }
        }.build()
        session.value = value
    }

    suspend fun updateSession(value: Session?) = mutex.withLock {
        withContext(Dispatchers.IO) { settings.saveSession(value) }
        install(value)
    }
}

val Context.container: AppContainer get() = (applicationContext as KikoeruApplication).container
