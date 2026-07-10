#include "LLMInference.h"
#include "ggml-backend.h"
#include <jni.h>
#include <atomic>
#include <string>

// Loads the ggml backend plugins (libggml-cpu-android_*.so variants + libggml-vulkan.so)
// from the app's nativeLibraryDir. ggml scores each CPU variant against the device's
// actual features (dotprod/fp16/i8mm/SVE...) and keeps only the best one. Idempotent.
extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_initBackends(JNIEnv* env, jobject thiz, jstring nativeLibDir) {
    static std::atomic_bool initialized{false};
    if (initialized.exchange(true)) {
        return;
    }
    jboolean isCopy = true;
    const char* dirCstr = env->GetStringUTFChars(nativeLibDir, &isCopy);
    if (dirCstr != nullptr && dirCstr[0] != '\0') {
        ggml_backend_load_all_from_path(dirCstr);
    } else {
        ggml_backend_load_all();
    }
    env->ReleaseStringUTFChars(nativeLibDir, dirCstr);
}

// Returns the description of the first GPU-type ggml backend device (e.g. the
// Vulkan device name like "Adreno (TM) 640"), or "" when no GPU backend
// registered — either not compiled in or Vulkan init failed on this device.
extern "C" JNIEXPORT jstring JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_getGpuDeviceName(JNIEnv* env, jobject thiz) {
    std::string result;
    try {
        for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
            ggml_backend_dev_t dev = ggml_backend_dev_get(i);
            enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
            if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
                const char* desc = ggml_backend_dev_description(dev);
                if (desc != nullptr) {
                    result = desc;
                }
                break;
            }
        }
    } catch (...) {
        result.clear();
    }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_loadModel(JNIEnv* env, jobject thiz, jstring modelPath, jfloat minP,
                                            jfloat temperature, jfloat topP, jint topK, jfloat repeatPenalty,
                                            jboolean storeChats, jlong contextSize,
                                            jstring chatTemplate, jint nThreads, jboolean useMmap, jboolean useMlock,
                                            jint nGpuLayers, jint nThreadsBatch, jboolean kvCacheQ8) {
    jboolean    isCopy           = true;
    const char* modelPathCstr    = env->GetStringUTFChars(modelPath, &isCopy);
    auto*       llmInference     = new LLMInference();
    const char* chatTemplateCstr = env->GetStringUTFChars(chatTemplate, &isCopy);

    try {
        llmInference->loadModel(modelPathCstr, minP, temperature, topP, topK, repeatPenalty,
                                storeChats, contextSize, chatTemplateCstr, nThreads, useMmap, useMlock,
                                nGpuLayers, nThreadsBatch, kvCacheQ8);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(modelPath, modelPathCstr);
        env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
        delete llmInference;
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return 0;
    }

    env->ReleaseStringUTFChars(modelPath, modelPathCstr);
    env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
    return reinterpret_cast<jlong>(llmInference);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_addChatMessage(JNIEnv* env, jobject thiz, jlong modelPtr, jstring message,
                                                 jstring role) {
    jboolean    isCopy       = true;
    const char* messageCstr  = env->GetStringUTFChars(message, &isCopy);
    const char* roleCstr     = env->GetStringUTFChars(role, &isCopy);
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->addChatMessage(messageCstr, roleCstr);
    env->ReleaseStringUTFChars(message, messageCstr);
    env->ReleaseStringUTFChars(role, roleCstr);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_getResponseGenerationSpeed(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getResponseGenerationTime();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_getContextSizeUsed(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getContextSizeUsed();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_close(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    delete llmInference;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_startCompletion(JNIEnv* env, jobject thiz, jlong modelPtr, jstring prompt) {
    jboolean    isCopy       = true;
    const char* promptCstr   = env->GetStringUTFChars(prompt, &isCopy);
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    try {
        llmInference->startCompletion(promptCstr);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(prompt, promptCstr);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return;
    }
    env->ReleaseStringUTFChars(prompt, promptCstr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_completionLoop(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    try {
        std::string response = llmInference->completionLoop();
        return env->NewStringUTF(response.c_str());
    } catch (std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_stopCompletion(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->stopCompletion();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_benchModel(JNIEnv* env, jobject /*unused*/, jlong modelPtr, jint pp, jint tg, jint pl,
                                             jint nr) {
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    std::string result       = llmInference->benchModel(pp, tg, pl, nr);
    return env->NewStringUTF(result.c_str());
}
