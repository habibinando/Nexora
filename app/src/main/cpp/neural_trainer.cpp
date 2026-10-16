#include "neural_trainer.h"
#include <android/log.h>
#include <algorithm>
#include <cstring>
#include <sstream>
#include <random>

#define TAG "NeuralTrainerNDK"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_neural_NdkNeuralAccelerator_nativeIsNdkSupported(
    JNIEnv *env,
    jobject thiz
) {
    (void)env;
    (void)thiz;
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_example_neural_NdkNeuralAccelerator_nativeGetNdkInfo(
    JNIEnv *env,
    jobject thiz
) {
    (void)thiz;
    std::ostringstream oss;
    oss << "NDK Architecture: ";
#if defined(__arm__)
    oss << "ARMv7 (32-bit)";
#elif defined(__aarch64__)
    oss << "ARM64-v8a (NEON SIMD enabled)";
#elif defined(__i386__)
    oss << "x86";
#elif defined(__x86_64__)
    oss << "x86_64 (AVX/SSE)";
#else
    oss << "Generic Target";
#endif

#if defined(__clang__)
    oss << " | Clang " << __clang_major__ << "." << __clang_minor__ << "." << __clang_patchlevel__;
#endif
    oss << " | C++" << (__cplusplus / 100);
    return env->NewStringUTF(oss.str().c_str());
}

JNIEXPORT jfloatArray JNICALL
Java_com_example_neural_NdkNeuralAccelerator_nativeTrainStep(
    JNIEnv *env,
    jobject thiz,
    jfloatArray wxh_arr,
    jfloatArray whh_arr,
    jfloatArray why_arr,
    jfloatArray bh_arr,
    jfloatArray by_arr,
    jintArray inputs_arr,
    jintArray targets_arr,
    jfloat learning_rate,
    jint optimizer_type,
    jfloat beta1,
    jfloat beta2,
    jfloat weight_decay
) {
    (void)thiz;
    (void)optimizer_type;
    (void)beta1;
    (void)beta2;
    (void)weight_decay;

    auto start_time = std::chrono::high_resolution_clock::now();

    jsize wxh_len = env->GetArrayLength(wxh_arr);
    jsize whh_len = env->GetArrayLength(whh_arr);
    jsize why_len = env->GetArrayLength(why_arr);
    jsize bh_len  = env->GetArrayLength(bh_arr);
    jsize by_len  = env->GetArrayLength(by_arr);
    jsize seq_len = env->GetArrayLength(inputs_arr);

    int hidden_size = bh_len;
    int vocab_size  = by_len;

    if (hidden_size <= 0 || vocab_size <= 0 || seq_len <= 0) {
        jfloatArray empty = env->NewFloatArray(4);
        return empty;
    }

    jfloat *wxh = env->GetFloatArrayElements(wxh_arr, nullptr);
    jfloat *whh = env->GetFloatArrayElements(whh_arr, nullptr);
    jfloat *why = env->GetFloatArrayElements(why_arr, nullptr);
    jfloat *bh  = env->GetFloatArrayElements(bh_arr, nullptr);
    jfloat *by  = env->GetFloatArrayElements(by_arr, nullptr);
    jint *inputs  = env->GetIntArrayElements(inputs_arr, nullptr);
    jint *targets = env->GetIntArrayElements(targets_arr, nullptr);

    // Dynamic state matrices:
    // hidden_states: (seq_len + 1) * hidden_size
    std::vector<float> hs((seq_len + 1) * hidden_size, 0.0f);
    // probs: seq_len * vocab_size
    std::vector<float> probs(seq_len * vocab_size, 0.0f);

    float total_loss = 0.0f;
    double flop_count = 0.0;

    // --- FORWARD PASS ---
    for (int t = 0; t < seq_len; ++t) {
        int x_idx = inputs[t];
        if (x_idx < 0 || x_idx >= vocab_size) x_idx = 0;

        float *prev_h = &hs[t * hidden_size];
        float *curr_h = &hs[(t + 1) * hidden_size];

        // curr_h = tanh(Wxh[:, x_idx] + Whh * prev_h + bh)
        for (int i = 0; i < hidden_size; ++i) {
            float sum = bh[i];
            // Wxh row i, col x_idx
            sum += wxh[i * vocab_size + x_idx];
            // Whh row i dot prev_h
            const float *whh_row = &whh[i * hidden_size];
            for (int j = 0; j < hidden_size; ++j) {
                sum += whh_row[j] * prev_h[j];
            }
            curr_h[i] = std::tanh(sum);
        }
        flop_count += 2.0 * hidden_size * hidden_size + 3.0 * hidden_size;

        // Output logits: ys = Why * curr_h + by
        // and softmax: probs[t]
        float *curr_prob = &probs[t * vocab_size];
        float max_logit = -1e9f;
        for (int k = 0; k < vocab_size; ++k) {
            float sum = by[k];
            const float *why_row = &why[k * hidden_size];
            for (int j = 0; j < hidden_size; ++j) {
                sum += why_row[j] * curr_h[j];
            }
            curr_prob[k] = sum;
            if (sum > max_logit) max_logit = sum;
        }
        flop_count += 2.0 * vocab_size * hidden_size;

        float sum_exp = 0.0f;
        for (int k = 0; k < vocab_size; ++k) {
            curr_prob[k] = std::exp(curr_prob[k] - max_logit);
            sum_exp += curr_prob[k];
        }
        float inv_sum = 1.0f / (sum_exp + 1e-9f);
        for (int k = 0; k < vocab_size; ++k) {
            curr_prob[k] *= inv_sum;
        }

        // Cross entropy loss
        int target_idx = targets[t];
        if (target_idx >= 0 && target_idx < vocab_size) {
            float p_target = curr_prob[target_idx];
            if (p_target < 1e-15f) p_target = 1e-15f;
            total_loss += -std::log(p_target);
        }
    }

    float avg_loss = total_loss / static_cast<float>(seq_len);

    // --- BACKWARD PASS (BPTT) ---
    std::vector<float> d_wxh(wxh_len, 0.0f);
    std::vector<float> d_whh(whh_len, 0.0f);
    std::vector<float> d_why(why_len, 0.0f);
    std::vector<float> d_bh(bh_len, 0.0f);
    std::vector<float> d_by(by_len, 0.0f);
    std::vector<float> dh_next(hidden_size, 0.0f);

    for (int t = seq_len - 1; t >= 0; --t) {
        int x_idx = inputs[t];
        if (x_idx < 0 || x_idx >= vocab_size) x_idx = 0;
        int target_idx = targets[t];

        float *curr_prob = &probs[t * vocab_size];
        float *curr_h = &hs[(t + 1) * hidden_size];
        float *prev_h = &hs[t * hidden_size];

        // dy = prob; dy[target] -= 1.0
        std::vector<float> dy(vocab_size);
        for (int k = 0; k < vocab_size; ++k) {
            dy[k] = curr_prob[k];
        }
        if (target_idx >= 0 && target_idx < vocab_size) {
            dy[target_idx] -= 1.0f;
        }

        // dWhy += dy * curr_h^T; dby += dy
        for (int k = 0; k < vocab_size; ++k) {
            d_by[k] += dy[k];
            float *d_why_row = &d_why[k * hidden_size];
            for (int j = 0; j < hidden_size; ++j) {
                d_why_row[j] += dy[k] * curr_h[j];
            }
        }
        flop_count += 2.0 * vocab_size * hidden_size;

        // dh = Why^T * dy + dh_next
        std::vector<float> dh(hidden_size, 0.0f);
        for (int j = 0; j < hidden_size; ++j) {
            float sum = dh_next[j];
            for (int k = 0; k < vocab_size; ++k) {
                sum += why[k * hidden_size + j] * dy[k];
            }
            dh[j] = sum;
        }
        flop_count += 2.0 * vocab_size * hidden_size;

        // dh_raw = (1 - curr_h^2) * dh
        std::vector<float> dh_raw(hidden_size, 0.0f);
        for (int j = 0; j < hidden_size; ++j) {
            float ch = curr_h[j];
            dh_raw[j] = (1.0f - ch * ch) * dh[j];
            d_bh[j] += dh_raw[j];
        }

        // dWxh[:, x_idx] += dh_raw
        for (int i = 0; i < hidden_size; ++i) {
            d_wxh[i * vocab_size + x_idx] += dh_raw[i];
        }

        // dWhh += dh_raw * prev_h^T
        for (int i = 0; i < hidden_size; ++i) {
            float *d_whh_row = &d_whh[i * hidden_size];
            for (int j = 0; j < hidden_size; ++j) {
                d_whh_row[j] += dh_raw[i] * prev_h[j];
            }
        }
        flop_count += 2.0 * hidden_size * hidden_size;

        // dh_next = Whh^T * dh_raw
        for (int j = 0; j < hidden_size; ++j) {
            float sum = 0.0f;
            for (int i = 0; i < hidden_size; ++i) {
                sum += whh[i * hidden_size + j] * dh_raw[i];
            }
            dh_next[j] = sum;
        }
        flop_count += 2.0 * hidden_size * hidden_size;
    }

    // Normalize gradients by sequence length so gradient scale matches avg_loss (mean cross-entropy)
    float inv_seq_len = 1.0f / static_cast<float>(seq_len);
    for (int i = 0; i < wxh_len; ++i) d_wxh[i] *= inv_seq_len;
    for (int i = 0; i < whh_len; ++i) d_whh[i] *= inv_seq_len;
    for (int i = 0; i < why_len; ++i) d_why[i] *= inv_seq_len;
    for (int i = 0; i < bh_len;  ++i) d_bh[i]  *= inv_seq_len;
    for (int i = 0; i < by_len;  ++i) d_by[i]  *= inv_seq_len;

    // Gradient clipping & L2 norm calculation
    double grad_sq_sum = 0.0;
    for (float g : d_wxh) grad_sq_sum += g * g;
    for (float g : d_whh) grad_sq_sum += g * g;
    for (float g : d_why) grad_sq_sum += g * g;
    for (float g : d_bh)  grad_sq_sum += g * g;
    for (float g : d_by)  grad_sq_sum += g * g;

    float grad_norm = static_cast<float>(std::sqrt(grad_sq_sum));
    float clip_factor = 1.0f;
    const float max_norm = 5.0f;
    if (grad_norm > max_norm) {
        clip_factor = max_norm / (grad_norm + 1e-7f);
    }

    // Weight update (SGD with gradient clipping)
    for (int i = 0; i < wxh_len; ++i) wxh[i] -= learning_rate * (d_wxh[i] * clip_factor);
    for (int i = 0; i < whh_len; ++i) whh[i] -= learning_rate * (d_whh[i] * clip_factor);
    for (int i = 0; i < why_len; ++i) why[i] -= learning_rate * (d_why[i] * clip_factor);
    for (int i = 0; i < bh_len; ++i)  bh[i]  -= learning_rate * (d_bh[i]  * clip_factor);
    for (int i = 0; i < by_len; ++i)  by[i]  -= learning_rate * (d_by[i]  * clip_factor);

    // Release JNI arrays and commit changes to Java memory
    env->ReleaseFloatArrayElements(wxh_arr, wxh, 0);
    env->ReleaseFloatArrayElements(whh_arr, whh, 0);
    env->ReleaseFloatArrayElements(why_arr, why, 0);
    env->ReleaseFloatArrayElements(bh_arr, bh, 0);
    env->ReleaseFloatArrayElements(by_arr, by, 0);
    env->ReleaseIntArrayElements(inputs_arr, inputs, JNI_ABORT);
    env->ReleaseIntArrayElements(targets_arr, targets, JNI_ABORT);

    auto end_time = std::chrono::high_resolution_clock::now();
    double duration_ms = std::chrono::duration<double, std::milli>(end_time - start_time).count();
    if (duration_ms < 0.001) duration_ms = 0.001;

    double gflops = (flop_count / (duration_ms * 1e-3)) / 1e9;

    jfloatArray result = env->NewFloatArray(4);
    jfloat out_vals[4] = {
        avg_loss,
        static_cast<float>(duration_ms),
        static_cast<float>(gflops),
        grad_norm
    };
    env->SetFloatArrayRegion(result, 0, 4, out_vals);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_example_neural_NdkNeuralAccelerator_nativeBenchmarkMatMul(
    JNIEnv *env,
    jobject thiz,
    jint m,
    jint n,
    jint k,
    jint iterations
) {
    (void)thiz;
    if (m <= 0 || n <= 0 || k <= 0 || iterations <= 0) {
        jfloatArray empty = env->NewFloatArray(2);
        return empty;
    }

    std::vector<float> A(m * k, 1.0f);
    std::vector<float> B(k * n, 0.5f);
    std::vector<float> C(m * n, 0.0f);

    auto start_time = std::chrono::high_resolution_clock::now();

    for (int it = 0; it < iterations; ++it) {
        for (int i = 0; i < m; ++i) {
            const float *a_row = &A[i * k];
            float *c_row = &C[i * n];
            for (int j = 0; j < n; ++j) {
                float sum = 0.0f;
                for (int p = 0; p < k; ++p) {
                    sum += a_row[p] * B[p * n + j];
                }
                c_row[j] = sum;
            }
        }
    }

    auto end_time = std::chrono::high_resolution_clock::now();
    double duration_ms = std::chrono::duration<double, std::milli>(end_time - start_time).count();
    if (duration_ms < 0.001) duration_ms = 0.001;

    double total_flops = 2.0 * static_cast<double>(m) * n * k * iterations;
    double gflops = (total_flops / (duration_ms * 1e-3)) / 1e9;

    jfloatArray result = env->NewFloatArray(2);
    jfloat out_vals[2] = {
        static_cast<float>(duration_ms),
        static_cast<float>(gflops)
    };
    env->SetFloatArrayRegion(result, 0, 2, out_vals);
    return result;
}

} // extern "C"
