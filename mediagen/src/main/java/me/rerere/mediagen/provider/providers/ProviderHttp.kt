package me.rerere.mediagen.provider.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal val mediaGenerationJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal val jsonMediaType = "application/json".toMediaType()

internal fun bearerRequest(url: String, apiKey: String): Request.Builder =
    Request.Builder().url(url).addHeader("Authorization", "Bearer $apiKey")

// 响应体在 IO 线程读取，调用方可以直接从主线程协程发起请求
// 绘图后端（如本地 Stable Diffusion 网关）在模型权重尚未加载完成时会返回
// 503 {"error":"engine not ready, try again"}。这里在传输层统一做退避重试，
// 避免把冷启动期间的临时 503 直接抛给用户。
private const val ENGINE_NOT_READY_MAX_RETRIES = 5
private const val ENGINE_NOT_READY_DELAY_MS = 4000L

internal suspend fun OkHttpClient.executeJson(request: Request, provider: String): JsonObject =
    withContext(Dispatchers.IO) {
        repeat(ENGINE_NOT_READY_MAX_RETRIES + 1) { index ->
            newCall(request).await().use { response ->
                val text = response.body.string()
                val body = runCatching { mediaGenerationJson.parseToJsonElement(text).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
                if (response.isSuccessful) {
                    return@withContext body
                }
                val notReady = response.code == 503 &&
                    text.contains("not ready", ignoreCase = true)
                if (!notReady || index == ENGINE_NOT_READY_MAX_RETRIES) {
                    val error = body.obj("error")
                    val detail = error?.string("message") ?: body.string("message") ?: text
                    throw MediaGenerationApiException(
                        provider = provider,
                        statusCode = response.code,
                        code = error?.string("code") ?: body.string("code") ?: error?.string("type"),
                        message = if (notReady) {
                            "绘图引擎加载超时，请确认绘图服务已完全启动或设备内存充足后重试"
                        } else {
                            detail
                        },
                    )
                }
            }
            delay(ENGINE_NOT_READY_DELAY_MS)
        }
        error("unreachable")
    }

internal fun Request.Builder.postJson(body: JsonObject): Request.Builder =
    post(
        mediaGenerationJson.encodeToString(JsonObject.serializer(), body)
            .toRequestBody(jsonMediaType)
    )

internal fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.long(name: String): Long? =
    this[name]?.jsonPrimitive?.longOrNull

internal fun JsonObject.int(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

internal fun JsonObject.double(name: String): Double? =
    this[name]?.jsonPrimitive?.doubleOrNull

internal fun JsonObject.obj(name: String): JsonObject? =
    this[name] as? JsonObject

class MediaGenerationApiException(
    val provider: String,
    val statusCode: Int,
    val code: String?,
    override val message: String,
) : Exception(
    "$provider media generation failed ($statusCode${
        code?.let { ", $it" }.orEmpty()
    }): $message"
)
