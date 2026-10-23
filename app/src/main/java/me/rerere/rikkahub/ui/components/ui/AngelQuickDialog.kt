package me.rerere.rikkahub.ui.components.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.service.AngelAccessibilityService
import me.rerere.rikkahub.utils.AngelAction
import me.rerere.rikkahub.utils.AngelAI

/**
 * Angel 快捷执行弹窗：聊天页直接输入任务让 AI 操作手机。
 * 复用设置页相同的决策与执行链路，方便聊天时随手用。
 */
@Composable
fun AngelQuickDialog(
    settings: Settings,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val connected by AngelAccessibilityService.instance.collectAsState()
    val status by AngelAccessibilityService.lastStatus.collectAsStateWithLifecycle()

    var task by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }

    val textNoProvider = stringResource(R.string.angel_no_provider)
    val textRunning = stringResource(R.string.angel_running)
    val textTaskDone = stringResource(R.string.angel_task_done)
    val textMaxRounds = stringResource(R.string.angel_task_max_rounds)

    fun appendLog(line: String) {
        log = if (log.isBlank()) line else "$log\n$line"
    }

    fun executeActions(actions: List<AngelAction>): String {
        val results = mutableListOf<String>()
        for (action in actions) {
            val ok = when (action.type) {
                "click" -> AngelAccessibilityService.performTap(action.text)
                "input" -> AngelAccessibilityService.performInput(action.text)
                "scroll" -> AngelAccessibilityService.performScroll(action.direction)
                "back" -> AngelAccessibilityService.performBack()
                "home" -> AngelAccessibilityService.performHome()
                else -> false
            }
            results += if (ok) "✓ ${action.type} ${action.text.ifEmpty { action.direction }}" else "✗ ${action.type} ${action.text.ifEmpty { action.direction }}"
        }
        return results.joinToString("\n")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.chat_page_angel),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_card_close))
                }
            }

            if (connected == null) {
                Button(
                    onClick = {
                        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.angel_open_accessibility))
                }
                Text(
                    text = stringResource(R.string.angel_status_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.angel_status_enabled) + " · " + status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            OutlinedTextField(
                value = task,
                onValueChange = { task = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.angel_task_placeholder)) },
                minLines = 2,
                maxLines = 4,
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
                    modifier = Modifier.weight(1f),
                ) {
                    if (running) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    } else {
                        Text(stringResource(R.string.angel_execute))
                    }
                }
                OutlinedButton(
                    onClick = { AngelAccessibilityService.refreshScreen() },
                    enabled = connected != null && !running,
                ) {
                    Text(stringResource(R.string.angel_refresh_screen))
                }
            }

            if (log.isNotBlank()) {
                Text(
                    text = log,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .verticalScroll(rememberScrollState()),
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
