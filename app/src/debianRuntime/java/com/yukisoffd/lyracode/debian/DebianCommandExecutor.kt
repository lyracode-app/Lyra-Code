package com.yukisoffd.lyracode.debian

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

internal class DebianCommandExecutor(context: Context) {
    private val appContext = context.applicationContext
    private val runtime = DebianRuntimeManager.getInstance(appContext)
    private val rootfsDir get() = runtime.rootfsDir
    private val tempDir = File(appContext.cacheDir, "debian-proot")

    fun isAvailable(): Boolean = runtime.isInstalledAndEnabled()

    suspend fun execute(
        command: String,
        workspaceRoot: String?,
        workDir: String?,
        timeoutSeconds: Int,
    ): String = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "debian_command requires a non-empty command." }
        val workspace = resolveWorkspace(workspaceRoot, workDir)
        runtime.requireInstalled()
        tempDir.mkdirs()
        patchRootfs()

        val proot = runtime.prootFile()
        val loader = runtime.loaderFile()

        val process = ProcessBuilder(buildCommand(proot, workspace, command))
            .directory(appContext.filesDir)
            .redirectErrorStream(false)
            .apply {
                environment().remove("LD_PRELOAD")
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = tempDir.absolutePath
                environment()["TMPDIR"] = tempDir.absolutePath
            }
            .start()
        process.outputStream.close()

        val readers = Executors.newFixedThreadPool(2)
        val stdoutFuture = readers.submit<CapturedOutput> { capture(process.inputStream) }
        val stderrFuture = readers.submit<CapturedOutput> { capture(process.errorStream) }
        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        val stdout = runCatching { stdoutFuture.get(5, TimeUnit.SECONDS) }
            .getOrElse { CapturedOutput("", 0, false) }
        val stderr = runCatching { stderrFuture.get(5, TimeUnit.SECONDS) }
            .getOrElse { CapturedOutput("", 0, false) }
        readers.shutdownNow()
        if (!finished) {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }

        JSONObject()
            .put("exit_code", if (finished) process.exitValue() else 124)
            .put("stdout", stdout.text)
            .put("stderr", stderr.text)
            .put("stdout_original_bytes", stdout.originalBytes)
            .put("stderr_original_bytes", stderr.originalBytes)
            .put("stdout_truncated", stdout.truncated)
            .put("stderr_truncated", stderr.truncated)
            .put("timed_out", !finished)
            .put("environment", "internal-proot-debian-trixie")
            .put("work_dir", workspace.guestWorkDir)
            .toString()
    }

    private fun patchRootfs() {
        File(rootfsDir, "root").mkdirs()
        listOf("tmp", "var/tmp", "dev", "proc", "sys", "workspace").forEach {
            File(rootfsDir, it).mkdirs()
        }
        runCatching { Os.chmod(File(rootfsDir, "tmp").absolutePath, 0x3ff) }
        runCatching { Os.chmod(File(rootfsDir, "var/tmp").absolutePath, 0x3ff) }
        File(rootfsDir, "etc/resolv.conf").writeTextIfMissing("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(rootfsDir, "etc/hosts").writeTextIfMissing("127.0.0.1 localhost lyra-debian\n::1 localhost\n")
        File(rootfsDir, "etc/hostname").writeTextIfMissing("lyra-debian\n")
    }

    private fun buildCommand(proot: File, workspace: DebianWorkspace, command: String): List<String> {
        val args = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            rootfsDir.absolutePath,
            "-w",
            workspace.guestWorkDir,
            "-b",
            "${workspace.hostRoot.absolutePath}:$WORKSPACE_DIR",
        )
        listOf("/dev", "/proc", "/sys", "/apex", "/system", "/system_ext", "/vendor").forEach { path ->
            if (File(path).exists()) {
                args += "-b"
                args += path
            }
        }
        args += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "/bin/bash",
            "-lc",
            command,
        )
        return args
    }

    private fun resolveWorkspace(workspaceRoot: String?, rawWorkDir: String?): DebianWorkspace {
        val rootText = workspaceRoot?.trim().orEmpty()
        require(rootText.isNotBlank()) {
            "debian_command requires a selected workspace with a direct shared-storage path."
        }
        val root = File(rootText).canonicalFile
        require(root.isDirectory) { "The selected workspace is not a directly accessible directory: $rootText" }

        val raw = rawWorkDir.orEmpty().trim().replace('\\', '/')
        val relative = when {
            raw.isBlank() || raw == "." || raw == "./" || raw == "/" -> ""
            raw.startsWith("/sdcard/") -> {
                val mapped = "/storage/emulated/0/${raw.removePrefix("/sdcard/")}"
                relativePathInside(root, File(mapped))
            }
            raw.startsWith("/") -> relativePathInside(root, File(raw))
            else -> raw.trim('/')
        }
        require(relative.split('/').none { it == ".." }) { "debian_command workDir must stay inside the workspace." }
        val hostWorkDir = File(root, relative).canonicalFile
        require(isInside(root, hostWorkDir)) { "debian_command workDir must stay inside the workspace." }
        require(hostWorkDir.isDirectory) { "debian_command workDir is not a directory: ${hostWorkDir.absolutePath}" }
        val cleanRelative = root.toPath().relativize(hostWorkDir.toPath()).toString().replace('\\', '/')
        val guest = if (cleanRelative.isBlank()) WORKSPACE_DIR else "$WORKSPACE_DIR/$cleanRelative"
        return DebianWorkspace(root, guest)
    }

    private fun relativePathInside(root: File, candidate: File): String {
        val canonical = candidate.canonicalFile
        require(isInside(root, canonical)) { "debian_command workDir must stay inside the workspace." }
        return root.toPath().relativize(canonical.toPath()).toString().replace('\\', '/')
    }

    private fun isInside(root: File, candidate: File): Boolean =
        candidate == root || candidate.absolutePath.startsWith(root.absolutePath + File.separator)

    private fun capture(input: InputStream): CapturedOutput {
        val visible = ByteArrayOutputStream(MAX_CAPTURE_BYTES)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        input.use {
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                val remaining = MAX_CAPTURE_BYTES - visible.size()
                if (remaining > 0) visible.write(buffer, 0, minOf(count, remaining))
                total += count
            }
        }
        return CapturedOutput(
            text = visible.toString(StandardCharsets.UTF_8.name()),
            originalBytes = total,
            truncated = total > MAX_CAPTURE_BYTES,
        )
    }

    private fun File.writeTextIfMissing(content: String) {
        if (!exists() && !Files.isSymbolicLink(toPath())) writeText(content)
    }

    private data class DebianWorkspace(val hostRoot: File, val guestWorkDir: String)
    private data class CapturedOutput(val text: String, val originalBytes: Long, val truncated: Boolean)

    private companion object {
        const val WORKSPACE_DIR = "/workspace"
        const val MAX_CAPTURE_BYTES = 256 * 1024
    }
}

