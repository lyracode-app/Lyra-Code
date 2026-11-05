package com.yukisoffd.lyracode.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate

internal data class DeveloperWallState(
    val people: List<DeveloperWallPerson> = emptyList(),
    val syncing: Boolean = false,
    val syncedAt: Long = 0L,
    val succeeded: Boolean = false,
)

/** All wall metadata and images live together so deleting the cache also removes the daily limit. */
internal class DeveloperWallRepository(
    val cacheDirectory: File,
    val sourceUrl: String,
    private val fetchJson: (String) -> String,
    private val fetchAvatar: (String) -> ByteArray?,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(DeveloperWallState())
    val state = mutableState.asStateFlow()
    val configured: Boolean get() = wallHttpUrl(sourceUrl) != null

    suspend fun sync(force: Boolean = false, clearCache: Boolean = false): DeveloperWallState =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (clearCache) {
                    check(!cacheDirectory.exists() || cacheDirectory.deleteRecursively()) { "Cannot clear developer wall cache" }
                    mutableState.value = DeveloperWallState()
                }
                if (!configured) {
                    mutableState.value = DeveloperWallState()
                    return@withContext mutableState.value
                }
                val date = today().toString()
                val cached = readCache()
                if (!force && cached?.first == date) {
                    mutableState.value = cached.second
                    return@withContext cached.second
                }
                mutableState.value = (cached?.second ?: DeveloperWallState()).copy(syncing = true)
                try {
                    val people = parseDeveloperWallJson(fetchJson(sourceUrl))
                    val avatars = downloadAvatars(people)
                    val result = DeveloperWallState(
                        people = people.map { it.copy(avatarFile = avatars[it.avatar]) },
                        syncedAt = now(),
                        succeeded = true,
                    )
                    writeCache(date, result)
                    mutableState.value = result
                } catch (cancelled: CancellationException) {
                    mutableState.value = mutableState.value.copy(syncing = false)
                    throw cancelled
                } catch (_: Exception) {
                    // A failed refresh hides old entries and is also limited to one attempt per day.
                    val result = DeveloperWallState(syncedAt = now())
                    runCatching { writeCache(date, result) }
                    mutableState.value = result
                }
                mutableState.value
            }
        }

    private fun readCache(): Pair<String, DeveloperWallState>? = runCatching {
        val json = JSONObject(File(cacheDirectory, "wall.json").readText())
        require(json.getInt("version") == 1 && json.getString("source") == sourceUrl)
        val people = parseDeveloperWallJson(json.getJSONArray("people").toString())
        val avatars = json.getJSONObject("avatars")
        val cachedPeople = people.map { person ->
            val filename = avatars.optString(person.avatar).takeIf { it.matches(Regex("[a-f0-9]{64}\\.png")) }
            val file = filename?.let { File(cacheDirectory, "avatars/$it") }
            // Partial cache deletion must trigger a new sync as well.
            require(file == null || file.isFile)
            person.copy(avatarFile = file)
        }
        json.getString("date") to DeveloperWallState(
            people = cachedPeople,
            syncedAt = json.getLong("syncedAt"),
            succeeded = json.getBoolean("succeeded"),
        )
    }.getOrNull()

    private suspend fun downloadAvatars(people: List<DeveloperWallPerson>): Map<String, File> = coroutineScope {
        val directory = File(cacheDirectory, "avatars").apply { mkdirs() }
        val semaphore = Semaphore(4)
        val files = people.map { it.avatar }.filter { it.isNotBlank() }.distinct().map { avatar ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val source = resolveWallAvatar(sourceUrl, avatar) ?: return@withPermit null
                        val bytes = fetchAvatar(source) ?: return@withPermit null
                        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                        val file = File(directory, "$hash.png")
                        if (!file.isFile) file.writeBytes(bytes)
                        avatar to file
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull().toMap()
        val retained = files.values.map { it.name }.toSet()
        directory.listFiles()?.filter { it.name !in retained }?.forEach { it.delete() }
        files
    }

    private fun writeCache(date: String, state: DeveloperWallState) {
        check(cacheDirectory.isDirectory || cacheDirectory.mkdirs())
        val avatars = JSONObject()
        state.people.forEach { person -> person.avatarFile?.let { avatars.put(person.avatar, it.name) } }
        val json = JSONObject().put("version", 1).put("source", sourceUrl).put("date", date)
            .put("syncedAt", state.syncedAt).put("succeeded", state.succeeded)
            .put("people", developerWallPeopleJson(state.people)).put("avatars", avatars)
        val temporary = File(cacheDirectory, "wall.tmp")
        temporary.writeText(json.toString())
        Files.move(temporary.toPath(), File(cacheDirectory, "wall.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

internal fun wallHttpUrl(value: String): String? = runCatching {
    val uri = URI(value)
    value.takeIf { uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank() }
}.getOrNull()

/** Relative paths refer to the JSON's server; file: URIs refer to files on the device. */
internal fun resolveWallAvatar(sourceUrl: String, avatar: String): String? = runCatching {
    val localFile = File(avatar)
    if (localFile.isAbsolute && localFile.isFile) return@runCatching localFile.toURI().toString()
    val uri = URI(avatar)
    when {
        uri.scheme.equals("file", ignoreCase = true) -> uri.toString()
        uri.scheme == null -> wallHttpUrl(URI(sourceUrl).resolve(uri).toString())
        else -> wallHttpUrl(avatar)
    }
}.getOrNull()
