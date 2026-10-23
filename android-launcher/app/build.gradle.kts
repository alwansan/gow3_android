plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Steam sign-in (SteamDllDialog.java) needs JavaSteam and its dependencies, fetched by
// tools/android/inputs/fetch_steam_libs.sh into .local-deps/android/steam-libs. When the
// libraries are absent (e.g. a plain Android Studio build before the fetcher has run) the
// src/steamStub/java fallback is compiled instead and GET FROM STEAM explains the build lacks
// Steam downloads. Once jars.txt exists the real implementation in src/steam/java is used and
// the jars and zstd JNI are packaged into the APK.
val steamLibs = rootProject.file("../.local-deps/android/steam-libs")
val steamAvailable = steamLibs.resolve("jars.txt").isFile

android {
    namespace = "com.gow3.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.gow3.android.bionic"
        minSdk = 28
        targetSdk = 28
        versionCode = 2010704
        versionName = "2.1.7-bionic-v4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 静态 libc++：launcher 不带 libc++_shared.so，不为两个小库多塞一份运行时。
        // NDK 与 runtime 工具链（tools/android/build_bionic.sh）保持同一个版本。
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                abiFilters += "arm64-v8a"
            }
        }
    }

    // launcher 自己的 JNI 桥库（libgow3present / libgow3prepare）由 CMake 在 APK
    // 构建中直接产出：src/main/cpp 是 Android Studio 原生识别的目录，AS 的 Run 与
    // 命令行 gradlew 走同一条 native 构建路径，不再有脚本旁路与过期 .so。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "29.0.14206865"

    buildTypes {
        debug {
            // debug 与 release 共存：包名加 .debug 后缀（RuntimeInstaller 校验时容忍该后缀）。
            applicationIdSuffix = ".debug"
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        // targetSdk 28 是本移植的硬性运行约束（Android 不变量）；它是
        // 侧载的游戏 APK，而非 Play Store 提交。
        disable += "ExpiredTargetSdkVersion"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    sourceSets {
        getByName("main") {
            // 页面与运行时共用按 ID 的 XML 目录，不维护第二份补丁名单。
            assets.srcDir("../../patches")
            // Steam real implementation vs. no-Steam fallback, gated on the fetched libraries.
            java.srcDir(if (steamAvailable) "src/steam/java" else "src/steamStub/java")
            if (steamAvailable) {
                jniLibs.srcDir(steamLibs.resolve("jni"))
            }
        }
        getByName("debug") {
            assets.srcDir("../../out/android-debug/assets")
        }
        getByName("release") {
            assets.srcDir("../../out/android-release/assets")
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("gow3.patchCatalogDir", rootProject.file("../patches").absolutePath)
}

// 调试运行时 payload 不是源文件：它由 CLI 工具生成（build_bionic.sh → out/bionic），
// Gradle 无法构建它。在任何 debug 构建之前（幂等地）暂存它，以便 Android Studio
// 一键构建携带匹配的无游戏运行时；指纹未变时不重写 payload（内容感知复制）。
val stageDebugRuntime by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir.parentFile
    commandLine("bash", "tools/android/debug/stage_bionic_runtime.sh")
    // 没有可靠的 up-to-date 输入：总是重新执行（指纹命中时只做一次 sha256 对比，很快）。
    outputs.upToDateWhen { false }
}
tasks.matching { it.name == "preDebugBuild" }.configureEach {
    dependsOn(stageDebugRuntime)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    // 本地单测（无 Robolectric）需要真实的 org.json 实现：android.jar 里的 org.json
    // 是会抛 "Stub!" 的桩。仅测试 classpath 使用，不进 APK。
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // JavaSteam and its dependencies when the Steam libraries are present; see steamAvailable above.
    if (steamAvailable) {
        // Drop the jars the Kotlin/AGP plugins already put on the compile/runtime classpath: D8's
        // duplicate-class check would otherwise fail. The platforms' kotlin-stdlib, kotlinx-coroutines
        // and kotlinx-serialization are binary-compatible with the versions JavaSteam was built
        // against. kotlin-stdlib-jdk7/jdk8 stay: nothing else provides them.
        implementation(fileTree(steamLibs.resolve("jars")) {
            include("*.jar")
            exclude("annotations-*.jar")
            exclude {
                it.name.startsWith("kotlinx-coroutines-core-jvm-") ||
                    it.name.startsWith("kotlinx-serialization-core-jvm-") ||
                    (it.name.startsWith("kotlin-stdlib-") && !it.name.startsWith("kotlin-stdlib-jdk"))
            }
        })
    }
}
