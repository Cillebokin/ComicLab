# ncnn Android runtime

Upstream: https://github.com/Tencent/ncnn

Unmodified headers, CMake metadata and shared libraries from the official
`ncnn-20261020-android-vulkan-shared.zip` release:
https://github.com/Tencent/ncnn/releases/download/20261020/ncnn-20261020-android-vulkan-shared.zip

Archive SHA-256: `1b01048590564bae1c3d2d2a2b37ff101d50ad86c2cd2dc862bce7087905444d`

Retained ABIs: arm64-v8a, armeabi-v7a, x86, x86_64. These are build inputs,
not generated project output. Keep them when sharing or checking out this feature.
The JNI wrapper uses NDK 28.2.13676358 and CMake 3.22.1. Android SDK Manager
can install these versions. No inference dependencies are downloaded at runtime.

License: LICENSE.txt, also packaged in assets/superresolution/LICENSE-ncnn.txt.
