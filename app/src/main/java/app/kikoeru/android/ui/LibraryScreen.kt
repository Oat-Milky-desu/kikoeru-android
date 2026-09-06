@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kikoeru.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kikoeru.android.data.Work

@Composable
fun LibraryScreen(state: AppState, vm: MainViewModel, gridState: LazyGridState, searches: List<String>) {
    var sorting by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Column {
        TopAppBar(title = { Text("音声库") }, actions = {
            ActionIcon(Icons.Outlined.Refresh, "刷新作品", !state.loading) { vm.load() }
            ActionIcon(Icons.Outlined.Sort, "排序") { sorting = true }
        })
        OutlinedTextField(state.query.keyword, vm::search,
            placeholder = { Text("搜索作品、声优、社团或标签", maxLines = 1) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (state.query.keyword.isNotEmpty()) ActionIcon(Icons.Outlined.Close, "清空搜索") { vm.search("") } },
            singleLine = true, shape = MaterialTheme.shapes.extraLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.rememberSearch(); focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        state.query.filter?.let {
            InputChip(selected = true, onClick = vm::clearFilter, label = { Text(it.title) },
                trailingIcon = { Icon(Icons.Outlined.Close, "移除筛选") }, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        }
        if (searches.isNotEmpty() && state.query.keyword.isEmpty() && state.query.filter == null) {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                searches.take(3).forEach { word -> SuggestionChip(onClick = { vm.search(word) }, label = { Text(word, maxLines = 1) }) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (state.query.keyword.isNotBlank()) "搜索结果" else "全部作品", style = MaterialTheme.typography.titleMedium)
            Text("${state.total} 部作品", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val scaled = LocalDensity.current.fontScale > 1.3f
        LazyVerticalGrid(columns = GridCells.Adaptive(if (scaled) 240.dp else 156.dp), state = gridState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading && state.works.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(state.works, key = { it.id }) { work -> WorkCard(work, vm) }
            if (state.browseError != null) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(Icons.Outlined.CloudOff, "暂时无法加载", state.browseError, "重试") { vm.load(more = state.works.isNotEmpty()) }
            }
            if (!state.loading && state.browseError == null && state.works.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(Icons.Outlined.LibraryMusic, if (state.query.keyword.isBlank()) "这里还没有作品" else "没有找到匹配作品", "试试其他关键词，或检查服务器是否已扫描音声库。")
            }
            if (state.works.isNotEmpty() && state.works.size < state.total && state.browseError == null) item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedButton(onClick = { vm.load(more = true) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.loading) "加载中…" else "加载更多")
                }
            }
        }
    }
    if (sorting) SortSheet(state.query.order, state.query.sort, { sorting = false }) { order, direction -> vm.sort(order, direction); sorting = false }
}

@Composable
private fun WorkCard(work: Work, vm: MainViewModel) {
    Card(onClick = { vm.rememberSearch(); vm.openWork(work) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Cover(vm.container.repository?.cover(work.id), vm.container, Modifier.fillMaxWidth().aspectRatio(1f))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(work.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Text(work.circle?.name?.ifBlank { "未知社团" } ?: "未知社团", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (work.vas.isNotEmpty()) Text(work.vas.joinToString(" / ") { it.name }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SortSheet(current: String, sort: String, close: () -> Unit, apply: (String, String) -> Unit) {
    var order by remember { mutableStateOf(current) }
    var descending by remember { mutableStateOf(sort == "desc") }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("作品排序", style = MaterialTheme.typography.titleLarge)
            listOf("release" to "发行日期", "created_at" to "入库时间", "id" to "作品编号", "rating" to "我的评分", "dl_count" to "下载数量").forEach { (value, label) ->
                Row(Modifier.fillMaxWidth().clickable { order = value }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(order == value, { order = value }); Text(label)
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("降序", Modifier.weight(1f)); Switch(descending, { descending = it }) }
            Button(onClick = { apply(order, if (descending) "desc" else "asc") }, modifier = Modifier.fillMaxWidth()) { Text("应用") }
        }
    }
}
