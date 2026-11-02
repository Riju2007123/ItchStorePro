package io.itchstore.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

fun fileUri(ctx: Context, f: File): Uri =
    FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

fun mimeOf(f: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"

/** Opens the system installer. If "install unknown apps" isn't allowed yet, opens that setting first. */
fun installApk(ctx: Context, f: File) {
    if (!ctx.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(ctx, "Allow installs from Itch Store, then tap Install again", Toast.LENGTH_LONG).show()
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    }
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(fileUri(ctx, f), "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(i) } catch (_: Exception) {
        Toast.makeText(ctx, "No installer found", Toast.LENGTH_LONG).show()
    }
}

fun openFile(ctx: Context, f: File) {
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(fileUri(ctx, f), mimeOf(f))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(i) } catch (_: Exception) {
        Toast.makeText(ctx, "No app can open this file. Use Save to Downloads.", Toast.LENGTH_LONG).show()
    }
}

/** Blocking: call from Dispatchers.IO. */
fun saveToDownloads(ctx: Context, f: File): Boolean = try {
    val cv = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, f.name)
        put(MediaStore.Downloads.MIME_TYPE, mimeOf(f))
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ItchStore")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
    true
} catch (_: Exception) { false }

fun fmtSize(b: Long): String = when {
    b <= 0 -> ""
    b < 1024 * 1024 -> "${b / 1024} KB"
    b < 1024L * 1024 * 1024 -> String.format("%.1f MB", b / 1048576.0)
    else -> String.format("%.2f GB", b / 1073741824.0)
}
