import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val bundledProotRuntimeDir = layout.projectDirectory.dir("src/debianRuntime/jniLibs")
val bundledProotRuntimeHashes = mapOf(
    "arm64-v8a/libproot_exec.so" to "a0628a09b064d60a2281400224b19ac05b22619ab2aeb85e6d946b05b1412238",
    "arm64-v8a/libproot_loader.so" to "39f8d98f345bd2f0cff53b6a9ee54418cec340f4738d6bdd2fb03112654a6183",
    "x86_64/libproot_exec.so" to "c5d89b620e7afc3386ecfc3addfeaaaf5090d0a5170d48a418d2e856329aa6b2",
    "x86_64/libproot_loader.so" to "61b2dd1a858caddbab4647c519cfe0c23bbd2d47981358b9cc6ae818e79e6869",
)

android {
    namespace = "com.yukisoffd.lyracode"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yukisoffd.lyracode"
        minSdk = 26
        targetSdk = 37
        versionCode = 78
        versionName = "4.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "LYRA_UPDATE_MANIFEST_URL",
            "\"${providers.gradleProperty("lyra.updateManifestUrl").orNull.orEmpty()}\"",
        )
    }

    signingConfigs {
        getByName("debug") {
            enableV2Signing = true
            enableV3Signing = true
        }
        create("release") {
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("beta") {
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta.2"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    packaging {
        resources {
            merges += "META-INF/LICENSE.md"
            merges += "META-INF/NOTICE.md"
        }
        jniLibs.useLegacyPackaging = true
    }
    sourceSets {
        getByName("main") {
            assets.directories.add(rootProject.file("deepseek_v3_tokenizer/deepseek_v3_tokenizer").absolutePath)
            kotlin.directories.add("src/debianRuntime/java")
            res.directories.add("src/debianRuntime/res")
            jniLibs.directories.add("src/debianRuntime/jniLibs")
        }
    }
}

val verifyBundledProotRuntime by tasks.registering {
    group = "verification"
    description = "Verifies the repository-local ARM64 and x86_64 PRoot binaries without network access."
    inputs.files(bundledProotRuntimeHashes.keys.map { bundledProotRuntimeDir.file(it) })

    doLast {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        bundledProotRuntimeHashes.forEach { (name, expected) ->
            val binary = bundledProotRuntimeDir.file(name).asFile
            check(binary.isFile) { "Bundled PRoot binary is missing: ${binary.absolutePath}" }
            val actual = sha256(binary)
            check(actual == expected) {
                "Bundled PRoot SHA-256 mismatch for $name: expected $expected, got $actual"
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyBundledProotRuntime) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.jsch)
    implementation(project(":jlatexmath"))
    implementation(libs.jetbrains.markdown)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.sora.editor.bom))
    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

