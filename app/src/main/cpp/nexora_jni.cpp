#include <jni.h>
#include <llama.h>
#include <ggml-backend.h>
#include <mtmd.h>
#include <mtmd-helper.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <sys/resource.h>
#include <string>
#include <utility>
#include <vector>

namespace {
std::mutex g_mutex;
bool g_backend_initialized = false;
llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
mtmd_context *g_mtmd = nullptr;
llama_sampler *g_sampler = nullptr;
llama_batch g_batch{};
bool g_batch_initialized = false;
int32_t g_context_size = 2048;
int32_t g_position = 0;
int32_t g_remaining_tokens = 0;
bool g_generation_active = false;
std::string g_system_prompt;
std::vector<std::pair<std::string, std::string>> g_history;
std::string g_pending_user, g_pending_response, g_utf8_pending, g_last_error;
std::atomic<float> g_load_progress{-1.0f};
std::atomic<int32_t> g_inference_phase{0}; // 0 idle, 1 input/prefill, 2 token generation
std::atomic<int32_t> g_prompt_processed{0};
std::atomic<int32_t> g_prompt_total{0};
std::atomic<int32_t> g_generated_tokens{0};
std::atomic<int32_t> g_generation_limit{0};
std::atomic<int64_t> g_last_load_duration_ns{0};

struct BenchmarkMetrics {
    int64_t prefill_start_ns = 0;
    int64_t prefill_end_ns = 0;
    int64_t prompt_tokens = 0;
    int64_t decode_start_ns = 0;
    int64_t decode_end_ns = 0;
    int64_t generated_tokens = 0;
    int64_t peak_rss_bytes = 0;
};
BenchmarkMetrics g_benchmark_metrics{};

int64_t monotonic_time_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

int64_t process_peak_rss_bytes() {
    struct rusage usage {};
    if (getrusage(RUSAGE_SELF, &usage) != 0 || usage.ru_maxrss <= 0) return 0;
    // Linux and Android report ru_maxrss in KiB.
    return static_cast<int64_t>(usage.ru_maxrss) * 1024;
}

struct ScopedLoadTimer {
    std::chrono::steady_clock::time_point started = std::chrono::steady_clock::now();
    ~ScopedLoadTimer() {
        const auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - started
        ).count();
        g_last_load_duration_ns.store(elapsed, std::memory_order_relaxed);
    }
};

bool model_load_progress_callback(float progress, void *) {
    const float safe_progress = std::clamp(progress, 0.0f, 1.0f);
    g_load_progress.store(0.05f + safe_progress * 0.85f, std::memory_order_relaxed);
    return true;
}

void set_error(const std::string &message) { g_last_error = message; }
void clear_error() { g_last_error.clear(); }

void free_model_locked() {
    g_generation_active = false;
    g_remaining_tokens = 0;
    g_position = 0;
    g_history.clear();
    g_pending_user.clear();
    g_pending_response.clear();
    g_utf8_pending.clear();
    g_inference_phase.store(0, std::memory_order_relaxed);
    g_prompt_processed.store(0, std::memory_order_relaxed);
    g_prompt_total.store(0, std::memory_order_relaxed);
    g_generated_tokens.store(0, std::memory_order_relaxed);
    g_generation_limit.store(0, std::memory_order_relaxed);
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_batch_initialized) { llama_batch_free(g_batch); g_batch_initialized = false; }
    if (g_mtmd) { mtmd_free(g_mtmd); g_mtmd = nullptr; }
    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

bool valid_utf8(const std::string &value) {
    const auto *bytes = reinterpret_cast<const unsigned char *>(value.data());
    size_t i = 0;
    while (i < value.size()) {
        const unsigned char c = bytes[i];
        size_t count = c <= 0x7f ? 1 : ((c & 0xe0) == 0xc0 ? 2 :
                       ((c & 0xf0) == 0xe0 ? 3 : ((c & 0xf8) == 0xf0 ? 4 : 0)));
        if (count == 0 || i + count > value.size()) return false;
        for (size_t j = 1; j < count; ++j) if ((bytes[i + j] & 0xc0) != 0x80) return false;
        if (count == 2 && c < 0xc2) return false;
        if (count == 3 && c == 0xe0 && bytes[i + 1] < 0xa0) return false;
        if (count == 3 && c == 0xed && bytes[i + 1] >= 0xa0) return false;
        if (count == 4 && (c > 0xf4 || (c == 0xf0 && bytes[i + 1] < 0x90) ||
                           (c == 0xf4 && bytes[i + 1] > 0x8f))) return false;
        i += count;
    }
    return true;
}

