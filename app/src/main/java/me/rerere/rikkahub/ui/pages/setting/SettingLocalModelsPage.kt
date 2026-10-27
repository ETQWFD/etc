package me.rerere.rikkahub.ui.pages.setting

import android.widget.Toast
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.ChunkedDownloader
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import java.io.File
import java.util.concurrent.TimeUnit

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class MsFileItem(
    val Path: String = "",
    val Size: Long = 0,
    val Type: String = "blob",
)

@Serializable
private data class MsFilesData(val Files: List<MsFileItem> = emptyList())

@Serializable
private data class MsFilesResp(val Code: Int = 0, val Data: MsFilesData = MsFilesData())

private data class MsRepo(val namespace: String, val name: String, val display: String)

private data class MsFileRow(val path: String, val size: Long)

/** 魔塔（ModelScope）热门 GGUF 模型，全部已验证存在 */
private val HOT_MS_REPOS = listOf(
    // —— 通义千问系列 ——
    MsRepo("Qwen", "Qwen2.5-0.5B-Instruct-GGUF", "Qwen2.5 0.5B · 32 位手机推荐"),
    MsRepo("Qwen", "Qwen2.5-1.5B-Instruct-GGUF", "Qwen2.5 1.5B · 64 位设备"),
    MsRepo("Qwen", "Qwen2.5-3B-Instruct-GGUF", "Qwen2.5 3B · 高配设备"),
    MsRepo("Qwen", "Qwen2.5-7B-Instruct-GGUF", "Qwen2.5 7B · 旗舰设备"),
    MsRepo("Qwen", "Qwen2.5-14B-Instruct-GGUF", "Qwen2.5 14B · 大内存设备"),
    MsRepo("Qwen", "Qwen2.5-32B-Instruct-GGUF", "Qwen2.5 32B · 顶级设备"),
    MsRepo("Qwen", "Qwen2-0.5B-Instruct-GGUF", "Qwen2 0.5B · 低配可用"),
    MsRepo("Qwen", "Qwen2-1.5B-Instruct-GGUF", "Qwen2 1.5B"),
    MsRepo("Qwen", "Qwen2-7B-Instruct-GGUF", "Qwen2 7B"),
    MsRepo("Qwen", "Qwen1.5-0.5B-Chat-GGUF", "Qwen1.5 0.5B 对话"),
    MsRepo("Qwen", "Qwen1.5-1.8B-Chat-GGUF", "Qwen1.5 1.8B 对话"),
    MsRepo("Qwen", "Qwen1.5-4B-Chat-GGUF", "Qwen1.5 4B 对话"),
    MsRepo("Qwen", "Qwen1.5-7B-Chat-GGUF", "Qwen1.5 7B 对话"),
    // —— 代码模型 ——
    MsRepo("Qwen", "Qwen2.5-Coder-0.5B-Instruct-GGUF", "Coder 0.5B 代码 · 低配"),
    MsRepo("Qwen", "Qwen2.5-Coder-1.5B-Instruct-GGUF", "Coder 1.5B 代码"),
    MsRepo("Qwen", "Qwen2.5-Coder-3B-Instruct-GGUF", "Coder 3B 代码"),
    MsRepo("Qwen", "Qwen2.5-Coder-7B-Instruct-GGUF", "Coder 7B 代码"),
    MsRepo("Qwen", "Qwen2.5-Coder-14B-Instruct-GGUF", "Coder 14B 代码"),
    // —— DeepSeek 系列 ——
    MsRepo("unsloth", "DeepSeek-R1-Distill-Qwen-1.5B-GGUF", "DeepSeek R1 1.5B 推理"),
    MsRepo("unsloth", "DeepSeek-R1-Distill-Qwen-7B-GGUF", "DeepSeek R1 7B 推理"),
    MsRepo("unsloth", "DeepSeek-R1-Distill-Llama-8B-GGUF", "DeepSeek R1 8B 推理"),
    // —— Llama 系列 ——
    MsRepo("unsloth", "Llama-3.2-1B-Instruct-GGUF", "Llama 3.2 1B"),
    MsRepo("unsloth", "Llama-3.2-3B-Instruct-GGUF", "Llama 3.2 3B"),
)

