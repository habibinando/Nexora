package com.example.nexora.engine.peft

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Standard Hugging Face PEFT LoRA Configuration Representation.
 * Matches `peft.LoraConfig` from Python `peft` library.
 */
data class PeftLoraConfig(
    val peftType: String = "LORA",
    val taskType: String = "CAUSAL_LM",
    val r: Int = 8,
    val loraAlpha: Int = 16,
    val loraDropout: Float = 0.05f,
    val targetModules: List<String> = listOf("w_h", "w_out"),
    val bias: String = "none",
    val baseModelNameOrPath: String = "Nexora-Local-Neural",
    val inferenceMode: Boolean = false,
    val initLoraWeights: Boolean = true
) {
    /**
     * Hitung jumlah parameter trainable LoRA secara matematis nyata:
     * Untuk setiap modul berdimensi (d_in, d_out), LoRA menambahkan matriks A (d_in x r) dan B (r x d_out).
     * Total parameter per modul = r * (d_in + d_out).
     *
     * vocabSize / hiddenDim / embedDim WAJIB diisi nilai nyata dari model aktif
     * (tidak ada default hardcode). Gunakan CharRNNModel.DEFAULT_VOCAB_SIZE sebagai fallback.
     */
    fun computeTrainableParams(vocabSize: Int, hiddenDim: Int, embedDim: Int): Long {
        var trainableCount = 0L
        for (module in targetModules) {
            when (module) {
                "w_h" -> {
                    // Hidden-to-hidden recurrent matrix (hiddenDim x hiddenDim)
                    trainableCount += r.toLong() * (hiddenDim + hiddenDim)
                }
                "w_out" -> {
                    // Output projection matrix (hiddenDim x vocabSize)
                    trainableCount += r.toLong() * (hiddenDim + vocabSize)
                }
                "w_in", "embed_tokens" -> {
                    // Embedding projection matrix (vocabSize x embedDim)
                    trainableCount += r.toLong() * (vocabSize + embedDim)
                }
                "q_proj", "v_proj", "k_proj" -> {
                    // Attention projection matrices (hiddenDim x hiddenDim)
                    trainableCount += r.toLong() * (hiddenDim + hiddenDim)
                }
                else -> {
                    trainableCount += r.toLong() * (hiddenDim + hiddenDim)
                }
            }
        }
        return trainableCount
    }

    /**
     * Total parameter model dasar (base model) untuk perhitungan rasio trainable.
     * Semua dimensi wajib diisi nilai nyata dari model aktif.
     */
    fun computeBaseModelParams(vocabSize: Int, hiddenDim: Int, embedDim: Int): Long {
        val embedParams = vocabSize.toLong() * embedDim
        val rnnParams = (embedDim + hiddenDim).toLong() * hiddenDim + hiddenDim // W_xh + W_hh + b_h
        val outParams = hiddenDim.toLong() * vocabSize + vocabSize // W_out + b_out
        return embedParams + rnnParams + outParams
    }

    /**
     * Menghasilkan string JSON yang identik dengan format `adapter_config.json` dari library Hugging Face `peft`.
     */
    fun toAdapterConfigJson(): String {
        val obj = JSONObject()
        obj.put("peft_type", peftType)
        obj.put("task_type", taskType)
        obj.put("r", r)
        obj.put("lora_alpha", loraAlpha)
        obj.put("lora_dropout", loraDropout)
        obj.put("target_modules", JSONArray(targetModules))
        obj.put("bias", bias)
        obj.put("modules_to_save", JSONObject.NULL)
        obj.put("base_model_name_or_path", baseModelNameOrPath)
        obj.put("inference_mode", inferenceMode)
        obj.put("fan_in_fan_out", false)
        obj.put("init_lora_weights", initLoraWeights)
        obj.put("auto_mapping", JSONObject.NULL)
        return obj.toString(2)
    }
}

object PeftConfigManager {

    fun getPeftDir(context: Context): File {
        return File(context.filesDir, "peft_adapters").apply { mkdirs() }
    }

    /**
     * Menyimpan berkas adapter_config.json dan adapter_model.safetensors ke format folder PEFT standar.
     */
    fun savePeftAdapter(
        context: Context,
        adapterId: String,
        config: PeftLoraConfig,
        safetensorsBytes: ByteArray? = null
    ): File {
        val adapterFolder = File(getPeftDir(context), adapterId).apply { mkdirs() }

        // 1. Simpan adapter_config.json (Standard PEFT)
        val configFile = File(adapterFolder, "adapter_config.json")
        configFile.writeText(config.toAdapterConfigJson())

        // 2. Simpan adapter_model.safetensors jika ada
        if (safetensorsBytes != null && safetensorsBytes.isNotEmpty()) {
            val weightsFile = File(adapterFolder, "adapter_model.safetensors")
            weightsFile.writeBytes(safetensorsBytes)
        }

        return adapterFolder
    }

    /**
     * Membaca ukuran file adapter nyata di storage. Mengembalikan null jika belum ada.
     */
    fun getAdapterSizeBytes(context: Context, adapterId: String): Long? {
        val folder = File(getPeftDir(context), adapterId)
        val safetensorsFile = File(folder, "adapter_model.safetensors")
        if (safetensorsFile.exists() && safetensorsFile.length() > 0L) {
            return safetensorsFile.length()
        }
        val binFile = File(File(context.filesDir, "adapters"), "$adapterId.safetensors")
        if (binFile.exists() && binFile.length() > 0L) {
            return binFile.length()
        }
        return null
    }
}
