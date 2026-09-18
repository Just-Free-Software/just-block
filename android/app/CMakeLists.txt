cmake_minimum_required(VERSION 3.22.1)
project("justblock_rust_jni")

get_filename_component(RUST_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../rust" ABSOLUTE)

if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(RUST_TARGET "aarch64-linux-android")
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(RUST_TARGET "armv7-linux-androideabi")
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    set(RUST_TARGET "x86_64-linux-android")
elseif(${ANDROID_ABI} STREQUAL "x86")
    set(RUST_TARGET "i686-linux-android")
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

set(RUST_SO_FILE "${RUST_DIR}/target/${RUST_TARGET}/release/libjust_block_rust.so")

# DNS trace logging (FORWARD/BLOCKED domain names) is debug-only.
# Release .so must never log queried domains.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(RUST_CARGO_FEATURES "--features" "dns-trace")
else()
    set(RUST_CARGO_FEATURES "")
endif()

add_custom_target(cargo_build_always ALL
    COMMAND ${CMAKE_COMMAND} -E env ANDROID_NDK_HOME=${CMAKE_ANDROID_NDK} PATH=$ENV{PATH}:/home/me/.cargo/bin cargo ndk -t ${ANDROID_ABI} build --release ${RUST_CARGO_FEATURES}
    WORKING_DIRECTORY ${RUST_DIR}
    COMMENT "Building Rust library for ABI ${ANDROID_ABI}"
)

# Dummy command to satisfy IMPORTED_LOCATION requirement if needed
add_custom_command(
    OUTPUT ${RUST_SO_FILE}
    COMMAND ${CMAKE_COMMAND} -E touch ${RUST_SO_FILE}
)

add_custom_target(cargo_build ALL DEPENDS ${RUST_SO_FILE})
add_dependencies(cargo_build cargo_build_always)

add_library(just_block_rust SHARED IMPORTED)
set_target_properties(just_block_rust PROPERTIES IMPORTED_LOCATION ${RUST_SO_FILE})

add_library(dummy SHARED dummy.cpp)
target_link_libraries(dummy just_block_rust)
add_dependencies(dummy cargo_build)
