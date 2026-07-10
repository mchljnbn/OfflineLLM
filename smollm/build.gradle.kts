plugins {
    alias(libs.plugins.android.library)
}

val sdkDir: String = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.readLines()
    ?.firstOrNull { it.startsWith("sdk.dir=") }
    ?.removePrefix("sdk.dir=")
    ?.trim()
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: ""

// Host toolchain for llama.cpp's vulkan-shaders-gen, which must run on the build
// machine (not Android). Mirrors upstream's host-toolchain.cmake.in but also pins
// CMAKE_MAKE_PROGRAM to the SDK's ninja when present, since the host may have no
// ninja on PATH and the inner ExternalProject configure fails without one.
val vulkanHostToolchain: File =
    layout.buildDirectory.file("vulkan-host-toolchain.cmake").get().asFile.apply {
        parentFile.mkdirs()
        val sdkNinja = file("$sdkDir/cmake/3.22.1/bin/ninja")
        val makeProgramLine =
            if (sdkDir.isNotEmpty() && sdkNinja.exists())
                "\nset(CMAKE_MAKE_PROGRAM \"${sdkNinja.absolutePath}\" CACHE FILEPATH \"\")"
            else ""
        writeText(
            """
            set(CMAKE_BUILD_TYPE Release)
            set(CMAKE_C_FLAGS -O2)
            set(CMAKE_CXX_FLAGS -O2)
            set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
            set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)
            set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)
            set(CMAKE_C_COMPILER /usr/bin/gcc)
            set(CMAKE_CXX_COMPILER /usr/bin/g++)
            """.trimIndent() + makeProgramLine
        )
    }

android {
    namespace = "com.jegly.offlineLLM.smollm"
    compileSdk = 37
    ndkVersion = "27.2.12479018"
    namespace = "com.jegly.offlineLLM.smollm"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig
    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-O3")
                // ggml-vulkan includes <spirv/unified1/spirv.hpp> assuming the Vulkan SDK
                // layout where spirv headers sit beside the vulkan ones; with our split
                // Khronos checkouts the SPIRV include dir must be added explicitly.
                run {
                    val spirvInclude = rootProject.file("../SPIRV-Headers/include")
                    if (spirvInclude.exists()) {
                        cppFlags += "-isystem${spirvInclude.absolutePath}"
                    }
                }
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                // common drags in HTTP/download code and is no longer needed —
                // the JNI wrapper uses the llama.h C API directly.
                arguments += "-DLLAMA_BUILD_COMMON=OFF"
                arguments += "-DLLAMA_CURL=OFF"
                // Vulkan GPU backend (opt-in at runtime via Settings; devices without
                // Vulkan 1.2 fail registration gracefully and stay CPU-only).
                arguments += "-DGGML_VULKAN=ON"
                // Backends as runtime-loadable plugins, with one ggml-cpu variant per
                // ARM feature level (dotprod/fp16/i8mm/SVE...). ggml scores the variants
                // against the device CPU at load time and picks the best — this is what
                // actually enables the fast quantized-matmul kernels on modern SoCs.
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                if (sdkDir.isNotEmpty()) {
                    // Only pin ninja when this exact cmake version exists — hosts like
                    // F-Droid's buildserver ship a different one and AGP falls back fine.
                    val ninja = file("$sdkDir/cmake/3.22.1/bin/ninja")
                    if (ninja.exists()) {
                        arguments += "-DCMAKE_MAKE_PROGRAM=${ninja.absolutePath}"
                    }
                    // glslc ships with the NDK; FindVulkan won't discover it in the
                    // cross-compile sysroot on its own.
                    arguments += "-DVulkan_GLSLC_EXECUTABLE=$sdkDir/ndk/27.2.12479018/shader-tools/linux-x86_64/glslc"
                    arguments += "-DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN=${vulkanHostToolchain.absolutePath}"
                    // The NDK sysroot has only the Vulkan C headers; ggml-vulkan.cpp
                    // needs Vulkan-Hpp (vulkan.hpp). Point FindVulkan at a checkout of
                    // KhronosGroup/Vulkan-Headers, whose include dir carries a
                    // self-consistent set of both C and C++ headers.
                    val vulkanHeaders = rootProject.file("../Vulkan-Headers/include")
                    if (vulkanHeaders.exists()) {
                        arguments += "-DVulkan_INCLUDE_DIR=${vulkanHeaders.absolutePath}"
                    } else {
                        logger.warn(
                            "Vulkan-Headers not found at ${vulkanHeaders.absolutePath} — " +
                            "ggml-vulkan will fail to compile. Clone " +
                            "https://github.com/KhronosGroup/Vulkan-Headers.git next to the project."
                        )
                    }
                    // SPIRV-Headers CMake package (header-only; installed locally from a
                    // clone of KhronosGroup/SPIRV-Headers via `cmake --install`).
                    val spirvHeaders = rootProject.file("../SPIRV-Headers/install")
                    if (spirvHeaders.exists()) {
                        arguments += "-DSPIRV-Headers_DIR=${spirvHeaders.absolutePath}/share/cmake/SPIRV-Headers"
                    } else {
                        logger.warn(
                            "SPIRV-Headers install not found at ${spirvHeaders.absolutePath} — " +
                            "ggml-vulkan will fail to configure."
                        )
                    }
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.coroutines.core)
}
