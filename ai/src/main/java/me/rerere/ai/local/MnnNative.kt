package me.rerere.ai.local

/**
 * MNN-LLM 本地推理（官方 MNN 3.6.1 预编译库，适合手机低内存设备）。
 * 模型：魔塔官方 MNN 仓库（llm.mnn + config.json + tokenizer.txt 整套）。
 */
object MnnNative {
    interface TokenCallback {
        fun onToken(token: String)
        fun onError(message: String) {}
    }

    /** MNN 库是否可用（x86_64 无官方预编译包时为 false，回退 GGUF） */
    val available: Boolean = try {
        System.loadLibrary("c++_shared")
        System.loadLibrary("MNN")
        System.loadLibrary("MNN_Express")
        System.loadLibrary("mnncore")
        System.loadLibrary("llm")
        true
    } catch (e: Throwable) {
        false
    }

    external fun create(configPath: String, threads: Int): Long
    external fun chat(
        handle: Long,
        userContent: String,
        maxTokens: Int,
        cb: TokenCallback?,
    ): String
    external fun reset(handle: Long)
    external fun free(handle: Long)
}
