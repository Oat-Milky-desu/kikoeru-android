@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.kikoeru.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun ConnectionScreen(state: AppState, onConnect: (String, String, String, Boolean) -> Unit, embedded: Boolean = false) {
    var address by rememberSaveable(state.profile?.baseUrl) { mutableStateOf(state.profile?.baseUrl ?: "") }
    var username by rememberSaveable { mutableStateOf(state.profile?.username?.takeUnless { it == "admin" } ?: "") }
    // Password deliberately never enters saved-instance state or persistent preferences.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var allowHttp by rememberSaveable { mutableStateOf(state.profile?.allowHttp ?: false) }
    var credentials by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.authRequired) { if (state.authRequired) credentials = true }
    LaunchedEffect(state.profile) { password = "" }

    Column(Modifier.fillMaxSize().then(if (!embedded) Modifier.safeDrawingPadding() else Modifier)
        .imePadding().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(if (embedded) 0.dp else 36.dp))
            if (!embedded) {
                FilledTonalIconButton(onClick = {}, modifier = Modifier.size(72.dp)) { Icon(Icons.Outlined.Headphones, null, Modifier.size(36.dp)) }
                Text("让声音，陪你慢下来。", style = MaterialTheme.typography.headlineMedium)
                Text("连接你的 Kikoeru 音声库，随时继续上一次聆听。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            Text("连接服务器", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(address, { address = it }, label = { Text("服务器地址") },
                placeholder = { Text("https://kikoeru.example.com") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), leadingIcon = { Icon(Icons.Outlined.Dns, null) }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(allowHttp, { allowHttp = it })
                Text("允许此服务器使用 HTTP", style = MaterialTheme.typography.bodyMedium)
            }
            if (allowHttp) Text("HTTP 不加密传输，仅建议用于可信的局域网。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("使用账号登录", Modifier.weight(1f)); Switch(credentials, { credentials = it })
            }
            if (credentials) {
                OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { ActionIcon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "隐藏密码" else "显示密码") { visible = !visible } }, modifier = Modifier.fillMaxWidth())
            }
            state.connectionError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = { onConnect(address, if (credentials) username else "", if (credentials) password else "", allowHttp) },
                enabled = address.isNotBlank() && !state.connecting, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (state.connecting) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }
                Text(if (state.connecting) "正在测试连接…" else "连接并进入音声库")
            }
            Text("资源来自你自己的服务器，应用不提供音声资源。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
