package top.wkbin.taixu.core.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import top.wkbin.taixu.core.model.AppUpdateInfo
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 太墟 · 应用版本更新管理器 (GitHub Releases API)
 */
@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    companion object {
        const val GITHUB_REPO = "wkbin/taixu"
        const val GITHUB_REPO_URL = "https://github.com/wkbin/taixu"
        const val QQ_GROUP_ID = "964382207"
        private const val RELEASES_API = "https://api.github.com/repos/wkbin/taixu/releases/latest"
    }

    /**
     * 向 GitHub 请求最新 Release 信息并与当前版本比对
     */
    suspend fun checkUpdate(currentVersionName: String): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "TaiXu-App/${currentVersionName}")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub 响应错误 HTTP ${response.code}")
                }
                val body = response.body.string()
                val jsonElement = json.parseToJsonElement(body).jsonObject

                val tagName = jsonElement["tag_name"]?.jsonPrimitive?.content.orEmpty()
                val latestVersion = tagName.removePrefix("v").trim()
                val title = jsonElement["name"]?.jsonPrimitive?.content ?: tagName
                // body 为 JSON null 时 contentOrNull 返回 null，否则 UI 会显示字面量 "null"
                val bodyText = jsonElement["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val htmlUrl = jsonElement["html_url"]?.jsonPrimitive?.content ?: GITHUB_REPO_URL
                val publishedAt = jsonElement["published_at"]?.jsonPrimitive?.content.orEmpty()

                // 查找 assets 中的 apk 文件
                var apkUrl: String? = null
                var apkSize: Long? = null
                val assets = jsonElement["assets"]?.jsonArray
                if (assets != null) {
                    for (asset in assets) {
                        val assetObj = asset.jsonObject
                        val name = assetObj["name"]?.jsonPrimitive?.content.orEmpty()
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = assetObj["browser_download_url"]?.jsonPrimitive?.content
                            apkSize = assetObj["size"]?.jsonPrimitive?.longOrNull
                            break
                        }
                    }
                }

                val hasUpdate = isNewerVersion(latestVersion, currentVersionName)

                AppUpdateInfo(
                    currentVersion = currentVersionName,
                    latestVersion = latestVersion.ifBlank { currentVersionName },
                    hasUpdate = hasUpdate,
                    releaseTitle = title,
                    releaseNotes = bodyText,
                    releaseUrl = htmlUrl,
                    apkDownloadUrl = apkUrl,
                    apkSizeBytes = apkSize,
                    publishedAt = publishedAt,
                )
            }
        }
    }

    /**
     * 下载 APK 文件并报告下载进度
     */
    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(downloadUrl)
                .get()
                .build()

            // 非 2xx 时也必须归还连接到连接池，否则每次失败的更新尝试都泄漏一个连接
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("下载失败 HTTP ${response.code}")
                }

                val body = response.body
                val contentLength = body.contentLength().takeIf { it > 0 }
                val downloadDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(downloadDir, "taixu-latest.apk")
                if (apkFile.exists()) apkFile.delete()

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, contentLength)
                        }
                        output.flush()
                    }
                }

                apkFile
            }
        }
    }

    /**
     * 调起系统安装器安装 APK
     */
    fun installApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 语义化版本比对：latest > current 返回 true
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        if (latest.isBlank() || current.isBlank()) return false
        val latestParts = latest.split('.').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val currentParts = current.split('.').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
