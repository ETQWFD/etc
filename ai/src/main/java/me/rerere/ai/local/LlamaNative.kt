package me.rerere.ai.local

/**
 * 本地 GGUF 模型推理（llama.cpp 最小 JNI）。
 * 同一时间只维护一个会话句柄，加载后通过 [stream] 进行流式对话。
 */
object LlamaNative {
    init {
        // 按依赖顺序加载：ggml → llama → chat_jni
        System.loadLibrary("ggml")
        System.loadLibrary("ggml-base")
        System.loadLibrary("ggml-cpu")
        System.loadLibrary("llama")
        System.loadLibrary("chat_jni")
    }

    interface TokenCallback {
        fun onToken(token: String)
        fun onError(message: String)
    }

    external fun create(modelPath: String, nCtx: Int, threads: Int): Long
    external fun free(handle: Long)
    external fun stream(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        cb: TokenCallback,
    ): String
}
