@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.kikoeru.android.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kikoeru.android.AppContainer
import app.kikoeru.android.playback.PlaybackState
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MiniPlayer(player: PlaybackState, container: AppContainer, open: () -> Unit, toggle: () -> Unit) {
    val current = player.current ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = open)) {
        Column {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(current.coverUrl, container, Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(current.workTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (player.buffering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                ActionIcon(if (player.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (player.playing) "暂停" else "播放", onClick = toggle)
            }
            if (player.duration > 0) LinearProgressIndicator(progress = { (player.position.toFloat() / player.duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@Composable
fun PlayerScreen(player: PlaybackState, vm: MainViewModel, close: () -> Unit, modifier: Modifier = Modifier) {
    var sheet by remember { mutableStateOf<String?>(null) }
    val subtitles by vm.subtitles.state.collectAsStateWithLifecycle()
    val showSubtitles = subtitles.enabled && subtitles.document != null && subtitles.trackIdentity == player.current?.identity
    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("正在播放", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { ActionIcon(Icons.Outlined.KeyboardArrowDown, "收起播放器", onClick = close) },
            actions = {
                ActionIcon(Icons.Outlined.Subtitles, "字幕设置", player.current != null) { sheet = "subtitles" }
                ActionIcon(Icons.Outlined.QueueMusic, "播放队列") { sheet = "queue" }
            })
        val track = player.current
        if (track == null) {
            EmptyState(Icons.Outlined.Headphones, "准备好聆听", player.error ?: "从作品目录中选择一首音频，即可开始播放。", "返回音声库", close)
        } else BoxWithConstraints(Modifier.weight(1f)) {
            if (maxWidth >= 720.dp) Row(Modifier.fillMaxSize().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                if (showSubtitles) SubtitlePanel(subtitles, player.position, vm.playback::seek, Modifier.weight(1f).fillMaxHeight())
                else Cover(track.coverUrl, vm.container, Modifier.weight(1f).aspectRatio(1f))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { PlayerControls(player, vm) { sheet = it } }
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                if (showSubtitles) SubtitlePanel(subtitles, player.position, vm.playback::seek, Modifier.fillMaxWidth().height(320.dp))
                else Cover(track.coverUrl, vm.container, Modifier.widthIn(max = 420.dp).fillMaxWidth().align(Alignment.CenterHorizontally).aspectRatio(1f))
                if (subtitles.enabled && (subtitles.discovering || subtitles.loading)) Text("正在加载字幕…", style = MaterialTheme.typography.bodySmall)
                if (subtitles.enabled && subtitles.error != null) Text("字幕加载失败，可在右上角字幕设置中重试。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                PlayerControls(player, vm) { sheet = it }
            }
        }
    }
    when (sheet) {
        "subtitles" -> SubtitleSheet(subtitles, vm.subtitles) { sheet = null }
        "queue" -> QueueSheet(player, vm) { sheet = null }
        "speed" -> ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text("播放速度", style = MaterialTheme.typography.titleLarge)
                listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                    ListItem(headlineContent = { Text("${speed}×" + if (speed == 1f) " · 正常" else "") },
                        trailingContent = { if (player.speed == speed) Icon(Icons.Outlined.Check, null) },
                        modifier = Modifier.clickable { vm.playback.speed(speed); sheet = null })
                }
            }
        }
        "timer" -> TimerSheet(player, { vm.playback.timer(it); sheet = null }) { sheet = null }
    }
}

@Composable
private fun PlayerControls(player: PlaybackState, vm: MainViewModel, openSheet: (String) -> Unit) {
    val track = player.current ?: return
    var dragging by remember(track.identity) { mutableStateOf<Float?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(player.timerDeadline) { while (player.timerDeadline > 0) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(track.title, style = MaterialTheme.typography.headlineSmall)
        Text(track.workTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        player.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Slider(value = dragging ?: player.position.toFloat().coerceAtMost(player.duration.toFloat()),
            onValueChange = { dragging = it }, valueRange = 0f..player.duration.coerceAtLeast(1).toFloat(),
            onValueChangeFinished = { dragging?.let { vm.playback.seek(it.toLong()) }; dragging = null }, enabled = player.seekable && player.duration > 0,
            modifier = Modifier.semantics { contentDescription = "播放进度" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(durationText(dragging?.toLong() ?: player.position), style = MaterialTheme.typography.labelLarge)
            Text(if (player.duration > 0) durationText(player.duration) else "--:--", style = MaterialTheme.typography.labelLarge)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(Icons.Outlined.SkipPrevious, "上一首", player.index > 0, vm.playback::previous)
            ActionIcon(Icons.Outlined.Replay, "快退 15 秒", player.seekable, vm.playback::back)
            FilledIconButton(onClick = vm.playback::toggle, modifier = Modifier.size(76.dp), enabled = player.connected) {
                if (player.buffering) CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
                else Icon(if (player.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (player.playing) "暂停" else "播放", Modifier.size(40.dp))
            }
            ActionIcon(Icons.Outlined.Forward30, "快进 30 秒", player.seekable, vm.playback::forward)
            ActionIcon(Icons.Outlined.SkipNext, "下一首", player.index < player.tracks.lastIndex, vm.playback::next)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = vm.playback::mode) { Text(when { player.shuffle -> "随机"; player.repeatMode == 1 -> "单曲循环"; player.repeatMode == 2 -> "列表循环"; else -> "顺序" }) }
            TextButton(onClick = { openSheet("speed") }) { Text("${player.speed}×") }
            TextButton(onClick = { openSheet("timer") }) { Text(when { player.stopAtEnd -> "本曲结束"; player.timerDeadline > 0 -> durationText(player.timerDeadline - now); else -> "定时" }) }
            IconButton(onClick = { openSheet("queue") }) { Icon(Icons.Outlined.QueueMusic, "队列") }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun QueueSheet(player: PlaybackState, vm: MainViewModel, close: () -> Unit) {
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("播放队列 · ${player.tracks.size} 首", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
        Text("长按右侧手柄拖动排序，也可用上下按钮调整。", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(player.tracks) { index, track ->
                var accumulated by remember { mutableFloatStateOf(0f) }
                ListItem(headlineContent = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(track.workTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Text(if (index == player.index) "▶" else "${index + 1}", color = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ActionIcon(Icons.Outlined.KeyboardArrowUp, "上移", index > 0) { vm.playback.move(index, index - 1) }
                            ActionIcon(Icons.Outlined.Close, "移除 ${track.title}") { vm.playback.remove(index) }
                            Icon(Icons.Outlined.DragHandle, "长按拖动排序", Modifier.size(48.dp).pointerInput(index, player.tracks.size) {
                                detectDragGesturesAfterLongPress(onDragStart = { accumulated = 0f }, onDragEnd = {
                                    val target = (index + (accumulated / threshold).toInt()).coerceIn(0, player.tracks.lastIndex)
                                    if (target != index) vm.playback.move(index, target)
                                    accumulated = 0f
                                }, onDragCancel = { accumulated = 0f }) { change, amount -> change.consume(); accumulated += amount.y }
                            })
                        }
                    }, modifier = Modifier.clickable { vm.playback.jump(index) })
            }
        }
    }
}

@Composable
private fun TimerSheet(player: PlaybackState, apply: (Int) -> Unit, close: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("睡眠定时", style = MaterialTheme.typography.titleLarge)
            Text("到时暂停，保留当前播放位置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(15, 30, 60).forEach { minutes -> OutlinedButton(onClick = { apply(minutes) }, modifier = Modifier.fillMaxWidth()) { Text("$minutes 分钟后") } }
            OutlinedButton(onClick = { apply(-1) }, modifier = Modifier.fillMaxWidth()) { Text("当前曲目结束时") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(custom, { custom = it.filter(Char::isDigit).take(3) }, label = { Text("自定义分钟") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { apply(custom.toInt()) }, enabled = custom.toIntOrNull() in 1..720) { Text("设置") }
            }
            if (player.timerDeadline > 0 || player.stopAtEnd) TextButton(onClick = { apply(0) }, modifier = Modifier.fillMaxWidth()) { Text("取消定时") }
        }
    }
}
