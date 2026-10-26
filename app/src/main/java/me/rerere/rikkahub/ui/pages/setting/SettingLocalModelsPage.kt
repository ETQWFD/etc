package me.rerere.rikkahub.ui.pages.setting

import android.widget.Toast
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 本地模型下载管理：可直接下载本地聊天模型（GGUF）与本地绘图模型（ONNX），
 * 使用分块并行下载加速；下载完成后自动注册为聊天模型并选中，可直接离线对话。
 */
@Composable
fun SettingLocalModelsPage() {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    val settingsStore: SettingsStore = koinInject()

    val modelsDir = remember {
        File(context.filesDir, "models").apply { mkdirs() }
    }
    val progressMap = remember { mutableStateMapOf<String, Float>() }
    val speedMap = remember { mutableStateMapOf<String, String>() }
    val runningMap = remember { mutableStateMapOf<String, Boolean>() }

    val chatModels = remember {
        listOf(
            LocalModelEntry(
                id = "qwen-0.5b",
                name = "Qwen2.5-0.5B-Instruct（32 位手机推荐）",
                fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                sizeText = "约 400 MB",
                url = "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                kind = "聊天模型",
            ),
            LocalModelEntry(
                id = "qwen-1.5b",
                name = "Qwen2.5-1.5B-Instruct（64 位设备）",
                fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                sizeText = "约 1.0 GB",
                url = "https://hf-mirror.com/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                kind = "聊天模型",
            ),
            LocalModelEntry(
                id = "qwen-3b",
                name = "Qwen2.5-3B-Instruct（高配设备）",
                fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
                sizeText = "约 2.0 GB",
                url = "https://hf-mirror.com/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
                kind = "聊天模型",
            ),
            LocalModelEntry(
                id = "sd-v1-4",
                name = "Stable Diffusion v1.4（本地绘图 ONNX，需高配设备）",
                fileName = "sd-v1-4-onnx.tar.gz",
                sizeText = "约 3.1 GB",
                url = "https://hf-mirror.com/bes-dev/stable-diffusion-v1-4-onnx/resolve/main/unet.tar.gz",
                kind = "绘图模型",
            ),
        )
    }

    fun targetFile(entry: LocalModelEntry): File = File(modelsDir, entry.fileName)

    fun isDownloaded(entry: LocalModelEntry): Boolean =
        targetFile(entry).exists() && targetFile(entry).length() > 0

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

            items(chatModels, key = { it.id }) { entry ->
                val progress = progressMap[entry.id] ?: 0f
                val speed = speedMap[entry.id]
                val running = runningMap[entry.id] == true
                val downloaded = isDownloaded(entry)

                Card(colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (entry.kind == "聊天模型") HugeIcons.AiBrain01 else HugeIcons.PaintBoard,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(0.dp))
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${entry.kind} · ${entry.sizeText}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (downloaded) {
                                if (entry.kind == "聊天模型") {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val file = targetFile(entry)
                                                val settings = settingsStore.settingsFlow.first()
                                                val localProvider = settings.providers
                                                    .filterIsInstance<ProviderSetting.Local>()
                                                    .firstOrNull { it.modelPath == file.absolutePath }
                                                if (localProvider == null) {
                                                    val provider = ProviderSetting.Local(
                                                        name = entry.name,
                                                        modelPath = file.absolutePath,
                                                        models = listOf(
                                                            Model(
                                                                modelId = entry.id,
                                                                displayName = entry.name,
                                                            ),
                                                        ),
                                                    )
                                                    settingsStore.update { s ->
                                                        s.copy(
                                                            providers = s.providers + provider,
                                                            chatModelId = provider.models.first().id,
                                                        )
                                                    }
                                                } else {
                                                    settingsStore.update { s ->
                                                        s.copy(chatModelId = localProvider.models.firstOrNull()?.id ?: s.chatModelId)
                                                    }
                                                }
                                                Toast.makeText(context, R.string.local_models_selected, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(stringResource(R.string.local_models_use))
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        val f = targetFile(entry)
                                        context.writeClipboardText(f.absolutePath)
                                        Toast.makeText(context, R.string.local_models_path_copied, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.local_models_copy_path))
                                }
                                OutlinedButton(
                                    onClick = {
                                        targetFile(entry).delete()
                                        Toast.makeText(context, R.string.local_models_deleted, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.local_models_delete))
                                }
                            } else {
                                Button(
                                    onClick = {
                                        val client = OkHttpClient.Builder()
                                            .connectTimeout(30, TimeUnit.SECONDS)
                                            .readTimeout(60, TimeUnit.SECONDS)
                                            .build()
                                        scope.launch {
                                            runningMap[entry.id] = true
                                            progressMap[entry.id] = 0f
                                            var lastBytes = 0L
                                            var lastNanos = System.nanoTime()
                                            try {
                                                ChunkedDownloader.download(
                                                    client = client,
                                                    url = entry.url,
                                                    target = targetFile(entry),
                                                    userAgent = "Reai-LocalModel",
                                                    onProgress = { done, total ->
                                                        if (total > 0) {
                                                            progressMap[entry.id] = (done.toFloat() / total).coerceIn(0f, 1f)
                                                            val now = System.nanoTime()
                                                            val elapsedSec = (now - lastNanos) / 1_000_000_000.0
                                                            if (elapsedSec >= 1.0) {
                                                                val rate = ((done - lastBytes) / elapsedSec).toLong()
                                                                lastBytes = done
                                                                lastNanos = now
                                                                speedMap[entry.id] = rate.toSpeedString()
                                                            }
                                                        }
                                                    },
                                                )
                                                // 下载完成：自动注册为本地聊天模型并选中，可直接在聊天页使用
                                                if (entry.kind == "聊天模型") {
                                                    val file = targetFile(entry)
                                                    val settings = settingsStore.settingsFlow.first()
                                                    val existing = settings.providers
                                                        .filterIsInstance<ProviderSetting.Local>()
                                                        .firstOrNull { it.modelPath == file.absolutePath }
                                                    val provider = existing ?: ProviderSetting.Local(
                                                        name = entry.name,
                                                        modelPath = file.absolutePath,
                                                        models = listOf(
                                                            Model(
                                                                modelId = entry.id,
                                                                displayName = entry.name,
                                                            ),
                                                        ),
                                                    )
                                                    val finalProviders = if (existing != null) {
                                                        settings.providers
                                                    } else {
                                                        settings.providers + provider
                                                    }
                                                    settingsStore.update { s ->
                                                        s.copy(
                                                            providers = finalProviders,
                                                            chatModelId = provider.models.first().id,
                                                        )
                                                    }
                                                }
                                                Toast.makeText(context, R.string.local_models_downloaded, Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "${context.getString(R.string.local_models_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
                                            } finally {
                                                runningMap[entry.id] = false
                                            }
                                        }
                                    },
                                    enabled = !running,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.local_models_download))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
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

data class LocalModelEntry(
    val id: String,
    val name: String,
    val fileName: String,
    val sizeText: String,
    val url: String,
    val kind: String,
)

private fun Long.toSpeedString(): String = when {
    this >= 1024L * 1024L -> "%.1f MB/s".format(this / (1024.0 * 1024.0))
    this >= 1024L -> "%.0f KB/s".format(this / 1024.0)
    else -> "$this B/s"
}
