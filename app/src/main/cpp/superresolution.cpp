#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <net.h>
#include <gpu.h>
#include <algorithm>
#include <memory>
#include <mutex>
#include <vector>
#include <string>


namespace {
struct Engine { ncnn::Net net; int scale = 2; };
std::once_flag gpuInitialization;
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_comiclab_core_reader_enhance_NativeSuperResolution_create(
        JNIEnv* env, jobject, jobject assets, jboolean gpu, jint threads, jint scale) {
    try {
        if (scale < 2 || scale > 4) { fail(env, "Invalid enhancement scale"); return 0; }
        std::call_once(gpuInitialization, [] {
            ncnn::create_gpu_instance();
        });
        if (gpu && ncnn::get_gpu_count() == 0) return 0;
        auto engine = std::make_unique<Engine>();
        engine->scale = scale;
        const std::string model = "superresolution/realesr-animevideov3-x" + std::to_string(scale);
        engine->net.opt.num_threads = std::clamp(static_cast<int>(threads), 1, 4);
        engine->net.opt.use_vulkan_compute = gpu;
        engine->net.opt.use_fp16_arithmetic = false;
        // CPU以FP32作为保底，避免不同指令集引入半精度差异
        if (!gpu) {
            engine->net.opt.use_fp16_packed = false;
            engine->net.opt.use_fp16_storage = false;
        }
        auto* manager = AAssetManager_fromJava(env, assets);
        if (!manager || engine->net.load_param(manager,
                (model + ".param").c_str()) != 0 ||
                engine->net.load_model(manager,
                (model + ".bin").c_str()) != 0) {
            fail(env, "Could not load Real-ESRGAN model");
            return 0;
        }
        return reinterpret_cast<jlong>(engine.release());
    } catch (...) {
        fail(env, "Could not initialize Real-ESRGAN");
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_comiclab_core_reader_enhance_NativeSuperResolution_infer(
        JNIEnv* env, jobject, jlong handle, jobject bitmap, jint left, jint top,
        jint width, jint height) {
    try {
        auto* engine = reinterpret_cast<Engine*>(handle);
        AndroidBitmapInfo info{};
        if (!engine || AndroidBitmap_getInfo(env, bitmap, &info) != 0 ||
                info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
                info.width > 320 || info.height > 320 || width <= 0 || height <= 0 ||
                width > 256 || height > 256 || left < 0 || top < 0 ||
                left + width > static_cast<int>(info.width) ||
                top + height > static_cast<int>(info.height)) {
            fail(env, "Invalid enhancement tile"); return nullptr;
        }
        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0) {
            fail(env, "Could not read enhancement tile"); return nullptr;
        }
        ncnn::Mat input;
        try {
            input = ncnn::Mat::from_pixels(static_cast<unsigned char*>(pixels),
                    ncnn::Mat::PIXEL_RGBA2RGB, info.width, info.height, info.stride);
        } catch (...) {
            AndroidBitmap_unlockPixels(env, bitmap);
            throw;
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        if (input.empty()) { fail(env, "Tile allocation failed"); return nullptr; }
        const float normalize[] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
        input.substract_mean_normalize(nullptr, normalize);
        auto extractor = engine->net.create_extractor();
        ncnn::Mat output;
        if (extractor.input("data", input) != 0 || extractor.extract("output", output) != 0 ||
                output.w != static_cast<int>(info.width * engine->scale) ||
                output.h != static_cast<int>(info.height * engine->scale) || output.c != 3) {
            fail(env, "Real-ESRGAN inference failed"); return nullptr;
        }
        const float denormalize[] = {255.f, 255.f, 255.f};
        output.substract_mean_normalize(nullptr, denormalize);
        std::vector<unsigned char> rgb(output.w * output.h * 3);
        output.to_pixels(rgb.data(), ncnn::Mat::PIXEL_RGB);
        const int rowBytes = width * engine->scale * 3;
        auto result = env->NewByteArray(rowBytes * height * engine->scale);
        if (!result) return nullptr;
        for (int y = 0; y < height * engine->scale; ++y) {
            const auto* row = rgb.data() + ((top * engine->scale + y) * output.w + left * engine->scale) * 3;
            env->SetByteArrayRegion(result, y * rowBytes, rowBytes,
                    reinterpret_cast<const jbyte*>(row));
        }
        return result;
    } catch (...) {
        fail(env, "Real-ESRGAN tile processing failed"); return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_comiclab_core_reader_enhance_NativeSuperResolution_release(
        JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Engine*>(handle);
}
