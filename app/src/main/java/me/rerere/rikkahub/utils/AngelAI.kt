package me.rerere.rikkahub.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.ProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Angel 模式的 AI 决策调用：走当前已配置的 OpenAI 兼容聊天接口，
 * 输入「屏幕节点摘要 + 用户任务」，输出可执行的 JSON 动作序列。
 */
object AngelAI {
    private val json = Json { ignoreUnknownKeys = true }

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 从设置中挑选当前启用的 provider 作为决策大脑。
     * 支持 OpenAI 兼容、Google Gemini、Claude 三种协议；无可用 provider 时返回 null。
     */
    fun pickDecisionProvider(settings: me.rerere.rikkahub.data.datastore.Settings): ProviderSetting? {
        val chatModelId = settings.chatModelId
        return settings.providers.firstOrNull { p ->
            p.enabled && p.models.any { it.id == chatModelId }
        } ?: settings.providers.firstOrNull { it.enabled }
    }

    /**
     * 让模型基于当前屏幕输出动作 JSON。
     * 返回形如：
     * {"done":false,"reason":"...","actions":[{"type":"click","text":"设置"},{"type":"input","text":"你好"}]}
     */
    suspend fun planActions(
        provider: ProviderSetting,
        screen: String,
        task: String,
    ): AngelPlan = withContext(Dispatchers.IO) {
        val systemPrompt = """
            你是一个手机自动化助手（Angel 模式）。用户会给你一段「当前屏幕可交互节点」列表和要完成的任务。
            每行格式：类型|坐标|文本。类型为 可点击 / 输入框 / 文本。
            请输出严格的 JSON（不要 markdown 代码块）：
            {"done":false,"actions":[{"type":"click","text":"节点文本"},{"type":"input","text":"要输入的文本"},{"type":"scroll","direction":"down|up"},{"type":"back"},{"type":"home"}]}
            规则：
            1. 优先用文本精确匹配可点击节点；找不到就滚动后再试。
            2. 需要输入文字时先点击输入框，再 input 文本。
            3. 任务完成时输出 {"done":true,"actions":[]}。
            4. 一次最多 3 个动作。
        """.trimIndent()
        val userPrompt = "任务：$task\n\n当前屏幕：\n${screen.take(4000)}"

        val text = when (provider) {
            is ProviderSetting.OpenAI -> callOpenAI(provider, systemPrompt, userPrompt)
            is ProviderSetting.Google -> callGoogle(provider, systemPrompt, userPrompt)
            is ProviderSetting.Claude -> callClaude(provider, systemPrompt, userPrompt)
        }

        val content = runCatching {
            json.parseToJsonElement(text).jsonObject
                .get("choices")?.jsonArray?.get(0)?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content
                .orEmpty()
        }.getOrDefault("")

        parsePlan(content)
    }

    private fun callOpenAI(provider: ProviderSetting.OpenAI, system: String, user: String): String {
        val body = buildJsonObject {
            put("model", provider.models.firstOrNull()?.id?.toString() ?: "gpt-4o-mini")
            put("temperature", 0.2)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", user)
                })
            }
        }
        val request = Request.Builder()
            .url("${provider.baseUrl}${provider.chatCompletionsPath}")
            .header("Authorization", "Bearer ${provider.apiKey}")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .build()
        return buildClient().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} ${resp.body?.string()?.take(200)}")
            }
            resp.body?.string().orEmpty()
        }
    }

    private fun callGoogle(provider: ProviderSetting.Google, system: String, user: String): String {
        val modelId = provider.models.firstOrNull()?.id?.toString() ?: "gemini-2.0-flash"
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", "$system\n\n$user") })
                    }
                })
            }
            put("generationConfig", buildJsonObject {
                put("temperature", 0.2)
            })
        }
        val url = if (provider.vertexAI) {
            "https://${provider.location}-aiplatform.googleapis.com/v1/projects/${provider.projectId}/locations/${provider.location}/publishers/google/models/$modelId:generateContent"
        } else {
            "${provider.baseUrl}/models/$modelId:generateContent"
        }
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", provider.apiKey)
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .build()
        val text = buildClient().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} ${resp.body?.string()?.take(200)}")
            }
            resp.body?.string().orEmpty()
        }
        // Gemini 响应：candidates[0].content.parts[0].text
        return runCatching {
            val parts = json.parseToJsonElement(text).jsonObject
                .get("candidates")?.jsonArray?.get(0)?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray
            parts?.joinToString("\n") { el ->
                el.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
            }.orEmpty()
        }.getOrDefault("")
    }

    private fun callClaude(provider: ProviderSetting.Claude, system: String, user: String): String {
        val body = buildJsonObject {
            put("model", provider.models.firstOrNull()?.id?.toString() ?: "claude-3-5-haiku-latest")
            put("max_tokens", 2048)
            put("temperature", 0.2)
            put("system", system)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", user)
                })
            }
        }
        val request = Request.Builder()
            .url("${provider.baseUrl}/messages")
            .header("x-api-key", provider.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .build()
        val text = buildClient().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} ${resp.body?.string()?.take(200)}")
            }
            resp.body?.string().orEmpty()
        }
        // Claude 响应：content[] 中 type=text 的 text 字段
        return runCatching {
            json.parseToJsonElement(text).jsonObject
                .get("content")?.jsonArray
                ?.joinToString("\n") { el ->
                    el.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
                }.orEmpty()
        }.getOrDefault("")
    }

    private fun parsePlan(raw: String): AngelPlan {
        val cleaned = raw
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return runCatching {
            val obj = json.parseToJsonElement(cleaned).jsonObject
            val done = obj["done"]?.jsonPrimitive?.content == "true"
            val actions = obj["actions"]?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                val type = o["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val action = AngelAction(
                    type = type,
                    text = o["text"]?.jsonPrimitive?.content.orEmpty(),
                    direction = o["direction"]?.jsonPrimitive?.content.orEmpty(),
                )
                action
            }.orEmpty()
            AngelPlan(done = done, actions = actions, raw = raw)
        }.getOrElse {
            AngelPlan(done = true, actions = emptyList(), raw = raw)
        }
    }
}

data class AngelAction(
    val type: String,
    val text: String = "",
    val direction: String = "",
)

data class AngelPlan(
    val done: Boolean,
    val actions: List<AngelAction>,
    val raw: String,
) {
    fun describe(): String = if (actions.isEmpty()) {
        "完成"
    } else {
        actions.joinToString(" → ") { a ->
            when (a.type) {
                "click" -> "点击「${a.text}」"
                "input" -> "输入「${a.text}」"
                "scroll" -> "向${if (a.direction == "up") "上" else "下"}滚动"
                "back" -> "返回"
                "home" -> "回桌面"
                else -> a.type
            }
        }
    }
}
