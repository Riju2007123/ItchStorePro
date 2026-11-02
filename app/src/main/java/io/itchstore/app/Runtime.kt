package io.itchstore.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.gpu.DriverBundle
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val BANNERS_RELEASE = "https://github.com/The412Banner/Banners-Turnip/releases/tag/v27.0.0-20261102"

data class DriverPack(
    val label: String,
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String
)

/** The exact Adreno 710/720 test driver DroidDeck picks for this GPU. */
val DRIVERS = listOf(
    DriverPack(
        label = "Android driver",
        name = "Turnip-v27.0.0-20261102-710-720-Test.zip",
        url = "https://github.com/The412Banner/Banners-Turnip/releases/download/v27.0.0-20261102/Turnip-v27.0.0-20261102-710-720-Test.zip",
        size = 2_646_568L,
        sha256 = "3048a1bedc98b24c69f9946c99810be2563e6645b245bc50aabbb0383e3ba868"
    ),
    DriverPack(
        label = "Linux driver",
        name = "Turnip-v27.0.0-20261102-710-720-Test-Linux.zip",
        url = "https://github.com/The412Banner/Banners-Turnip/releases/download/v27.0.0-20261102/Turnip-v27.0.0-20261102-710-720-Test-Linux.zip",
        size = 3_228_633L,
        sha256 = "a45808a87998708fde6ec4e54c58d818d8601cddbe0659ae44c9b1b137373818"
    )
)

