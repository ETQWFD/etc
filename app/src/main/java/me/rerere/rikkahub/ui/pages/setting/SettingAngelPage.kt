package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.AngelAccessibilityService
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.AngelAI
import me.rerere.rikkahub.utils.AngelAction
import org.koin.androidx.compose.koinViewModel

/**
 * Angel 模式：让 AI 通过无障碍通道读取屏幕并执行点击/输入/滑动操作。
 * 需先在系统「无障碍」中开启本服务；开启后输入任务即可自动执行。
 */
@Composable
fun SettingAngelPage(vm: SettingVM = koinViewModel()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    val connected by AngelAccessibilityService.instance.collectAsState()
    val screen by AngelAccessibilityService.screenSummary.collectAsState()
    val status by AngelAccessibilityService.lastStatus.collectAsState()

    var task by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    val layoutDirection = LocalLayoutDirection.current

    val textNoProvider = stringResource(R.string.angel_no_provider)
    val textRunning = stringResource(R.string.angel_running)
    val textTaskDone = stringResource(R.string.angel_task_done)
    val textMaxRounds = stringResource(R.string.angel_task_max_rounds)
    val textScreenRefreshed = stringResource(R.string.angel_screen_refreshed)
    val textScreenEmpty = stringResource(R.string.angel_screen_empty)

    fun appendLog(line: String) {
        log = if (log.isBlank()) line else "$log\n$line"
    }

    fun openAccessibilitySettings() {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun executeActions(actions: List<AngelAction>): String {
        val results = mutableListOf<String>()
        for (action in actions) {
            val ok = when (action.type) {
                "click" -> {
                    AngelAccessibilityService.performTap(action.text)
                }
                "input" -> {
                    AngelAccessibilityService.performInput(action.text)
                }
                "scroll" -> {
                    AngelAccessibilityService.performScroll(action.direction)
                }
                "back" -> AngelAccessibilityService.performBack()
                "home" -> AngelAccessibilityService.performHome()
                else -> false
            }
            results += if (ok) "✓ ${action.type} ${action.text.ifEmpty { action.direction }}" else "✗ ${action.type} ${action.text.ifEmpty { action.direction }}"
        }
        return results.joinToString("\n")
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.angel_page_title)) },
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
                Card(colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(
                                    if (connected != null) R.string.angel_status_enabled else R.string.angel_status_disabled
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (connected != null) {
                                Icon(
                                    imageVector = HugeIcons.CheckmarkCircle02,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.angel_status_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (connected == null) {
                            Button(
                                onClick = { openAccessibilitySettings() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.angel_open_accessibility))
                            }
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.angel_task_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = task,
                            onValueChange = { task = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.angel_task_placeholder)) },
                            maxLines = 3,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    val provider = AngelAI.pickDecisionProvider(settings)
                                    if (provider == null) {
                                        appendLog(textNoProvider)
                                        return@Button
                                    }
                                    scope.launch {
                                        running = true
                                        appendLog("→ $textRunning")
                                        try {
                                            val decisionProvider = AngelAI.pickDecisionProvider(settings)!!
                                            var round = 0
                                            while (round < 6) {
                                                AngelAccessibilityService.refreshScreen()
                                                delay(300)
                                                val currentScreen = AngelAccessibilityService.screenSummary.value
                                                val plan = AngelAI.planActions(
                                                    provider = decisionProvider,
                                                    screen = currentScreen,
                                                    task = task,
                                                )
                                                appendLog("AI: ${plan.describe()}")
                                                if (plan.done || plan.actions.isEmpty()) {
                                                    appendLog(textTaskDone)
                                                    break
                                                }
                                                val result = executeActions(plan.actions)
                                                appendLog(result)
                                                delay(400)
                                                round++
                                            }
                                            if (round >= 6) {
                                                appendLog(textMaxRounds)
                                            }
                                        } catch (e: Exception) {
                                            appendLog("错误: ${e.message}")
                                        } finally {
                                            running = false
                                        }
                                    }
                                },
                                enabled = connected != null && task.isNotBlank() && !running,
                            ) {
                                if (running) {
                                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                                } else {
                                    Text(stringResource(R.string.angel_execute))
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.Main) {
                                            AngelAccessibilityService.refreshScreen()
                                        }
                                        delay(200)
                                        appendLog("→ $textScreenRefreshed")
                                        appendLog(AngelAccessibilityService.screenSummary.value.ifBlank { textScreenEmpty })
                                    }
                                },
                                enabled = connected != null,
                            ) {
                                Text(stringResource(R.string.angel_refresh_screen))
                            }
                        }
                    }
                }
            }

            if (screen.isNotBlank()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.angel_current_screen),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            SelectionContainer {
                                Text(
                                    text = screen,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 10,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
            }

            item {
                Text(
                    text = stringResource(R.string.angel_manual_controls),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { AngelAccessibilityService.performBack() },
                        enabled = connected != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.angel_back)) }
                    OutlinedButton(
                        onClick = { AngelAccessibilityService.performHome() },
                        enabled = connected != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.angel_home)) }
                    OutlinedButton(
                        onClick = { AngelAccessibilityService.performScroll("down") },
                        enabled = connected != null,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.angel_scroll_down)) }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.angel_log),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        SelectionContainer {
                            Text(
                                text = log.ifBlank { stringResource(R.string.angel_log_empty) },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (log.isNotBlank()) {
                            TextButton(onClick = { log = "" }) {
                                Text(stringResource(R.string.angel_clear_log))
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.angel_status_line, status.ifBlank { "-" }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
