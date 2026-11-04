// Adapted from joyeli/yakuyomi-engine f990b451, GPL-3.0.
// See assets/translation/NOTICE.txt. ComicLab: shared NCNN, serial bounded I/O.
#include <jni.h>
#include <net.h>
#include <cpu.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <limits>

namespace {
struct Utf {
    JNIEnv* env; jstring value; const char* data;
    Utf(JNIEnv* e, jstring v) : env(e), value(v), data(v ? e->GetStringUTFChars(v, nullptr) : nullptr) {}
    ~Utf() { if (data) env->ReleaseStringUTFChars(value, data); }
};
bool fits(JNIEnv* env, jarray a, size_t n) {
    return a && n <= static_cast<size_t>(std::numeric_limits<jsize>::max()) && static_cast<size_t>(env->GetArrayLength(a)) >= n;
}
bool input(JNIEnv* env, jfloatArray a, ncnn::Mat& m, int c, size_t plane) {
    if (m.empty() || !fits(env, a, c * plane)) return false;
    for (int i = 0; i < c; ++i) {
        env->GetFloatArrayRegion(a, static_cast<jsize>(i * plane), static_cast<jsize>(plane), m.channel(i));
        if (env->ExceptionCheck()) return false;
    }
    return true;
}
bool output(JNIEnv* env, jfloatArray a, const ncnn::Mat& m, int c) {
    size_t plane = static_cast<size_t>(m.w) * m.h;
    if (m.empty() || m.elemsize != 4 || m.elempack != 1 || m.c != c || !fits(env, a, plane * c)) return false;
    for (int i = 0; i < c; ++i) {
        env->SetFloatArrayRegion(a, static_cast<jsize>(i * plane), static_cast<jsize>(plane), m.channel(i));
        if (env->ExceptionCheck()) return false;
    }
    return true;
}
jlong create(JNIEnv* env, jstring param, jstring bin, int threads, bool storage, bool arithmetic) {
    try {
        Utf p(env, param); if (!p.data) return 0;
        Utf b(env, bin); if (!b.data) return 0;
        auto net = std::make_unique<ncnn::Net>();
        net->opt.num_threads = std::clamp(threads, 1, 2);
        net->opt.use_vulkan_compute = false;
        net->opt.use_local_pool_allocator = false;
        // 低内存优先：避免卷积预变换权重和大工作区；不改变模型精度。
        net->opt.use_winograd_convolution = false;
        net->opt.use_sgemm_convolution = false;
        net->opt.use_fp16_storage = storage;
        net->opt.use_fp16_packed = storage;
        net->opt.use_fp16_arithmetic = arithmetic;
        net->opt.use_bf16_storage = false;
        if (net->load_param(p.data) != 0 || net->load_model(b.data) != 0) return 0;
        return reinterpret_cast<jlong>(net.release());
    } catch (...) { return 0; }
}
}
extern "C" JNIEXPORT jlong JNICALL Java_li_joye_yakuyomi_engine_NcnnBackend_createNetNative(
    JNIEnv* env, jobject, jstring param, jstring bin) { return create(env, param, bin, 2, true, true); }
extern "C" JNIEXPORT jlong JNICALL Java_li_joye_yakuyomi_engine_NcnnBackend_createNetExNative(
    JNIEnv* env, jobject, jstring param, jstring bin, jint threads, jboolean storage, jboolean arithmetic) {
    return create(env, param, bin, threads, storage, arithmetic);
}
extern "C" JNIEXPORT void JNICALL Java_li_joye_yakuyomi_engine_NcnnBackend_releaseNet(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<ncnn::Net*>(handle);
}
extern "C" JNIEXPORT jboolean JNICALL Java_li_joye_yakuyomi_engine_NcnnBackend_cpuSupportsFp16Native(JNIEnv*, jobject) {
#if __aarch64__
    return ncnn::cpu_support_arm_asimdhp() ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}
