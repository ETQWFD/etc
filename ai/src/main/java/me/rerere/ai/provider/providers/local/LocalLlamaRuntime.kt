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
                throw IllegalStateException("模型加载失败：内存不足或模型文件损坏，请重启应用后重试")
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
