package com.example.neural

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class DeviceHardwareSpec(
    val cpuCores: Int,
    val primaryAbi: String,
    val allAbis: String,
    val maxHeapMemoryMb: Long,
    val totalMemoryMb: Long,
    val freeMemoryMb: Long,
    val androidVersion: String,
    val deviceModel: String,
    val ndkInfo: String
)

class HardwareBenchmarkEngine {

    private val _latestBenchmark = MutableStateFlow<BenchmarkResult?>(null)
    val latestBenchmark: StateFlow<BenchmarkResult?> = _latestBenchmark.asStateFlow()

    private val _isBenchmarking = MutableStateFlow(false)
    val isBenchmarking: StateFlow<Boolean> = _isBenchmarking.asStateFlow()

    fun getDeviceSpec(): DeviceHardwareSpec {
        val runtime = Runtime.getRuntime()
        val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)
        val totalMemoryMb = runtime.totalMemory() / (1024 * 1024)
        val freeMemoryMb = runtime.freeMemory() / (1024 * 1024)

        return DeviceHardwareSpec(
            cpuCores = runtime.availableProcessors(),
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            allAbis = Build.SUPPORTED_ABIS.joinToString(", "),
            maxHeapMemoryMb = maxMemoryMb,
            totalMemoryMb = totalMemoryMb,
            freeMemoryMb = freeMemoryMb,
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            ndkInfo = NdkNeuralAccelerator.getNdkHardwareInfo()
        )
    }

    suspend fun runBenchmark(matrixDimension: Int = 128, iterations: Int = 10): BenchmarkResult {
        _isBenchmarking.value = true
        return withContext(Dispatchers.Default) {
            val result = NdkNeuralAccelerator.runMatMulBenchmark(
                m = matrixDimension,
                n = matrixDimension,
                k = matrixDimension,
                iterations = iterations
            )
            _latestBenchmark.value = result
            _isBenchmarking.value = false
            result
        }
    }

    fun clearBenchmark() {
        _latestBenchmark.value = null
    }
}
