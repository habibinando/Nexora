package com.nexora.ai

internal object ModelFileValidator {
    private val ggufMagic = byteArrayOf(0x47, 0x47, 0x55, 0x46)

    fun isSupportedSingleFile(displayName: String): Boolean =
        displayName.endsWith(".gguf", ignoreCase = true) &&
            !displayName.contains("-00001-of-", ignoreCase = true)

    fun isGgufFile(displayName: String, header: ByteArray): Boolean {
        if (!isSupportedSingleFile(displayName) || header.size < ggufMagic.size) return false
        return ggufMagic.indices.all { header[it] == ggufMagic[it] }
    }
}
