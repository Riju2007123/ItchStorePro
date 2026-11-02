package io.itchstore.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.runtime.UserApps
import com.droiddeck.launcher.session.NoSteamLaunch
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.stores.StoreExe
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Installs an itch.io upload into the bundled DroidDeck engine. Linux builds register as user apps
 * so the runtime's droiddeck-script-run starts them (FEX translates x86-64 on the fly); Windows
 * builds land in the guest Games folder and start through droiddeck-proton-run, which Proton,
 * DXVK and VKD3D serve exactly as the DroidDeck launcher does.
 */
object LinuxInstall {
    data class Installed(
        val url: String,
        val title: String,
        val windows: Boolean,
        val cover: String?,
        val key: String?,
        val program: String,
        val args: List<String>,
        val dir: String? = null
    )

    private const val PREFS = "linux_games"
    private const val WRAPPER = "itch-start.sh"
    private const val ELF_SCAN_LIMIT = 4000
    private val SKIP_DIRS = setOf("lib", "lib64", "lib32", "libexec", "locale", "locales", "man", "share")
    private val SKIP_NAMES = listOf("uninstall", "unins", "crash", "report", "setup", "install")

    fun runtimeReady(ctx: Context): Boolean = runCatching { LinuxRuntime.isInstalled(ctx) }.getOrDefault(false)

