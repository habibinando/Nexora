package com.nexora.ai

internal object NativeLlama {
    init { System.loadLibrary("nexora-inference") }

    external fun initialize(nativeLibraryDir: String): String
    external fun loadModel(path: String, projectorPath: String?, contextSize: Int, threadCount: Int): Int
    external fun loadProgress(): Float
    external fun contextSize(): Int
    external fun isModelLoaded(): Boolean
    external fun activePath(): String
    external fun restoreHistory(systemPrompt: String, roles: Array<String>, contents: Array<String>): Int
    external fun inferenceStats(): IntArray
    external fun runBenchmark(prompt: String, maxTokens: Int): Int
    external fun benchmarkStats(): LongArray
    external fun benchmarkModelQuantization(): String
    external fun hasVision(): Boolean
    external fun startVisionPrompt(systemPrompt: String, userMessage: String, imageBytes: ByteArray, maxTokens: Int): Int
    external fun startPrompt(systemPrompt: String, userMessage: String, maxTokens: Int): Int
    external fun nextToken(): String?
    external fun cancelGeneration()
    external fun generationWasCancelled(): Boolean
    external fun lastError(): String
    external fun resetChat()
    external fun unload()
    external fun shutdown()
}