/** 魔塔热门图片生成模型（checkpoint），已验证存在，可下载管理 */
private val HOT_IMG_REPOS = listOf(
    MsRepo("CompVis", "stable-diffusion-v1-4", "Stable Diffusion 1.4 官方"),
    MsRepo("AI-ModelScope", "stable-diffusion-v1-5", "Stable Diffusion 1.5"),
    MsRepo("AI-ModelScope", "stable-diffusion-xl-base-1.0", "Stable Diffusion XL 1.0"),
)

private fun MsRepo.key(): String = "$namespace/$name"
private fun MsRepo.filesUrl(): String = "https://modelscope.cn/api/v1/models/$namespace/$name/repo/files?Recursive=true"
private fun MsRepo.downloadUrl(path: String): String = "https://modelscope.cn/api/v1/models/$namespace/$name/repo?FilePath=$path"

/** GGUF 量化格式优先级：越小越推荐 */
private fun ggufPriority(path: String): Int = when {
    path.contains("q4_k_m") -> 0
    path.contains("q8_0") -> 1
    path.contains("q5_k_m") || path.contains("q5_0") -> 2
    path.contains("q4_0") || path.contains("q4_1") -> 3
    path.contains("q3_k_m") -> 4
    path.contains("q2_k") -> 5
    else -> 9
}

private fun formatSize(size: Long): String {
    if (size <= 0) return ""
    val gb = size / 1024.0 / 1024.0 / 1024.0
    val mb = size / 1024.0 / 1024.0
    return if (gb >= 1.0) "%.1f GB".format(gb) else "%.0f MB".format(mb)
}

private fun Long.toSpeedString(): String {
    val bps = this
    if (bps <= 0) return "0 B/s"
    val mbps = bps / 1024.0 / 1024.0
    val kbps = bps / 1024.0
    return if (mbps >= 1.0) "%.1f MB/s".format(mbps) else "%.0f KB/s".format(kbps)
}

private suspend fun fetchMsFiles(repo: MsRepo, client: OkHttpClient): List<MsFileRow> =
    withContext(Dispatchers.IO) {
        val body = client.newCall(
            okhttp3.Request.Builder().url(repo.filesUrl()).get().build(),
        ).execute().use { it.body?.string().orEmpty() }
        val resp = runCatching { json.decodeFromString(MsFilesResp.serializer(), body) }.getOrNull()
        resp?.Data?.Files.orEmpty()
            .filter { it.Type == "blob" && it.Path.endsWith(".gguf") && !it.Path.contains("-of-") }
            .map { MsFileRow(it.Path, it.Size) }
            .sortedBy { ggufPriority(it.path) }
    }

