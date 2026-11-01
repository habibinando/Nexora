package com.nexora.ai

internal object NativeLlama {
    init { System.loadLibrary("nexora-inference") }

    external fun initialize(nativeLibraryDir: String): String
    external fun loadModel(path: String, projectorPath: String?, contextSize: Int, threadCount: Int): Int
    external fun hasVision(): Boolean
    external fun startVisionPrompt(systemPrompt: String, userMessage: String, imageBytes: ByteArray, maxTokens: Int): Int
    external fun startPrompt(systemPrompt: String, userMessage: String, maxTokens: Int): Int
    external fun nextToken(): String?
    external fun lastError(): String
    external fun resetChat()
    external fun unload()
    external fun shutdown()
}
