package me.rerere.ai.provider.providers.local

import me.rerere.ai.local.MnnNative
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * MNN-LLM 运行时（全局单例）：串行推理，切换模型自动释放旧句柄。
 * modelPath 为包含 config.json 的模型目录。
 */
object LocalMnnRuntime {
    private val lock = ReentrantLock()
    private var handle = 0L
    private var path = ""

    fun chat(
        modelDir: String,
        userContent: String,
        maxTokens: Int,
        threads: Int,
        streamTo: ((String) -> Unit)?,
    ): String = lock.withLock {
        if (handle == 0L || path != modelDir) {
            if (handle != 0L) {
                runCatching { MnnNative.free(handle) }
                handle = 0L
                path = ""
            }
            val config = File(modelDir, "config.json")
            if (!config.exists()) {
                throw IllegalStateException("MNN 模型目录缺少 config.json")
            }
            val created = MnnNative.create(config.absolutePath, if (threads > 0) threads else 2)
            if (created == 0L) {
                throw IllegalStateException("MNN 模型加载失败")
            }
            if (created < 0L) {
                throw IllegalStateException(
                    if (created == -2L) "MNN 模型文件损坏，请重新下载"
                    else "MNN 模型加载失败，请检查文件"
                )
            }
            handle = created
            path = modelDir
        }
        val cb = object : MnnNative.TokenCallback {
            override fun onToken(token: String) {
                streamTo?.invoke(token)
            }
        }
        val result = MnnNative.chat(handle, userContent, maxTokens, cb)
        result
    }

    fun release() {
        lock.withLock {
            if (handle != 0L) {
                runCatching { MnnNative.free(handle) }
                handle = 0L
                path = ""
            }
        }
    }
}
