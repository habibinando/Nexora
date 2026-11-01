package com.nexora.ai

internal object ModelFileValidator {
    private val ggufMagic = byteArrayOf(0x47, 0x47, 0x55, 0x46)

    private val shardPattern = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)

    fun isSupportedSingleFile(displayName: String): Boolean =
        displayName.endsWith(".gguf", ignoreCase = true) &&
            !shardPattern.containsMatchIn(displayName)

    fun isGgufFile(displayName: String, header: ByteArray): Boolean {
        if (!isSupportedSingleFile(displayName) || header.size < ggufMagic.size) return false
        return ggufMagic.indices.all { header[it] == ggufMagic[it] }
    }
}
