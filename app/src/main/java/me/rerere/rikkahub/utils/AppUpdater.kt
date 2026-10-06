package me.rerere.rikkahub.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@Serializable
data class GithubAsset(
    val name: String,
    val size: Long = 0L,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

object AppUpdater {
    private const val API_URL = "https://api.github.com/repos/ETQWFD/etc/releases/latest"
    private const val APK_NAME = "etc-armv7.apk"

    private val json = Json { ignoreUnknownKeys = true }

    fun coreVersion(tag: String): String =
        tag.trim().removePrefix("v").substringBefore('-').substringBefore('+').trim()

    suspend fun fetchLatestRelease(client: OkHttpClient): GithubRelease? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder()
                    .url(API_URL)
                    .get()
                    .header("User-Agent", "Reai/${BuildConfig.VERSION_NAME}")
                    .header("Accept", "application/vnd.github+json")
                    .build()
            ).await().use { response ->
                if (!response.isSuccessful) return@runCatching null
                json.decodeFromString<GithubRelease>(response.body.string())
            }
        }.getOrNull()
    }

    fun hasUpdate(release: GithubRelease): Boolean = runCatching {
        val latest = coreVersion(release.tagName)
        latest.isNotEmpty() && Version(latest) > Version(BuildConfig.VERSION_NAME)
    }.getOrDefault(false)

    fun resolveApkUrl(release: GithubRelease): String? =
        release.assets.firstOrNull { it.name.equals(APK_NAME, ignoreCase = true) }?.browserDownloadUrl
            ?: release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }?.browserDownloadUrl

    suspend fun downloadApk(
        client: OkHttpClient,
        context: Context,
        url: String,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, APK_NAME)
        client.newCall(
            Request.Builder().url(url).get()
                .header("User-Agent", "Reai/${BuildConfig.VERSION_NAME}")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body
            val total = body.contentLength()
            body.byteStream().use { input ->
                java.io.FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 99))
                        }
                    }
                    output.flush()
                }
            }
        }
        onProgress(100)
        target
    }

    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
