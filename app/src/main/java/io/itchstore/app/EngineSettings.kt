package io.itchstore.app

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LinuxFex
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.runtime.RuntimeInstallService
import com.droiddeck.launcher.runtime.UserApps
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The engine controls that matter for playing games, driven straight through DroidDeck's own
 * managers. The DroidDeck front end stays compiled in for the session, but nothing here opens it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EngineScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var runtimeVersion by remember { mutableStateOf<String?>(null) }
    var protons by remember { mutableStateOf<List<ComponentsManager.ProtonView>>(emptyList()) }
    var androidDriver by remember { mutableStateOf("") }
    var linuxDriver by remember { mutableStateOf("") }
    var directAudio by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var fexPreset by remember { mutableStateOf("PERFORMANCE_TSO") }
    var games by remember { mutableStateOf<List<LinuxInstall.Installed>>(emptyList()) }
    var apps by remember { mutableStateOf<List<UserApps.App>>(emptyList()) }
    var editArgsFor by remember { mutableStateOf<LinuxInstall.Installed?>(null) }
    var argsDraft by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            withContext(Dispatchers.IO) {
                runtimeVersion = runCatching { LinuxRuntimeInstaller.installedVersion(ctx) }.getOrNull()
                protons = if (runtimeVersion != null) runCatching { ComponentsManager.snapshot(ctx).protons }.getOrDefault(emptyList()) else emptyList()
                androidDriver = SessionPrefs.androidDriver(ctx)
                linuxDriver = SessionPrefs.linuxDriver(ctx)
                directAudio = SessionPrefs.directAudio(ctx)
                fexPreset = SessionPrefs.fexPreset(ctx)
                games = LinuxInstall.list(ctx)
                apps = runCatching { UserApps.list(ctx) }.getOrDefault(emptyList())
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    fun run(label: String, work: () -> String?) {
        if (busy != null) return
        busy = label
        progress = -1f
        message = null
        failed = false
        scope.launch {
            try {
                message = withContext(Dispatchers.IO) { work() } ?: "Done."
                progress = 1f
            } catch (e: Exception) {
                failed = true
                message = e.message ?: "That failed."
            } finally {
                busy = null
                refresh()
            }
        }
    }

    fun installProton() = run("proton") {
        val entry = DesktopCatalog.fetch(DesktopCatalog.STEAM_SEED_URL)
            ?.firstOrNull { it.id == DesktopCatalog.PROTON_SEED_ID }
            ?: throw IllegalStateException("Could not reach the Proton catalog.")
        DesktopCatalog.install(ctx, entry) { stage, percent ->
            message = stage
            if (percent >= 0) progress = percent / 100f
        }.let { problem -> if (problem != null) throw IllegalStateException(problem) }
        "ARM64 Proton installed. Windows games can start now."
    }

    fun updateComponent(protonId: String, comp: String) = run("$comp-$protonId") {
        val item = ComponentsManager.catalog(ctx, true).items.filter { it.comp == comp }
            .maxWithOrNull(compareBy({ it.release }, { it.size }))
            ?: throw IllegalStateException("The component catalog has no $comp package yet.")
        message = "Downloading ${item.release}"
        val pkg = ComponentsManager.download(ctx, item) { percent -> progress = percent / 100f }
        ComponentsManager.swap(ctx, protonId, pkg.file)
    }

    fun restoreComponent(protonId: String, comp: String) = run("restore-$comp-$protonId") {
        val original = ComponentsManager.originals(ctx, protonId).firstOrNull { it.comp == comp }
            ?: throw IllegalStateException("No saved original for $comp.")
        ComponentsManager.restore(ctx, protonId, comp, original.protonVersion)
    }

    fun removeRuntime() = run("remove") {
        val removal = LinuxRuntimeInstaller.beginUninstall(ctx)
            ?: throw IllegalStateException("Another runtime operation is running.")
        RuntimeInstallService.keepRemovalAlive(ctx)
        val ok = removal.run { stage, percent ->
            message = stage
            if (percent >= 0) progress = percent / 100f
        }
        if (ok != true) throw IllegalStateException(LinuxRuntimeInstaller.removalError() ?: "Could not remove the runtime.")
        "Runtime removed. Install it again any time from the Linux tab."
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Engine & graphics") },
                navigationIcon = {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "These controls change the same settings the game engine reads. They apply to the next game you start.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            message?.let {
                item {
                    Text(
                        it, fontSize = 13.sp,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (busy != null) item {
                if (progress >= 0f) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Proton", fontWeight = FontWeight.Bold)
                        if (protons.isEmpty()) {
                            Text(
                                "No Proton yet. It is fetched automatically the first time a Windows game starts, or install it now (about 400 MB).",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button({ installProton() }, enabled = busy == null) { Text("Install ARM64 Proton") }
                        } else {
                            protons.forEach { view ->
                                Column(Modifier.fillMaxWidth()) {
                                    Text(view.proton.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "version ${view.proton.version}" + if (ComponentsManager.inUse(ctx, view.proton)) " · running" else "",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (protons.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Game components", fontWeight = FontWeight.Bold)
                        Text(
                            "DXVK translates DirectX 9–11 to Vulkan, VKD3D-Proton does DirectX 12, FEX ships the Windows DLLs x86-64 games load. Updating patches the newest build into Proton.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        protons.forEach { view ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(view.proton.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                val savedOriginals = ComponentsManager.originals(ctx, view.proton.id)
                                ComponentsManager.COMPONENTS.forEach { comp ->
                                    val state = view.components[comp]
                                    val label = mapOf("fex" to "FEX", "dxvk" to "DXVK", "vkd3d" to "VKD3D-Proton")[comp] ?: comp
                                    Column(Modifier.fillMaxWidth()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(label, fontSize = 14.sp)
                                                Text(
                                                    state?.detected?.takeIf { it.isNotBlank() } ?: "not present",
                                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    state?.inUse?.takeIf { it.isNotBlank() } ?: "",
                                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            TextButton({ updateComponent(view.proton.id, comp) }, enabled = busy == null) {
                                                Text("Update")
                                            }
                                            if (savedOriginals.any { it.comp == comp }) {
                                                TextButton({ restoreComponent(view.proton.id, comp) }, enabled = busy == null) {
                                                    Text("Restore")
                                                }
                                            }
                                        }
                                    }
                                }
                                Text(
                                    "Original Proton build: ${view.proton.version}",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("GPU drivers", fontWeight = FontWeight.Bold)
                        Text(
                            "Import the Adreno 710/720 builds from the Linux tab; they are selected automatically when installed.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Android driver: " + (androidDriver.ifBlank { "auto (bundled Turnip)" }),
                            fontSize = 13.sp
                        )
                        Text(
                            "Linux driver: " + (linuxDriver.ifBlank { "runtime default" }),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Audio", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("DirectAudio", fontSize = 14.sp)
                                Text(
                                    "Low-latency game audio relay. Turn off only if sound stutters.",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = directAudio,
                                onCheckedChange = { on ->
                                    directAudio = on
                                    SessionPrefs.setDirectAudio(ctx, on)
                                }
                            )
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("FEX preset", fontWeight = FontWeight.Bold)
                        Text(
                            "How x86-64 code is translated: accuracy keeps old games stable, performance trades a little of it for speed.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        listOf(
                            "PERFORMANCE_TSO" to "Performance (default)",
                            "PERFORMANCE_NO_TSO" to "Performance, no TSO",
                            "ACCURACY_TSO" to "Accuracy",
                            "ACCURACY_NO_TSO" to "Accuracy, no TSO"
                        ).forEach { (id, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.RadioButton(
                                    selected = fexPreset == id,
                                    onClick = {
                                        fexPreset = id
                                        SessionPrefs.setFexPreset(ctx, id)
                                    }
                                )
                                Text(label, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            if (games.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Per-game settings", fontWeight = FontWeight.Bold)
                        Text(
                            "Compatibility arguments are passed to the game on launch. FEX mode forces x86 translation on or off for a Linux build.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        games.forEach { entry ->
                            Column(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.title, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1)
                                    TextButton({
                                        argsDraft = LinuxInstall.compatArgs(ctx, entry.url)
                                        editArgsFor = entry
                                    }) { Text("Arguments") }
                                }
                                val compat = LinuxInstall.compatArgs(ctx, entry.url)
                                if (compat.isNotBlank()) Text(
                                    "args: $compat", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!entry.windows) {
                                    val app = apps.firstOrNull { it.key == entry.key }
                                    if (app != null) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            LinuxFex.MODES.forEach { mode ->
                                                androidx.compose.material3.FilterChip(
                                                    selected = app.fex == mode,
                                                    onClick = {
                                                        scope.launch {
                                                            withContext(Dispatchers.IO) {
                                                                UserApps.edit(ctx, app, app.name, null, mode)
                                                            }
                                                            refresh()
                                                        }
                                                    },
                                                    label = { Text(mode, fontSize = 12.sp) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Linux runtime", fontWeight = FontWeight.Bold)
                        Text(
                            runtimeVersion?.let { "Installed: $it" } ?: "Not installed.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton({ confirmRemove = true }, enabled = runtimeVersion != null && busy == null) {
                            Text("Remove runtime", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the Linux runtime?") },
            text = { Text("Every installed Linux game and the runtime itself are deleted (about 1 GB freed). Windows games in the library lose their data too.") },
            confirmButton = {
                TextButton({ confirmRemove = false; removeRuntime() }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel") } }
        )
    }

    editArgsFor?.let { entry ->
        AlertDialog(
            onDismissRequest = { editArgsFor = null },
            title = { Text("Arguments for ${entry.title}") },
            text = {
                Column {
                    Text(
                        "Added after the engine's own launch words. Use spaces between words.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = argsDraft,
                        onValueChange = { argsDraft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton({
                    LinuxInstall.setCompatArgs(ctx, entry.url, argsDraft)
                    editArgsFor = null
                    refresh()
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ editArgsFor = null }) { Text("Cancel") } }
        )
    }
}
