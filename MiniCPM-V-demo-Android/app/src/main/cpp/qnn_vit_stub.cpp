// Stub implementation used when the QAIRT headers are absent.
//
// qnn_vit.cpp needs Qualcomm's "Confidential and Proprietary" QAIRT SDK
// (2.5x) to compile; those headers are not redistributable, so a clean
// checkout cannot build it. CMakeLists.txt detects the missing
// `cpp/qnn-include/` directory and swaps this file in instead.
//
// It has to provide *both* surfaces, because llama_jni.cpp only knows the
// QNN-free interface from qnn_vit.h and calls into it directly:
//   * the qnnvit:: functions (ready / encode_rgba / take_pending ...), and
//   * the JNI entry points used by the Kotlin QnnVit object.
//
// Effect: OCR falls back to the ggml vision tower on the CPU (slower, same
// output). Everything else is unaffected. Drop a real QAIRT SDK into
// cpp/qnn-include/ (plus the libQnn*.so files into jniLibs/) and the NPU path
// comes back with no source changes.

#include "qnn_vit.h"

#include <android/log.h>
#include <jni.h>

#define TAG "QnnVitStub"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace qnnvit {

int init(const char * /*skel_dir*/, const char * /*context_binary*/, int /*arch*/) {
    LOGW("QNN stub build: no NPU vision tower, staying on the CPU tower");
    return -1;
}

bool runtime_available() { return false; }

bool ready() { return false; }

void shutdown() {}

int encode_rgba(const uint8_t * /*rgba*/, int /*pos_w*/, int /*pos_h*/) { return -1; }

const float * take_pending(int * n_tokens) {
    if (n_tokens) *n_tokens = 0;
    return nullptr;
}

}  // namespace qnnvit

extern "C" JNIEXPORT jint JNICALL
Java_com_example_minicpm_1v_1demo_QnnVit_nativeInit(JNIEnv * env, jclass, jstring jSkelDir,
                                                    jstring jBinPath, jint jArch) {
    const char * skel = env->GetStringUTFChars(jSkelDir, nullptr);
    const char * bin = env->GetStringUTFChars(jBinPath, nullptr);
    const int rc = qnnvit::init(skel, bin, (int) jArch);
    env->ReleaseStringUTFChars(jSkelDir, skel);
    env->ReleaseStringUTFChars(jBinPath, bin);
    return rc;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_minicpm_1v_1demo_QnnVit_nativeRuntimeAvailable(JNIEnv *, jclass) {
    return qnnvit::runtime_available() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_minicpm_1v_1demo_QnnVit_nativeReady(JNIEnv *, jclass) {
    return qnnvit::ready() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_minicpm_1v_1demo_QnnVit_nativeShutdown(JNIEnv *, jclass) {
    qnnvit::shutdown();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_minicpm_1v_1demo_QnnVit_nativeEncode(JNIEnv * env, jclass, jbyteArray jRgba,
                                                      jint posW, jint posH) {
    if (posW <= 0 || posH <= 0) {
        return -1;
    }
    jbyte * data = env->GetByteArrayElements(jRgba, nullptr);
    if (!data) {
        return -1;
    }
    const int rc = qnnvit::encode_rgba(reinterpret_cast<const uint8_t *>(data), posW, posH);
    env->ReleaseByteArrayElements(jRgba, data, JNI_ABORT);
    return rc;
}
