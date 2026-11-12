import java.security.MessageDigest
import java.io.DataOutputStream
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
        check(model["type"] == "BPE" && model["dropout"] == null && model["unk_token"] == null)
        check(model["continuing_subword_prefix"] == null && model["end_of_word_suffix"] == null)
        check(model["byte_fallback"] == false && model["fuse_unk"] == false)
        val normalizer = tokenizer["normalizer"] as Map<*, *>
        check(normalizer["type"] == "Sequence" && (normalizer["normalizers"] as List<*>).isEmpty())
        val preTokenizer = tokenizer["pre_tokenizer"] as Map<*, *>
        check(preTokenizer["type"] == "Sequence")
        val steps = preTokenizer["pretokenizers"] as List<*>
        val byteLevel = steps.last() as Map<*, *>
        check(byteLevel["type"] == "ByteLevel" && byteLevel["add_prefix_space"] == false && byteLevel["use_regex"] == false)
        val patterns = steps.dropLast(1).map { step ->
            val split = step as Map<*, *>
            check(split["type"] == "Split" && split["behavior"] == "Isolated" && split["invert"] == false)
            (split["pattern"] as Map<*, *>)["Regex"] as String
        }
        fun tokenId(token: String) = (vocabulary[token] as? Number)?.toInt()
            ?: error("Missing BPE token: $token")
        // GPT-2 ByteLevel alphabet: printable bytes retain their code point, the rest use U+0100 onward.
        var extra = 256
        val byteTokens = IntArray(256) { byte ->
            val codePoint = if (byte in 33..126 || byte in 161..172 || byte in 174..255) byte else extra++
            tokenId(codePoint.toChar().toString())
        }
        val merges = (model["merges"] as List<*>).mapIndexed { rank, entry ->
            val pair = (entry as String).split(' ')
            check(pair.size == 2)
            val key = (tokenId(pair[0]).toLong() shl 32) or tokenId(pair[1]).toLong()
            Triple(key, rank, tokenId(pair[0] + pair[1]))
        }.sortedBy { it.first }
        check(merges.zipWithNext().all { (a, b) -> a.first < b.first })
        val addedTokens = (tokenizer["added_tokens"] as List<*>).map { entry ->
            val token = entry as Map<*, *>
            check(token["single_word"] == false && token["lstrip"] == false && token["rstrip"] == false)
            token["content"] as String
        }
        // Compile all counting rules into primitive tables. No Python or tokenizer library is needed on Android.
        val output = outputDirectory.file("deepseek-v4.bin").get().asFile
        output.parentFile.mkdirs()
        DataOutputStream(output.outputStream().buffered()).use { data ->
            data.writeInt(0x44535634) // DSV4, kept in sync with DeepSeekBpeEncoder.read.
            data.writeInt(1)
            data.writeInt(patterns.size)
            patterns.forEach(data::writeUTF)
            byteTokens.forEach(data::writeInt)
            data.writeInt(merges.size)
            merges.forEach { (pair, rank, merged) ->
                data.writeLong(pair)
                data.writeInt(rank)
                data.writeInt(merged)
            }
            data.writeInt(addedTokens.size)
            addedTokens.forEach(data::writeUTF)
        }
    }
}

val generateTokenizerAssets by tasks.registering(GenerateTokenizerAssets::class) {
    tokenizerFile.set(rootProject.layout.projectDirectory.file("deepseek_v4_tokenizer/tokenizer.json"))
    outputDirectory.set(layout.buildDirectory.dir("generated/deepseekV4Assets"))
}

tasks.withType<Test>().configureEach {
    val tokenizerAsset = generateTokenizerAssets.flatMap { it.outputDirectory.file("deepseek-v4.bin") }
    dependsOn(generateTokenizerAssets)
    inputs.file(tokenizerAsset)
    systemProperty("lyra.tokenizerAsset", tokenizerAsset.get().asFile.absolutePath)
}

// Ship one universal APK for now; per-ABI outputs remain available on demand.
val enableAbiSplits = providers.gradleProperty("lyra.abiSplits").orNull?.toBooleanStrict() ?: false
val packagedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

val bundledProotRuntimeDir = layout.projectDirectory.dir("src/debianRuntime/jniLibs")
val bundledProotRuntimeHashes = mapOf(
    "arm64-v8a/libproot_exec.so" to "8cd986e8e9505f20ef263095037d59ece8756b230e5730315dc142332a8032e2",
    "arm64-v8a/libproot_loader.so" to "825d0362db3319c2b3ed3b75fe300287c0f9eeb7f2427ceb05a4a004ad33ed12",
    "x86_64/libproot_exec.so" to "e3c6a7bde6a8e4851b2ddc36965b5a6c3fe71b5e630731aa7869e046cce82a72",
    "x86_64/libproot_loader.so" to "61b2dd1a858caddbab4647c519cfe0c23bbd2d47981358b9cc6ae818e79e6869",
)

android {
    namespace = "com.yukisoffd.lyracode"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yukisoffd.lyracode"
        minSdk = 26
        targetSdk = 37
        versionCode = 91
        versionName = "4.2.2"

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
        buildConfigField(
            "String",
            "LYRA_DEVELOPER_WALL_URL",
            groovy.json.JsonOutput.toJson(providers.gradleProperty("lyra.developerWallUrl").orNull.orEmpty()),
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
        getByName("androidTest") {
            assets.directories.add("src/test/resources")
        }
        getByName("main") {
            kotlin.directories.add(rootProject.file("lyra_x11/host/java").absolutePath)
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
    implementation(project(":lyra_x11"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.browser)
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

