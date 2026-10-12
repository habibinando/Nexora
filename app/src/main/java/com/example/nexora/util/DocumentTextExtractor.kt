package com.example.nexora.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream
import kotlin.coroutines.resume

data class TextExtractionResult(
    val fileName: String,
    val extractedText: String,
    val sourceFormat: String, // "TEXT_FILE", "PDF_STREAM", "PDF_OCR", "IMAGE_OCR", "UNSUPPORTED"
    val characterCount: Int,
    val isSuccess: Boolean,
    val notes: String = ""
)

object DocumentTextExtractor {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Mengekstrak teks dari berbagai jenis berkas (Dokumen Teks, PDF, maupun Foto/Gambar).
     */
    suspend fun extractTextFromUri(context: Context, uri: Uri, fileName: String): TextExtractionResult =
        withContext(Dispatchers.IO) {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = context.contentResolver.getType(uri) ?: ""

            when {
                // 1. Gambar / Foto (JPG, PNG, WEBP, BMP)
                extension in listOf("jpg", "jpeg", "png", "webp", "bmp") || mimeType.startsWith("image/") -> {
                    extractFromImage(context, uri, fileName)
                }

                // 2. Berkas PDF
                extension == "pdf" || mimeType == "application/pdf" -> {
                    extractFromPdf(context, uri, fileName)
                }

                // 3. Dokumen Teks Polos & Kode Program
                else -> {
                    extractFromPlainText(context, uri, fileName)
                }
            }
        }