std::vector<llama_token> tokenize_locked(const std::string &text) {
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    if (!vocab) { set_error("Tokenizer model tidak tersedia."); return {}; }
    const int32_t needed = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                          nullptr, 0, false, true);
    if (needed == INT32_MIN) { set_error("Ukuran token melebihi batas runtime."); return {}; }
    const int32_t capacity = needed < 0 ? -needed : needed;
    if (capacity == 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(capacity));
    const int32_t actual = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                          tokens.data(), capacity, false, true);
    if (actual < 0) { set_error("Tokenisasi prompt gagal."); return {}; }
    tokens.resize(static_cast<size_t>(actual));
    return tokens;
}

bool decode_tokens_locked(const std::vector<llama_token> &tokens) {
    if (!g_context || !g_batch_initialized) { set_error("Model belum dimuat."); return false; }
    constexpr int32_t capacity = 32;
    g_prompt_processed.store(0, std::memory_order_relaxed);
    g_prompt_total.store(static_cast<int32_t>(tokens.size()), std::memory_order_relaxed);
    g_inference_phase.store(1, std::memory_order_relaxed);
    size_t offset = 0;
    while (offset < tokens.size()) {
        const int32_t count = static_cast<int32_t>(
            std::min(tokens.size() - offset, static_cast<size_t>(capacity)));
        g_batch.n_tokens = count;
        for (int32_t i = 0; i < count; ++i) {
            g_batch.token[i] = tokens[offset + static_cast<size_t>(i)];
            g_batch.pos[i] = g_position + i;
            g_batch.n_seq_id[i] = 1;
            g_batch.seq_id[i][0] = 0;
            g_batch.logits[i] = (offset + static_cast<size_t>(i) == tokens.size() - 1) ? 1 : 0;
        }
        const int32_t result = llama_decode(g_context, g_batch);
        if (result != 0) {
            set_error("llama_decode gagal memproses prompt (kode " + std::to_string(result) + ").");
            return false;
        }
        g_position += count;
        offset += static_cast<size_t>(count);
        g_prompt_processed.store(static_cast<int32_t>(offset), std::memory_order_relaxed);
    }
    return true;
}

std::string format_prompt_locked(const std::string &user_message) {
    std::vector<llama_chat_message> messages;
    messages.reserve(g_history.size() + 2);
    if (!g_system_prompt.empty()) messages.push_back({"system", g_system_prompt.c_str()});
    for (const auto &entry : g_history) messages.push_back({entry.first.c_str(), entry.second.c_str()});
    messages.push_back({"user", user_message.c_str()});

    const char *chat_template = llama_model_chat_template(g_model, nullptr);
    if (chat_template && chat_template[0] != '\0') {
        std::vector<char> output(64 * 1024);
        int32_t length = llama_chat_apply_template(chat_template, messages.data(), messages.size(),
                                                   true, output.data(), static_cast<int32_t>(output.size()));
        if (length < 0) { set_error("Template percakapan model tidak dapat diproses."); return {}; }
        if (static_cast<size_t>(length) >= output.size()) {
            output.resize(static_cast<size_t>(length) + 1);
            length = llama_chat_apply_template(chat_template, messages.data(), messages.size(),
                                               true, output.data(), static_cast<int32_t>(output.size()));
            if (length < 0 || static_cast<size_t>(length) >= output.size()) {
                set_error("Buffer template percakapan tidak mencukupi."); return {};
            }
        }
        return std::string(output.data(), static_cast<size_t>(length));
    }

    std::string prompt;
    if (!g_system_prompt.empty()) prompt += "System: " + g_system_prompt + "\n";
    for (const auto &entry : g_history) {
        prompt += entry.first == "assistant" ? "Assistant: " : "User: ";
        prompt += entry.second + "\n";
    }
    prompt += "User: " + user_message + "\nAssistant:";
    return prompt;
}