@Composable
fun SettingLocalModelsPage() {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    val settingsStore: SettingsStore = koinInject()
    val currentSettings by settingsStore.settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    val client = remember {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    val modelsDir = remember {
        File(context.filesDir, "models").apply { mkdirs() }
    }
    val progressMap = remember { mutableStateMapOf<String, Float>() }
    val speedMap = remember { mutableStateMapOf<String, String>() }
    val runningMap = remember { mutableStateMapOf<String, Boolean>() }
    val filesMap = remember { mutableStateMapOf<String, List<MsFileRow>>() }
    val expandedRepo = remember { mutableStateOf<String?>(null) }
    val loadingFiles = remember { mutableStateOf<String?>(null) }
    var customQuery by remember { mutableStateOf("Qwen/Qwen2.5-3B-Instruct-GGUF") }
    var customFiles by remember { mutableStateOf<List<MsFileRow>?>(null) }
    var customLoading by remember { mutableStateOf(false) }
    var customRepoName by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    fun fileOf(repoKey: String, path: String): File = File(modelsDir, File(path).name)

    fun registerAndSelect(file: File, displayName: String, modelId: String) {
        scope.launch {
            val settings = settingsStore.settingsFlow.first()
            val existing = settings.providers
                .filterIsInstance<ProviderSetting.Local>()
                .firstOrNull { it.modelPath == file.absolutePath }
            val provider = if (existing != null) {
                existing.copy(
                    models = existing.models.map {
                        it.copy(abilities = listOf(ModelAbility.TOOL))
                    }.ifEmpty {
                        listOf(
                            Model(
                                modelId = modelId,
                                displayName = displayName,
                                abilities = listOf(ModelAbility.TOOL),
                            ),
                        )
                    },
                )
            } else {
                ProviderSetting.Local(
                    name = displayName,
                    modelPath = file.absolutePath,
                    models = listOf(
                        Model(
                            modelId = modelId,
                            displayName = displayName,
                            abilities = listOf(ModelAbility.TOOL),
                        ),
                    ),
                )
            }
            val finalProviders = if (existing != null) {
                settings.providers.map { if (it === existing) provider else it }
            } else {
                settings.providers + provider
            }
            val targetId = provider.models.first().id
            settingsStore.update { s ->
                s.copy(
                    providers = finalProviders,
                    chatModelId = targetId,
                    fastModelId = targetId,
                )
            }
            me.rerere.rikkahub.utils.AppLogger.i("LocalModels", "使用模型: $displayName ($file)")
            Toast.makeText(context, R.string.local_models_selected, Toast.LENGTH_SHORT).show()
        }
    }

    // 从手机存储导入模型（SAF 文件选择器，复制到 App 模型目录并自动注册使用）
    val importModelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            var ok = false
            withContext(Dispatchers.IO) {
                try {
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx) else null
                    } ?: "imported-${System.currentTimeMillis()}.gguf"
                    val target = File(modelsDir, name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { out -> input.copyTo(out) }
                    }
                    if (target.exists() && target.length() > 0) {
                        me.rerere.rikkahub.utils.AppLogger.i("LocalModels", "导入模型: $name -> ${target.absolutePath}")
                        registerAndSelect(target, target.name, "local-${target.name}")
                        refreshTick++
                        ok = true
                    }
                } catch (e: Exception) {
                    me.rerere.rikkahub.utils.AppLogger.e("LocalModels", "导入失败: ${e.message}")
                }
            }
            Toast.makeText(
                context,
                if (ok) R.string.local_models_imported else R.string.local_models_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    // 导入前申请存储权限（满足运行时授权要求；SAF 选择本身无需权限）
    val requestStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        importModelLauncher.launch(arrayOf("application/octet-stream", "*/*"))
    }
    val onImportClick: () -> Unit = {
        val perm = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        requestStoragePermission.launch(perm)
    }

    fun startDownload(repoKey: String, path: String, url: String, displayName: String) {
        val key = "$repoKey|$path"
        if (runningMap[key] == true) return
        val file = fileOf(repoKey, path)
        if (file.exists() && file.length() > 0) return
        me.rerere.rikkahub.utils.AppLogger.i("Download", "开始下载: $displayName ($url)")
        runningMap[key] = true
        var lastBytes = 0L
        var lastNanos = System.nanoTime()
        scope.launch {
            try {
                ChunkedDownloader.download(
                    client = client,
                    url = url,
                    target = file,
                    userAgent = "Reai-LocalModel",
                    onProgress = { done, total ->
                        if (total > 0) {
                            progressMap[key] = (done.toFloat() / total).coerceIn(0f, 1f)
                            val now = System.nanoTime()
                            val elapsedSec = (now - lastNanos) / 1_000_000_000.0
                            if (elapsedSec >= 1.0) {
                                val rate = ((done - lastBytes) / elapsedSec).toLong()
                                lastBytes = done
                                lastNanos = now
                                speedMap[key] = rate.toSpeedString()
                            }
                        }
                    },
                )
                registerAndSelect(file, displayName, "local-${File(path).name}")
                refreshTick++
                Toast.makeText(context, R.string.local_models_downloaded, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "${context.getString(R.string.local_models_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                runningMap[key] = false
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.local_models_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        // 顶层计算已下载列表：refreshTick 变化时重新扫描，下载/删除后立即刷新
        val _tick = refreshTick
        val localModels = remember(_tick) {
            modelsDir.listFiles().orEmpty()
                .filter { it.isFile && it.length() > 0 }
                .sortedByDescending { it.length() }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                top = innerPadding.calculateTopPadding() + 16.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.local_models_page_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.local_models_ms_market),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.local_models_ms_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = customQuery,
                            onValueChange = { customQuery = it },
                            label = { Text(stringResource(R.string.local_models_custom_query)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                val parts = customQuery.trim().trim('/').split('/')
                                if (parts.size != 2) {
                                    customError = context.getString(R.string.local_models_query_format)
                                    return@Button
                                }
                                customError = null
                                customLoading = true
                                customFiles = null
                                customRepoName = customQuery.trim().trim('/')
                                scope.launch {
                                    try {
                                        val repo = MsRepo(parts[0], parts[1], customQuery.trim().trim('/'))
                                        val files = fetchMsFiles(repo, client)
                                        if (files.isEmpty()) {
                                            customError = context.getString(R.string.local_models_no_gguf)
                                        } else {
                                            customFiles = files
                                        }
                                    } catch (e: Exception) {
                                        customError = e.message
                                    } finally {
                                        customLoading = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (customLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.local_models_fetch_files))
                        }
                        if (customError != null) {
                            Text(
                                text = customError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (customFiles != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = customRepoName,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = stringResource(R.string.local_models_choose_quant),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            customFiles.orEmpty().forEach { row ->
                                val key = "$customRepoName|${row.path}"
                                val progress = progressMap[key] ?: 0f
                                val speed = speedMap[key]
                                val running = runningMap[key] == true
                                val file = fileOf(customRepoName, row.path)
                                val downloaded = file.exists() && file.length() > 0
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = row.path,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = formatSize(row.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        when {
                                            downloaded -> Text(
                                                text = stringResource(R.string.local_models_done),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            running -> Text(
                                                text = stringResource(R.string.local_models_downloading, (progress * 100).toInt()),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            else -> OutlinedButton(
                                                onClick = {
                                                    startDownload(
                                                        repoKey = customRepoName,
                                                        path = row.path,
                                                        url = "https://modelscope.cn/api/v1/models/$customRepoName/repo?FilePath=${row.path}",
                                                        displayName = "${customRepoName.split('/').last()} · ${row.path}",
                                                    )
                                                },
                                                modifier = Modifier.height(32.dp),
                                            ) {
                                                Text(stringResource(R.string.local_models_download), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                    if (running) {
                                        LinearProgressIndicator(
                                            progress = { progress },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Text(
                                            text = if (speed != null) {
                                                stringResource(R.string.local_models_downloading_speed, (progress * 100).toInt(), speed)
                                            } else {
                                                stringResource(R.string.local_models_downloading, (progress * 100).toInt())
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.local_models_hot),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            items(HOT_MS_REPOS, key = { it.key() }) { repo ->
                val repoKey = repo.key()
                val files = filesMap[repoKey].orEmpty()
                val expanded = expandedRepo.value == repoKey
                val loading = loadingFiles.value == repoKey

                Card(colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (expanded) {
                                    expandedRepo.value = null
                                } else {
                                    expandedRepo.value = repoKey
                                    if (files.isEmpty() && !loading) {
                                        loadingFiles.value = repoKey
                                        scope.launch {
                                            try {
                                                filesMap[repoKey] = fetchMsFiles(repo, client)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "${context.getString(R.string.local_models_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
                                            } finally {
                                                loadingFiles.value = null
                                            }
                                        }
                                    }
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = HugeIcons.AiBrain01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    text = repo.key(),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = repo.display,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = HugeIcons.AiBrain01,
                                contentDescription = null,
                            )
                        }

                        if (expanded) {
                            if (loading) {
                                Row(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.local_models_loading_files), style = MaterialTheme.typography.bodySmall)
                                }
                            } else if (files.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.local_models_no_gguf),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                )
                            } else {
                                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                                    files.forEach { row ->
                                        val key = "$repoKey|${row.path}"
                                        val progress = progressMap[key] ?: 0f
                                        val speed = speedMap[key]
                                        val running = runningMap[key] == true
                                        val file = fileOf(repoKey, row.path)
                                        val downloaded = file.exists() && file.length() > 0
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = row.path,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = formatSize(row.size),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                when {
                                                    downloaded -> Text(
                                                        text = stringResource(R.string.local_models_done),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                    running -> Text(
                                                        text = stringResource(R.string.local_models_downloading, (progress * 100).toInt()),
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                    else -> OutlinedButton(
                                                        onClick = {
                                                            startDownload(
                                                                repoKey = repoKey,
                                                                path = row.path,
                                                                url = repo.downloadUrl(row.path),
                                                                displayName = "${repo.name} · ${row.path}",
                                                            )
                                                        },
                                                        modifier = Modifier.height(32.dp),
                                                    ) {
                                                        Text(stringResource(R.string.local_models_download), style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                            }
                                            if (running) {
                                                LinearProgressIndicator(
                                                    progress = { progress },
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                                Text(
                                                    text = if (speed != null) {
                                                        stringResource(R.string.local_models_downloading_speed, (progress * 100).toInt(), speed)
                                                    } else {
                                                        stringResource(R.string.local_models_downloading, (progress * 100).toInt())
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
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

            // 图片生成模型区
            item {
                Text(
                    text = stringResource(R.string.local_models_img_market),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(HOT_IMG_REPOS, key = { "img-${it.key()}" }) { repo ->
                val repoKey = "img-${repo.key()}"
                val files = filesMap[repoKey].orEmpty()
                val expanded = expandedRepo.value == repoKey
                val loading = loadingFiles.value == repoKey
                Card(colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (expanded) {
                                    expandedRepo.value = null
                                } else {
                                    expandedRepo.value = repoKey
                                    if (files.isEmpty() && !loading) {
                                        loadingFiles.value = repoKey
                                        scope.launch {
                                            try {
                                                filesMap[repoKey] = fetchMsFiles(repo, client)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "${context.getString(R.string.local_models_failed)}: ${e.message}", Toast.LENGTH_SHORT).show()
                                            } finally {
                                                loadingFiles.value = null
                                            }
                                        }
                                    }
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = HugeIcons.PaintBoard,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    text = repo.key(),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = repo.display,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(imageVector = HugeIcons.PaintBoard, contentDescription = null)
                        }
                        if (expanded) {
                            if (loading) {
                                Row(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.local_models_loading_files), style = MaterialTheme.typography.bodySmall)
                                }
                            } else if (files.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.local_models_no_files),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                )
                            } else {
                                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                                    files.forEach { row ->
                                        val key = "$repoKey|${row.path}"
                                        val progress = progressMap[key] ?: 0f
                                        val speed = speedMap[key]
                                        val running = runningMap[key] == true
                                        val file = fileOf(repoKey, row.path)
                                        val downloaded = file.exists() && file.length() > 0
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = row.path,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = formatSize(row.size),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                when {
                                                    downloaded -> Text(
                                                        text = stringResource(R.string.local_models_done),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                    running -> Text(
                                                        text = stringResource(R.string.local_models_downloading, (progress * 100).toInt()),
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                    else -> OutlinedButton(
                                                        onClick = {
                                                            startDownload(
                                                                repoKey = repoKey,
                                                                path = row.path,
                                                                url = repo.downloadUrl(row.path),
                                                                displayName = "${repo.name} · ${row.path}",
                                                            )
                                                        },
                                                        modifier = Modifier.height(32.dp),
                                                    ) {
                                                        Text(stringResource(R.string.local_models_download), style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                            }
                                            if (running) {
                                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                                Text(
                                                    text = if (speed != null) {
                                                        stringResource(R.string.local_models_downloading_speed, (progress * 100).toInt(), speed)
                                                    } else {
                                                        stringResource(R.string.local_models_downloading, (progress * 100).toInt())
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.local_models_downloaded_list),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { onImportClick() },
                        modifier = Modifier.height(36.dp),
                    ) {
                        Text(stringResource(R.string.local_models_import), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // 已下载列表（GGUF 聊天模型与图片模型都显示，下载/删除后自动刷新）
            if (localModels.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.local_models_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(localModels, key = { it.absolutePath }) { file ->
                    val cur = currentSettings
                    val localProvider = cur?.providers
                        ?.filterIsInstance<ProviderSetting.Local>()
                        ?.firstOrNull { it.modelPath == file.absolutePath }
                    val modelId = localProvider?.models?.firstOrNull()?.id
                    val isActive = cur != null && modelId != null &&
                        (cur.chatModelId == modelId || cur.fastModelId == modelId)
                    Card(colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = HugeIcons.AiBrain01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = if (file.name.endsWith(".gguf")) {
                                            "${stringResource(R.string.model_type_chat)} · ${formatSize(file.length())}"
                                        } else {
                                            "${stringResource(R.string.model_type_image)} · ${formatSize(file.length())}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = file.absolutePath,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (isActive) {
                                    Text(
                                        text = stringResource(R.string.local_models_in_use),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (file.name.endsWith(".gguf")) {
                                    Button(
                                        onClick = { registerAndSelect(file, file.name, "local-${file.name}") },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(if (isActive) stringResource(R.string.local_models_using) else stringResource(R.string.local_models_use))
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        context.writeClipboardText(file.absolutePath)
                                        Toast.makeText(context, R.string.local_models_path_copied, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.local_models_copy_path))
                                }
                                OutlinedButton(
                                    onClick = {
                                        file.delete()
                                        me.rerere.ai.provider.providers.local.LocalLlamaRuntime.release()
                                        me.rerere.rikkahub.utils.AppLogger.i("LocalModels", "删除模型文件: ${file.absolutePath}")
                                        scope.launch {
                                            val removedProvider = currentSettings?.providers
                                                ?.filterIsInstance<ProviderSetting.Local>()
                                                ?.firstOrNull { it.modelPath == file.absolutePath }
                                            val removedModelId = removedProvider?.models?.firstOrNull()?.id
                                            settingsStore.update { st ->
                                                val providers = st.providers.filterNot {
                                                    it is ProviderSetting.Local && it.modelPath == file.absolutePath
                                                }
                                                // 删除当前选中模型后重置到任一可用模型，避免选中项悬空导致标题无法生成
                                                val fallbackId = providers.asSequence()
                                                    .mapNotNull { it.models.firstOrNull()?.id }
                                                    .firstOrNull()
                                                st.copy(
                                                    providers = providers,
                                                    chatModelId = if (removedModelId != null && st.chatModelId == removedModelId) fallbackId ?: st.chatModelId else st.chatModelId,
                                                    fastModelId = if (removedModelId != null && st.fastModelId == removedModelId) fallbackId ?: st.fastModelId else st.fastModelId,
                                                )
                                            }
                                        }
                                        refreshTick++
                                        Toast.makeText(context, R.string.local_models_deleted, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.local_models_delete))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.local_models_usage_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.local_models_usage_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
