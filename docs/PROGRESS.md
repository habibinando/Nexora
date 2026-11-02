# Nexora — Progress Log

## Snapshot terkini

- Tanggal pembaruan: 2026-11-02
- Repository: `habibinando/Nexora`
- PR: [#5 — Android native audit](https://github.com/habibinando/Nexora/pull/5)
- Base branch: `main` (`8e329c661f83d2a5023d0d9c73ef7d423938d377`)
- Branch pekerjaan: `audit/fase-0-baseline`
- Head sumber aplikasi/workflow: `aba78fb601fd13f9a2e39852be8260468f7e77fe` (perubahan dokumentasi berikutnya tidak mengubah source atau workflow).
- B0: **MEASURED — satu sampel pasca-instrumentasi**; baseline pra-instrumentasi tidak tersedia dan hasil belum menjadi benchmark komparatif.
- K1: **SUCCESS** — release build, R8/resource shrinking, signature verification, ARM64 library, CMake Release, dan JNI `-O3`.
- K2: **IMPLEMENTED / CI SUCCESS** — batching dan common-prefix KV reuse; kesetaraan output serta peningkatan throughput tetap memerlukan benchmark GGUF berulang.
- K3: **IMPLEMENTED / CI SUCCESS** — cancellation native/UI; smoke test Stop pada perangkat masih diperlukan.
- K4: **IMPLEMENTED / CI SUCCESS** — ViewModel, StateFlow, transcript persistence, Activity recreation/process recovery; runtime lifecycle smoke test masih diperlukan.
- K5: **IMPLEMENTED (source-level)** — chat-template Jinja common API dan penanganan BOS; validasi runtime lintas keluarga model memerlukan GGUF masing-masing.
- K6: **IMPLEMENTED (source-level), corrections added** — metadata key memakai prefix dari `general.architecture`, fallback dimensi per-head, F16 2-byte yang disamakan eksplisit dengan tipe cache aktual, dan estimasi Q8_0 34-byte/32-value block.
- Run `41402872067` / #69 (**SUCCESS**) memverifikasi source arsitektur-dinamis sebelum koreksi ukuran F16. Run #71 dibatalkan saat manifest diperbarui, dan #72 dibatalkan ketika workflow action diperbarui. Run final #73 (`41404288063`) sedang memverifikasi head `aba78fb`; jangan menganggap hasil final tersedia sebelum selesai.
- K7/R1–R7/U1–U4/G1–G3: **belum dikerjakan**; definisi resminya belum ditemukan dalam README, progress log, PR #5, PR historis #1–#4, diskusi/review PR, atau issue repository yang cocok. Tidak ada scope yang ditambahkan berdasarkan tebakan. Verifikasi fisik GGUF, Stop, lifecycle, dan penggunaan RAM tetap memerlukan smoke test perangkat.

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

**Tidak pernah diperoleh.** Tidak ada angka baseline inference lama yang dapat direkonstruksi dari CI atau source. Data perangkat tanggal 2026-11-01 di bawah adalah pengukuran pasca-instrumentasi pertama, bukan baseline pra-instrumentasi. Karena APK/commit yang menghasilkan laporan tersebut tidak dicantumkan pada laporan pengguna, hasil ini tidak diatribusikan ke commit K1/K2/K3 secara spekulatif.

## Pengukuran B0 pertama — perangkat ARM64 nyata (2026-11-01)

### Perangkat dan backend
- Perangkat: Infinix X6725C (laporan pengguna menuliskan Infinix Infinix X6725C)
- OS: Android 15 / API 35; ABI `arm64-v8a`; 8 CPU cores
- SoC: `UMS9230E`
- RAM perangkat: 7,6 GB total; 2,7 GB tersedia saat pemeriksaan perangkat
- Backend: llama.cpp / arm64-v8a
- Fitur CPU aktual yang dilaporkan: NEON=1, ARM_FMA=1, FP16_VA=1, DOTPROD=1, KLEIDIAI=1, REPACK=1

### Model dan konfigurasi
- Model: `Qwen2.5-3B-Instruct-Abliterated.Q4_K_M.gguf`
- Quantization: Q4_K_M (UI melaporkan Q4_K — Medium)
- Context aktif: 8.192 token; thread: 6
- Prompt benchmark: 73 token; target output: 128 token
- Alasan berhenti: batas output 128 token tercapai; 128/128 token dihasilkan

### Hasil terukur
| Metrik | Hasil |
|---|---:|
| Prefill throughput | **7,01 tok/s** |
| Prefill duration | 10,41 s |
| Prompt tokens | 73 |
| Decode throughput | **3,04 tok/s** |
| Decode duration | 42,16 s |
| Output tokens | 128 / 128 |
| Model load duration | 33,89 s |
| Process peak RSS | 3,5 GB |

Throughput yang dilaporkan konsisten setelah pembulatan: 73 / 10,41 ≈ 7,01 tok/s dan 128 / 42,16 ≈ 3,04 tok/s.

### Interpretasi dan keterbatasan
- Ini adalah sampel baseline performa pasca-instrumentasi pertama; jangan menyebutnya baseline pra-instrumentasi.
- Bottleneck yang tampak dari sampel ini adalah decode (3,04 tok/s), lebih lambat daripada prefill (7,01 tok/s). Ini observasi satu run, bukan kesimpulan universal tentang seluruh model/backend.
- Peak RSS 3,5 GB adalah high-water mark seluruh proses sejak proses berjalan; tidak boleh dikurangkan dari RAM tersedia 2,7 GB atau dianggap sebagai delta penggunaan model karena keduanya metrik dan waktu sampling yang berbeda.
- Laporan tidak mencantumkan commit APK, suhu/thermal throttling, kondisi baterai/pengisian, aplikasi latar, atau pengulangan benchmark. Perbandingan optimasi berikutnya perlu memakai model, context, thread, prompt, output limit, dan kondisi termal yang sama, idealnya beberapa run setelah pemanasan.

## Verifikasi perangkat yang masih perlu dilakukan

- Uji Stop saat prefill dan saat decode; verifikasi jawaban parsial tersimpan serta chat berikutnya dapat melanjutkan tanpa state KV yang rusak.
- Ulangi benchmark minimal tiga kali pada kondisi termal stabil setelah setiap optimasi K2/K3; catat median dan rentang, bukan hanya satu hasil.
- Catat commit APK yang terpasang beserta suhu/thermal dan status baterai agar hasil dapat diatribusikan.
- CI runner tidak boleh dipakai sebagai pengganti pengukuran perangkat.

## Cara mengambil data B0 pada perangkat

1. Build/install APK dari PR #5 setelah CI untuk commit koreksi ini selesai.
2. Impor dan muat model GGUF milik pengguna; pastikan model yang dipilih memiliki ruang RAM dan context memadai.
3. Buka halaman **Perangkat** lalu tekan **Jalankan Benchmark**.
4. Simpan seluruh hasil yang ditampilkan: perangkat/SoC/RAM, model/quantization, context/thread, prefill tok/s, decode tok/s, durasi load, peak RSS, jumlah token output aktual, dan alasan berhenti.
5. Sertakan kondisi perangkat saat pengukuran (suhu/thermal, aplikasi lain, status baterai/pengisian, serta apakah perangkat baru selesai memuat model).

B0 measurement gate **TERPENUHI untuk satu sampel pertama**. B0 belum menjadi benchmark komparatif final karena baseline pra-instrumentasi tidak ada dan belum ada pengulangan terkontrol; optimasi berikutnya wajib dibandingkan dengan kondisi pengujian yang konsisten.

## Batas scope

Riwayat scope awal dipertahankan sebagai catatan historis; pekerjaan selanjutnya K1–K4 dicatat pada bagian masing-masing di bawah. Tidak membuat branch tambahan dan tidak menyentuh repository `Nexora2`.


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
- CI [41328386105](https://github.com/habibinando/Nexora/actions/runs/41328386105) **SUCCESS**. Unit tests, debug + release builds, signature verification, ABI ARM64, R8 mapping, CMakeCache `CMAKE_BUILD_TYPE=Release`, dan compile command JNI `-O3` telah diverifikasi.
- Release signing secrets belum lengkap; kedua APK menggunakan Android Debug certificate. Kunci release permanen perlu dikonfigurasi agar pembaruan lintas-build dapat dipasang tanpa konflik tanda tangan.
- Kedua APK tersedia pada [GitHub Release pr-5-latest](https://github.com/habibinando/Nexora/releases/tag/pr-5-latest). Distribusi Play Store dan uji install/update tetap memerlukan signing key permanen.
- B0 belum mempunyai angka prefill/decode/load/RSS dari perangkat ARM64 nyata. Status tersebut tetap tercatat; CI tidak dianggap sebagai pengganti benchmark perangkat.


## Fase 1 — K2: Prefill batching dan KV prefix reuse

### Implementasi pada commit K2
- Kapasitas batch terpusat di `kInferenceBatchCapacity = 256`; `n_batch`, `n_ubatch`, batch allocation, dan chunking prompt menggunakan kapasitas 256.
- Cache token menyimpan urutan token yang benar-benar dievaluasi di sequence 0.
- Setiap prompt baru dibandingkan dengan cache token untuk menemukan common prefix; suffix lama dipangkas melalui `llama_memory_seq_rm(memory, 0, keep, -1)`.
- Bila penghapusan parsial gagal, memory dibersihkan penuh dan seluruh prompt diproses ulang.
- Satu token prompt terakhir selalu dievaluasi ulang untuk memastikan logits valid tersedia bagi sampler.
- Token output yang berhasil melewati `llama_decode` ditambahkan ke cache; token EOS tidak ditambahkan karena tidak masuk KV.
- Cache teks dibatalkan pada perubahan system prompt, reset/unload, benchmark, dan jalur vision agar posisi embedding multimodal tidak salah dianggap token teks.
- Progress inference melaporkan jumlah prefix token yang benar-benar digunakan ulang dari native.

### Verifikasi / batas
- API `llama_memory_seq_rm` telah diperiksa terhadap `include/llama.h` commit pinned; signature dan semantics [p0, p1) cocok.
- K1 CI telah selesai sukses; K2 menjadi commit lanjutan pada branch yang sama dan dijalankan dengan satu CI terintegrasi.
- Identitas output greedy antara mode cached dan no-cache serta manfaat waktu prefill perlu validasi dengan model nyata; belum diklaim teruji tanpa runtime GGUF.


## Fase 1 — K3: Stop generation dan pembatalan native

### Implementasi pada commit K3
- JNI `cancelGeneration()` mengatur atomic request dan memakai `try_lock`; UI tidak menunggu mutex ketika llama.cpp sedang decode.
- Build CPU memasang `llama_set_abort_callback()` pada context sesuai API pinned yang hanya menjamin callback untuk CPU execution. Phase native diaktifkan sebelum tokenisasi/encoding MTMD agar tombol Stop dapat muncul sejak pemrosesan gambar dimulai.
- Prefill batch, evaluasi chunk MTMD vision, dan decode token memeriksa cancellation request pada batas operasi.
- Jika CPU abort callback menghentikan `llama_decode()`, jalur native membedakan pembatalan dari decode error biasa.
- Jika generasi output dihentikan, token yang sudah berhasil didecode dipertahankan sebagai jawaban parsial di native history; KV dan cache token dibersihkan dan posisi diselaraskan.
- Jika prefill dihentikan sebelum output dimulai, pesan pengguna tetap disimpan ke native history dengan jawaban parsial kosong.
- JNI `generationWasCancelled()` membedakan Stop dari EOS/error; UI menampilkan tombol Hentikan hanya ketika native phase aktif.
- Coroutine cleanup menggunakan `NonCancellable + Dispatchers.IO` untuk membatalkan native generation bila coroutine dibatalkan.

### Verifikasi / batas
- Header pinned `llama.h` dan callback ggml telah diperiksa; API abort callback memang CPU-only.
- CI [41330455361](https://github.com/habibinando/Nexora/actions/runs/41330455361) **SUCCESS**: unit tests, compile JNI/C++, debug + release APK, signing check, ARM64 library checks, R8 mapping, CMake Release flags, dan publikasi APK PR semuanya lulus.
- Perilaku Stop saat runtime inference tetap memerlukan smoke test dengan GGUF. Build Vulkan tidak mengklaim abort callback di tengah kernel; pembatalan Vulkan dilakukan pada batas decode/token setelah operasi berjalan kembali.


## Fase 1 — K4: StateFlow ViewModel dan lifecycle

### Implementasi dan koreksi
- Seluruh state UI, termasuk daftar model dan pesan, berada sebagai immutable list/scalar dalam satu `StateFlow<NexoraUiState>` di `NexoraViewModel`.
- `MainActivity` hanya membaca snapshot Flow dan memanggil metode ViewModel untuk perubahan model/transcript; Activity tidak memutasi daftar secara langsung.
- Input chat, batas output, dan pertanyaan vision memakai `rememberSaveable`.
- Atribut `configChanges` manual dihapus sehingga Activity recreation ditangani Android.
- JNI `isModelLoaded()`, `activePath()`, dan `restoreHistory()` menyinkronkan model/transcript native. Restore menolak bila phase inference masih aktif atau batch belum siap.
- Riwayat JSON disimpan dalam direktori privat dengan `android.util.AtomicFile`, `fd.sync()`, dan transaksi `finishWrite()`/`failWrite()`.
- Revision counter mencegah snapshot penyimpanan lama menimpa transcript terbaru.
- Jika proses mati setelah pesan user tersimpan tetapi sebelum jawaban selesai, restore menambahkan marker lokal `generation_interrupted`; native transcript tidak berakhir dengan user turn tanpa assistant.
- Saat model sama dimuat kembali, history role/content dipulihkan ke native; KV dibangun ulang lazy pada giliran berikutnya dan tidak diklaim bertahan setelah process death.
- Unload mempertahankan riwayat; penghapusan model menghapus riwayat yang terkait dengan model tersebut.
- `onCleared()` menulis snapshot final lalu menjalankan cancellation/unload/shutdown native pada thread IO.

### Verifikasi / batas
- Pemeriksaan statis JNI dan referensi resource dilakukan sebelum commit koreksi.
- CI koreksi K4 menjalankan unit test serta build debug/release; hasil terbaru wajib ditinjau dari workflow.
- Process death menghapus model dari RAM; GGUF harus dimuat ulang saat aplikasi berjalan kembali.
- CI compile bukan bukti runtime recovery transcript setelah process death atau seluruh siklus Activity recreation.


## Fase 1 — K5: Chat template dan BOS

- CMake mengaktifkan `LLAMA_BUILD_COMMON=ON` pada pinned llama.cpp dan menghubungkan `llama-common`; tools, OpenSSL, subprocess, serta LLGUIDANCE dimatikan karena tidak dipakai Nexora.
- Prompt chat memakai `common_chat_templates_init()` dan `common_chat_templates_apply()` pada `common/chat.h` pinned, dengan Jinja aktif. Ini memperluas dukungan di luar subset C API `llama_chat_apply_template()`.
- Jika template tidak disediakan, gagal diparse, atau gagal dirender, inference tetap berjalan dengan fallback ChatML/generik. UI menampilkan peringatan lokal melalui `strings.xml`.
- BOS ditambahkan hanya ketika metadata `llama_vocab_get_add_bos()` meminta dan token BOS belum menjadi token pertama; template yang sudah mengeluarkan BOS tidak mendapat BOS ganda.
- System prompt chat/coding pada jalur Activity tetap menggunakan helper `systemPromptForMode()` untuk penyelarasan antara restore dan inference pada refactor yang ada.
- Llama 3, Gemma, Qwen, dan Mistral sudah dinilai dari jalur API/template; test runtime per model masih memerlukan GGUF dari keluarga tersebut. Tidak mengklaim semua varian tervalidasi empiris.



## K6 — Context/KV safety audit: IMPLEMENTED (source-level)

Commit: `41a57b08d74f5bc072cb4fb83dc8ab70c673dd4c`.

- Native context selection now reads GGUF architecture metadata through the pinned llama.cpp public API: block count, KV head count, key/value dimensions, with embedding-length fallback where the model metadata does not expose explicit key/value lengths.
- The pinned `llama_context_default_params()` defaults both K and V cache types to `GGML_TYPE_F16`; the estimator and context parameters explicitly use F16 (2 bytes per element). A separate Q8_0 estimate accounts for 34-byte blocks of 32 values, rounding K and V dimensions independently.
- Context selection budgets 60% of free system RAM after a fixed 512 MiB reserve. This is a heuristic for KV allocation, not a guarantee for total process memory or every model/backend allocation; physical GGUF/device validation remains necessary.
- Requested context remains bounded by model-trained context and a minimum of 512 tokens.
- Flash Attention and experimental KV quantization are not enabled implicitly; this change only selects a safer context before `llama_init_from_model`.
- Follow-up audit correction in commit `e15beaccbe8a9bd01eac57903b04f3bb58e7152b`: metadata keys are built from `general.architecture` instead of hardcoding `llama.*`; missing `attention.head_count_kv` falls back to `attention.head_count`; absent key/value dimensions are derived from `embedding_length / attention.head_count` when divisible.
- Follow-up estimate correction in commit `9392b9c5fa50d0bc518b62f7c1f5a45c92d82827`: F16 bytes/token use 2 bytes/value, matching pinned llama.cpp defaults, and context params explicitly set both K/V caches to F16. Q8_0 estimate uses 34 bytes per 32-value block and rounds the K/V rows independently. Checked 64-bit arithmetic prevents overflow.
- The 512 MiB reserve and 60% free-memory budget remain a heuristic, not a guarantee for total process RSS, backend graph buffers, or every architecture.
- Build cleanup: explicit `android:extractNativeLibs` removed in favor of existing `jniLibs.useLegacyPackaging`; workflow action versions advanced to `actions/setup-java@v6` and `gradle/actions/setup-gradle@v6`.
- Runtime validation on physical GGUF models remains required. The current CI gate must confirm the exact pinned llama.cpp header signature and Android build; physical model memory behavior still needs device testing.

K7/R1–R7/U1–U4/G1–G3 remain pending until their definitions are present in repository documentation or PR history; no undocumented scope is being invented.


## Fase 2 — Unified Chat, Vision transcript, and Agent Skills

- Chat and image analysis share one conversation composer and timeline. Image attachment is offered only when the active GGUF + mmproj combination reports vision support.
- Vision questions and assistant responses are appended to the local transcript. After image processing, the text transcript is restored into native history because the current MTMD path is one-shot and does not persist image embeddings in KV/history.
- Added a bounded tool-call envelope and allowlisted runtime for calculator, current_time, web_search, and web_open. Tool results are fed into the next local inference pass. Maximum three tool executions per user turn.
- Web access is disabled by default and can be enabled on the Skills page. Added Android INTERNET permission. Web calls enforce HTTPS-only destinations, redirect/time/response limits, and reject obvious localhost/private-host URL forms. This is not a general-purpose unrestricted browser.
- Tool protocol is model-dependent: imported GGUF models must emit the documented Nexora tool-call envelope. Unsupported models continue to work as ordinary text models; tool use is not guaranteed across all model families.
- No image-generation or image-editing diffusion engine is included in this phase. MTMD remains an image-understanding input path, not an image generator.
- Vulkan build activation remains a separate native-build gate: the pinned ggml Vulkan backend requires host glslc and a SPIRV-Headers CMake package in addition to Android Vulkan headers/loader. It must not be marked active until CI packages the backend and runtime enumeration confirms a usable Vulkan device. Existing CPU behavior remains available.
