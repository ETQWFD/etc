package me.rerere.ai.provider.providers.local

import me.rerere.ai.local.LlamaNative
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 本地推理运行时（全局单例）：
 * - 同一时间仅保留一个模型句柄，切换模型自动释放旧句柄；
 * - 推理全程持锁串行执行，避免并发对话互相释放对方句柄导致「模型句柄无效」。
 */
object LocalLlamaRuntime {
    private val lock = ReentrantLock()
    private var handle = 0L
    private var path = ""

    fun run(
        modelPath: String,
        nCtx: Int,
        threads: Int,
        block: (Long) -> String,
    ): String = lock.withLock {
        if (handle == 0L || path != modelPath) {
            if (handle != 0L) {
                runCatching { LlamaNative.free(handle) }
                handle = 0L
                path = ""
            }
            val created = LlamaNative.create(modelPath, nCtx, threads)
            if (created == 0L) {
                throw IllegalStateException("模型加载失败，请检查文件后重试")
            }
            if (created < 0L) {
                // -1 文件损坏 / -2 加载失败：损坏文件无保留价值，自动清除以便重新下载
                if (created == -1L || created == -2L) {
                    runCatching {
                        val f = java.io.File(modelPath)
                        if (f.exists()) f.delete()
                    }
                    throw IllegalStateException("模型文件损坏，已自动清除，请重新下载该模型")
                }
                // -3 内存不足
                throw IllegalStateException("内存不足，请使用更小的模型或关闭其它应用后重试")
            }
            handle = created
            path = modelPath
        }
        val result = block(handle)
        result
    }

    /** 释放当前句柄（模型删除/应用退出时调用） */
    fun release() {
        lock.withLock {
            if (handle != 0L) {
                runCatching { LlamaNative.free(handle) }
                handle = 0L
                path = ""
            }
        }
    }
}