@Composable
fun RuntimeScreen(vm: AppVM, onOpenEngine: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var runtimeVersion by remember { mutableStateOf<String?>(null) }
    var games by remember { mutableStateOf<List<LinuxInstall.Installed>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val version = withContext(Dispatchers.IO) {
                runCatching { LinuxRuntimeInstaller.installedVersion(ctx) }.getOrNull()
            }
            val installed = withContext(Dispatchers.IO) { LinuxInstall.list(ctx) }
            runtimeVersion = version
            games = installed
        }
    }
    LaunchedEffect(Unit) { refresh() }

    fun installRuntime() {
        if (busy != null) return
        busy = "runtime"
        progress = -1f
        message = "Checking the runtime catalog"
        failed = false
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { LinuxRuntimeInstaller.fetchRelease() }
                    ?: throw IllegalStateException("Could not reach the runtime catalog. Check the connection and try again.")
                message = "Downloading ${release.version}"
                val ok = withContext(Dispatchers.IO) {
                    LinuxRuntimeInstaller.install(ctx, release) { stage, percent ->
                        message = stage
                        if (percent >= 0) progress = percent / 100f
                    }
                }
                if (!ok) throw IllegalStateException("The runtime download or unpack failed. Check the connection — the install resumes where it stopped.")
                progress = 1f
                message = "Linux runtime ${release.version} is ready."
            } catch (e: Exception) {
                failed = true
                message = e.message ?: "The runtime install failed."
            } finally {
                busy = null
                refresh()
            }
        }
    }

    fun installDriver(p: DriverPack) {
        if (busy != null) return
        busy = p.name
        progress = -1f
        message = "Downloading ${p.name}"
        failed = false
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { fetchVerified(ctx, p) { done, total -> if (total > 0) progress = done.toFloat() / total } }
                val id = withContext(Dispatchers.IO) {
                    val uri = Uri.fromFile(file)
                    val name = p.name.substringBeforeLast('.')
                    when {
                        DriverBundle.isBundle(ctx, uri) -> {
                            val bundle = DriverBundle.install(ctx, uri)
                            DriverBundle.select(ctx, bundle)
                            bundle.id
                        }
                        p.label == "Linux driver" -> {
                            val driverId = LinuxVulkanDriverManager(ctx).installDriver(uri, name)
                            SessionPrefs.setLinuxDriver(ctx, driverId)
                            driverId
                        }
                        else -> {
                            val driverId = TurnipDriver(ctx).installFromZip(uri, name)
                            SessionPrefs.setAndroidDriver(ctx, driverId)
                            driverId
                        }
                    }
                }
                progress = 1f
                message = "${p.label} installed and selected ($id). Restart any running game."
            } catch (e: Exception) {
                failed = true
                message = e.message ?: "The driver install failed."
            } finally {
                busy = null
                refresh()
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Linux on Android", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item {
            Text(
                "This app ships the whole DroidDeck engine: a proot Linux runtime, FEX, Proton, DXVK/VKD3D and the gamescope session. Linux builds from itch install here and play here.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val installed = runtimeVersion != null
                    Text("1. Linux runtime", fontWeight = FontWeight.Bold)
                    Text(
                        if (installed) "Installed: ${runtimeVersion}. The engine is ready."
                        else "Not installed. The runtime is downloaded once (about 300 MB, expands to roughly 1 GB) and unpacked into the app's private storage.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ installRuntime() }, enabled = busy == null) {
                            Text(
                                when {
                                    busy == "runtime" -> "Installing…"
                                    installed -> "Update"
                                    else -> "Install runtime"
                                }
                            )
                        }
                        OutlinedButton({ onOpenEngine() }) { Text("Engine & graphics") }
                    }
                    if (installed) Text(
                        "Keep this app in the foreground while the runtime installs.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (games.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Installed Linux games", fontWeight = FontWeight.Bold)
                        games.forEach { entry ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton({ LinuxInstall.play(ctx, entry) }) { Text("Play") }
                                TextButton({
                                    scope.launch {
                                        withContext(Dispatchers.IO) { LinuxInstall.remove(ctx, entry.url) }
                                        refresh()
                                    }
                                }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.size(8.dp))
                        Text("Adreno 710 / 720 driver", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "DroidDeck lists the Adreno 710 as unsupported. These are Banners-Turnip test builds pinned to v27.0.0-20261102, the same files DroidDeck selects for 710/720. Expect crashes, artefacts or no boot. Installing puts the driver into the engine and selects it.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton({ openUrl(ctx, BANNERS_RELEASE) }) { Text("Driver release page") }

                    DRIVERS.forEach { p ->
                        DriverRow(
                            p,
                            active = busy == p.name,
                            progress = if (busy == p.name) progress else -1f,
                            onInstall = { installDriver(p) }
                        )
                    }

                    message?.let {
                        Text(
                            it,
                            fontSize = 12.sp,
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("What the engine does", fontWeight = FontWeight.Bold)
                    Text(
                        "Games run in a proot Arch/Ubuntu ARM64 rootfs under a gamescope Wayland session. x86-64 Windows games go through Proton with FEX translating x86 to ARM; DXVK and VKD3D-Proton are patched into Proton automatically. Native Linux x86-64 builds run through FEX the same way.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Engine & graphics opens this app's own controls for Proton, DXVK/VKD3D, FEX, drivers and audio.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Blocking download with the pinned checksum, kept in the app cache. */
private fun fetchVerified(ctx: Context, p: DriverPack, onProgress: (Long, Long) -> Unit): File {
    val dir = File(ctx.cacheDir, "drivers").apply { mkdirs() }
    val out = File(dir, p.name)
    Repo.fetchTo(p.url, out) { done, total -> onProgress(done, total) }
    val hash = Repo.sha256(out)
    if (!hash.equals(p.sha256, ignoreCase = true)) {
        out.delete()
        throw IllegalStateException("Checksum mismatch. Delete the file and download again.")
    }
    return out
}

@Composable
private fun DriverRow(p: DriverPack, active: Boolean, progress: Float, onInstall: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    p.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${fmtSize(p.size)} · sha256 ${p.sha256.take(12)}…",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onInstall, enabled = !active) { Text(if (active) "…" else "Install") }
        }
        if (active) {
            if (progress >= 0f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("${(progress * 100).toInt()}%", fontSize = 11.sp)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
