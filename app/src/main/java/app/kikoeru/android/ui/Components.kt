package app.kikoeru.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kikoeru.android.AppContainer
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

@Composable
fun Cover(url: String?, container: AppContainer, modifier: Modifier = Modifier) {
    val privacy by container.privacyMode.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    val placeholder: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Headphones, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
    key(session?.profile?.scope, url, privacy) { SubcomposeAsyncImage(
        model = ImageRequest.Builder(container.context).data(url?.replace("?type=360x360", "?type=main"))
            .memoryCacheKey("${session?.profile?.scope}|$url|privacy=$privacy")
            .crossfade(false)
            .apply { if (privacy) transformations(PrivacyBlurTransformation()) }
            .build(),
        imageLoader = container.imageLoader,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(16.dp)),
        loading = { placeholder() }, error = { placeholder() },
    ) }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun ActionIcon(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, description) }
}

fun durationText(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