extern "C" JNIEXPORT jint JNICALL Java_li_joye_yakuyomi_engine_NcnnBackend_detectDbnetNative(
    JNIEnv* env, jobject, jlong handle, jfloatArray src, jint w, jint h, jfloatArray dbArray, jfloatArray maskArray) {
    try {
        if (!handle || w <= 0 || h <= 0 || w > 1024 || h > 1024) return -1;
        size_t plane = static_cast<size_t>(w) * h;
        if (!fits(env, dbArray, 2 * plane) || !fits(env, maskArray, plane)) return -2;
        ncnn::Mat image(w, h, 3);
        if (!input(env, src, image, 3, plane)) return -100;
        auto ex = reinterpret_cast<ncnn::Net*>(handle)->create_extractor();
        ncnn::Mat db, mask;
        if (ex.input("in0", image) != 0 || ex.extract("out0", db) != 0 || ex.extract("out1", mask) != 0) return -3;
        if (db.w != w || db.h != h || mask.w <= 0 || mask.h <= 0 || static_cast<size_t>(mask.w) * mask.h > plane) return -4;
        if (!output(env, dbArray, db, 2) || !output(env, maskArray, mask, 1)) return -5;
        return mask.w * 10000 + mask.h;
    } catch (...) { return -100; }
}
extern "C" JNIEXPORT jint JNICALL Java_li_joye_yakuyomi_engine_NcnnBackend_inpaintAotNative(
    JNIEnv* env, jobject, jlong handle, jfloatArray src, jfloatArray maskArray, jint size, jfloatArray result) {
    try {
        if (!handle || size != 512) return -1;
        size_t plane = static_cast<size_t>(size) * size;
        if (!fits(env, result, 3 * plane)) return -2;
        ncnn::Mat image(size, size, 3), mask(size, size, 1);
        if (!input(env, src, image, 3, plane) || !input(env, maskArray, mask, 1, plane)) return -100;
        auto ex = reinterpret_cast<ncnn::Net*>(handle)->create_extractor();
        ncnn::Mat out;
        if (ex.input("in0", image) != 0 || ex.input("in1", mask) != 0 || ex.extract("out0", out) != 0) return -3;
        if (out.w != size || out.h != size || !output(env, result, out, 3)) return -4;
        return 0;
    } catch (...) { return -100; }
}
extern "C" JNIEXPORT jint JNICALL Java_li_joye_yakuyomi_engine_NcnnBackend_ocrCtcNative(
    JNIEnv* env, jobject, jlong handle, jfloatArray src, jint w, jint h, jfloatArray peArray, jint t, jintArray indices, jfloatArray logp) {
    try {
        if (!handle || w < 4 || w > 8196 || h != 48 || t != w / 4 - 1 || t <= 0 || t > 2048 || !fits(env, indices, t) || !fits(env, logp, t)) return -1;
        ncnn::Mat image(w, h, 3), pe(320, t);
        if (!input(env, src, image, 3, static_cast<size_t>(w) * h) || pe.empty() || !fits(env, peArray, 320u * t)) return -100;
        env->GetFloatArrayRegion(peArray, 0, 320 * t, static_cast<float*>(pe.data));
        if (env->ExceptionCheck()) return -100;
        auto ex = reinterpret_cast<ncnn::Net*>(handle)->create_extractor();
        ncnn::Mat out;
        if (ex.input("in0", image) != 0 || ex.input("in1", pe) != 0 || ex.extract("out0", out) != 0) return -3;
        if (out.empty() || out.h != t || out.w <= 0 || out.c != 1 || out.elemsize != 4 || out.elempack != 1) return -4;
        for (int i = 0; i < t; ++i) {
            const float* row = out.row(i);
            jint index = 0; float best = row[0];
            for (int j = 0; j < out.w; ++j) { if (!std::isfinite(row[j])) return -5; if (row[j] > best) { best = row[j]; index = j; } }
            double sum = 0; for (int j = 0; j < out.w; ++j) sum += std::exp(static_cast<double>(row[j] - best));
            jfloat probability = static_cast<float>(-std::log(sum));
            env->SetIntArrayRegion(indices, i, 1, &index); env->SetFloatArrayRegion(logp, i, 1, &probability);
            if (env->ExceptionCheck()) return -100;
        }
        return t;
    } catch (...) { return -100; }
}
