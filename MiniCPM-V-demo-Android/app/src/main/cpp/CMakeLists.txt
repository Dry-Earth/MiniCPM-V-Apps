cmake_minimum_required(VERSION 3.22.1)

project("minicpm_v_demo" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    elseif(ANDROID_ABI STREQUAL "x86")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(WARNING "Unsupported ABI: ${ANDROID_ABI}, defaulting to generic CPU")
        set(GGML_SYSTEM_ARCH "")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    endif()
endif()

# llama.cpp-omni source lives at the repository root so it can be shared with the iOS demo.
# Allow override via -DLLAMA_SRC=... if you keep llama.cpp-omni elsewhere.
if(NOT DEFINED LLAMA_SRC)
    set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../llama.cpp-omni)
endif()
get_filename_component(LLAMA_SRC ${LLAMA_SRC} ABSOLUTE)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp-omni not found at ${LLAMA_SRC}. "
        "Run `git submodule update --init` from the repository root.")
endif()
message(STATUS "Using llama.cpp-omni source at: ${LLAMA_SRC}")

if(DEFINED MINICPMV_VULKAN_BUILD AND MINICPMV_VULKAN_BUILD)
    message(STATUS "Vulkan build: enabling the 1.1 entry point shim")
endif()

# GPU acceleration is off: the vision tower runs on the Hexagon NPU when it can
# and on the CPU otherwise, so Vulkan would only add a third path and 41 MB of
# vendored headers nobody can rebuild from this tree.

add_subdirectory(${LLAMA_SRC} ${CMAKE_BINARY_DIR}/build-llama)

if(NOT DEFINED LLAMA_INSTALL_VERSION)
    set(LLAMA_INSTALL_VERSION "0.0.0")
endif()

# The Hexagon vision path needs Qualcomm's QAIRT 2.51.200 headers, which are
# not redistributable and therefore not in this repo. Detect them: present ->
# build the real NPU tower; absent -> link the stub, OCR silently stays on the
# ggml CPU tower. Override with -DMINICPM_NO_QNN=ON/OFF if needed.
option(MINICPM_NO_QNN "Force the QNN stub even when qnn-include/ exists" OFF)
if(MINICPM_NO_QNN OR NOT EXISTS ${CMAKE_CURRENT_LIST_DIR}/qnn-include)
    message(STATUS "QNN headers not available -> building the stub NPU path")
    set(MINICPM_QNN_SOURCES qnn_vit_stub.cpp)
else()
    message(STATUS "QNN headers found -> building the Hexagon vision path")
    set(MINICPM_QNN_SOURCES qnn_vit.cpp qnn_vit_jni.cpp)
endif()

# ggml-vulkan is its own shared object (BUILD_SHARED_LIBS=ON), so the stub's
# missing Vulkan 1.1 symbol has to be satisfied when *that* library links, not
# in the app's own .so. Inject the forwarder as a static helper.
if(MINICPMV_VULKAN_BUILD AND TARGET ggml-vulkan)
    message(STATUS "Vulkan build: linking the 1.1 entry point shim into ggml-vulkan")
    add_library(vulkan_api_shim STATIC vulkan_api_shim.c)
    set_target_properties(vulkan_api_shim PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_compile_definitions(vulkan_api_shim PRIVATE MINICPMV_VULKAN_SHIM=1)
    target_link_libraries(ggml-vulkan PRIVATE vulkan_api_shim)
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED
        llama_jni.cpp
        omni_jni.cpp
        ${MINICPM_QNN_SOURCES})

# Only compiled into Vulkan builds: it exists to satisfy a link-time reference
# from ggml-vulkan that the Android stub library cannot.
target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        MINICPMV_VULKAN_SHIM=$<BOOL:${MINICPMV_VULKAN_BUILD}>
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src
        ${LLAMA_SRC}/tools/mtmd
        ${LLAMA_SRC}/tools/omni
        ${LLAMA_SRC}/tools/omni/voxcpm2
        # llama-common/peg-parser pulls in <nlohmann/json_fwd.hpp> via chat.h.
        # The vendored nlohmann headers live under llama.cpp-omni/vendor.
        ${LLAMA_SRC}/vendor
        # Qualcomm QNN headers, copied from the QAIRT 2.51.200 SDK. They are
        # marked "Confidential and Proprietary" and stay out of git (see
        # .gitignore), so this directory only exists on dev machines.
        # Both the parent and QNN/ are on the path: QnnSystemInterface.h pulls
        # in "System/QnnSystemCommon.h" relative to the QNN/ directory.
        ${CMAKE_CURRENT_LIST_DIR}/qnn-include
        ${CMAKE_CURRENT_LIST_DIR}/qnn-include/QNN)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        mtmd
        voxcpm2_runtime
        android
        log)