internal enum class DebianRuntimePhase { UNSUPPORTED, NOT_INSTALLED, DOWNLOADING, INSTALLING, INSTALLED, ERROR }

internal data class DebianRuntimeState(
    val phase: DebianRuntimePhase,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String = "",
    val enabled: Boolean = false,
)

/**
 * Owns the downloadable Debian data component. The live rootfs is user data: once /bin/bash
 * exists it is never replaced because a seed version or metadata file changed.
 */
internal class DebianRuntimeManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val runtimeDir = File(appContext.filesDir, "debian-runtime")
    internal val rootfsDir = File(runtimeDir, "rootfs")
    private val stagingDir = File(runtimeDir, "rootfs-installing")
    private val architecture = ProotArchitecture.installed(
        File(appContext.applicationInfo.nativeLibraryDir), Build.SUPPORTED_ABIS.toList(),
    )
    private val rootfsSource = if (architecture == ProotArchitecture.X86_64) AMD64_ROOTFS else ARM64_ROOTFS
    private val archiveFile = File(appContext.cacheDir, "debian-rootfs/${rootfsSource.version}.tgz")
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val installMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()
    private val _state = MutableStateFlow(currentState())
    val state: StateFlow<DebianRuntimeState> = _state.asStateFlow()

    fun isSupported(): Boolean = architecture != null

    fun isInstalled(): Boolean = File(rootfsDir, "bin/bash").isFile

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, true)

    fun isInstalledAndEnabled(): Boolean = isSupported() && isInstalled() && isEnabled()

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        refresh()
    }

    fun refresh() {
        if (_state.value.phase !in setOf(DebianRuntimePhase.DOWNLOADING, DebianRuntimePhase.INSTALLING)) {
            _state.value = currentState()
        }
    }

    fun requireInstalled() {
        check(isSupported()) { "The internal Debian runtime requires arm64-v8a or x86_64 native libraries." }
        check(isInstalled()) { "Internal Debian is not installed. Open Settings > Debian runtime to install it." }
        check(ProotArchitecture.readElfMachine(File(rootfsDir, "bin/bash")) == architecture?.elfMachine) {
            "The installed Debian rootfs does not match this device (${architecture?.rootfsName})."
        }
        check(isEnabled()) { "Internal Debian is disabled in Settings." }
    }

    /** Starts a persistent interactive Bash behind Debian's `script` PTY helper. */
    fun startInteractiveShell(workspaceRoot: String?, columns: Int, rows: Int): Process {
        requireInstalled()
        prepareRuntimeDirectories()
        val workspace = workspaceRoot?.takeIf { it.isNotBlank() }?.let(::File)?.canonicalFile
            ?.takeIf { it.isDirectory }
        val workDir = if (workspace != null) "/workspace" else "/root"
        val args = mutableListOf(
            prootFile().absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            rootfsDir.absolutePath,
            "-w",
            workDir,
        )
        if (workspace != null) args += listOf("-b", "${workspace.absolutePath}:/workspace")
        listOf("/dev", "/proc", "/sys", "/apex", "/system", "/system_ext", "/vendor").forEach { path ->
            if (File(path).exists()) args += listOf("-b", path)
        }
        args += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "/usr/bin/script",
            "-qefc",
            "stty cols ${columns.coerceIn(2, 500)} rows ${rows.coerceIn(2, 300)} 2>/dev/null; exec /bin/bash -l",
            "/dev/null",
        )
        val processTemp = File(appContext.cacheDir, "debian-proot").apply { mkdirs() }
        return ProcessBuilder(args)
            .directory(appContext.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment().remove("LD_PRELOAD")
                environment()["PROOT_LOADER"] = loaderFile().absolutePath
                environment()["PROOT_TMP_DIR"] = processTemp.absolutePath
                environment()["TMPDIR"] = processTemp.absolutePath
            }
            .start()
    }

    suspend fun install() = withContext(Dispatchers.IO) {
        installMutex.withLock {
            if (isInstalled()) {
                setEnabled(true)
                _state.value = currentState()
                return@withLock
            }
            check(isSupported()) { "The internal Debian runtime requires arm64-v8a or x86_64 native libraries." }
            try {
                downloadArchive()
                _state.value = DebianRuntimeState(DebianRuntimePhase.INSTALLING)
                runtimeDir.mkdirs()
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                check(stagingDir.mkdirs()) { "Unable to create the Debian installation staging directory." }
                GZIPInputStream(archiveFile.inputStream().buffered()).use { archive ->
                    TarExtractor.extract(archive, stagingDir)
                }
                check(File(stagingDir, "bin/bash").isFile) { "The downloaded Debian rootfs does not contain /bin/bash." }
                check(ProotArchitecture.readElfMachine(File(stagingDir, "bin/bash")) == architecture?.elfMachine) {
                    "The downloaded Debian rootfs does not match this device."
                }
                File(stagingDir, INSTALL_MARKER).writeText(rootfsSource.version)
                // rootfsDir cannot exist here unless another/older process installed it. Never
                // delete such a directory: it may already contain a configured user environment.
                if (rootfsDir.exists()) {
                    check(File(rootfsDir, "bin/bash").isFile) { "A non-empty Debian rootfs path already exists; refusing to overwrite it." }
                    stagingDir.deleteRecursively()
                } else {
                    check(stagingDir.renameTo(rootfsDir)) { "Unable to activate the extracted Debian rootfs." }
                }
                preferences.edit().putBoolean(KEY_ENABLED, true).apply()
                archiveFile.delete()
                _state.value = currentState()
            } catch (error: Throwable) {
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                _state.value = DebianRuntimeState(DebianRuntimePhase.ERROR, error = error.message ?: error.javaClass.simpleName)
                throw error
            }
        }
    }

    internal fun prootFile(): File = File(appContext.applicationInfo.nativeLibraryDir, PROOT_EXECUTABLE).also {
        check(it.isFile) { "Bundled PRoot executable is missing for this device ABI: ${it.absolutePath}" }
    }

    internal fun loaderFile(): File = File(appContext.applicationInfo.nativeLibraryDir, PROOT_LOADER).also {
        check(it.isFile) { "Bundled PRoot loader is missing for this device ABI: ${it.absolutePath}" }
    }

    private fun prepareRuntimeDirectories() {
        File(rootfsDir, "root").mkdirs()
        listOf("tmp", "var/tmp", "dev", "proc", "sys", "workspace").forEach { File(rootfsDir, it).mkdirs() }
        runCatching { Os.chmod(File(rootfsDir, "tmp").absolutePath, 0x3ff) }
        runCatching { Os.chmod(File(rootfsDir, "var/tmp").absolutePath, 0x3ff) }
    }

    private fun currentState(): DebianRuntimeState = when {
        !isSupported() -> DebianRuntimeState(DebianRuntimePhase.UNSUPPORTED)
        isInstalled() -> DebianRuntimeState(DebianRuntimePhase.INSTALLED, enabled = isEnabled())
        else -> DebianRuntimeState(DebianRuntimePhase.NOT_INSTALLED)
    }

    private fun downloadArchive() {
        archiveFile.parentFile?.mkdirs()
        val part = File(archiveFile.parentFile, "${archiveFile.name}.part")
        if (archiveFile.isFile && sha256(archiveFile) == rootfsSource.sha256) return
        part.delete()
        val request = Request.Builder().url(rootfsSource.url).header("User-Agent", "LyraCode-Android").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Debian download failed: HTTP ${response.code}" }
            val body = response.body ?: error("Debian download returned an empty response.")
            val total = body.contentLength().coerceAtLeast(0L)
            var downloaded = 0L
            body.byteStream().use { input ->
                FileOutputStream(part).buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        val percent = if (total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
                        _state.value = DebianRuntimeState(DebianRuntimePhase.DOWNLOADING, percent, downloaded, total)
                    }
                }
            }
        }
        val actual = sha256(part)
        check(actual == rootfsSource.sha256) { "Debian rootfs SHA-256 mismatch: expected ${rootfsSource.sha256}, got $actual" }
        if (archiveFile.exists()) archiveFile.delete()
        check(part.renameTo(archiveFile)) { "Unable to finalize the Debian rootfs download." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private data class RootfsSource(val url: String, val sha256: String, val version: String)
        private val ARM64_ROOTFS = RootfsSource(
            "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/14d91d295c23da6cc04d4bfe8b3d74a8a6c54e5c/trixie/oci/blobs/rootfs.tar.gz",
            "018e5aeb5455352b2e96f5c9cb604b5767162ec71fcd22ca9d02b088cdeaf49d",
            "trixie-arm64-018e5aeb5455352b",
        )
        private val AMD64_ROOTFS = RootfsSource(
            "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/bae6d64d90b4068b09ff9d8b564c2773ef5d8d83/trixie/oci/blobs/rootfs.tar.gz",
            "27ee9a8250487842a26b1ffa1215982ba9ae27010bce1997d52f9f8628578d17",
            "trixie-amd64-27ee9a8250487842",
        )
        private const val INSTALL_MARKER = ".lyra-rootfs-version"
        private const val PROOT_EXECUTABLE = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private const val PREFERENCES = "debian_runtime"
        private const val KEY_ENABLED = "enabled"

        @Volatile private var instance: DebianRuntimeManager? = null

        fun getInstance(context: Context): DebianRuntimeManager = instance ?: synchronized(this) {
            instance ?: DebianRuntimeManager(context).also { instance = it }
        }
    }
}

