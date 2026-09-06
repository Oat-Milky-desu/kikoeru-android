@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.kikoeru.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kikoeru.android.data.AppJson
import app.kikoeru.android.data.TrackRef

@Composable
fun HistoryScreen(state: AppState, vm: MainViewModel, showPlayer: () -> Unit) {
    Column {
        TopAppBar(title = { Text("我的聆听") })
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text("最近播放", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge) }
            if (state.history.isEmpty()) item {
                EmptyState(Icons.Outlined.History, "从一段声音开始", "播放过的作品会出现在这里，随时接着听。")
            }
            items(state.history, key = { "${it.scope}:${it.workId}" }) { entry ->
                val track = remember(entry.trackJson) { runCatching { AppJson.decodeFromString<TrackRef>(entry.trackJson) }.getOrNull() }
                if (track != null) ListItem(
                    headlineContent = { Text(track.workTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${track.title}\n已听 ${durationText(entry.positionMs)}", maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Cover(track.coverUrl, vm.container, Modifier.size(64.dp)) },
                    trailingContent = { Icon(Icons.Outlined.PlayCircle, "继续播放", tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable(enabled = !state.preparing) { vm.resume(entry); showPlayer() },
                )
            }
            item { Text("播放位置保存在本机，按服务器与账号分别记录。", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun SettingsScreen(state: AppState, vm: MainViewModel, theme: String, dynamic: Boolean) {
    val privacy by vm.container.privacyMode.collectAsStateWithLifecycle()
    var editServer by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    Column {
        TopAppBar(title = { Text(if (editServer) "服务器设置" else "设置") }, navigationIcon = {
            if (editServer) ActionIcon(Icons.AutoMirrored.Outlined.ArrowBack, "返回设置") { editServer = false }
        })
        if (editServer) ConnectionScreen(state, vm::connect, embedded = true)
        else Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("服务器", Modifier.padding(20.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ListItem(headlineContent = { Text(state.profile?.baseUrl.orEmpty()) }, supportingContent = { Text("账号：${state.profile?.username}") },
                leadingContent = { Icon(Icons.Outlined.Dns, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }, modifier = Modifier.clickable { editServer = true })
            ListItem(headlineContent = { Text("断开连接") }, supportingContent = { Text("停止播放并移除登录凭据，保留本地聆听记录") },
                leadingContent = { Icon(Icons.Outlined.Logout, null) }, modifier = Modifier.clickable { confirmLogout = true })
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            ListItem(headlineContent = { Text("隐私模式") },
                supportingContent = { Text("模糊所有封面；通知和锁屏控制仅显示“正在播放中”，隐藏作品标题与封面") },
                leadingContent = { Icon(Icons.Outlined.VisibilityOff, null) },
                trailingContent = { Switch(privacy, { vm.privacy(it) }) })
            Text("外观", Modifier.padding(20.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ListItem(headlineContent = { Text("主题") }, supportingContent = { Text(when (theme) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }) },
                leadingContent = { Icon(Icons.Outlined.Palette, null) }, modifier = Modifier.clickable { themeDialog = true })
            ListItem(headlineContent = { Text("动态配色") }, supportingContent = { Text("Android 12 及以上使用系统壁纸配色") },
                leadingContent = { Icon(Icons.Outlined.ColorLens, null) }, trailingContent = { Switch(dynamic, { vm.dynamic(it) }) })
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            ListItem(headlineContent = { Text("Kikoeru 0.2.1") }, supportingContent = { Text("原生安卓音声播放器 · Material 3\n本地续播 / 后台播放 / 本地与 AI 字幕") }, leadingContent = { Icon(Icons.Outlined.Headphones, null) })
            Text("离线下载及服务端历史同步将在后续版本提供。", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("断开服务器？") },
        text = { Text("当前播放将停止，重新连接后可恢复此账号的本地记录。") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; vm.disconnect() }) { Text("断开") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
    if (themeDialog) AlertDialog(onDismissRequest = { themeDialog = false }, title = { Text("选择主题") }, text = {
        Column { listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (value, label) ->
            Row(Modifier.fillMaxWidth().clickable { vm.theme(value); themeDialog = false }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(theme == value, { vm.theme(value); themeDialog = false }); Text(label)
            }
        } }
    }, confirmButton = {})
}
