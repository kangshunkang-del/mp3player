package com.car.mp3player.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.car.mp3player.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AppUpdateInfo(
    val version: String,
    val versionCode: Int,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
) {
    val isNewer: Boolean get() = versionCode > BuildConfig.VERSION_CODE
}

sealed interface InstallRequest {
    data object Launched : InstallRequest
    data class PermissionRequired(val intent: Intent) : InstallRequest
}

class AppUpdateManager(private val context: Context) {
    suspend fun check(): AppUpdateInfo = withContext(Dispatchers.IO) {
        val connection = open(VERSION_URL)
        try {
            check(connection.responseCode in 200..299) { "检查更新失败（\${connection.responseCode}）" }
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val apkUrl = json.getString("apkUrl")
            check(URL(apkUrl).protocol == "https") { "更新地址不安全" }
            AppUpdateInfo(
                version = json.getString("version"),
                versionCode = json.getInt("versionCode"),
                apkUrl = apkUrl,
                sha256 = json.getString("sha256").lowercase(),
                sizeBytes = json.optLong("sizeBytes", -1L),
                notes = json.optString("notes", ""),
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(info: AppUpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val connection = open(info.apkUrl)
            val updateDir = File(context.externalCacheDir ?: context.cacheDir, "updates").apply { mkdirs() }
            val target = File(updateDir, "mp3-player-\${info.version}.apk")
            val partial = File(updateDir, "\${target.name}.download")
            try {
                check(connection.responseCode in 200..299) { "下载更新失败（\${connection.responseCode}）" }
                val expected = when {
                    info.sizeBytes > 0 -> info.sizeBytes
                    connection.contentLengthLong > 0 -> connection.contentLengthLong
                    else -> -1L
                }
                connection.inputStream.use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            if (expected > 0) {
                                onProgress(((copied * 100L) / expected).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                }
                if (info.sizeBytes > 0) {
                    check(partial.length() == info.sizeBytes) { "更新包大小校验失败" }
                }
                check(partial.sha256() == info.sha256) { "更新包完整性校验失败" }
                if (target.exists()) target.delete()
                check(partial.renameTo(target)) { "无法保存更新包" }
                onProgress(100)
                target
            } catch (error: Throwable) {
                partial.delete()
                throw error
            } finally {
                connection.disconnect()
            }
        }

    fun requestInstall(apk: File): InstallRequest {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return InstallRequest.PermissionRequired(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:\${context.packageName}"),
                ),
            )
        }
        val uri = FileProvider.getUriForFile(context, "\${context.packageName}.update_files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        return InstallRequest.Launched
    }

    private fun open(rawUrl: String): HttpURLConnection =
        (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, application/vnd.android.package-archive")
            setRequestProperty("User-Agent", "MP3Player/\${BuildConfig.VERSION_NAME}")
        }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val VERSION_URL =
            "https://chuya-d6gyub7awb35a8bf7-1300580117.ap-shanghai.app.tcloudbase.com/mp3/version.json"
    }
}
