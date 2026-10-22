cmake_minimum_required(VERSION 3.22.1)
project(comiclab_superresolution LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
# 官方共享库已包含其运行依赖；直接导入，避免继承ncnn导出的-fno-exceptions编译选项
set(NCNN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/ncnn/${ANDROID_ABI}")
add_library(ncnn SHARED IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION "${NCNN_ROOT}/lib/libncnn.so"
    INTERFACE_INCLUDE_DIRECTORIES "${NCNN_ROOT}/include/ncnn")
add_library(comiclab_superresolution SHARED superresolution.cpp)
target_link_libraries(comiclab_superresolution PRIVATE ncnn jnigraphics android log)
target_link_options(comiclab_superresolution PRIVATE "-Wl,-z,max-page-size=16384")