internal object TarExtractor {
    private const val BLOCK_SIZE = 512

    fun extract(input: InputStream, destination: File) {
        val root = destination.canonicalFile
        val directoryModes = mutableListOf<Pair<File, Int>>()
        val pendingHardLinks = mutableListOf<Triple<File, String, Int>>()
        var paxPath: String? = null
        var paxLinkPath: String? = null
        var longName: String? = null
        var longLink: String? = null
        val header = ByteArray(BLOCK_SIZE)

        while (readBlock(input, header)) {
            if (header.all { it == 0.toByte() }) break
            val checksum = tarOctal(header, 148, 8)
            val actualChecksum = header.indices.sumOf { if (it in 148..155) 32 else header[it].toInt() and 0xff }
            require(checksum == actualChecksum.toLong()) { "Invalid tar header checksum." }
            val headerName = tarString(header, 0, 100)
            val prefix = tarString(header, 345, 155)
            val archiveName = paxPath ?: longName ?: listOf(prefix, headerName).filter { it.isNotBlank() }.joinToString("/")
            val linkName = paxLinkPath ?: longLink ?: tarString(header, 157, 100)
            val mode = tarOctal(header, 100, 8).toInt()
            val size = tarOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            paxPath = null
            paxLinkPath = null
            longName = null
            longLink = null

            when (type) {
                'x', 'g' -> {
                    val values = parsePax(readPayload(input, size))
                    paxPath = values["path"]
                    paxLinkPath = values["linkpath"]
                }
                'L' -> longName = readPayload(input, size).toString(StandardCharsets.UTF_8).trimEnd('\u0000', '\n')
                'K' -> longLink = readPayload(input, size).toString(StandardCharsets.UTF_8).trimEnd('\u0000', '\n')
                else -> {
                    val target = safeTarget(root, archiveName, allowRoot = type == '5')
                    when (type) {
                        '5' -> {
                            target.mkdirs()
                            directoryModes += target to mode
                            skipPayload(input, size)
                        }
                        '2' -> {
                            prepareParent(root, target)
                            target.delete()
                            Os.symlink(linkName, target.absolutePath)
                            skipPayload(input, size)
                        }
                        '1' -> {
                            prepareParent(root, target)
                            pendingHardLinks += Triple(target, linkName, mode)
                            skipPayload(input, size)
                        }
                        '0', '\u0000' -> {
                            prepareParent(root, target)
                            target.delete()
                            FileOutputStream(target).buffered().use { output -> copyExact(input, output, size) }
                            skipPadding(input, size)
                            chmod(target, mode)
                        }
                        else -> skipPayload(input, size)
                    }
                }
            }
        }

        pendingHardLinks.forEach { (target, linkName, mode) ->
            val source = safeTarget(root, linkName)
            check(source.isFile) { "Unresolved hard link in Debian rootfs: $linkName" }
            target.delete()
            val linked = runCatching { Os.link(source.absolutePath, target.absolutePath) }.isSuccess
            if (!linked) {
                // Some Android/vendor filesystems deny hard links even inside app-private storage.
                // A byte-for-byte copy preserves the rootfs content and is sufficient for PRoot.
                source.copyTo(target, overwrite = true)
            }
            chmod(target, mode)
        }
        directoryModes.asReversed().forEach { (directory, mode) -> chmod(directory, mode) }
    }

