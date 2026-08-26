#include <jni.h>
#include <android/log.h>

#include "whisper.h"

#include <algorithm>
#include <cctype>
#include <iomanip>
#include <sstream>
#include <string>

namespace {
constexpr char kLogTag[] = "OnDeviceAI";

void throwIllegalArgument(JNIEnv * env, const char * message) {
    jclass exception = env->FindClass("java/lang/IllegalArgumentException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

void throwIllegalState(JNIEnv * env, const char * message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message);
}

std::string jsonEscape(const std::string & value) {
    std::ostringstream output;
    for (unsigned char ch : value) {
        switch (ch) {
            case '"': output << "\\\""; break;
            case '\\': output << "\\\\"; break;
            case '\b': output << "\\b"; break;
            case '\f': output << "\\f"; break;
            case '\n': output << "\\n"; break;
            case '\r': output << "\\r"; break;
            case '\t': output << "\\t"; break;
            default:
                if (ch < 0x20) {
                    output << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                           << static_cast<int>(ch) << std::dec;
                } else {
                    output << static_cast<char>(ch);
                }
        }
    }
    return output.str();
}

std::string trim(const std::string & value) {
    const auto first = std::find_if_not(value.begin(), value.end(), [](unsigned char ch) {
        return std::isspace(ch) != 0;
    });
    const auto last = std::find_if_not(value.rbegin(), value.rend(), [](unsigned char ch) {
        return std::isspace(ch) != 0;
    }).base();
    if (first >= last) return {};
    return std::string(first, last);
}

bool hasWordCharacter(const std::string & value) {
    for (unsigned char ch : value) {
        if (std::isalnum(ch) != 0 || ch >= 0x80) return true;
    }
    return false;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ondeviceai_LocalASR_nativeTranscribe(
        JNIEnv * env,
        jobject /* self */,
        jstring model_path,
        jfloatArray samples_array,
        jint sample_rate_hz,
        jint threads,
        jstring language,
        jboolean translate) {
    if (model_path == nullptr || samples_array == nullptr || language == nullptr) {
        throwIllegalArgument(env, "modelPath, samples, and language are required");
        return nullptr;
    }
    if (sample_rate_hz != 16000) {
        throwIllegalArgument(env, "Whisper.cpp requires 16 kHz mono samples");
        return nullptr;
    }

    const jsize sample_count = env->GetArrayLength(samples_array);
    if (sample_count <= 0) {
        throwIllegalArgument(env, "samples must not be empty");
        return nullptr;
    }

    const char * model_path_chars = env->GetStringUTFChars(model_path, nullptr);
    const char * language_chars = env->GetStringUTFChars(language, nullptr);
    if (model_path_chars == nullptr || language_chars == nullptr) {
        if (model_path_chars != nullptr) env->ReleaseStringUTFChars(model_path, model_path_chars);
        if (language_chars != nullptr) env->ReleaseStringUTFChars(language, language_chars);
        return nullptr;
    }

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    whisper_context * context = whisper_init_from_file_with_params(model_path_chars, context_params);
    if (context == nullptr) {
        env->ReleaseStringUTFChars(model_path, model_path_chars);
        env->ReleaseStringUTFChars(language, language_chars);
        throwIllegalState(env, "Unable to load the Whisper model");
        return nullptr;
    }

    jfloat * samples = env->GetFloatArrayElements(samples_array, nullptr);
    if (samples == nullptr) {
        whisper_free(context);
        env->ReleaseStringUTFChars(model_path, model_path_chars);
        env->ReleaseStringUTFChars(language, language_chars);
        return nullptr;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, std::min(static_cast<int>(threads), 8));
    params.translate = translate == JNI_TRUE;
    params.no_context = true;
    params.no_timestamps = false;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.token_timestamps = true;
    params.max_len = 1;
    params.split_on_word = true;
    params.language = language_chars;
    params.suppress_blank = true;

    const int result = whisper_full(context, params, samples, sample_count);
    env->ReleaseFloatArrayElements(samples_array, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(model_path, model_path_chars);
    env->ReleaseStringUTFChars(language, language_chars);
    if (result != 0) {
        whisper_free(context);
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "whisper_full failed: %d", result);
        throwIllegalState(env, "Whisper transcription failed");
        return nullptr;
    }

    std::ostringstream json;
    json << "[";
    bool first_word = true;
    const int segment_count = whisper_full_n_segments(context);
    for (int segment_index = 0; segment_index < segment_count; ++segment_index) {
        const int token_count = whisper_full_n_tokens(context, segment_index);
        if (token_count <= 0) continue;

        std::string text;
        int64_t start_cs = -1;
        int64_t end_cs = -1;
        float confidence_sum = 0.0f;
        int confidence_count = 0;
        for (int token_index = 0; token_index < token_count; ++token_index) {
            const char * token_text = whisper_full_get_token_text(context, segment_index, token_index);
            if (token_text == nullptr) continue;
            const std::string piece(token_text);
            if (piece.rfind("<|", 0) == 0) continue;
            text += piece;
            const int64_t token_start = whisper_full_get_token_t0(context, segment_index, token_index);
            const int64_t token_end = whisper_full_get_token_t1(context, segment_index, token_index);
            if (token_start >= 0 && (start_cs < 0 || token_start < start_cs)) start_cs = token_start;
            if (token_end >= 0 && token_end > end_cs) end_cs = token_end;
            const float probability = whisper_full_get_token_p(context, segment_index, token_index);
            if (probability >= 0.0f) {
                confidence_sum += probability;
                ++confidence_count;
            }
        }

        text = trim(text);
        if (!hasWordCharacter(text)) continue;
        if (start_cs < 0) start_cs = whisper_full_get_segment_t0(context, segment_index);
        if (end_cs < 0) end_cs = whisper_full_get_segment_t1(context, segment_index);
        if (end_cs < start_cs) end_cs = start_cs;

        const float confidence = confidence_count == 0
            ? 0.0f
            : std::max(0.0f, std::min(1.0f, confidence_sum / confidence_count));
        if (!first_word) json << ",";
        first_word = false;
        json << "{\"word\":\"" << jsonEscape(text)
             << "\",\"startMs\":" << start_cs * 10
             << ",\"endMs\":" << end_cs * 10
             << ",\"confidence\":" << std::fixed << std::setprecision(5) << confidence
             << "}";
    }
    json << "]";
    whisper_free(context);
    return env->NewStringUTF(json.str().c_str());
}
