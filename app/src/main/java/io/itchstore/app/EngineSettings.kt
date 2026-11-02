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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.runtime.DesktopCatalog
import com.droiddeck.launcher.runtime.LinuxFex
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.runtime.RuntimeInstallService
import com.droiddeck.launcher.runtime.UserApps
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.session.SessionDisplay
import com.droiddeck.launcher.session.SessionPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Base Engine & Graphics controls for Proton, Wine, FEX, Resolution, and Per-Game Overrides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var runtimeVersion by remember { mutableStateOf<String?>(null) }
    var protons by remember { mutableStateOf<List<ComponentsManager.ProtonView>>(emptyList()) }
    var androidDriver by remember { mutableStateOf("") }
    var linuxDriver by remember { mutableStateOf("") }
    var directAudio by remember { mutableStateOf(true) }
    var stretch16x9 by remember { mutableStateOf(false) }
    var tuSysmem by remember { mutableStateOf(true) }
    var baseRes by remember { mutableStateOf("1280x720") }
    var syncBackend by remember { mutableStateOf(SessionPrefs.SYNC_NTSYNC) }
    var fexPreset by remember { mutableStateOf("PERFORMANCE_TSO") }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var showTermuxGuide by remember { mutableStateOf(false) }

    var games by remember { mutableStateOf<List<LinuxInstall.Installed>>(emptyList()) }
    var apps by remember { mutableStateOf<List<UserApps.App>>(emptyList()) }
    var editGameFor by remember { mutableStateOf<LinuxInstall.Installed?>(null) }

    fun refresh() {
        scope.launch {
            withContext(Dispatchers.IO) {
                runtimeVersion = runCatching { LinuxRuntimeInstaller.installedVersion(ctx) }.getOrNull()
                protons = if (runtimeVersion != null) runCatching { ComponentsManager.snapshot(ctx).protons }.getOrDefault(emptyList()) else emptyList()
                androidDriver = SessionPrefs.androidDriver(ctx)
                linuxDriver = SessionPrefs.linuxDriver(ctx)
                directAudio = SessionPrefs.directAudio(ctx)
                stretch16x9 = SessionPrefs.stretch16x9(ctx)
                tuSysmem = SessionPrefs.tuSysmem(ctx)
                baseRes = SessionPrefs.resolutionChoice(ctx, "run", 1920 to 1080)
                syncBackend = SessionPrefs.syncBackend(ctx)
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
                message = e.message ?: "Operation failed."
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
        "ARM64 Proton installed successfully."
    }

    fun updateComponent(protonId: String, comp: String) = run("$comp-$protonId") {
        val item = ComponentsManager.catalog(ctx, true).items.filter { it.comp == comp }
            .maxWithOrNull(compareBy({ it.release }, { it.size }))
            ?: throw IllegalStateException("No catalog package found for $comp.")
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
            ?: throw IllegalStateException("Another runtime operation is in progress.")
        RuntimeInstallService.keepRemovalAlive(ctx)
        val ok = removal.run { stage, percent ->
            message = stage
            if (percent >= 0) progress = percent / 100f
        }
        if (ok != true) throw IllegalStateException(LinuxRuntimeInstaller.removalError() ?: "Could not remove runtime.")
        "Runtime removed."
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Engine & Emulation Settings") },
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
                    "Configure global emulation defaults for Proton, FEX, Wine Sync, Resolution, and Graphics.",
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

            // --- Base Display & Resolution ---
            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Base Resolution & Display", fontWeight = FontWeight.Bold)
                        Text(
                            "Base target resolution for games. Gamescope scales this to your screen.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "1280x720" to "720p",
                                "1920x1080" to "1080p",
                                "2560x1440" to "1440p",
                                SessionDisplay.MATCH_SCREEN to "Match Screen"
                            ).forEach { (resVal, resLabel) ->
                                FilterChip(
                                    selected = baseRes == resVal,
                                    onClick = {
                                        baseRes = resVal
                                        SessionPrefs.setResolutionChoice(ctx, "run", resVal)
                                    },
                                    label = { Text(resLabel, fontSize = 12.sp) }
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Stretch 16:9 to Screen", fontSize = 14.sp)
                                Text("Fill full screen height without pillarboxes", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = stretch16x9,
                                onCheckedChange = { on ->
                                    stretch16x9 = on
                                    SessionPrefs.setStretch16x9(ctx, on)
                                }
                            )
                        }
                    }
                }
            }

            // --- Wine Sync Backend ---
            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Wine Sync Backend", fontWeight = FontWeight.Bold)
                        Text(
                            "Synchronization mechanism between Windows game threads.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        listOf(
                            SessionPrefs.SYNC_NTSYNC to "NTSYNC (Kernel Sync - Fast)",
                            SessionPrefs.SYNC_FSYNC to "FSYNC (Fast Userspace)",
                            SessionPrefs.SYNC_ESYNC to "ESYNC (Eventfd)",
                            SessionPrefs.SYNC_WINESERVER to "Wineserver (Fallback)"
                        ).forEach { (id, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = syncBackend == id,
                                    onClick = {
                                        syncBackend = id
                                        SessionPrefs.setSyncBackend(ctx, id)
                                    }
                                )
                                Text(label, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // --- FEX Preset ---
            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("FEX x86-64 Emulation Preset", fontWeight = FontWeight.Bold)
                        Text(
                            "Translation model for x86-64 code: Performance gives higher FPS, Accuracy aids older games.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        listOf(
                            "PERFORMANCE_TSO" to "Performance (TSO Default)",
                            "PERFORMANCE_NO_TSO" to "Performance (No TSO)",
                            "ACCURACY_TSO" to "Accuracy (TSO)",
                            "ACCURACY_NO_TSO" to "Accuracy (No TSO)"
                        ).forEach { (id, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
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

            // --- Proton & Components ---
            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Proton Engine", fontWeight = FontWeight.Bold)
                        if (protons.isEmpty()) {
                            Text(
                                "Proton translates Windows DirectX calls. Install ARM64 Proton to play Windows games.",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button({ installProton() }, enabled = busy == null) { Text("Install ARM64 Proton") }
                        } else {
                            protons.forEach { view ->
                                Column(Modifier.fillMaxWidth()) {
                                    Text(view.proton.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "version ${view.proton.version}" + if (ComponentsManager.inUse(ctx, view.proton)) " · in use" else "",
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
                        Text("DXVK / VKD3D / FEX Components", fontWeight = FontWeight.Bold)
                        Text(
                            "DXVK (DirectX 9-11), VKD3D-Proton (DirectX 12), and FEX translation libraries.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        protons.forEach { view ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val savedOriginals = ComponentsManager.originals(ctx, view.proton.id)
                                ComponentsManager.COMPONENTS.forEach { comp ->
                                    val state = view.components[comp]
                                    val label = mapOf("fex" to "FEX", "dxvk" to "DXVK", "vkd3d" to "VKD3D-Proton")[comp] ?: comp
                                    Column(Modifier.fillMaxWidth()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                                Text(
                                                    state?.detected?.takeIf { it.isNotBlank() } ?: "not present",
                                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            }
                        }
                    }
                }
            }

            // --- GPU & Audio Controls ---
            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Graphics & Audio", fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Turnip Sysmem", fontSize = 14.sp)
                                Text("Vulkan system memory allocation for Adreno GPUs", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = tuSysmem,
                                onCheckedChange = { on ->
                                    tuSysmem = on
                                    SessionPrefs.setTuSysmem(ctx, on)
                                }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("DirectAudio Relay", fontSize = 14.sp)
                                Text("Low-latency game audio relay", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = directAudio,
                                onCheckedChange = { on ->
                                    directAudio = on
                                    SessionPrefs.setDirectAudio(ctx, on)
                                }
                            )
                        }
                        Text("Android Driver: " + androidDriver.ifBlank { "Auto (Turnip)" }, fontSize = 13.sp)
                        Text("Linux Driver: " + linuxDriver.ifBlank { "Runtime Default" }, fontSize = 13.sp)
                    }
                }
            }

            // --- Termux & Android 12+ Setup Guide ---
            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(8.dp))
                            Text("Termux & Android 12+ Setup Commands", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Android 12, 13, and 14+ restrict child processes (Phantom Process Killer), which can kill Proot/Proton sessions. Run these commands once in Termux or ADB to disable restrictions for top performance:",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { showTermuxGuide = !showTermuxGuide }) {
                            Text(if (showTermuxGuide) "Hide Termux Commands" else "Show Termux Commands")
                        }
                        if (showTermuxGuide) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("1. Install ADB in Termux:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("pkg update && pkg install android-tools", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)

                                Text("2. Pair & Connect (Wireless Debugging):", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("adb pair localhost:<PORT> <CODE>\nadb connect localhost:<PORT>", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)

                                Text("3. Disable Phantom Process Restrictions:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "adb shell device_config put activity_manager max_phantom_processes 2147483647\n" +
                                        "adb shell settings put global settings_enable_monitor_phantom_procs false",
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // --- Per-Game Overrides Section ---
            if (games.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Installed Games & Overrides", fontWeight = FontWeight.Bold)
                        games.forEach { entry ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    val platform = if (entry.windows) "Windows (Proton)" else "Linux"
                                    val res = LinuxInstall.gameResolution(ctx, entry.url).ifBlank { "Base" }
                                    val sync = LinuxInstall.gameSyncBackend(ctx, entry.url).ifBlank { "Base" }
                                    Text("$platform · Res: $res · Sync: $sync", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton({ editGameFor = entry }) {
                                    Icon(Icons.Default.Settings, "Game Settings")
                                    Spacer(Modifier.size(4.dp))
                                    Text("Settings")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Linux Runtime", fontWeight = FontWeight.Bold)
                        Text(
                            runtimeVersion?.let { "Installed: $it" } ?: "Not installed.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton({ confirmRemove = true }, enabled = runtimeVersion != null && busy == null) {
                            Text("Remove Runtime", color = MaterialTheme.colorScheme.error)
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
            title = { Text("Remove Linux Runtime?") },
            text = { Text("The proot Linux environment and all game files will be deleted (frees ~1 GB).") },
            confirmButton = {
                TextButton({ confirmRemove = false; removeRuntime() }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel") } }
        )
    }

    // --- Per-Game Settings Dialog ---
    editGameFor?.let { game ->
        PerGameSettingsDialog(
            game = game,
            apps = apps,
            onDismiss = { editGameFor = null; refresh() }
        )
    }
}

@Composable
fun PerGameSettingsDialog(
    game: LinuxInstall.Installed,
    apps: List<UserApps.App>,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var argsDraft by remember { mutableStateOf(LinuxInstall.compatArgs(ctx, game.url)) }
    var selectedRes by remember { mutableStateOf(LinuxInstall.gameResolution(ctx, game.url)) }
    var selectedSync by remember { mutableStateOf(LinuxInstall.gameSyncBackend(ctx, game.url)) }
    var selectedFex by remember { mutableStateOf(LinuxInstall.gameFexPreset(ctx, game.url)) }

    val app = apps.firstOrNull { it.key == game.key }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${game.title} Settings") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("Game Resolution Override", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("" to "Base", "1280x720" to "720p", "1920x1080" to "1080p", "2560x1440" to "1440p").forEach { (valStr, labelStr) ->
                            FilterChip(
                                selected = selectedRes == valStr,
                                onClick = { selectedRes = valStr },
                                label = { Text(labelStr, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                item {
                    Text("Wine Sync Override", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("" to "Base", SessionPrefs.SYNC_NTSYNC to "NTSYNC", SessionPrefs.SYNC_FSYNC to "FSYNC", SessionPrefs.SYNC_ESYNC to "ESYNC").forEach { (valStr, labelStr) ->
                            FilterChip(
                                selected = selectedSync == valStr,
                                onClick = { selectedSync = valStr },
                                label = { Text(labelStr, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                item {
                    Text("FEX Preset Override", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("" to "Base", "PERFORMANCE_TSO" to "Perf TSO", "ACCURACY_TSO" to "Acc TSO").forEach { (valStr, labelStr) ->
                            FilterChip(
                                selected = selectedFex == valStr,
                                onClick = { selectedFex = valStr },
                                label = { Text(labelStr, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                if (!game.windows && app != null) {
                    item {
                        Text("Linux FEX Mode", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinuxFex.MODES.forEach { mode ->
                                FilterChip(
                                    selected = app.fex == mode,
                                    onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                UserApps.edit(ctx, app, app.name, null, mode)
                                            }
                                        }
                                    },
                                    label = { Text(mode, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                item {
                    Text("Extra Launch Arguments / Envs", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("E.g. MESA_VK_WNODE_ENABLE=1 DXVK_HUD=1", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = argsDraft,
                        onValueChange = { argsDraft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton({
                LinuxInstall.setCompatArgs(ctx, game.url, argsDraft)
                LinuxInstall.setGameResolution(ctx, game.url, selectedRes)
                LinuxInstall.setGameSyncBackend(ctx, game.url, selectedSync)
                LinuxInstall.setGameFexPreset(ctx, game.url, selectedFex)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}
