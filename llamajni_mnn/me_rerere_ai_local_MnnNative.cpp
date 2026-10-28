// MNN-LLM 本地推理 JNI（预编译 libllm.so 封装）
// 品牌：RikkaHub etc 2.0（Reai），开发者 etc，AGPL-3.0
#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <fstream>
#include <ostream>
#include <sstream>
#include <streambuf>
#include <functional>

#include "llm/llm.hpp"

using MNN::Transformer::Llm;

namespace {

struct MnnHandle {
    Llm * llm = nullptr;
};

// 流式输出捕获：逐 token 回调 Java 并收集全文
class JniStreamBuffer : public std::streambuf {
public:
    JniStreamBuffer(JNIEnv * env, jobject cb, std::string & out)
        : env_(env), cb_(cb), out_(out) {}
protected:
    std::streamsize xsputn(const char * s, std::streamsize n) override {
        if (n > 0) {
            out_.append(s, (size_t)n);
            if (cb_) {
                jclass cls = env_->GetObjectClass(cb_);
                if (cls) {
                    jmethodID onToken = env_->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
                    if (onToken) {
                        jstring js = env_->NewStringUTF(std::string(s, (size_t)n).c_str());
                        env_->CallVoidMethod(cb_, onToken, js);
                        env_->DeleteLocalRef(js);
                    }
                    env_->DeleteLocalRef(cls);
                }
            }
        }
        return n;
    }
private:
    JNIEnv * env_;
    jobject cb_;
    std::string & out_;
};

MnnHandle * h(jlong p) { return reinterpret_cast<MnnHandle *>(p); }

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_me_rerere_ai_local_MnnNative_create(
    JNIEnv * env, jobject, jstring configPath, jint threads ) {
    std::string path = "";
    if (configPath) {
        const char * c = env->GetStringUTFChars(configPath, nullptr);
        if (c) { path = c; env->ReleaseStringUTFChars(configPath, c); }
    }
    if (path.empty()) return 0;

    MnnHandle * mh = new MnnHandle();
    try {
        Llm * llm = Llm::createLLM(path);
        if (!llm) { delete mh; return -1; }
        mh->llm = llm;
        // 官方流程：set_config 补充 MNN-LLM 必需字段
        // 魔塔官方 MNN 仓库的 config.json 缺 llm_config/tokenizer_file 等字段，
        // 不补会导致 prompt_template/分词器加载为空 → 回复空字符串。
        {
            std::string cfg = "";
            std::ifstream f(path.c_str());
            if (f.good()) {
                std::stringstream ss;
                ss << f.rdbuf();
                cfg = ss.str();
            }
            bool has_llm_config = cfg.find("\"llm_config\"") != std::string::npos;
            bool has_tokenizer = cfg.find("\"tokenizer_file\"") != std::string::npos;
            if (!has_llm_config || !has_tokenizer) {
                std::string merged = cfg;
                if (cfg.size() > 1 && cfg.back() == '}') {
                    merged.pop_back();
                    if (!has_llm_config) {
                        merged += ",\"llm_config\":\"llm_config.json\"";
                    }
                    if (!has_tokenizer) {
                        merged += ",\"tokenizer_file\":\"tokenizer.txt\"";
                    }
                    merged += "}";
                } else {
                    merged = "{\"llm_model\":\"llm.mnn\",\"llm_weight\":\"llm.mnn.weight\","
                             "\"llm_config\":\"llm_config.json\",\"tokenizer_file\":\"tokenizer.txt\","
                             "\"backend_type\":\"cpu\",\"thread_num\":4,\"precision\":\"low\"}";
                }
                llm->set_config(merged);
            } else {
                llm->set_config(cfg);
            }
        }
        if (!llm->load()) {
            Llm::destroy(llm);
            mh->llm = nullptr;
            delete mh;
            return -2;
        }
    } catch (...) {
        if (mh->llm) Llm::destroy(mh->llm);
        delete mh;
        return -3;
    }
    return reinterpret_cast<jlong>(mh);
}

JNIEXPORT jstring JNICALL
Java_me_rerere_ai_local_MnnNative_chat(
    JNIEnv * env, jobject, jlong handle, jstring userContent,
    jint maxTokens, jobject cb ) {
    MnnHandle * mh = h(handle);
    if (!mh || !mh->llm) return env->NewStringUTF("");
    std::string content = "";
    if (userContent) {
        const char * c = env->GetStringUTFChars(userContent, nullptr);
        if (c) { content = c; env->ReleaseStringUTFChars(userContent, c); }
    }
    std::string out;
    try {
        JniStreamBuffer sbuf(env, cb, out);
        std::ostream os(&sbuf);
        mh->llm->response(content, &os, nullptr, maxTokens > 0 ? maxTokens : 512);
    } catch (const std::exception & e) {
        // 异常透传：不再吞成空串，让上层显示真实错误
        if (cb) {
            jclass cls = env->GetObjectClass(cb);
            if (cls) {
                jmethodID onError = env->GetMethodID(cls, "onError", "(Ljava/lang/String;)V");
                if (onError) {
                    jstring js = env->NewStringUTF(e.what());
                    env->CallVoidMethod(cb, onError, js);
                    env->DeleteLocalRef(js);
                }
                env->DeleteLocalRef(cls);
            }
        }
        return env->NewStringUTF("");
    } catch (...) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_me_rerere_ai_local_MnnNative_reset(
    JNIEnv *, jobject, jlong handle ) {
    MnnHandle * mh = h(handle);
    if (mh && mh->llm) {
        try { mh->llm->reset(); } catch (...) {}
    }
}

JNIEXPORT void JNICALL
Java_me_rerere_ai_local_MnnNative_free(
    JNIEnv *, jobject, jlong handle ) {
    MnnHandle * mh = h(handle);
    if (!mh) return;
    if (mh->llm) {
        try { Llm::destroy(mh->llm); } catch (...) {}
        mh->llm = nullptr;
    }
    delete mh;
}

} // extern "C"
