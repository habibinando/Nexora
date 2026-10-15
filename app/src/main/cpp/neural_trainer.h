#ifndef NEURAL_TRAINER_H
#define NEURAL_TRAINER_H

#include <jni.h>
#include <vector>
#include <cmath>
#include <string>
#include <chrono>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jboolean JNICALL
Java_com_example_neural_NdkNeuralAccelerator_nativeIsNdkSupported(
    JNIEnv *env,
    jobject thiz
);

JNIEXPORT jstring JNICALL
Java_com_example_neural_NdkNeuralAccelerator_nativeGetNdkInfo(
    JNIEnv *env,
    jobject thiz
);

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
);

JNIEXPORT jfloatArray JNICALL
Java_com_example_neural_NdkNeuralAccelerator_nativeBenchmarkMatMul(
    JNIEnv *env,
    jobject thiz,
    jint m,
    jint n,
    jint k,
    jint iterations
);

#ifdef __cplusplus
}
#endif

#endif // NEURAL_TRAINER_H
