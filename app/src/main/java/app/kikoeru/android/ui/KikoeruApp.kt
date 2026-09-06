@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.kikoeru.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun KikoeruApp(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val player by vm.playback.state.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val dynamic by vm.dynamic.collectAsStateWithLifecycle()
    val searches by vm.searches.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.message(null) } }
    LaunchedEffect(state.profile) { if (state.profile == null) { fullPlayer = false; tab = 0 } }
    BackHandler(enabled = fullPlayer || state.detail != null || tab != 0) {
        when { fullPlayer -> fullPlayer = false; state.detail != null -> vm.closeDetail(); else -> tab = 0 }
    }

    KikoeruTheme(theme, dynamic) {
        Surface(Modifier.fillMaxSize()) {
            if (!state.initialized) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (state.profile == null) {
                ConnectionScreen(state, vm::connect)
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 840.dp
                    val entries = listOf("作品" to Icons.Outlined.LibraryMusic, "我的" to Icons.Outlined.History, "设置" to Icons.Outlined.Settings)
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbar) },
                        bottomBar = {
                            if (!fullPlayer) Column {
                                if (player.current != null) MiniPlayer(player, vm.container, { fullPlayer = true }, vm.playback::toggle)
                                if (!wide) NavigationBar {
                                    entries.forEachIndexed { index, entry ->
                                        NavigationBarItem(selected = tab == index, onClick = { tab = index; vm.closeDetail() },
                                            icon = { Icon(entry.second, null) }, label = { Text(entry.first) })
                                    }
                                }
                            }
                        },
                    ) { padding ->
                        if (fullPlayer) {
                            PlayerScreen(player, vm, { fullPlayer = false }, Modifier.padding(padding).consumeWindowInsets(padding))
                        } else Row(Modifier.padding(padding).consumeWindowInsets(padding)) {
                            if (wide) NavigationRail(Modifier.fillMaxHeight()) {
                                Spacer(Modifier.height(24.dp))
                                entries.forEachIndexed { index, entry ->
                                    NavigationRailItem(selected = tab == index, onClick = { tab = index; vm.closeDetail() },
                                        icon = { Icon(entry.second, null) }, label = { Text(entry.first) })
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                when (tab) {
                                    0 -> {
                                        if (wide) Row {
                                            Box(Modifier.weight(1f)) { LibraryScreen(state, vm, gridState, searches) }
                                            if (state.detail != null) {
                                                VerticalDivider()
                                                Box(Modifier.weight(1f)) { DetailScreen(state, vm) }
                                            }
                                        } else if (state.detail != null) DetailScreen(state, vm)
                                        else LibraryScreen(state, vm, gridState, searches)
                                    }
                                    1 -> HistoryScreen(state, vm) { fullPlayer = true }
                                    2 -> SettingsScreen(state, vm, theme, dynamic)
                                }
                                if (state.preparing) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                            }
                        }
                    }
                }
            }
        }
    }
}
