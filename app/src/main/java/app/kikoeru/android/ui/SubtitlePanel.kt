@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.kikoeru.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import app.kikoeru.android.playback.SubtitleController
import app.kikoeru.android.playback.SubtitleState

@Composable
fun SubtitlePanel(state: SubtitleState, position: Long, seek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val document = state.document ?: return
    val active = remember(document, position, state.delayMs) { document.activeIndices(position, state.delayMs) }
    val scroll = rememberLazyListState()
    var follow by remember(document) { mutableStateOf(true) }
    LaunchedEffect(document, active.firstOrNull(), follow) {
        if (follow) active.firstOrNull()?.let { scroll.animateScrollToItem((it - 1).coerceAtLeast(0)) }
    }
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("字幕", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { follow = !follow }) { Text(if (follow) "自动跟随：开" else "自动跟随：关") }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = scroll, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(document.cues) { index, cue ->
                    Text(cue.text, Modifier.fillMaxWidth().clickable { seek((cue.startMs + state.delayMs).coerceAtLeast(0)) }.padding(vertical = 6.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (index in active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (index in active) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Text("点击字幕跳转播放", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun SubtitleSheet(state: SubtitleState, controller: SubtitleController, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text("字幕设置", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
                ListItem(headlineContent = { Text("显示字幕") }, supportingContent = { Text("优先同名本地字幕，其次已完成的 AI 字幕") },
                    trailingContent = { Switch(state.enabled, controller::enabled) })
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = controller::refresh, enabled = !state.discovering) { Text("刷新字幕列表") }
                    if (state.document != null) Text("${state.document.cues.size} 条", Modifier.padding(12.dp))
                }
                if (state.discovering || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.notices.forEach { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
                state.error?.let { message ->
                    Text(message, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { state.selectedId?.let(controller::select) ?: controller.refresh() }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("重试") }
                }
                if (!state.discovering && state.sources.isEmpty()) Text("此音轨没有可用字幕。可在服务端放置同名字幕或完成 AI 生成任务后刷新。", Modifier.padding(24.dp))
                if (state.sources.isNotEmpty() && state.selectedId == null) Text("未自动匹配到字幕，请从下面手动选择。", Modifier.padding(24.dp))
            }
            itemsIndexed(state.sources, key = { _, source -> source.id }) { _, source ->
                ListItem(headlineContent = { Text(source.label) },
                    leadingContent = { RadioButton(state.selectedId == source.id, onClick = null, enabled = source.available) },
                    modifier = Modifier.clickable(enabled = source.available) { controller.select(source.id) })
            }
            if (state.selectedId != null) item {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("时间调整：%+.1f 秒".format(state.delayMs / 1000.0), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleMedium)
                Text("正值让字幕晚出现，负值让字幕提前；当前字幕单独调整。", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    OutlinedButton(onClick = { controller.delay(state.delayMs - 500) }) { Text("提前 0.5 秒") }
                    OutlinedButton(onClick = { controller.delay(state.delayMs + 500) }) { Text("延后 0.5 秒") }
                }
                TextButton(onClick = { controller.delay(0) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("重置时间调整") }
            }
        }
    }
}
