@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kikoeru.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kikoeru.android.data.*

@Composable
fun DetailScreen(state: AppState, vm: MainViewModel) {
    val work = state.detail ?: return
    val player by vm.playback.state.collectAsStateWithLifecycle()
    var menuTrack by remember { mutableStateOf<TrackRef?>(null) }
    var collapsed by rememberSaveable(work.id) { mutableStateOf(emptyList<String>()) }
    var allTags by rememberSaveable(work.id) { mutableStateOf(false) }
    val groups = remember(state.tracks) { state.tracks.groupBy { it.directory } }
    Column {
        TopAppBar(title = { Text("作品详情") }, navigationIcon = { ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") { vm.closeDetail() } })
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Cover(vm.container.repository?.cover(work.id), vm.container, Modifier.fillMaxWidth(.72f).align(Alignment.CenterHorizontally).aspectRatio(1f))
                    Text(work.title, style = MaterialTheme.typography.headlineSmall)
                    work.release?.let { Text(it.take(10), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.play(state.tracks) }, enabled = state.tracks.isNotEmpty() && !state.preparing) {
                            Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("播放全部")
                        }
                        state.history.firstOrNull { it.workId == work.id }?.let { history ->
                            FilledTonalButton(onClick = { vm.resume(history) }, enabled = !state.preparing) { Text("继续播放") }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        work.circle?.takeIf { it.name.isNotBlank() }?.let { label -> AssistChip(onClick = { vm.filter("circles", label) }, label = { Text(label.name) }) }
                        work.vas.forEach { label -> AssistChip(onClick = { vm.filter("vas", label) }, label = { Text(label.name) }) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (if (allTags) work.tags else work.tags.take(6)).forEach { label -> SuggestionChip(onClick = { vm.filter("tags", label) }, label = { Text(label.name) }) }
                    }
                    if (work.tags.size > 6) TextButton(onClick = { allTags = !allTags }) { Text(if (allTags) "收起标签" else "查看全部 ${work.tags.size} 个标签") }
                    if (!work.description.isNullOrBlank()) Text(work.description, style = MaterialTheme.typography.bodyMedium)
                    Text("文件目录 · ${state.tracks.size} 首音频", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                }
            }
            if (state.detailLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) }
            if (state.detailError != null) item { EmptyState(Icons.Outlined.CloudOff, "目录加载失败", state.detailError, "重试") { vm.openWork(work) } }
            if (!state.detailLoading && state.detailError == null && state.tracks.isEmpty()) item {
                EmptyState(Icons.Outlined.FolderOpen, "没有可播放的音频", "请检查作品文件夹，或在服务器上重新扫描。")
            }
            groups.forEach { (directory, tracks) ->
                item(key = "folder:$directory") {
                    ListItem(headlineContent = { Text(directory.ifEmpty { "根目录" }) },
                        supportingContent = { Text("${tracks.size} 首音频") },
                        leadingContent = { Icon(if (directory in collapsed) Icons.Outlined.Folder else Icons.Outlined.FolderOpen, null) },
                        trailingContent = { ActionIcon(Icons.Outlined.PlayCircle, "播放此目录", !state.preparing) { vm.play(tracks) } },
                        modifier = Modifier.clickable { collapsed = if (directory in collapsed) collapsed - directory else collapsed + directory })
                }
                if (directory !in collapsed) items(tracks, key = { "track:${it.path}:${it.hash}" }) { track ->
                    ListItem(
                        headlineContent = { Text(track.title, style = MaterialTheme.typography.bodyLarge) },
                        supportingContent = { Text(if (track.durationMs > 0) durationText(track.durationMs) else "时长未知") },
                        leadingContent = { Icon(if (player.current?.identity == track.identity) Icons.Outlined.GraphicEq else Icons.Outlined.AudioFile, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { ActionIcon(Icons.Outlined.MoreVert, "${track.title} 的更多操作") { menuTrack = track } },
                        modifier = Modifier.padding(start = 16.dp).clickable(enabled = !state.preparing) { vm.playTrack(track) },
                    )
                }
            }
        }
    }
    menuTrack?.let { track ->
        ModalBottomSheet(onDismissRequest = { menuTrack = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(track.title, Modifier.padding(24.dp), style = MaterialTheme.typography.titleMedium)
                ListItem(headlineContent = { Text("立即播放") }, leadingContent = { Icon(Icons.Outlined.PlayArrow, null) }, modifier = Modifier.clickable { vm.playTrack(track); menuTrack = null })
                ListItem(headlineContent = { Text("下一首播放") }, leadingContent = { Icon(Icons.Outlined.PlaylistPlay, null) }, modifier = Modifier.clickable { vm.play(listOf(track), mode = "next"); menuTrack = null })
                ListItem(headlineContent = { Text("加入队列") }, leadingContent = { Icon(Icons.Outlined.PlaylistAdd, null) }, modifier = Modifier.clickable { vm.play(listOf(track), mode = "append"); menuTrack = null })
            }
        }
    }
}
