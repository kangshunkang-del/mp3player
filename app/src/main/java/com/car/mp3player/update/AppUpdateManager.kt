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
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val releaseConnection = open(LATEST_RELEASE_URL)
        try {
            check(releaseConnection.responseCode in 200..299) {
                "检查更新失败（${releaseConnection.responseCode}）"
            }
            check(releaseConnection.url.protocol == "https") {
                "更新服务被重定向到不安全地址"
            }

            val release = JSONObject(
                releaseConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() },
            )
            check(!release.optBoolean("draft", false) && !release.optBoolean("prerelease", false)) {
                "当前没有稳定版更新"
            }

            val assets = release.optJSONArray("assets")
                ?: error("稳定版更新信息缺失")
            var metadataUrl: String? = null
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                if (asset.optString("name") == RELEASE_METADATA_ASSET) {
                    metadataUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                    break
                }
            }
            check(metadataUrl != null && URL(metadataUrl).protocol == "https") {
                "稳定版更新信息地址无效"
            }

            val metadataConnection = open(metadataUrl!!)
            try {
                check(metadataConnection.responseCode in 200..299) {
                    "读取更新信息失败（${metadataConnection.responseCode}）"
                }
                val json = JSONObject(
                    metadataConnection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() },
                )
                val apkUrl = json.getString("apkUrl")
                check(URL(apkUrl).protocol == "https") { "更新地址不安全" }
                val sha256 = json.getString("sha256").lowercase()
                check(sha256.matches(Regex("^[a-f0-9]{64}$"))) { "更新包校验信息无效" }
                val sizeBytes = json.getLong("sizeBytes")
                check(sizeBytes > 0L) { "更新包大小无效" }

                AppUpdateInfo(
                    version = json.getString("version"),
                    versionCode = json.getInt("versionCode"),
                    apkUrl = apkUrl,
                    sha256 = sha256,
                    sizeBytes = sizeBytes,
                    notes = json.optString("notes", ""),
                )
            } finally {
                metadataConnection.disconnect()
            }
        } finally {
            releaseConnection.disconnect()
        }
    }

    suspend fun download(info: AppUpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val connection = open(info.apkUrl)
            val updateDir = File(context.externalCacheDir ?: context.cacheDir, "updates").apply { mkdirs() }
            val target = File(updateDir, UpdateFilePolicy.apkFileName(info.version))
            val partial = File(updateDir, "${target.name}.download")
            try {
                check(connection.responseCode in 200..299) { "下载更新失败（${connection.responseCode}）" }
                check(connection.url.protocol == "https") { "更新下载被重定向到不安全地址" }
                val expected = when {
                    info.sizeBytes > 0 -> info.sizeBytes
                    connection.contentLengthLong > 0 -> connection.contentLengthLong
                    else -> -1L
                }
                connection.inputStream.use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        var lastProgress = -1
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            if (expected > 0) {
                                val progress = ((copied * 100L) / expected).toInt().coerceIn(0, 100)
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }
                if (info.sizeBytes > 0) {
                    check(partial.length() == info.sizeBytes) { "更新包大小校验失败" }
                }
                check(UpdateIntegrity.sha256(partial) == info.sha256) { "更新包完整性校验失败" }
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
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update_files", apk)
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
            setRequestProperty("User-Agent", "MP3Player/${BuildConfig.VERSION_NAME}")
        }

    companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/kangshunkang-del/mp3player/releases/latest"
        const val RELEASE_METADATA_ASSET = "update-info.json"
    }
}