    fun list(ctx: Context): List<Installed> = try {
        val a = JSONArray(prefs(ctx).getString("games", "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            val program = o.optString("program")
            if (url.isEmpty() || program.isEmpty()) null
            else Installed(
                url, o.optString("title"), o.optBoolean("windows"),
                o.optString("cover").ifBlank { null },
                o.optString("key").ifBlank { null },
                program,
                jsonStrings(o, "args"),
                o.optString("dir").ifBlank { null }
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun jsonStrings(o: JSONObject, name: String): List<String> {
        val array = o.optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }
    }

    fun get(ctx: Context, url: String): Installed? = list(ctx).firstOrNull { it.url == url }

    private fun save(ctx: Context, item: Installed) {
        val kept = list(ctx).filterNot { it.url == item.url }
        val a = JSONArray()
        (kept + item).forEach { entry ->
            val args = JSONArray()
            entry.args.forEach { args.put(it) }
            a.put(
                JSONObject().put("url", entry.url).put("title", entry.title)
                    .put("windows", entry.windows).put("cover", entry.cover ?: "")
                    .put("key", entry.key ?: "").put("program", entry.program).put("args", args)
                    .put("dir", entry.dir ?: "")
            )
        }
        prefs(ctx).edit().putString("games", a.toString()).apply()
    }

    fun forget(ctx: Context, url: String) {
        val a = JSONArray()
        list(ctx).filterNot { it.url == url }.forEach {
            val args = JSONArray()
            it.args.forEach { arg -> args.put(arg) }
            a.put(
                JSONObject().put("url", it.url).put("title", it.title).put("windows", it.windows)
                    .put("cover", it.cover ?: "").put("key", it.key ?: "").put("program", it.program)
                    .put("args", args).put("dir", it.dir ?: "")
            )
        }
        prefs(ctx).edit().putString("games", a.toString()).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Blocking. Returns null when the game is ready to play, or a message for the user. */
    fun install(ctx: Context, game: Game, upload: Upload, archive: File, windows: Boolean, onStage: (String) -> Unit): String? {
        if (!runtimeReady(ctx)) return "Install the Linux runtime first, in the Linux tab."
        if (!archive.isFile) return "The download is missing."
        return try {
            if (windows) installWindows(ctx, game, upload, archive, onStage)
            else installLinux(ctx, game, upload, archive, onStage)
        } catch (e: Exception) {
            e.message ?: "Could not install ${game.title}."
        }
    }

    private fun installLinux(ctx: Context, game: Game, upload: Upload, archive: File, onStage: (String) -> Unit): String? {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val work = File(base, "linux/${upload.id}")
        val dir = File(work, "game")
        deleteTree(dir)
        dir.mkdirs()
        onStage("Unpacking ${archive.name}")
        unpack(archive, dir, onStage)
        var launcher = findLinuxLauncher(dir, game.title)
        if (launcher == null) {
            val inner = dir.listFiles()?.firstOrNull { it.isFile && kind(it.name) != null }
            if (inner != null) {
                onStage("Unpacking ${inner.name}")
                unpack(inner, dir, onStage)
                inner.delete()
                launcher = findLinuxLauncher(dir, game.title)
            }
        }
        if (launcher == null) return "No Linux program or start script was found in ${archive.name}."
        val appImage = launcher.name.endsWith(".appimage", ignoreCase = true)
        val entry = if (appImage) launcher else wrapper(dir, launcher)
        onStage("Adding ${game.title} to the library")
        remove(ctx, game.url, keepFiles = true)
        val source = if (appImage) UserApps.Source.AppImage(launcher) else UserApps.Source.Script(entry)
        val meta = mapOf("itch-url" to game.url, "itch-upload" to upload.id.toString())
        val problem = UserApps.add(ctx, UserApps.Request(source, game.title, game.cover)) { message, _ -> onStage(message) }
        if (problem != null) return problem
        val app = UserApps.list(ctx).firstOrNull {
            (appImage && it.kind == UserApps.Kind.APPIMAGE && it.name == game.title) ||
                (!appImage && it.kind == UserApps.Kind.SCRIPT && it.detail == entry.canonicalPath)
        } ?: return "Installed, but the library entry could not be found."
        save(
            ctx,
            Installed(game.url, game.title, false, game.cover, app.key, app.program, app.args, upload.id.toString())
        )
        if (app.copied) deleteTree(work)
        return null
    }

    private fun installWindows(ctx: Context, game: Game, upload: Upload, archive: File, onStage: (String) -> Unit): String? {
        val root = LinuxRuntime.rootDir(ctx)
        val dir = File(root, "Games/itch-${slug(game.title)}")
        deleteTree(dir)
        dir.mkdirs()
        onStage("Unpacking ${archive.name}")
        unpack(archive, dir, onStage)
        var rel = StoreExe.pick(dir, upload.filename.substringBeforeLast('.'), game.title)
        if (rel.isBlank()) {
            val inner = dir.listFiles()?.firstOrNull { it.isFile && kind(it.name) != null }
            if (inner != null) {
                onStage("Unpacking ${inner.name}")
                unpack(inner, dir, onStage)
                inner.delete()
                rel = StoreExe.pick(dir, null, game.title)
            }
        }
        if (rel.isBlank()) return "No Windows program was found in ${archive.name}."
        val guestExe = "/root/Games/${dir.name}/$rel"
        onStage("Registering ${game.title} for Proton")
        remove(ctx, game.url, keepFiles = true)
        save(ctx, Installed(game.url, game.title, true, game.cover, null, NoSteamLaunch.PROTON_RUN, listOf(guestExe)))
        return null
    }

    /** Blocking. Returns true when a session was asked to start. */
    fun play(ctx: Context, item: Installed): Boolean {
        val gameRes = gameResolution(ctx, item.url)
        if (gameRes.isNotBlank()) {
            com.droiddeck.launcher.session.SessionPrefs.setResolutionChoice(ctx, "run", gameRes)
        }
        val gameSync = gameSyncBackend(ctx, item.url)
        if (gameSync.isNotBlank()) {
            com.droiddeck.launcher.session.SessionPrefs.setSyncBackend(ctx, gameSync)
        }
        val gameFex = gameFexPreset(ctx, item.url)
        if (gameFex.isNotBlank()) {
            com.droiddeck.launcher.session.SessionPrefs.setFexPreset(ctx, gameFex)
        }

        val (program, args) = if (item.key != null && !item.windows) {
            val app = UserApps.list(ctx).firstOrNull { it.key == item.key } ?: return false
            app.program to app.args
        } else {
            item.program to item.args
        }
        val extra = compatArgs(ctx, item.url)
        val argv = (args + if (extra.isBlank()) emptyList() else extra.split(' ').filter { it.isNotBlank() }).toTypedArray()
        val intent = Intent(ctx, SessionActivity::class.java)
            .putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)
            .putExtra(SessionService.EXTRA_PROGRAM, program)
            .putExtra(SessionService.EXTRA_PROGRAM_ARGS, argv)
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }

    /** Extra compatibility arguments for one game, appended after the engine's own launch words. */
    fun compatArgs(ctx: Context, url: String): String =
        prefs(ctx).getString("compat-args-$url", "") ?: ""

    fun setCompatArgs(ctx: Context, url: String, value: String) {
        prefs(ctx).edit().putString("compat-args-$url", value.trim()).apply()
    }

    fun gameResolution(ctx: Context, url: String): String =
        prefs(ctx).getString("res-$url", "") ?: ""

    fun setGameResolution(ctx: Context, url: String, value: String) {
        prefs(ctx).edit().putString("res-$url", value.trim()).apply()
    }

    fun gameSyncBackend(ctx: Context, url: String): String =
        prefs(ctx).getString("sync-$url", "") ?: ""

    fun setGameSyncBackend(ctx: Context, url: String, value: String) {
        prefs(ctx).edit().putString("sync-$url", value.trim()).apply()
    }

    fun gameFexPreset(ctx: Context, url: String): String =
        prefs(ctx).getString("fex-$url", "") ?: ""

    fun setGameFexPreset(ctx: Context, url: String, value: String) {
        prefs(ctx).edit().putString("fex-$url", value.trim()).apply()
    }

    /** Blocking. Removes the library entry and the unpacked game folder. */
    fun remove(ctx: Context, url: String, keepFiles: Boolean = false) {
        val item = get(ctx, url) ?: return
        val key = item.key
        if (key != null) {
            val app = runCatching { UserApps.list(ctx).firstOrNull { it.key == key } }.getOrNull()
            if (app != null) runCatching { UserApps.remove(ctx, app) { _, _ -> } }
        }
        forget(ctx, url)
        if (!keepFiles) {
            val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            item.dir?.takeIf { it.isNotBlank() }?.let { deleteTree(File(File(base, "linux"), it)) }
            if (item.windows) deleteTree(File(File(LinuxRuntime.rootDir(ctx), "Games"), "itch-${slug(item.title)}"))
        }
    }

    private fun wrapper(root: File, launcher: File): File {
        val rel = launcher.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)
        val slash = rel.lastIndexOf(File.separatorChar)
        val dir = if (slash < 0) "" else rel.substring(0, slash)
        val name = if (slash < 0) rel else rel.substring(slash + 1)
        val script = File(root, WRAPPER)
        script.writeText(
            "#!/bin/bash\n" +
                (if (dir.isEmpty()) "cd \"\$(dirname \"\$0\")\" || exit 127\n"
                else "cd \"\$(dirname \"\$0\")\"/" + shellQuote(dir) + " || exit 127\n") +
                "chmod +x ./" + shellQuote(name) + " 2>/dev/null\n" +
                "exec ./" + shellQuote(name) + " \"\$@\"\n"
        )
        return script
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun slug(title: String) = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        .ifEmpty { "game" }

    private fun findLinuxLauncher(dir: File, title: String): File? {
        val key = title.lowercase().replace(Regex("[^a-z0-9]"), "")
        val files = ArrayList<File>()
        var scanned = 0
        dir.walkTopDown().maxDepth(6).forEach { file ->
            if (scanned++ > ELF_SCAN_LIMIT || file == dir) return@forEach
            if (file.isDirectory) return@forEach
            if (file.name == WRAPPER) return@forEach
            files.add(file)
        }
        if (files.isEmpty()) return null
        data class Ranked(val score: Int, val depth: Int, val matches: Boolean, val file: File)
        val ranked = files.mapNotNull { f ->
            val name = f.name.lowercase()
            val relPath = f.absolutePath.removePrefix(dir.absolutePath).trimStart('/', '\\')
            val depth = relPath.count { it == '/' || it == '\\' }
            if (depth > 4) return@mapNotNull null
            val parent = f.parentFile?.name?.lowercase()
            if (parent != null && parent in SKIP_DIRS) return@mapNotNull null
            if (SKIP_NAMES.any { name.startsWith(it) }) return@mapNotNull null
            if (name.endsWith(".so") || name.contains(".so.")) return@mapNotNull null
            val plain = name.substringBeforeLast('.').replace(Regex("[^a-z0-9]"), "")
            val matches = key.isNotEmpty() && (plain == key || (key.length >= 4 && plain.startsWith(key)) ||
                (plain.length >= 4 && plain.startsWith(key)))
            var score = when {
                name.endsWith(".appimage") -> 90
                isElf(f) -> 70
                isExecutableScript(f) -> 65
                name.endsWith(".sh") -> 50
                name.endsWith(".x86_64") || name.endsWith(".x86") -> 45
                name.endsWith(".py") -> 40
                name.endsWith(".desktop") -> 10
                else -> -1
            }
            if (score < 0) return@mapNotNull null
            if (name == "start.sh" || name == "run.sh" || name == "launch.sh" || name == "play.sh" || name == "${key}.sh") score += 25
            if (matches) score += 20
            Ranked(score, depth, matches, f)
        }
        val best = ranked.maxWithOrNull(compareBy<Ranked>({ it.score }, { -it.depth }, { it.matches }))?.file
            ?: return null
        if (best.name.endsWith(".desktop", ignoreCase = true)) return desktopTarget(best) ?: best
        return best
    }

    private fun desktopTarget(desktop: File): File? {
        val exec = desktop.readText().lineSequence().firstOrNull { it.startsWith("Exec=") }
            ?.removePrefix("Exec=")?.trim()?.substringBefore(' ') ?: return null
        val resolved = if (exec.startsWith("/")) null else File(desktop.parentFile, exec)
        return resolved?.takeIf { it.isFile }
    }

    private fun isElf(f: File): Boolean = try {
        FileInputStream(f).use { input ->
            val b = ByteArray(4)
            input.read(b) == 4 && b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte() &&
                b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }

    private fun isExecutableScript(f: File): Boolean = try {
        FileInputStream(f).use { input ->
            val b = ByteArray(2)
            input.read(b) == 2 && b[0] == '#'.code.toByte() && b[1] == '!'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }

    private fun kind(name: String): String? {
        val n = name.lowercase()
        return when {
            n.endsWith(".zip") -> "zip"
            n.endsWith(".7z") -> "7z"
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> "tar.gz"
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> "tar.xz"
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> "tar.bz2"
            n.endsWith(".tar") -> "tar"
            n.endsWith(".gz") -> "tar.gz"
            n.endsWith(".xz") -> "tar.xz"
            n.endsWith(".bz2") -> "tar.bz2"
            else -> null
        }
    }

    private fun unpack(archive: File, into: File, onStage: (String) -> Unit) {
        when (val format = kind(archive.name)) {
            "zip" -> unzip(archive, into)
            "7z" -> sevenZip(archive, into, onStage)
            null -> {
                val target = File(into, archive.name)
                archive.copyTo(target, overwrite = true)
                target.setExecutable(true)
            }
            else -> {
                val codec = format.substringAfter('.')
                try {
                    untar(archive, codec, into)
                } catch (e: Exception) {
                    if (codec == "tar") throw e
                    val single = File(into, archive.name.substringBeforeLast('.'))
                    decompress(archive, codec, single)
                    single.setExecutable(true)
                }
            }
        }
        into.walkTopDown().forEach {
            if (it.isFile && (it.name.endsWith(".sh") || it.name.endsWith(".x86_64") || it.name.endsWith(".x86") || isElf(it) || isExecutableScript(it))) {
                it.setExecutable(true)
            }
        }
    }

    private fun safe(root: File, name: String): File? {
        val file = File(root, name.replace('\\', '/').trimStart('/'))
        val base = root.canonicalPath
        val path = file.canonicalPath
        return if (path == base || path.startsWith(base + File.separator)) file else null
    }

    private fun unzip(archive: File, into: File) {
        ZipFile.builder().setFile(archive).get().use { zip ->
            for (entry in zip.entries) {
                val target = safe(into, entry.name) ?: continue
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> FileOutputStream(target).use { out -> input.copyTo(out, 128 * 1024) } }
                if (entry.unixMode and 0x49 != 0) target.setExecutable(true)
            }
        }
    }

    private fun sevenZip(archive: File, into: File, onStage: (String) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        java.nio.file.Files.newByteChannel(archive.toPath()).use { channel ->
            SevenZFile.builder().setSeekableByteChannel(channel).get().use { seven ->
                while (true) {
                    val entry = seven.nextEntry ?: break
                    val target = safe(into, entry.name) ?: continue
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    onStage("Unpacking ${entry.name}")
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        while (true) {
                            val read = seven.read(buffer, 0, buffer.size)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
    }

    private fun untar(archive: File, codec: String, into: File) {
        val buffer = ByteArray(64 * 1024)
        val opened = runCatching {
            FileInputStream(archive).use { raw ->
                val buffered = BufferedInputStream(raw)
                val input = when (codec) {
                    "gz" -> GzipCompressorInputStream(buffered, true)
                    "bz2" -> BZip2CompressorInputStream(buffered, true)
                    "xz" -> XZCompressorInputStream(buffered, true)
                    else -> buffered
                }
                TarArchiveInputStream(input).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val target = safe(into, entry.name) ?: continue
                        if (entry.isDirectory) {
                            target.mkdirs()
                            continue
                        }
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            while (true) {
                                val read = tar.read(buffer, 0, buffer.size)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                            }
                        }
                        if (entry.mode and 0x49 != 0) target.setExecutable(true)
                    }
                }
            }
        }
        if (opened.isFailure) throw opened.exceptionOrNull() ?: IllegalStateException("tar")
    }

    private fun decompress(archive: File, codec: String, target: File) {
        FileInputStream(archive).use { raw ->
            val buffered = BufferedInputStream(raw)
            val input = when (codec) {
                "bz2" -> BZip2CompressorInputStream(buffered, true)
                "xz" -> XZCompressorInputStream(buffered, true)
                else -> GzipCompressorInputStream(buffered, true)
            }
            input.use { FileOutputStream(target).use { out -> it.copyTo(out, 128 * 1024) } }
        }
    }

    private fun deleteTree(file: File) {
        if (!file.exists()) return
        file.walkBottomUp().forEach { runCatching { it.delete() } }
    }
}