void finish_generation_locked() {
    if (!g_generation_active) return;
    if (!g_pending_user.empty()) {
        g_history.emplace_back("user", g_pending_user);
        g_history.emplace_back("assistant", g_pending_response);
    }
    g_pending_user.clear();
    g_pending_response.clear();
    g_utf8_pending.clear();
    g_remaining_tokens = 0;
    g_generation_active = false;
    g_inference_phase.store(0, std::memory_order_relaxed);
}

std::string from_java_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string output;
    output.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = chars[i];
        if (codepoint >= 0xd800 && codepoint <= 0xdbff && i + 1 < length &&
            chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
            codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (chars[++i] - 0xdc00);
        } else if (codepoint >= 0xd800 && codepoint <= 0xdfff) {
            codepoint = 0xfffd;
        }
        if (codepoint <= 0x7f) {
            output.push_back(static_cast<char>(codepoint));
        } else if (codepoint <= 0x7ff) {
            output.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
            output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
        } else if (codepoint <= 0xffff) {
            output.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
            output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
            output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
        } else {
            output.push_back(static_cast<char>(0xf0 | (codepoint >> 18)));
            output.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
            output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
            output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return output;
}

jstring to_java_string(JNIEnv *env, const std::string &value) {
    std::vector<jchar> output;
    output.reserve(value.size());
    const auto *bytes = reinterpret_cast<const unsigned char *>(value.data());
    size_t i = 0;
    while (i < value.size()) {
        const unsigned char c = bytes[i++];
        uint32_t codepoint = 0xfffd;
        if (c <= 0x7f) {
            codepoint = c;
        } else if ((c & 0xe0) == 0xc0 && i < value.size()) {
            codepoint = ((c & 0x1f) << 6) | (bytes[i++] & 0x3f);
        } else if ((c & 0xf0) == 0xe0 && i + 1 < value.size()) {
            codepoint = ((c & 0x0f) << 12) | ((bytes[i] & 0x3f) << 6) | (bytes[i + 1] & 0x3f);
            i += 2;
        } else if ((c & 0xf8) == 0xf0 && i + 2 < value.size()) {
            codepoint = ((c & 0x07) << 18) | ((bytes[i] & 0x3f) << 12) |
                        ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f);
            i += 3;
        }
        if (codepoint <= 0xffff) {
            output.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            output.push_back(static_cast<jchar>(0xd800 + (codepoint >> 10)));
            output.push_back(static_cast<jchar>(0xdc00 + (codepoint & 0x3ff)));
        }
    }
    const jchar empty = 0;
    return env->NewString(output.empty() ? &empty : output.data(), static_cast<jsize>(output.size()));
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_nexora_ai_NativeLlama_initialize(JNIEnv *env, jobject, jstring native_library_dir) {
    std::lock_guard<std::mutex> guard(g_mutex);
    clear_error();
    if (!g_backend_initialized) {
        const char *library_dir = env->GetStringUTFChars(native_library_dir, nullptr);
        if (library_dir) {
            ggml_backend_load_all_from_path(library_dir);
            env->ReleaseStringUTFChars(native_library_dir, library_dir);
        }
        llama_backend_init();
        g_backend_initialized = true;
    }
    return to_java_string(env, llama_print_system_info());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nexora_ai_NativeLlama_loadModel(JNIEnv *env, jobject, jstring model_path,
                                         jstring projector_path, jint context_size, jint thread_count) {
    std::lock_guard<std::mutex> guard(g_mutex);
    ScopedLoadTimer load_timer;
    clear_error();
    const std::string path = from_java_string(env, model_path);
    const std::string projector = from_java_string(env, projector_path);
    if (path.empty()) { set_error("Path model tidak valid."); return -1; }
    free_model_locked();
    g_load_progress.store(0.0f, std::memory_order_relaxed);
    llama_model_params model_params = llama_model_default_params();
    model_params.progress_callback = model_load_progress_callback;
    model_params.progress_callback_user_data = nullptr;
#if defined(NEXORA_HAS_VULKAN)
    model_params.n_gpu_layers = -1; // Offload all supported layers when built with Vulkan.
#else
    model_params.n_gpu_layers = 0;  // CPU-only in the default distributable APK.
#endif
    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (!g_model) {
        g_load_progress.store(-1.0f, std::memory_order_relaxed);
        set_error("llama.cpp gagal memuat GGUF. Periksa file, arsitektur, dan kapasitas RAM.");
        return -2;
    }
    g_load_progress.store(0.90f, std::memory_order_relaxed);

    const int32_t requested = std::clamp(static_cast<int32_t>(context_size), 512, 100000);
    const int32_t trained = llama_model_n_ctx_train(g_model);
    g_context_size = trained > 0 ? std::min(requested, trained) : requested;
    g_context_size = std::max(512, g_context_size);

    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(g_context_size);
    params.n_batch = 256;
    params.n_ubatch = 128;
    params.n_seq_max = 1;
    params.n_threads = std::max(1, static_cast<int32_t>(thread_count));
    params.n_threads_batch = std::max(1, static_cast<int32_t>(thread_count));
    g_context = llama_init_from_model(g_model, params);
    if (!g_context) {
        free_model_locked();
        g_load_progress.store(-1.0f, std::memory_order_relaxed);
        set_error("Gagal mengalokasikan context. Coba model lebih kecil atau context 1024.");
        return -3;
    }
    g_load_progress.store(0.93f, std::memory_order_relaxed);

    if (!projector.empty()) {
        mtmd_context_params mtmd_params = mtmd_context_params_default();
        mtmd_params.use_gpu = false;
        mtmd_params.n_threads = std::max(1, static_cast<int32_t>(thread_count));
        mtmd_params.print_timings = false;
        mtmd_params.warmup = false;
        g_mtmd = mtmd_init_from_file(projector.c_str(), g_model, mtmd_params);
        if (g_mtmd == nullptr || !mtmd_support_vision(g_mtmd)) {
            free_model_locked();
            g_load_progress.store(-1.0f, std::memory_order_relaxed);
            set_error("mmproj tidak cocok dengan model ini atau tidak menyediakan encoder vision yang didukung llama.cpp.");
            return -4;
        }
    }
    g_load_progress.store(0.96f, std::memory_order_relaxed);

    g_batch = llama_batch_init(256, 0, 1);
    g_batch_initialized = true;
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!g_sampler) {
        free_model_locked();
        g_load_progress.store(-1.0f, std::memory_order_relaxed);
        set_error("Gagal menginisialisasi sampler.");
        return -5;
    }
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.70f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    g_position = 0;
    g_history.clear();
    g_load_progress.store(1.0f, std::memory_order_relaxed);
    return 0;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_nexora_ai_NativeLlama_loadProgress(JNIEnv *, jobject) {
    return g_load_progress.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nexora_ai_NativeLlama_contextSize(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    return g_context != nullptr ? g_context_size : 0;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_nexora_ai_NativeLlama_inferenceStats(JNIEnv *env, jobject) {
    const jint values[5] = {
        g_inference_phase.load(std::memory_order_relaxed),
        g_prompt_processed.load(std::memory_order_relaxed),
        g_prompt_total.load(std::memory_order_relaxed),
        g_generated_tokens.load(std::memory_order_relaxed),
        g_generation_limit.load(std::memory_order_relaxed)
    };
    jintArray result = env->NewIntArray(5);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 5, values);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nexora_ai_NativeLlama_runBenchmark(JNIEnv *env, jobject, jstring prompt, jint max_tokens) {
    std::lock_guard<std::mutex> guard(g_mutex);
    clear_error();

    if (!g_model || !g_context || !g_sampler || !g_batch_initialized) {
        set_error("Muat model GGUF sebelum menjalankan benchmark.");
        return -1;
    }
    if (g_generation_active) {
        set_error("Selesaikan atau hentikan generasi aktif sebelum benchmark.");
        return -2;
    }
    if (prompt == nullptr) {
        set_error("Prompt benchmark tidak valid.");
        return -3;
    }

    const std::string benchmark_prompt = from_java_string(env, prompt);
    const int32_t token_limit = std::clamp(static_cast<int32_t>(max_tokens), 1, 128);
    if (benchmark_prompt.empty() || token_limit <= 0) {
        set_error("Prompt atau batas token benchmark tidak valid.");
        return -3;
    }

    // Benchmark uses the loaded model/context, then restores native conversation
    // history. The next chat turn rebuilds KV from that preserved history.
    std::vector<std::pair<std::string, std::string>> saved_history = g_history;
    const std::string saved_system_prompt = g_system_prompt;
    auto restore_conversation = [&]() {
        llama_memory_clear(llama_get_memory(g_context), true);
        g_position = 0;
        g_history = std::move(saved_history);
        g_system_prompt = saved_system_prompt;
        g_generation_active = false;
        g_remaining_tokens = 0;
        g_pending_user.clear();
        g_pending_response.clear();
        g_utf8_pending.clear();
        llama_sampler_reset(g_sampler);
        g_inference_phase.store(0, std::memory_order_relaxed);
    };

    llama_memory_clear(llama_get_memory(g_context), true);
    g_position = 0;
    g_history.clear();
    g_system_prompt = "You are running a fixed on-device inference benchmark. Answer in Indonesian.";
    g_pending_user.clear();
    g_pending_response.clear();
    g_utf8_pending.clear();
    g_generated_tokens.store(0, std::memory_order_relaxed);
    g_generation_limit.store(token_limit, std::memory_order_relaxed);
    g_benchmark_metrics = {};

    const std::string formatted = format_prompt_locked(benchmark_prompt);
    if (formatted.empty()) {
        if (g_last_error.empty()) set_error("Prompt benchmark kosong setelah pemformatan.");
        restore_conversation();
        return -4;
    }

    const std::vector<llama_token> tokens = tokenize_locked(formatted);
    if (!g_last_error.empty() || tokens.empty()) {
        if (g_last_error.empty()) set_error("Prompt benchmark tidak menghasilkan token.");
        restore_conversation();
        return -5;
    }
    if (tokens.size() >= static_cast<size_t>(g_context_size)) {
        set_error("Prompt benchmark melebihi context model.");
        restore_conversation();
        return -6;
    }

    g_benchmark_metrics.prompt_tokens = static_cast<int64_t>(tokens.size());
    g_prompt_processed.store(0, std::memory_order_relaxed);
    g_prompt_total.store(static_cast<int32_t>(tokens.size()), std::memory_order_relaxed);
    g_inference_phase.store(1, std::memory_order_relaxed);

    g_benchmark_metrics.prefill_start_ns = monotonic_time_ns();
    const bool prefill_ok = decode_tokens_locked(tokens);
    g_benchmark_metrics.prefill_end_ns = monotonic_time_ns();
    if (!prefill_ok) {
        restore_conversation();
        return -7;
    }

    llama_sampler_reset(g_sampler);
    g_inference_phase.store(2, std::memory_order_relaxed);
    g_generated_tokens.store(0, std::memory_order_relaxed);
    g_benchmark_metrics.decode_start_ns = monotonic_time_ns();

    int32_t generated = 0;
    int32_t decode_result = 0;
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    while (generated < token_limit && g_position < g_context_size - 1) {
        const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
        llama_sampler_accept(g_sampler, token);
        if (llama_vocab_is_eog(vocab, token)) break;

        g_batch.n_tokens = 1;
        g_batch.token[0] = token;
        g_batch.pos[0] = g_position;
        g_batch.n_seq_id[0] = 1;
        g_batch.seq_id[0][0] = 0;
        g_batch.logits[0] = 1;

        decode_result = llama_decode(g_context, g_batch);
        if (decode_result != 0) {
            set_error("llama_decode gagal saat benchmark (kode " +
                      std::to_string(decode_result) + ").");
            break;
        }
        ++g_position;
        ++generated;
        g_generated_tokens.store(generated, std::memory_order_relaxed);
    }

    g_benchmark_metrics.decode_end_ns = monotonic_time_ns();
    g_benchmark_metrics.generated_tokens = generated;
    g_benchmark_metrics.peak_rss_bytes = process_peak_rss_bytes();
    restore_conversation();

    if (decode_result != 0) return -8;
    return 0;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_nexora_ai_NativeLlama_benchmarkStats(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    const jlong values[8] = {
        static_cast<jlong>(g_last_load_duration_ns.load(std::memory_order_relaxed)),
        static_cast<jlong>(g_benchmark_metrics.prefill_start_ns),
        static_cast<jlong>(g_benchmark_metrics.prefill_end_ns),
        static_cast<jlong>(g_benchmark_metrics.prompt_tokens),
        static_cast<jlong>(g_benchmark_metrics.decode_start_ns),
        static_cast<jlong>(g_benchmark_metrics.decode_end_ns),
        static_cast<jlong>(g_benchmark_metrics.generated_tokens),
        static_cast<jlong>(g_benchmark_metrics.peak_rss_bytes)
    };
    jlongArray result = env->NewLongArray(8);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 8, values);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nexora_ai_NativeLlama_hasVision(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    return (g_mtmd != nullptr && mtmd_support_vision(g_mtmd)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nexora_ai_NativeLlama_startVisionPrompt(JNIEnv *env, jobject, jstring system_prompt,
                                                 jstring user_message, jbyteArray image_bytes,
                                                 jint max_tokens) {
    std::lock_guard<std::mutex> guard(g_mutex);
    clear_error();
    g_inference_phase.store(0, std::memory_order_relaxed);
    if (!g_model || !g_context || !g_sampler) {
        set_error("Muat model GGUF terlebih dahulu.");
        return -1;
    }
    if (g_mtmd == nullptr || !mtmd_support_vision(g_mtmd)) {
        set_error("Model vision belum aktif. Impor mmproj yang cocok, lalu muat ulang model.");
        return -2;
    }
    if (g_generation_active) {
        set_error("Generasi sebelumnya masih berjalan.");
        return -3;
    }
    if (image_bytes == nullptr) {
        set_error("Pilih gambar terlebih dahulu.");
        return -4;
    }

    const std::string next_system = from_java_string(env, system_prompt);
    const std::string user = from_java_string(env, user_message);
    if (system_prompt == nullptr || user_message == nullptr || user.empty()) {
        set_error("Pertanyaan vision tidak boleh kosong.");
        return -5;
    }

    const jsize image_length = env->GetArrayLength(image_bytes);
    if (image_length <= 0 || image_length > 20 * 1024 * 1024) {
        set_error("Ukuran gambar harus lebih dari 0 byte dan maksimal 20 MB.");
        return -6;
    }
    std::vector<unsigned char> image(static_cast<size_t>(image_length));
    env->GetByteArrayRegion(image_bytes, 0, image_length,
                            reinterpret_cast<jbyte *>(image.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        set_error("Gagal membaca byte gambar dari Android.");
        return -7;
    }

    // Image turns are one-shot: their embeddings are not persisted in text history.
    llama_memory_clear(llama_get_memory(g_context), true);
    g_position = 0;
    g_history.clear();
    g_system_prompt = next_system;

    const std::string marker = mtmd_default_marker();
    const std::string formatted = format_prompt_locked(user + "\\n" + marker);
    if (formatted.empty()) {
        if (g_last_error.empty()) set_error("Prompt vision kosong setelah pemformatan.");
        return -8;
    }
    const size_t marker_pos = formatted.find(marker);
    if (marker_pos == std::string::npos ||
        formatted.find(marker, marker_pos + marker.size()) != std::string::npos) {
        set_error("Template chat model mengubah atau menggandakan marker gambar; vision tidak dapat diproses.");
        return -9;
    }

    mtmd_helper_init_opt bitmap_options = mtmd_helper_init_opt_default();
    mtmd_helper_bitmap_wrapper bitmap_wrapper = mtmd_helper_bitmap_init_from_buf(
        g_mtmd, image.data(), image.size(), false, bitmap_options
    );
    if (bitmap_wrapper.bitmap == nullptr) {
        set_error("Format gambar tidak dapat dibaca oleh backend vision. Gunakan JPG, PNG, atau BMP.");
        if (bitmap_wrapper.video_ctx != nullptr) mtmd_helper_video_free(bitmap_wrapper.video_ctx);
        return -10;
    }

    const std::string before = formatted.substr(0, marker_pos);
    const std::string after = formatted.substr(marker_pos + marker.size());
    mtmd_input_text text_before{before.data(), before.size(), false, true};
    mtmd_input_text text_after{after.data(), after.size(), false, true};
    mtmd_input_part parts[3] = {
        {&text_before, nullptr},
        {nullptr, bitmap_wrapper.bitmap},
        {&text_after, nullptr}
    };
    const mtmd_input_part *part_ptrs[3] = {&parts[0], &parts[1], &parts[2]};

    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    if (chunks == nullptr) {
        mtmd_bitmap_free(bitmap_wrapper.bitmap);
        if (bitmap_wrapper.video_ctx != nullptr) mtmd_helper_video_free(bitmap_wrapper.video_ctx);
        set_error("Gagal mengalokasikan buffer multimodal.");
        return -11;
    }

    const int32_t tokenize_result = mtmd_tokenize_from_parts(
        g_mtmd, chunks, part_ptrs, 3, true
    );
    if (tokenize_result != 0) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap_wrapper.bitmap);
        if (bitmap_wrapper.video_ctx != nullptr) mtmd_helper_video_free(bitmap_wrapper.video_ctx);
        set_error("Preprocessing vision gagal (kode " + std::to_string(tokenize_result) + ").");
        return -12;
    }

    const size_t chunk_count = mtmd_input_chunks_size(chunks);
    g_prompt_total.store(static_cast<int32_t>(chunk_count), std::memory_order_relaxed);
    g_prompt_processed.store(0, std::memory_order_relaxed);
    g_inference_phase.store(1, std::memory_order_relaxed);
    bool evaluated = chunk_count > 0;
    for (size_t index = 0; evaluated && index < chunk_count; ++index) {
        const mtmd_input_chunk *chunk = mtmd_input_chunks_get(chunks, index);
        llama_pos next_position = g_position;
        const int32_t result = mtmd_helper_eval_chunk_single(
            g_mtmd, g_context, chunk, g_position, 0, 256,
            index + 1 == chunk_count, &next_position
        );
        if (result != 0) {
            set_error("Encoder/decode vision gagal pada chunk " + std::to_string(index) +
                      " (kode " + std::to_string(result) + ").");
            evaluated = false;
            break;
        }
        g_position = static_cast<int32_t>(next_position);
        g_prompt_processed.store(static_cast<int32_t>(index + 1), std::memory_order_relaxed);
    }

    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap_wrapper.bitmap);
    if (bitmap_wrapper.video_ctx != nullptr) mtmd_helper_video_free(bitmap_wrapper.video_ctx);

    if (!evaluated) {
        llama_memory_clear(llama_get_memory(g_context), true);
        g_position = 0;
        if (g_last_error.empty()) set_error("Vision tidak menghasilkan chunk multimodal.");
        return -13;
    }

    const int32_t requested_tokens = std::max(1, std::min(1024, static_cast<int32_t>(max_tokens)));
    if (g_position + requested_tokens >= g_context_size) {
        llama_memory_clear(llama_get_memory(g_context), true);
        g_position = 0;
        set_error("Gambar dan prompt terlalu besar untuk context model ini.");
        return -14;
    }

    llama_sampler_reset(g_sampler);
    g_pending_user.clear();
    g_pending_response.clear();
    g_utf8_pending.clear();
    g_remaining_tokens = requested_tokens;
    g_generated_tokens.store(0, std::memory_order_relaxed);
    g_generation_limit.store(requested_tokens, std::memory_order_relaxed);
    g_inference_phase.store(2, std::memory_order_relaxed);
    g_generation_active = true;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nexora_ai_NativeLlama_startPrompt(JNIEnv *env, jobject, jstring system_prompt,
                                            jstring user_message, jint max_tokens) {
    std::lock_guard<std::mutex> guard(g_mutex);
    clear_error();
    g_inference_phase.store(0, std::memory_order_relaxed);
    if (!g_model || !g_context || !g_sampler) {
        set_error("Impor dan muat model GGUF terlebih dahulu."); return -1;
    }
    if (g_generation_active) { set_error("Generasi sebelumnya masih berjalan."); return -2; }
    const std::string next_system = from_java_string(env, system_prompt);
    const std::string user = from_java_string(env, user_message);
    if (system_prompt == nullptr || user_message == nullptr) {
        set_error("Prompt tidak valid."); return -3;
    }
    if (user.empty()) { set_error("Pesan tidak boleh kosong."); return -4; }

    if (next_system != g_system_prompt) {
        g_history.clear();
        g_system_prompt = next_system;
    }

    // Chat templates render the complete transcript. Rebuild the KV cache from
    // that transcript on every turn so previous tokens are never appended twice.
    llama_memory_clear(llama_get_memory(g_context), true);
    g_position = 0;

    const std::string formatted = format_prompt_locked(user);
    if (formatted.empty()) {
        if (g_last_error.empty()) set_error("Prompt kosong setelah pemformatan.");
        return -5;
    }
    const auto tokens = tokenize_locked(formatted);
    if (!g_last_error.empty()) return -6;
    if (tokens.empty()) { set_error("Prompt tidak menghasilkan token."); return -7; }

    // UI may request up to 100,000 output tokens. The real limit is the
    // model context remaining after the formatted prompt has been evaluated.
    const int32_t requested_tokens = std::clamp(static_cast<int32_t>(max_tokens), 1, 100000);
    if (tokens.size() >= static_cast<size_t>(g_context_size)) {
        set_error("Prompt melebihi context model. Kurangi panjang pesan atau gunakan context yang lebih besar.");
        return -8;
    }
    if (!decode_tokens_locked(tokens)) {
        llama_memory_clear(llama_get_memory(g_context), true);
        g_position = 0;
        return -9;
    }

    const int32_t available_tokens = std::max(0, g_context_size - g_position - 1);
    const int32_t effective_tokens = std::min(requested_tokens, available_tokens);
    if (effective_tokens <= 0) {
        llama_memory_clear(llama_get_memory(g_context), true);
        g_position = 0;
        set_error("Tidak ada ruang context tersisa untuk menghasilkan token. Mulai chat baru atau pilih context yang lebih besar.");
        return -10;
    }

    llama_sampler_reset(g_sampler);
    g_pending_user = user;
    g_pending_response.clear();
    g_utf8_pending.clear();
    g_remaining_tokens = effective_tokens;
    g_generated_tokens.store(0, std::memory_order_relaxed);
    g_generation_limit.store(effective_tokens, std::memory_order_relaxed);
    g_inference_phase.store(2, std::memory_order_relaxed);
    g_generation_active = true;
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nexora_ai_NativeLlama_nextToken(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!g_generation_active || !g_context || !g_sampler) return nullptr;
    if (g_remaining_tokens <= 0 || g_position >= g_context_size - 1) {
        finish_generation_locked(); return nullptr;
    }
    const llama_token token = llama_sampler_sample(g_sampler, g_context, -1);
    llama_sampler_accept(g_sampler, token);
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    if (llama_vocab_is_eog(vocab, token)) { finish_generation_locked(); return nullptr; }

    g_batch.n_tokens = 1;
    g_batch.token[0] = token;
    g_batch.pos[0] = g_position;
    g_batch.n_seq_id[0] = 1;
    g_batch.seq_id[0][0] = 0;
    g_batch.logits[0] = 1;
    const int32_t result = llama_decode(g_context, g_batch);
    if (result != 0) {
        set_error("llama_decode gagal saat generasi (kode " + std::to_string(result) + ").");
        finish_generation_locked(); return nullptr;
    }
    ++g_position;
    --g_remaining_tokens;
    g_generated_tokens.fetch_add(1, std::memory_order_relaxed);

    char piece[256];
    int32_t size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
    if (size < 0) {
        std::string extended(static_cast<size_t>(-size), char{0});
        size = llama_token_to_piece(vocab, token, extended.data(),
                                    static_cast<int32_t>(extended.size()), 0, false);
        if (size < 0) {
            set_error("Potongan token melebihi buffer output.");
            finish_generation_locked(); return nullptr;
        }
        if (size > 0) g_utf8_pending.append(extended.data(), static_cast<size_t>(size));
    } else if (size > 0) {
        g_utf8_pending.append(piece, static_cast<size_t>(size));
    }
    if (!valid_utf8(g_utf8_pending)) return to_java_string(env, "");
    const std::string ready = g_utf8_pending;
    g_pending_response += ready;
    g_utf8_pending.clear();
    return to_java_string(env, ready);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_nexora_ai_NativeLlama_lastError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    return to_java_string(env, g_last_error);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nexora_ai_NativeLlama_resetChat(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_context) llama_memory_clear(llama_get_memory(g_context), true);
    g_position = 0;
    g_history.clear();
    g_generation_active = false;
    g_pending_user.clear();
    g_pending_response.clear();
    g_utf8_pending.clear();
    g_remaining_tokens = 0;
    g_inference_phase.store(0, std::memory_order_relaxed);
    g_prompt_processed.store(0, std::memory_order_relaxed);
    g_prompt_total.store(0, std::memory_order_relaxed);
    g_generated_tokens.store(0, std::memory_order_relaxed);
    g_generation_limit.store(0, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nexora_ai_NativeLlama_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    free_model_locked();
    g_load_progress.store(-1.0f, std::memory_order_relaxed);
    g_system_prompt.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_com_nexora_ai_NativeLlama_shutdown(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    free_model_locked();
    if (g_backend_initialized) {
        llama_backend_free();
        g_backend_initialized = false;
    }
}