    private fun safeTarget(root: File, rawName: String, allowRoot: Boolean = false): File {
        val clean = rawName.replace('\\', '/').removePrefix("./").trim('/')
        if (clean.isBlank()) {
            require(allowRoot) { "Empty non-directory path in Debian rootfs archive." }
            return root
        }
        require(clean.split('/').none { it == ".." }) { "Unsafe path in Debian rootfs archive: $rawName" }
        val target = File(root, clean)
        val parent = target.parentFile?.canonicalFile ?: root
        require(parent == root || parent.absolutePath.startsWith(root.absolutePath + File.separator)) {
            "Debian rootfs archive path escapes the install directory: $rawName"
        }
        return target
    }

    private fun prepareParent(root: File, target: File) {
        target.parentFile?.mkdirs()
        val parent = target.parentFile?.canonicalFile ?: root
        require(parent == root || parent.absolutePath.startsWith(root.absolutePath + File.separator)) {
            "Debian rootfs archive traversed a symbolic link: ${target.path}"
        }
    }

    private fun chmod(file: File, mode: Int) {
        runCatching { Os.chmod(file.absolutePath, mode and 0x0fff) }
    }

    private fun parsePax(bytes: ByteArray): Map<String, String> {
        val text = bytes.toString(StandardCharsets.UTF_8)
        val values = mutableMapOf<String, String>()
        var offset = 0
        while (offset < text.length) {
            val space = text.indexOf(' ', offset)
            if (space < 0) break
            val length = text.substring(offset, space).toIntOrNull() ?: break
            val end = (offset + length).coerceAtMost(text.length)
            val record = text.substring(space + 1, end).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) values[record.substring(0, equals)] = record.substring(equals + 1)
            offset += length
        }
        return values
    }

    private fun readPayload(input: InputStream, size: Long): ByteArray {
        require(size <= Int.MAX_VALUE) { "Oversized metadata entry in Debian rootfs archive." }
        val bytes = ByteArray(size.toInt())
        readFully(input, bytes)
        skipPadding(input, size)
        return bytes
    }

    private fun skipPayload(input: InputStream, size: Long) {
        skipExact(input, size)
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = (BLOCK_SIZE - size % BLOCK_SIZE) % BLOCK_SIZE
        skipExact(input, padding)
    }

    private fun copyExact(input: InputStream, output: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = size
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Unexpected end of Debian rootfs archive.")
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var offset = 0
        while (offset < block.size) {
            val count = input.read(block, offset, block.size - offset)
            if (count < 0) return offset != 0 && throw EOFException("Truncated Debian rootfs tar header.")
            offset += count
        }
        return true
    }

    private fun readFully(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw EOFException("Unexpected end of Debian rootfs archive.")
            offset += count
        }
    }

    private fun skipExact(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Unexpected end of Debian rootfs archive.")
            remaining -= read
        }
    }

    private fun tarString(header: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() } ?: offset + length
        return String(header, offset, end - offset, StandardCharsets.UTF_8).trim()
    }

    private fun tarOctal(header: ByteArray, offset: Int, length: Int): Long {
        val text = tarString(header, offset, length).trim().trimStart('0')
        return if (text.isBlank()) 0 else text.toLong(8)
    }
}
