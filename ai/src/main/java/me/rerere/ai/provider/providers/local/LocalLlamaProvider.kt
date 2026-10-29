package me.rerere.ai.provider.providers.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.local.MnnNative
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

private const val TAG = "LocalLlamaProvider"

/**
 * 本地模型 Provider：完全离线，通过 MNN-LLM（魔塔官方 MNN 模型）在设备上推理。
 * 只支持 MNN 官方模型（llm.mnn 整套），不支持 GGUF。
 */
class LocalLlamaProvider(private val context: Context) : Provider<ProviderSetting.Local> {
    private val handleHolder = AtomicLong(0L)
    private val loadedPathHolder = AtomicReference("")

    override suspend fun listModels(providerSetting: ProviderSetting.Local): List<Model> =
        providerSetting.models

    override suspend fun generateText(
        providerSetting: ProviderSetting.Local,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val full = withContext(Dispatchers.IO) {
            runChat(providerSetting, messages, params, streamTo = null)
        }
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(full)),
        )
        return TextGenerationResult(
            id = Uuid.random().toString(),
            model = params.model.modelId,
            message = assistant,
            finishReason = "stop",
            usage = null,
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Local,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        val id = Uuid.random().toString()
        emit(StreamChunk.TextStart(id))
        val sb = StringBuilder()
        withContext(Dispatchers.IO) {
            runChat(providerSetting, messages, params) { token ->
                sb.append(token)
            }
        }
        emit(StreamChunk.TextDelta(id, sb.toString()))
        emit(StreamChunk.TextEnd(id))
        emit(
            StreamChunk.Usage(
                TokenUsage(
                    promptTokens = 0,
                    completionTokens = 0,
                    totalTokens = 0,
                )
            )
        )
        emit(StreamChunk.Finish(finishReason = "stop", responseId = id, model = params.model.modelId))
    }

    /**
     * 执行一次对话。streamTo 非空时逐 token 回调。
     * 通过 [LocalLlamaRuntime] 串行化：全局单句柄、自动重建、并发安全。
     */
    private fun runChat(
        setting: ProviderSetting.Local,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        streamTo: ((String) -> Unit)?,
    ): String {
        val path = setting.modelPath
        if (path.isBlank()) throw IllegalStateException("本地模型路径为空，请先在本地模型页下载模型")

        val pathFile = java.io.File(path)
        // 仅支持 MNN 模型：modelPath 为包含 config.json 的模型目录（魔塔官方 MNN 模型）
        if (!pathFile.isDirectory || !java.io.File(pathFile, "config.json").exists()) {
            throw IllegalStateException("仅支持 MNN 官方模型（模型目录需包含 config.json），不支持 GGUF 等其他格式")
        }
        if (!MnnNative.available) {
            throw IllegalStateException("当前设备架构不支持 MNN 本地模型，请使用 ARM 设备或接入 API")
        }
        val userContent = messages
            .filter { it.role != MessageRole.TOOL }
            .joinToString("\n") { msg ->
                val who = if (msg.role == MessageRole.USER) "User" else "Assistant"
                "$who: ${msg.toText()}"
            }
        return LocalMnnRuntime.chat(
            modelDir = path,
            userContent = userContent,
            maxTokens = params.maxTokens ?: 512,
            threads = setting.threads,
            streamTo = streamTo,
        )
    }
}
