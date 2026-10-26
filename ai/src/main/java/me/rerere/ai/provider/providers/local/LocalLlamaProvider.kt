package me.rerere.ai.provider.providers.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.local.LlamaNative
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

private const val TAG = "LocalLlamaProvider"

/**
 * 本地 GGUF 模型 Provider：完全离线，通过 llama.cpp JNI 在设备上推理。
 * 支持流式输出；工具调用由 qwen 系列模型的 chat template 原生支持，
 * 通过 system 提示注入工具说明。
 */
class LocalLlamaProvider(private val context: Context) : Provider<ProviderSetting.Local> {
    private val handleHolder = AtomicLong(0L)

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
     */
    private fun runChat(
        setting: ProviderSetting.Local,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        streamTo: ((String) -> Unit)?,
    ): String {
        val handle = handleHolder.get()
        if (handle == 0L) {
            val path = setting.modelPath
            if (path.isBlank()) throw IllegalStateException("本地模型路径为空，请先在本地模型页下载模型")
            val created = LlamaNative.create(path, setting.nCtx, setting.threads)
            if (created == 0L) throw IllegalStateException("模型加载失败：内存不足或模型文件损坏，请重启应用后重试")
            handleHolder.set(created)
        }

        val roles = mutableListOf<String>()
        val contents = mutableListOf<String>()
        if (params.tools.isNotEmpty()) {
            roles.add("system")
            contents.add(buildToolSystemPrompt(params.tools.joinToString { it.name }))
        }
        messages.filter { it.role != MessageRole.TOOL }.forEach { msg ->
            roles.add(msg.role.name.lowercase())
            contents.add(msg.toText())
        }

        val cb = object : LlamaNative.TokenCallback {
            override fun onToken(token: String) {
                streamTo?.invoke(token)
            }

            override fun onError(message: String) {
                throw IllegalStateException(message)
            }
        }

        return try {
            LlamaNative.stream(
                handle,
                roles.toTypedArray(),
                contents.toTypedArray(),
                params.maxTokens ?: 512,
                params.temperature ?: 0.8f,
                params.topP ?: 0.9f,
                cb,
            )
        } catch (e: IllegalStateException) {
            if (e.message?.contains("模型句柄无效") == true) {
                handleHolder.set(0L)
            }
            throw e
        }
    }

    private fun buildToolSystemPrompt(tools: String): String =
        "你可以调用以下工具：$tools。需要调用工具时，严格输出 JSON：{\"tool\":\"工具名\",\"args\":{...}}，否则直接回复文本。"
}
