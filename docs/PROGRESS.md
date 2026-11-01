# Nexora — Progress Log

## Status terkini

- Tanggal audit: 2026-11-01
- Repository: `habibinando/Nexora`
- Branch dasar: `main`
- Commit dasar yang diaudit: `8e329c661f83d2a5023d0d9c73ef7d423938d377`
- Branch audit: `audit/fase-0-baseline`
- Fase aktif: **Fase 0 — Baseline (B0)**
- Status: **BLOCKED — benchmark perangkat nyata belum dapat diambil**
- Perubahan aplikasi: **tidak ada**
- CI baru: **tidak dijalankan** (audit ini hanya membaca kode; tidak ada perubahan build input).

## Temuan baseline dari source saat ini

1. `app/src/main/java/com/nexora/ai/NexoraScreen.kt`
   - `HardwarePage` saat ini hanya menampilkan informasi hardware/RAM dan informasi backend.
   - Belum ada tombol **Benchmark** atau tampilan hasil benchmark.
2. `app/src/main/java/com/nexora/ai/NativeLlama.kt` dan `app/src/main/cpp/nexora_jni.cpp`
   - JNI `inferenceStats()` saat ini hanya mengembalikan fase inferensi, token prompt diproses/total, token output, dan batas output.
   - Belum ada timestamp native untuk prefill/decode, durasi load, atau peak resident memory (RSS).
3. `app/src/main/cpp/nexora_jni.cpp`
   - `decode_tokens_locked()` menggunakan kapasitas batch tetap **32 token**.
   - Jalur prompt saat ini membangun ulang KV cache pada setiap giliran; belum ada prefix-cache reuse.
4. `app/src/main/java/com/nexora/ai/MainActivity.kt`
   - Aplikasi sudah memantau fase/penghitung token untuk progress UI, tetapi data tersebut belum cukup untuk memisahkan throughput prefill dan decode secara valid.

## B0 — Pengukuran yang diwajibkan

| Metrik | Hasil baseline |
|---|---|
| Model GGUF dan quantization | Belum diukur |
| Perangkat / SoC / RAM | Belum diukur pada sesi benchmark |
| Prefill throughput (tok/s) | **Belum tersedia** |
| Decode throughput (tok/s) | **Belum tersedia** |
| Waktu load model | **Belum tersedia** |
| Peak RAM / RSS | **Belum tersedia** |
| Prompt benchmark | Belum dijalankan |
| Output benchmark | Target 128 token; belum dijalankan |

Tidak ada angka yang diestimasi atau direkayasa. Benchmark harus dijalankan pada perangkat Android ARM64 yang menjadi target, memakai model yang diimpor pengguna. Build CI di runner bukan pengganti benchmark perangkat karena tidak merepresentasikan SoC, memori, backend, atau kondisi termal perangkat target.

## Hambatan verifikasi

- Sesi audit tidak memiliki perangkat Android yang terhubung, `adb` tidak tersedia, serta `ANDROID_HOME`/`ANDROID_SDK_ROOT` belum disetel.
- Checkout lokal tidak dapat dilakukan karena lingkungan eksekusi tidak dapat menyelesaikan host `github.com`.
- Tidak ada file model GGUF milik pengguna yang tersedia pada sesi ini.
- Karena itu tidak mungkin mengukur prefill/decode tok/s, waktu load, atau peak RSS secara jujur.
- Perintah `./gradlew clean testDebugUnitTest assembleDebug assembleRelease` belum dijalankan pada sesi audit ini. Audit ini tidak mengklaim build lokal berhasil.

## Status CI yang sudah ada (bukan benchmark)

- Commit main saat audit: `8e329c661f83d2a5023d0d9c73ef7d423938d377`.
- Workflow Android **Nexora Android - Build and Verify** sebelumnya berakhir sukses pada commit `636e6476e00bff8cee724aa8f032dd68249e620a` (run `41312607153`).
- Run tersebut menjalankan unit test dan build APK debug sesuai workflow saat itu. Itu tidak mengukur performa inference perangkat dan bukan bukti bahwa APK release telah dibangun atau diuji.

## Langkah berikutnya — tetap di Fase 0

1. Tambahkan instrumentasi benchmark khusus B0: prompt tetap, tepat 128 token output yang diminta, timestamp native terpisah untuk prefill dan decode, durasi load, dan peak RSS.
2. Build APK instrumentasi satu kali, lalu jalankan pada perangkat target dengan model GGUF yang dipilih pengguna.
3. Catat model/quantization, perangkat, konfigurasi thread/context, serta angka aktual ke bagian B0 di dokumen ini.
4. **Jangan mulai Fase 1 sebelum baseline nyata tercatat.**

## Batasan perubahan

Audit ini hanya menambahkan dokumen progres. Tidak mengubah source Kotlin, Compose, JNI/C++, dependency, Gradle, manifest, workflow, llama.cpp pin, atau konfigurasi izin aplikasi. Tidak ada branch lain yang dibuat.