    /**
     * Ekstraksi OCR dari Gambar/Foto menggunakan ML Kit Text Recognition on-device.
     */
    suspend fun extractFromImage(context: Context, uri: Uri, fileName: String): TextExtractionResult {
        return try {
            val inputImage = InputImage.fromFilePath(context, uri)
            val recognized = suspendCancellableCoroutine<String> { continuation ->
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        continuation.resume(visionText.text)
                    }
                    .addOnFailureListener { error ->
                        continuation.resume("OCR Error: ${error.message}")
                    }
            }

            val cleaned = recognized.trim()
            if (cleaned.isNotBlank() && !cleaned.startsWith("OCR Error:")) {
                TextExtractionResult(
                    fileName = fileName,
                    extractedText = cleaned,
                    sourceFormat = "IMAGE_OCR",
                    characterCount = cleaned.length,
                    isSuccess = true,
                    notes = "Teks berhasil diekstrak dari gambar menggunakan ML Kit OCR On-Device."
                )
            } else {
                TextExtractionResult(
                    fileName = fileName,
                    extractedText = "[Foto: $fileName]\n(Tidak terdeteksi karakter teks yang dapat dibaca pada gambar ini)",
                    sourceFormat = "IMAGE_OCR",
                    characterCount = 0,
                    isSuccess = false,
                    notes = if (cleaned.startsWith("OCR Error:")) cleaned else "Gambar tidak mengandung teks yang dapat dibaca."
                )
            }
        } catch (e: Exception) {
            TextExtractionResult(
                fileName = fileName,
                extractedText = "[Foto: $fileName]\n(Gagal memproses gambar: ${e.message})",
                sourceFormat = "IMAGE_OCR",
                characterCount = 0,
                isSuccess = false,
                notes = "Kesalahan pembacaan citra: ${e.message}"
            )
        }
    }

    /**
     * Ekstraksi teks dari PDF:
     * Langkah 1: Ekstraksi stream teks digital PDF (uncompress FlateDecode + parse operator PDF).
     * Langkah 2: Jika stream kosong (misal PDF hasil scan kertas), render halaman ke Bitmap & jalankan ML Kit OCR!
     */
    suspend fun extractFromPdf(context: Context, uri: Uri, fileName: String): TextExtractionResult {
        // Coba ekstraksi digital stream terlebih dahulu
        val pdfBytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }

        var digitalText = ""
        if (pdfBytes != null) {
            digitalText = parseDigitalPdfStream(pdfBytes).trim()
        }

        if (digitalText.length >= 30) {
            return TextExtractionResult(
                fileName = fileName,
                extractedText = digitalText,
                sourceFormat = "PDF_STREAM",
                characterCount = digitalText.length,
                isSuccess = true,
                notes = "Teks digital berhasil diekstrak langsung dari struktur dokumen PDF."
            )
        }

        // Jika teks digital kosong atau sangat pendek, kemungkinan PDF scan/gambar!
        // Render halaman PDF menggunakan PdfRenderer bawaan Android dan ekstrak dengan OCR ML Kit.
        return try {
            val ocrPdfText = extractPdfWithRendererOcr(context, uri, maxPages = 5)
            if (ocrPdfText.isNotBlank()) {
                TextExtractionResult(
                    fileName = fileName,
                    extractedText = ocrPdfText,
                    sourceFormat = "PDF_OCR",
                    characterCount = ocrPdfText.length,
                    isSuccess = true,
                    notes = "Teks diekstrak melalui perenderan halaman PDF & ML Kit OCR visual."
                )
            } else if (digitalText.isNotBlank()) {
                TextExtractionResult(
                    fileName = fileName,
                    extractedText = digitalText,
                    sourceFormat = "PDF_STREAM",
                    characterCount = digitalText.length,
                    isSuccess = true,
                    notes = "Teks diekstrak dari stream PDF parsial."
                )
            } else {
                TextExtractionResult(
                    fileName = fileName,
                    extractedText = "[Dokumen PDF: $fileName]\n(PDF tidak mengandung teks digital dan OCR tidak mendeteksi tulisan)",
                    sourceFormat = "PDF_EMPTY",
                    characterCount = 0,
                    isSuccess = false,
                    notes = "PDF kosong atau halaman tidak memuat teks."
                )
            }
        } catch (e: Exception) {
            if (digitalText.isNotBlank()) {
                TextExtractionResult(
                    fileName = fileName,
                    extractedText = digitalText,
                    sourceFormat = "PDF_STREAM",
                    characterCount = digitalText.length,
                    isSuccess = true,
                    notes = "Fallback stream digital PDF."
                )
            } else {
                TextExtractionResult(
                    fileName = fileName,
                    extractedText = "[Dokumen PDF: $fileName]\n(Gagal mengekstrak teks PDF: ${e.message})",
                    sourceFormat = "PDF_ERROR",
                    characterCount = 0,
                    isSuccess = false,
                    notes = e.message ?: "Kesalahan membaca PDF"
                )
            }
        }
    }

    /**
     * Render halaman PDF menjadi Bitmap menggunakan Android PdfRenderer, lalu OCR dengan ML Kit.
     */
    private suspend fun extractPdfWithRendererOcr(context: Context, uri: Uri, maxPages: Int = 5): String {
        val tempFile = File.createTempFile("pdf_render_", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY) ?: return ""
            pfd.use { descriptor ->
                val renderer = PdfRenderer(descriptor)
                val totalPages = renderer.pageCount.coerceAtMost(maxPages)
                val sb = StringBuilder()

                for (pageIndex in 0 until totalPages) {
                    val page = renderer.openPage(pageIndex)
                    // Render halaman ke Bitmap resolusi baik (lebar ~1200px)
                    val width = 1200
                    val height = (width.toFloat() / page.width * page.height).toInt().coerceAtLeast(100)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    // Jalankan OCR pada Bitmap halaman
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val pageText = suspendCancellableCoroutine<String> { continuation ->
                        recognizer.process(inputImage)
                            .addOnSuccessListener { continuation.resume(it.text) }
                            .addOnFailureListener { continuation.resume("") }
                    }
                    bitmap.recycle()

                    if (pageText.isNotBlank()) {
                        sb.append("--- [Halaman ${pageIndex + 1}] ---\n")
                        sb.append(pageText.trim()).append("\n\n")
                    }
                }
                renderer.close()
                return sb.toString().trim()
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Parser cerdas untuk membaca aliran teks langsung dari file PDF tanpa library eksternal.
     * Mengurai blok stream, mendekompresi FlateDecode (zlib), dan mengekstrak operator teks:
     * - `(...) Tj`
     * - `[(...) ...] TJ`
     * - `<hex> Tj`
     */
    fun parseDigitalPdfStream(pdfBytes: ByteArray): String {
        val sb = StringBuilder()
        val textBytes = String(pdfBytes, StandardCharsets.ISO_8859_1)

        var searchIndex = 0
        while (searchIndex < textBytes.length) {
            val streamStart = textBytes.indexOf("stream", searchIndex)
            if (streamStart == -1) break

            // Cari dictionary sebelum stream untuk cek apakah terkompresi FlateDecode
            val dictStart = textBytes.lastIndexOf("<<", streamStart).coerceAtLeast(0)
            val dictHeader = if (dictStart in 0 until streamStart) textBytes.substring(dictStart, streamStart) else ""
            val isFlate = dictHeader.contains("/FlateDecode")

            // Stream konten sesungguhnya mulai setelah newline
            var dataStart = streamStart + 6
            if (dataStart < pdfBytes.size && pdfBytes[dataStart] == '\r'.code.toByte()) dataStart++
            if (dataStart < pdfBytes.size && pdfBytes[dataStart] == '\n'.code.toByte()) dataStart++

            val streamEnd = textBytes.indexOf("endstream", dataStart)
            if (streamEnd == -1 || streamEnd <= dataStart) {
                searchIndex = streamStart + 6
                continue
            }

            val length = streamEnd - dataStart
            if (length > 0 && dataStart + length <= pdfBytes.size) {
                val rawStreamBytes = pdfBytes.copyOfRange(dataStart, dataStart + length)
                val decompressedBytes = if (isFlate) {
                    try {
                        InflaterInputStream(ByteArrayInputStream(rawStreamBytes)).use { it.readBytes() }
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    rawStreamBytes
                }

                if (decompressedBytes != null) {
                    val streamContent = String(decompressedBytes, StandardCharsets.ISO_8859_1)
                    val extractedFromStream = extractPdfTextOperators(streamContent)
                    if (extractedFromStream.isNotBlank()) {
                        sb.append(extractedFromStream).append("\n")
                    }
                }
            }

            searchIndex = streamEnd + 9
        }

        return sb.toString().trim()
    }

    /**
     * Ekstraksi string dari operator PDF teks (Tj, TJ, hex)
     */
    private fun extractPdfTextOperators(streamContent: String): String {
        val out = StringBuilder()
        var i = 0
        val len = streamContent.length

        while (i < len) {
            val ch = streamContent[i]

            // 1. Literal String di dalam kurung: (...)
            if (ch == '(') {
                val strBuilder = StringBuilder()
                var depth = 1
                i++
                while (i < len && depth > 0) {
                    val c = streamContent[i]
                    if (c == '\\' && i + 1 < len) {
                        // Escape character
                        val next = streamContent[i + 1]
                        when (next) {
                            'n' -> strBuilder.append('\n')
                            'r' -> strBuilder.append('\r')
                            't' -> strBuilder.append('\t')
                            '(', ')', '\\' -> strBuilder.append(next)
                            else -> strBuilder.append(next)
                        }
                        i += 2
                    } else if (c == '(') {
                        depth++
                        strBuilder.append(c)
                        i++
                    } else if (c == ')') {
                        depth--
                        if (depth > 0) strBuilder.append(c)
                        i++
                    } else {
                        strBuilder.append(c)
                        i++
                    }
                }

                val str = strBuilder.toString()
                // Cek apakah string ini diikuti operator teks
                val peek = streamContent.substring(i, (i + 10).coerceAtMost(len)).trimStart()
                if (peek.startsWith("Tj") || peek.startsWith("'") || peek.startsWith("\"")) {
                    out.append(str).append(" ")
                } else {
                    // String di dalam array TJ
                    out.append(str).append(" ")
                }
                continue
            }

            // 2. Hex String di dalam kurung siku tajam: <48656C6C6F>
            if (ch == '<' && i + 1 < len && streamContent[i + 1] != '<') {
                val hexBuilder = StringBuilder()
                i++
                while (i < len && streamContent[i] != '>') {
                    val c = streamContent[i]
                    if (c.isLetterOrDigit()) hexBuilder.append(c)
                    i++
                }
                if (i < len && streamContent[i] == '>') i++

                val hex = hexBuilder.toString()
                if (hex.length >= 2 && hex.length % 2 == 0) {
                    try {
                        val bytes = ByteArray(hex.length / 2)
                        for (idx in 0 until hex.length step 2) {
                            bytes[idx / 2] = hex.substring(idx, idx + 2).toInt(16).toByte()
                        }
                        out.append(String(bytes, StandardCharsets.UTF_8)).append(" ")
                    } catch (_: Exception) {}
                }
                continue
            }

            // Line break operator di PDF
            if (streamContent.startsWith("T*", i) || streamContent.startsWith("ET", i)) {
                out.append("\n")
                i += 2
                continue
            }

            i++
        }

        return out.toString().lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
    }

    /**
     * Membaca berkas teks standar (.txt, .md, .json, .csv, dll) dengan proteksi buffer aman (maksimal 32 KB)
     * untuk mencegah OutOfMemoryError dan freeze UI saat membaca file besar.
     */
    private fun extractFromPlainText(context: Context, uri: Uri, fileName: String): TextExtractionResult {
        return try {
            val maxChars = 32_768
            val charBuffer = CharArray(maxChars)
            var charsRead = 0
            var isTruncated = false

            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                charsRead = reader.read(charBuffer, 0, maxChars)
                if (charsRead >= maxChars && reader.read() != -1) {
                    isTruncated = true
                }
            }

            if (charsRead > 0) {
                val rawString = String(charBuffer, 0, charsRead).trim()
                val finalContent = if (isTruncated) {
                    "$rawString\n\n[... Berkas sangat panjang: Teks dipotong pada 32.000 karakter pertama agar memori HP tetap stabil dan aman ...]"
                } else {
                    rawString
                }
                TextExtractionResult(
                    fileName = fileName,
                    extractedText = finalContent,
                    sourceFormat = if (isTruncated) "TEXT_FILE_TRUNCATED" else "TEXT_FILE",
                    characterCount = finalContent.length,
                    isSuccess = true,
                    notes = if (isTruncated) "Sebagian teks dibaca (dipotong demi kestabilan memori)" else "Berkas teks berhasil dibaca secara lengkap."
                )
            } else {
                TextExtractionResult(
                    fileName = fileName,
                    extractedText = "",
                    sourceFormat = "EMPTY_FILE",
                    characterCount = 0,
                    isSuccess = false,
                    notes = "Berkas teks kosong."
                )
            }
        } catch (oom: OutOfMemoryError) {
            System.gc()
            TextExtractionResult(
                fileName = fileName,
                extractedText = "",
                sourceFormat = "OOM_ERROR",
                characterCount = 0,
                isSuccess = false,
                notes = "Ukuran berkas terlalu besar untuk memori perangkat."
            )
        } catch (e: Exception) {
            TextExtractionResult(
                fileName = fileName,
                extractedText = "",
                sourceFormat = "READ_ERROR",
                characterCount = 0,
                isSuccess = false,
                notes = "Gagal membaca berkas teks: ${e.message}"
            )
        }
    }
}
