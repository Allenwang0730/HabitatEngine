plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.ailun.habitat"
    compileSdk = 36
    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    // JVM 单测里 android.util.Log 等桩方法返回默认值而非抛 "not mocked"
    testOptions { unitTests.isReturnDefaultValues = true }
}

// 独立发布：./gradlew :lib:publishReleasePublicationToMavenLocal → mavenLocal 出一个 habitat-engine AAR
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                // JitPack 构建时（JITPACK=true）坐标为 com.github.Allenwang0730:HabitatEngine:<tag>
                val onJitPack = System.getenv("JITPACK") == "true"
                groupId = if (onJitPack) System.getenv("GROUP") ?: "com.github.Allenwang0730" else "com.ailun"
                artifactId = if (onJitPack) System.getenv("ARTIFACT") ?: "HabitatEngine" else "habitat-engine"
                version = if (onJitPack) System.getenv("VERSION") ?: "1.8.0" else "1.8.0"
            }
        }
    }
}

dependencies {
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
