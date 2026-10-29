import java.security.MessageDigest
import groovy.json.JsonGenerator
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

@CacheableTask
abstract class GenerateTokenizerAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tokenizerFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val tokenizer = JsonSlurper().parse(tokenizerFile.get().asFile) as Map<*, *>
        val model = tokenizer["model"] as Map<*, *>
        val vocabulary = model["vocab"] as Map<*, *>
        check(vocabulary.isNotEmpty()) { "The bundled tokenizer vocabulary is empty" }
        // DeepSeekV3Tokenizer reads only model.vocab. Keep the complete upstream file in
        // the repository, but omit unused merges, metadata and Python files from the APK.
        val output = outputDirectory.file("tokenizer.json").get().asFile
        output.parentFile.mkdirs()
        val json = JsonGenerator.Options().disableUnicodeEscaping().build()
        output.writeText(json.toJson(mapOf("model" to mapOf("vocab" to vocabulary))), Charsets.UTF_8)
    }
}

val generateTokenizerAssets by tasks.registering(GenerateTokenizerAssets::class) {
    tokenizerFile.set(rootProject.layout.projectDirectory.file("deepseek_v3_tokenizer/deepseek_v3_tokenizer/tokenizer.json"))
    outputDirectory.set(layout.buildDirectory.dir("generated/tokenizerAssets"))
}

// Ship one universal APK for now; per-ABI outputs remain available on demand.
val enableAbiSplits = providers.gradleProperty("lyra.abiSplits").orNull?.toBooleanStrict() ?: false
val packagedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

val bundledProotRuntimeDir = layout.projectDirectory.dir("src/debianRuntime/jniLibs")
val bundledProotRuntimeHashes = mapOf(
    "arm64-v8a/libproot_exec.so" to "be2fbd3a0e88c0d159ef6cd044392720ea3f0bc1ee524bf67879d49f5e9b9eb4",
    "arm64-v8a/libproot_loader.so" to "825d0362db3319c2b3ed3b75fe300287c0f9eeb7f2427ceb05a4a004ad33ed12",
    "x86_64/libproot_exec.so" to "5f4dc068cb80fa23713573f14105d597c225e8d56ace3b4d0a77c87824710dad",
    "x86_64/libproot_loader.so" to "61b2dd1a858caddbab4647c519cfe0c23bbd2d47981358b9cc6ae818e79e6869",
)

android {
    namespace = "com.yukisoffd.lyracode"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yukisoffd.lyracode"
        minSdk = 26
        targetSdk = 37
        versionCode = 90
        versionName = "4.2.1"

        if (!enableAbiSplits) {
            ndk {
                abiFilters += packagedAbis
            }
        }

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
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    splits {
        abi {
            isEnable = enableAbiSplits
            reset()
            include(*packagedAbis.toTypedArray())
            isUniversalApk = providers.gradleProperty("lyra.universalApk").orNull?.toBooleanStrict() ?:true
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
            // The JavaMail and activation providers share these service registries.
            merges += "META-INF/javamail.*"
            merges += "META-INF/mailcap"
        }
        jniLibs.useLegacyPackaging = true
    }
    sourceSets {
        getByName("main") {
            kotlin.directories.add("src/debianRuntime/java")
            res.directories.add("src/debianRuntime/res")
            jniLibs.directories.add("src/debianRuntime/jniLibs")
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateTokenizerAssets,
            GenerateTokenizerAssets::outputDirectory,
        )
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
    implementation(libs.android.svg)
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
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

