#include <jni.h>
#include <llama.h>
#include <ggml-backend.h>
#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace {
std::mutex g_mutex;
bool g_backend_initialized = false;
llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
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
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_batch_initialized) { llama_batch_free(g_batch); g_batch_initialized = false; }
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
    constexpr int32_t capacity = 256;
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
                                         jint context_size, jint thread_count) {
    std::lock_guard<std::mutex> guard(g_mutex);
    clear_error();
    const std::string path = from_java_string(env, model_path);
    if (path.empty()) { set_error("Path model tidak valid."); return -1; }
    free_model_locked();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU backend in the default APK.
    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (!g_model) {
        set_error("llama.cpp gagal memuat GGUF. Periksa file, arsitektur, dan kapasitas RAM.");
        return -2;
    }

    const int32_t requested = std::max(512, std::min(8192, static_cast<int32_t>(context_size)));
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
        set_error("Gagal mengalokasikan context. Coba model lebih kecil atau context 1024.");
        return -3;
    }

    g_batch = llama_batch_init(256, 0, 1);
    g_batch_initialized = true;
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!g_sampler) {
        free_model_locked();
        set_error("Gagal menginisialisasi sampler.");
        return -4;
    }
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(0.70f));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    g_position = 0;
    g_history.clear();
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nexora_ai_NativeLlama_startPrompt(JNIEnv *env, jobject, jstring system_prompt,
                                            jstring user_message, jint max_tokens) {
    std::lock_guard<std::mutex> guard(g_mutex);
    clear_error();
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

    const int32_t requested_tokens = std::max(1, std::min(2048, static_cast<int32_t>(max_tokens)));
    if (g_position + static_cast<int32_t>(tokens.size()) + requested_tokens > g_context_size) {
        set_error("Context penuh. Mulai percakapan baru atau kurangi jumlah token/context.");
        return -8;
    }
    if (!decode_tokens_locked(tokens)) {
        llama_memory_clear(llama_get_memory(g_context), true);
        g_position = 0;
        return -9;
    }

    llama_sampler_reset(g_sampler);
    g_pending_user = user;
    g_pending_response.clear();
    g_utf8_pending.clear();
    g_remaining_tokens = requested_tokens;
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
}

extern "C" JNIEXPORT void JNICALL
Java_com_nexora_ai_NativeLlama_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    free_model_locked();
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
