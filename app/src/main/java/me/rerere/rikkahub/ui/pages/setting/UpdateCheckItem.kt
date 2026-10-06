package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.utils.AppUpdater
import me.rerere.rikkahub.utils.GithubRelease
import me.rerere.rikkahub.utils.openUrl
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import java.io.File
import java.util.Locale

enum class UpdatePhase { Idle, Checking, Found, Downloading, Failed, Latest }

class UpdateCheckState(
    val zh: Boolean,
    private val client: OkHttpClient,
) {
    var phase by mutableStateOf(UpdatePhase.Idle)
        internal set
    var release by mutableStateOf<GithubRelease?>(null)
        internal set
    var progress by mutableIntStateOf(0)
        internal set
    var message by mutableStateOf("")
        internal set

    private var context: android.content.Context? = null
    private var scope: kotlinx.coroutines.CoroutineScope? = null

    fun t(cn: String, en: String) = if (zh) cn else en

    fun bind(context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope) {
        this.context = context
        this.scope = scope
    }

    fun check() {
        val ctx = context ?: return
        val sc = scope ?: return
        if (phase == UpdatePhase.Checking || phase == UpdatePhase.Downloading) return
        phase = UpdatePhase.Checking
        sc.launch {
            val data = AppUpdater.fetchLatestRelease(client)
            if (data == null) {
                phase = UpdatePhase.Failed
                message = t("无法连接更新服务器，请稍后再试", "Cannot reach the update server, try again later.")
            } else if (AppUpdater.hasUpdate(data)) {
                release = data
                phase = UpdatePhase.Found
            } else {
                phase = UpdatePhase.Latest
                message = t("当前已是最新版本", "You're already on the latest version.")
            }
        }
    }

    fun startDownload() {
        val ctx = context ?: return
        val sc = scope ?: return
        val data = release ?: return
        val url = AppUpdater.resolveApkUrl(data)
        if (url == null) {
            phase = UpdatePhase.Failed
            message = t("未找到可用的安装包", "No installable package was found.")
            return
        }
        phase = UpdatePhase.Downloading
        progress = 0
        sc.launch {
            runCatching {
                AppUpdater.downloadApk(client, ctx, url) { progress = it }
            }.onSuccess { file: File ->
                AppUpdater.installApk(ctx, file)
                phase = UpdatePhase.Idle
            }.onFailure {
                phase = UpdatePhase.Failed
                message = t("下载失败，请稍后再试", "Download failed, try again later.")
            }
        }
    }
}

@Composable
fun rememberUpdateCheckState(): UpdateCheckState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client: OkHttpClient = koinInject()
    val zh = remember { Locale.getDefault().language == "zh" }
    val state = remember { UpdateCheckState(zh = zh, client = client) }
    state.bind(context, scope)
    return state
}

@Composable
fun UpdateCheckDialogs(st: UpdateCheckState) {
    val context = LocalContext.current
    when (st.phase) {
        UpdatePhase.Found -> st.release?.let { data ->
            val version = AppUpdater.coreVersion(data.tagName)
            AlertDialog(
                onDismissRequest = { st.phase = UpdatePhase.Idle },
                title = { Text(st.t("发现新版本 v$version", "New version v$version")) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MarkdownBlock(
                            content = data.body.ifBlank {
                                st.t("前往更新以获取最新功能与修复。", "Update to get the latest features and fixes.")
                            }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { st.startDownload() }) {
                        Text(st.t("立即下载", "Download"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { st.phase = UpdatePhase.Idle }) {
                        Text(st.t("以后再说", "Later"))
                    }
                },
            )
        }

        UpdatePhase.Downloading -> {
            AlertDialog(
                onDismissRequest = { },
                title = { Text(st.t("正在下载更新", "Downloading update")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(
                            progress = { st.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text("${st.progress}%")
                    }
                },
                confirmButton = {},
            )
        }

        UpdatePhase.Failed, UpdatePhase.Latest -> {
            val isFailed = st.phase == UpdatePhase.Failed
            AlertDialog(
                onDismissRequest = { st.phase = UpdatePhase.Idle },
                title = {
                    Text(if (isFailed) st.t("检查失败", "Check failed") else st.t("已是最新", "Up to date"))
                },
                text = { Text(st.message) },
                confirmButton = {
                    TextButton(onClick = {
                        if (isFailed) {
                            context.openUrl("https://github.com/ETQWFD/etc/releases/latest")
                        }
                        st.phase = UpdatePhase.Idle
                    }) {
                        Text(if (isFailed) st.t("去 Release 页面", "Open releases") else st.t("好的", "OK"))
                    }
                },
            )
        }

        else -> {}
    }
}

@Composable
fun UpdateCheckTrailing(st: UpdateCheckState) {
    if (st.phase == UpdatePhase.Checking) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}
