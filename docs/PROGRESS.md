# Nexora — Progress Log

## Snapshot terkini

- Tanggal pembaruan: 2026-11-01
- Repository: `habibinando/Nexora`
- PR: [#5 — Fase 0 B0](https://github.com/habibinando/Nexora/pull/5)
- Base branch: `main`
- Branch pekerjaan: `audit/fase-0-baseline`
- Commit dasar `main`: `8e329c661f83d2a5023d0d9c73ef7d423938d377`
- Commit branch sebelum audit koreksi ini: `6c202f0cc18842be78842758fdc76cc0b76d8715`
- Fase aktif: **Fase 1 — K1 (release build dan distribusi APK)**
- B0: instrumentasi tersedia; pengukuran perangkat nyata belum tersedia dan tidak diklaim selesai
- K1: implementasi konfigurasi release sedang diverifikasi oleh CI

## Riwayat yang dipertahankan

### Audit awal pada commit dasar `8e329c6`

Temuan berikut benar untuk snapshot source pada commit dasar, bukan deskripsi source branch saat ini:

- Halaman Hardware belum mempunyai tombol Benchmark.
- JNI belum menyediakan timestamp prefill/decode, durasi load model, atau peak RSS.
- `decode_tokens_locked()` menggunakan batch tetap 32 token.
- Jalur chat membangun ulang KV cache pada setiap giliran.

Pada snapshot audit awal, hanya dokumen progres yang ditambahkan. Catatan historis ini tidak berarti source aplikasi branch sekarang belum berubah.

### Implementasi instrumentasi pada commit `6c202f0`

Commit tersebut menambahkan:

- JNI `runBenchmark(prompt, maxTokens)` dan `benchmarkStats()`.
- Prompt tetap melalui `strings.xml`, dengan target output maksimum 128 token.
- Timestamp native `std::chrono::steady_clock` terpisah untuk prefill dan decode.
- Durasi pemuatan model.
- Peak RSS melalui `getrusage(RUSAGE_SELF)`.
- Tombol dan tampilan hasil benchmark pada halaman Hardware.
- Penghitungan token prompt dan token output aktual.
- Penyimpanan/pemulihan riwayat dan system prompt setelah benchmark.

### Status CI yang sudah ada

- Workflow run: [41317407903](https://github.com/habibinando/Nexora/actions/runs/41317407903)
- Hasil run yang diperiksa: **SUCCESS**.
- Job berhasil menyelesaikan unit test, build APK ARM64 debug, dan pemeriksaan output APK.
- Ini membuktikan build/test pada runner untuk commit yang diuji; ini **bukan** bukti benchmark GGUF berjalan pada perangkat, dan bukan verifikasi release APK.

## Audit B0 terhadap implementasi

### A. Prefill

- Jumlah prompt diambil dari token hasil `tokenize_locked()` setelah chat template diterapkan. Ini adalah jumlah token prompt yang benar-benar dievaluasi.
- Timestamp prefill dimulai tepat sebelum pemanggilan `decode_tokens_locked()` dan berakhir setelah seluruh batch prompt selesai dievaluasi.
- Throughput dihitung sebagai token prompt aktual dibagi durasi prefill native.
- Rendering chat template dan tokenisasi tidak termasuk durasi prefill; hal ini dijelaskan pada catatan hasil.

### B. Decode

- Timestamp dimulai sebelum sampling token output pertama dan berakhir setelah loop generasi.
- Token output dihitung hanya setelah token non-EOS berhasil dievaluasi oleh `llama_decode()`.
- Target tetap 128 token.
- Status akhir membedakan EOS, batas output, kapasitas context, serta kondisi ketika batas output dan context tercapai bersamaan.
- Posisi KV terakhir yang valid adalah `context_size - 1`; loop benchmark menggunakan pemeriksaan posisi berikutnya `g_position < g_context_size` sehingga tidak kehilangan satu slot context.

### C. Waktu load

- Pengukuran native dimulai sesudah argumen JNI diterjemahkan dan model sebelumnya dilepas.
- Pengukuran berakhir setelah model, context, komponen MTMD bila diminta, batch, dan sampler berhasil diinisialisasi.
- Durasi tidak lagi mencakup teardown model lama dan tidak menyajikan durasi sukses lama saat proses load gagal.

### D. RAM

- Sumber: `getrusage(RUSAGE_SELF).ru_maxrss`.
- Satuan Linux/Android yang digunakan: KiB, dikonversi ke byte.
- Nilai adalah high-water mark RSS seluruh proses sejak proses dimulai. Ini bukan delta RAM khusus benchmark, bukan penggunaan RAM model saja, dan dapat mencakup puncak saat model dimuat.

### E. Isolasi

- Benchmark ditolak bila model/context/sampler belum siap atau generasi normal masih aktif.
- `g_mutex` menserialisasi benchmark dengan JNI inference normal; benchmark tidak berjalan paralel dengan generasi chat.
- Riwayat, system prompt, pending buffer, sisa token, serta penghitung/progress native disimpan dan dipulihkan.
- KV cache benchmark dibersihkan sesudahnya dan posisi dikembalikan ke nol. Giliran chat berikutnya merekonstruksi KV dari riwayat yang dipulihkan.
- Flag generasi dan phase dikembalikan ke state idle sebelumnya; benchmark tidak meninggalkan `g_generation_active` aktif.

### F. UI dan metadata

- Tombol berada di halaman Hardware dan dinonaktifkan bila tidak ada model aktif atau aplikasi sedang sibuk.
- Progress memakai penghitung fase/token atomik dari native.
- Hasil hanya ditampilkan setelah native benchmark selesai tanpa error.
- Hasil mencatat nama model, quantization dari `llama_model_ftype()`/`llama_ftype_name()`, perangkat, SoC/HW, RAM perangkat, context, jumlah thread, prefill, decode, alasan berhenti, waktu load, dan peak RSS.
- Tidak ada nilai throughput atau RAM contoh/simulasi.

### G. Kesesuaian API llama.cpp yang dipin

Header pada commit `8642541b255bfb5dc39cd351a178be09013a55d6` telah diperiksa secara langsung:

- `include/llama.h`: API memory clear, EOG detection, sampler reset, model ftype/quantization, dan model metadata tersedia.
- `tools/mtmd/mtmd.h`: API context MTMD dan pemeriksaan dukungan vision tersedia.
- `tools/mtmd/mtmd-helper.h`: API helper bitmap dan evaluasi chunk tersedia.

Tidak ada penggantian engine atau perubahan pin llama.cpp.

## Baseline pra-instrumentasi

**Tidak pernah diperoleh.** Tidak ada angka baseline inference lama yang dapat direkonstruksi dari CI atau source. Hasil B0 pertama yang kelak diambil dengan instrumentasi ini harus diberi label sebagai pengukuran pertama pasca-instrumentasi, bukan dibandingkan dengan angka baseline pra-instrumentasi yang tidak ada.

## Verifikasi yang belum dapat dilakukan

- Perintah lokal `./gradlew clean testDebugUnitTest assembleDebug assembleRelease` belum dijalankan pada sesi ini.
- Lingkungan yang tersedia tidak mempunyai checkout repository, Android SDK/NDK, atau `adb`; hanya JDK 21 terdeteksi.
- Tidak ada perangkat ARM64 yang terhubung dan tidak ada file GGUF pengguna yang tersedia.
- Belum ada hasil inference perangkat: prefill tok/s, decode tok/s, load duration, peak RSS, dan jumlah output aktual belum diketahui.
- Release build belum diverifikasi.
- CI runner tidak boleh dipakai sebagai pengganti pengukuran perangkat.

## Cara mengambil data B0 pada perangkat

1. Build/install APK dari PR #5 setelah CI untuk commit koreksi ini selesai.
2. Impor dan muat model GGUF milik pengguna; pastikan model yang dipilih memiliki ruang RAM dan context memadai.
3. Buka halaman **Perangkat** lalu tekan **Jalankan Benchmark**.
4. Simpan seluruh hasil yang ditampilkan: perangkat/SoC/RAM, model/quantization, context/thread, prefill tok/s, decode tok/s, durasi load, peak RSS, jumlah token output aktual, dan alasan berhenti.
5. Sertakan kondisi perangkat saat pengukuran (suhu/thermal, aplikasi lain, status baterai/pengisian, serta apakah perangkat baru selesai memuat model).

B0 tetap **BLOCKED** sampai pengukuran tersebut berasal dari perangkat Android ARM64 nyata dengan GGUF yang benar-benar dimuat. Jangan mulai K1 atau fase berikutnya sebelum hasil ini tersedia.

## Batas scope

Perubahan koreksi ini hanya menyentuh instrumentasi benchmark B0, metadata/penyajian hasil, dan dokumentasi progres. Tidak mengerjakan optimasi prefill/KV cache, stop generation, arsitektur ViewModel, release pipeline, atau fase roadmap berikutnya. Tidak membuat branch baru dan tidak menyentuh repository `Nexora2`.


## Fase 1 — K1: Release build dan distribusi APK

### Implementasi
- Build type `release` mengaktifkan R8 dan resource shrinking dengan `proguard-android-optimize.txt`.
- `NativeLlama` dipertahankan oleh ProGuard agar nama class dan metode JNI tidak berubah setelah minification.
- Release signing menggunakan environment `NEXORA_RELEASE_*`; workflow dapat menyiapkan keystore dari secret base64 tanpa menulisnya ke repository.
- Bila secret signing tidak lengkap, release APK memakai Android debug certificate sebagai fallback installable untuk pengujian. Ini bukan sertifikat produksi.
- Native CMake release dipaksa dengan `CMAKE_BUILD_TYPE=Release` dan compile database diaktifkan. Target JNI mendapat `-O3` khusus konfigurasi Release/RelWithDebInfo.
- CI menjalankan `clean testDebugUnitTest assembleDebug assembleRelease`, memeriksa kedua APK, tanda tangan, isi ABI ARM64, mapping R8, serta CMakeCache/compile_commands.
- APK PR diterbitkan sebagai prerelease GitHub Release untuk unduhan langsung. Tidak menggunakan Actions artifact upload.

### Batas verifikasi
- Run CI untuk commit K1 ini harus lulus sebelum APK hasilnya dianggap tersedia.
- Bila secret signing belum dipasang, APK release tetap bisa diuji tetapi akan menggunakan debug key.
- Distribusi Play Store, signing key permanen, dan pengujian install/update pada perangkat tetap memerlukan pemeriksaan terpisah.
- B0 belum mempunyai angka prefill/decode/load/RSS dari perangkat ARM64 nyata. Status tersebut tetap tercatat; CI tidak dianggap sebagai pengganti benchmark perangkat.
