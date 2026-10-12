package com.example.nexora.engine.hardware

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class HardwareAccelerationInfo(
    val hasVulkan: Boolean,
    val vulkanVersionString: String,
    val vulkanLevel: Int,
    val openGlEsVersion: String,
    val socModel: String,
    val hardwareBoard: String,
    val isHardwareAccelerationSupported: Boolean,
    val backendLabel: String,
    val diagnosticSummary: String
)

object HardwareAcceleratorDetector {

    fun detect(context: Context): HardwareAccelerationInfo {
        val pm = context.packageManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        // 1. OpenGL ES Version
        val configInfo = am?.deviceConfigurationInfo
        val glEsVersion = configInfo?.glEsVersion ?: "Unknown"

        // 2. Vulkan Hardware Detection via PackageManager System Features
        val hasVulkan = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)

        val hasVulkanLevel1 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, 1)
        } else false
        val vulkanLevel = if (hasVulkanLevel1) 1 else 0

        // Parse Vulkan Version integer (encoded as major[10 bits], minor[10 bits], patch[12 bits])
        var vulkanVersionCode = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                for (feature in pm.systemAvailableFeatures) {
                    if (feature.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) {
                        vulkanVersionCode = feature.version
                        break
                    }
                }
            } catch (_: Exception) {}
        }

        val vulkanVersionStr = if (vulkanVersionCode > 0) {
            val major = vulkanVersionCode shr 22
            val minor = (vulkanVersionCode shr 12) and 0x3FF
            val patch = vulkanVersionCode and 0xFFF
            "v$major.$minor.$patch"
        } else if (hasVulkan) {
            "v1.0"
        } else {
            "Tidak Didukung"
        }

        // 3. Chipset & SoC Identification
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.ifBlank { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }
        val board = Build.BOARD

        val glMajor = glEsVersion.split(".").firstOrNull()?.toIntOrNull() ?: 0
        val isSupported = hasVulkan || glMajor >= 3

        val backend = if (hasVulkan) {
            "Vulkan Compute ($vulkanVersionStr)"
        } else if (glMajor >= 3) {
            "OpenGL ES Compute ($glEsVersion)"
        } else {
            "CPU Vectorized NEON"
        }

        val diagnostic = if (hasVulkan) {
            "Driver Vulkan $vulkanVersionStr terverifikasi aktif pada chipset $soc. Akselerasi tensor GPU siap digunakan tanpa emulasi."
        } else if (glMajor >= 3) {
            "Vulkan native driver tidak tersedia. GLES $glEsVersion terdeteksi. Akselerasi dialihkan ke buffer grafis GLES dan thread SIMD."
        } else {
            "Chipset GPU ($soc) tidak memiliki driver Vulkan/GLES 3.0+. Akselerasi dialihkan secara jujur ke CPU multi-threading."
        }

        return HardwareAccelerationInfo(
            hasVulkan = hasVulkan,
            vulkanVersionString = vulkanVersionStr,
            vulkanLevel = vulkanLevel,
            openGlEsVersion = glEsVersion,
            socModel = soc,
            hardwareBoard = board,
            isHardwareAccelerationSupported = isSupported,
            backendLabel = backend,
            diagnosticSummary = diagnostic
        )
    }
}
