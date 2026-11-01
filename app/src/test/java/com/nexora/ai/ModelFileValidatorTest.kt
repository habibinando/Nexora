package com.nexora.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFileValidatorTest {
    @Test
    fun acceptsValidSingleGgufHeader() {
        assertTrue(ModelFileValidator.isGgufFile("tiny.gguf", byteArrayOf(0x47, 0x47, 0x55, 0x46)))
    }

    @Test
    fun rejectsWrongMagic() {
        assertFalse(ModelFileValidator.isGgufFile("model.gguf", byteArrayOf(0, 0, 0, 0)))
    }

    @Test
    fun rejectsNonGgufFile() {
        assertFalse(ModelFileValidator.isGgufFile("weights.bin", byteArrayOf(0x47, 0x47, 0x55, 0x46)))
    }

    @Test
    fun rejectsEveryShardUntilMultiFileImportIsSupported() {
        assertFalse(ModelFileValidator.isSupportedSingleFile("model-00001-of-00003.gguf"))
        assertFalse(ModelFileValidator.isSupportedSingleFile("model-00002-of-00003.gguf"))
        assertFalse(ModelFileValidator.isSupportedSingleFile("model-00003-of-00003.gguf"))
    }
}
