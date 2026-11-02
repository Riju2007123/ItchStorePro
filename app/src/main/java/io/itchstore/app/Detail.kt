package io.itchstore.app

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: AppVM, g: Game) {
    val ctx = LocalContext.current
    var d by remember(g.url) { mutableStateOf<Detail?>(null) }
    var err by remember(g.url) { mutableStateOf<String?>(null) }
    var ups by remember(g.url) { mutableStateOf<List<Upload>?>(null) }
    var upErr by remember(g.url) { mutableStateOf<String?>(null) }

    LaunchedEffect(g.url) {
        try { d = Repo.detail(g) } catch (e: Exception) { err = e.message ?: "Network error" }
    }
    val gid = g.id.takeIf { s -> s.isNotEmpty() && s.all { it.isDigit() } } ?: d?.gameId
    LaunchedEffect(gid, vm.apiKey) {
        if (gid != null && vm.apiKey.isNotBlank()) {
            try { ups = Repo.uploads(vm.apiKey, gid, g.dlKey); upErr = null }
            catch (e: Exception) { upErr = e.message }
        }
    }
    val saved = vm.saved.any { it.url == g.url }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(g.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton({ vm.selected = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton({ vm.toggleSave(g) }) {
                        Icon(
                            if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Save",
                            tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton({
                        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, g.url)
                        ctx.startActivity(Intent.createChooser(i, null))
                    }) { Icon(Icons.Default.Share, "Share") }
                }
            )
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                AsyncImage(
                    model = d?.cover ?: g.cover, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(d?.title ?: g.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (g.author.isNotBlank()) Text(g.author, color = MaterialTheme.colorScheme.primary)
                    OutlinedButton({ openUrl(ctx, g.url) }, Modifier.fillMaxWidth()) {
                        val p = d?.price ?: g.price
                        Text(if (p.isNullOrBlank()) "Open on itch.io" else "Open on itch.io · $p")
                    }
                }
            }
            item { DownloadsSection(vm, g, ups, upErr) }
            if (d == null && err == null) item {
                Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
            }
            err?.let { e -> item { Text("Couldn't load details ($e).", Modifier.padding(16.dp), MaterialTheme.colorScheme.error) } }
            d?.let { det ->
                if (det.shots.isNotEmpty()) item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(det.shots) { s ->
                            AsyncImage(
                                model = s, contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.height(150.dp).width(240.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                    }
                }
                if (det.info.isNotEmpty()) item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        det.info.forEach { (k, v) ->
                            Row {
                                Text(k, Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                Text(v, Modifier.weight(1f), fontSize = 13.sp)
                            }
                        }
                    }
                }
                item { Text(det.description, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlatformPills(list: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        list.forEach { p ->
            Text(
                p, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
fun DownloadsSection(vm: AppVM, g: Game, ups: List<Upload>?, err: String?) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Downloads", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            when {
                vm.apiKey.isBlank() -> Text(
                    "Connect your itch.io API key in the Account tab to download here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                )
                err != null -> Text(err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                ups == null -> CircularProgressIndicator(Modifier.padding(4.dp))
                ups.isEmpty() -> Text(
                    "No downloadable files. This one may be browser-only.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                )
                else -> {
                    val plats = ups.flatMap { it.platforms }.distinct()
                    if (plats.isNotEmpty()) PlatformPills(plats)
                    if (ups.any { "Linux" in it.platforms }) {
                        Text(
                            "Linux builds install straight into the built-in Linux runtime and get a Play button.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (ups.size > 1) {
                        Button(
                            onClick = { ups.forEach { vm.download(it, g.dlKey, autoInstall = false) } },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Download all (${ups.size})") }
                    }
                    ups.forEach { up -> UploadRow(vm, g, up) }
                }
            }
        }
    }
}

@Composable
fun UploadRow(vm: AppVM, g: Game, up: Upload) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = vm.downloads[up.id]
    val isLinux = !up.isApk && "Linux" in up.platforms
    val isWindows = !up.isApk && !isLinux && "Windows" in up.platforms
    var installed by remember(g.url) { mutableStateOf<LinuxInstall.Installed?>(null) }
    LaunchedEffect(g.url) {
        installed = withContext(Dispatchers.IO) { LinuxInstall.get(ctx, g.url) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(up.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            val s = fmtSize(up.size)
            if (s.isNotEmpty()) Text(s, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (up.platforms.isNotEmpty()) PlatformPills(up.platforms)

        when (st?.status) {
            "downloading" -> {
                if (st.progress >= 0f) {
                    LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(st.progress * 100).toInt()}%", fontSize = 12.sp)
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            "installing" -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(st.stage.ifBlank { "Installing…" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "ready", "done" -> {
                val f = st.file
                if (st.status == "ready") {
                    val entry = st.appKey?.let { key -> installed?.takeIf { it.key == key } }
                        ?: LinuxInstall.get(ctx, g.url)
                    Button(
                        { entry?.let { LinuxInstall.play(ctx, it) } },
                        Modifier.fillMaxWidth(),
                        enabled = entry != null
                    ) { Text("Play") }
                    OutlinedButton(
                        {
                            scope.launch {
                                withContext(Dispatchers.IO) { LinuxInstall.remove(ctx, g.url) }
                                installed = null
                            }
                        },
                        Modifier.fillMaxWidth()
                    ) { Text("Remove from library") }
                } else if (f != null) {
                    if (up.isApk) {
                        Button({ installApk(ctx, f) }, Modifier.fillMaxWidth()) { Text("Install") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { saveToDownloads(ctx, f) }
                                    Toast.makeText(
                                        ctx, if (ok) "Saved to Downloads/ItchStore" else "Couldn't save",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }) { Text("Save to Downloads") }
                            OutlinedButton({ openFile(ctx, f) }) { Text("Open") }
                        }
                    }
                }
            }
            else -> {
                if (isLinux && installed != null) {
                    Button({ LinuxInstall.play(ctx, installed!!) }, Modifier.fillMaxWidth()) { Text("Play") }
                    OutlinedButton(
                        {
                            scope.launch {
                                withContext(Dispatchers.IO) { LinuxInstall.remove(ctx, g.url) }
                                installed = null
                            }
                        },
                        Modifier.fillMaxWidth()
                    ) { Text("Remove from library") }
                } else {
                    Button({ vm.download(up, g.dlKey, game = g, linux = isLinux, windows = isWindows) }, Modifier.fillMaxWidth()) {
                        Text(
                            when {
                                isLinux || isWindows -> "Install & play"
                                up.isApk -> "Download and install"
                                else -> "Download"
                            }
                        )
                    }
                }
                st?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        }
    }
}
