package me.rerere.ai.provider.providers.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.local.LlamaNative
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
 * 本地 GGUF 模型 Provider：完全离线，通过 llama.cpp JNI 在设备上推理。
 * 支持流式输出；工具调用由 qwen 系列模型的 chat template 原生支持，
 * 通过 system 提示注入工具说明。
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
        // MNN 模型：modelPath 为包含 config.json 的模型目录，走 MNN-LLM（魔塔官方 MNN 模型）
        if (pathFile.isDirectory && java.io.File(pathFile, "config.json").exists()) {
            if (!MnnNative.available) {
                throw IllegalStateException("当前设备架构不支持 MNN 本地模型，请使用 GGUF 模型")
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

        // 其余为 GGUF 文件，走 llama.cpp
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
            LocalLlamaRuntime.run(path, setting.nCtx, setting.threads) { handle ->
                LlamaNative.stream(
                    handle,
                    roles.toTypedArray(),
                    contents.toTypedArray(),
                    params.maxTokens ?: 512,
                    params.temperature ?: 0.8f,
                    params.topP ?: 0.9f,
                    cb,
                )
            }
        } catch (e: IllegalStateException) {
            if (e.message?.contains("模型句柄无效") == true) {
                LocalLlamaRuntime.release()
            }
            throw e
        }
    }

    private fun buildToolSystemPrompt(tools: String): String =
        "你可以调用以下工具：$tools。仅当用户明确要求（如“搜索”“调用工具”“用MCP”）时才调用工具；" +
            "需要调用时严格输出 JSON：{\"tool\":\"工具名\",\"args\":{...}}，否则直接回复文本，绝不主动调用。"
}
