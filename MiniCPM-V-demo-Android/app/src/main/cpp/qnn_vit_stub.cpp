// Stub JNI surface used when the QAIRT headers are absent.
//
// qnn_vit.cpp needs Qualcomm's "Confidential and Proprietary" QAIRT SDK
// (2.51.200) to compile; those headers are not redistributable, so a clean
// checkout cannot build it. CMakeLists.txt detects the missing
// `cpp/qnn-include/` directory and swaps this file in instead, which keeps the
// Kotlin QnnVit object linkable while reporting the runtime as unavailable.
//
// Effect: OCR falls back to the ggml vision tower on the CPU (slower, identical
// output). Everything else in the app is unaffected. Drop a real QAIRT SDK into
// cpp/qnn-include/ (plus the libQnn*.so files into jniLibs/) and the NPU path
// comes back with no source changes.

#include <android/log.h>
#include <jni.h>

#define TAG "QnnVitStub"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_com_example_minicpm_1v_1demo_QnnVit_nativeInit(JNIEnv *, jclass, jstring, jstring, jint) {
    LOGW("QNN stub build: no NPU vision tower, fall back to CPU");
    return -1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_minicpm_1v_1demo_QnnVit_nativeRuntimeAvailable(JNIEnv *, jclass) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_minicpm_1v_1demo_QnnVit_nativeReady(JNIEnv *, jclass) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_QnnVit_nativeShutdown(JNIEnv *, jclass) {
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_minicpm_1v_1demo_QnnVit_nativeEncode(JNIEnv *, jclass, jbyteArray, jint, jint) {
    return -1;
}
