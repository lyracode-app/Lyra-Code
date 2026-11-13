package com.yukisoffd.lyracode.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class DeveloperWallRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val directory get() = File(temporary.root, "wall-cache")
    private var date = LocalDate.of(2026, 11, 4)
    private val jsonCalls = AtomicInteger()
    private val avatarCalls = AtomicInteger()
    private var response = """[{"name":"Alice","avatar":"avatars/a.png","role":[0,1],"link":"https://example.com/alice"}]"""
    private var offline = false
    private var avatarFails = false
    private var avatarBytes = byteArrayOf(1, 2, 3)

    private fun repository(source: String = "https://example.com/api/wall.json") = DeveloperWallRepository(
        cacheDirectory = directory,
        sourceUrl = source,
        fetchJson = {
            jsonCalls.incrementAndGet()
            if (offline) throw IOException("Offline")
            response
        },
        fetchAvatar = {
            assertEquals("https://example.com/api/avatars/a.png", it)
            avatarCalls.incrementAndGet()
            if (avatarFails) throw IOException("No image")
            avatarBytes
        },
        today = { date },
    )

    @Test fun persistsPeopleAndImagesAndMakesNoSameDayRequestsEvenAfterRestart() = runBlocking {
        val first = repository().sync()
        assertTrue(first.succeeded)
        assertEquals(setOf(0, 1), first.people.single().roles)
        assertArrayEquals(avatarBytes, first.people.single().avatarFile!!.readBytes())
        offline = true
        val restarted = repository()
        assertEquals(first, restarted.sync())
        assertEquals(first, restarted.sync())
        assertEquals(1, jsonCalls.get())
        assertEquals(1, avatarCalls.get())
    }

    @Test fun syncsAgainOnNextCalendarDay() = runBlocking {
        val repository = repository()
        repository.sync()
        date = date.plusDays(1)
        response = """[{"name":"Bob","role":3}]"""
        assertEquals("Bob", repository.sync().people.single().name)
        repository.sync()
        assertEquals(2, jsonCalls.get())
    }

    @Test fun manualSyncBypassesDailyLimitAndUpdatesImageBytes() = runBlocking {
        val repository = repository()
        val previousFile = repository.sync().people.single().avatarFile!!
        avatarBytes = byteArrayOf(4, 5, 6)
        val result = repository.sync(force = true)
        assertArrayEquals(avatarBytes, result.people.single().avatarFile!!.readBytes())
        assertFalse(previousFile.exists())
        assertEquals(2, jsonCalls.get())
        assertEquals(2, avatarCalls.get())
    }

    @Test fun clearingCacheImmediatelySyncsAgainAndRestoresImages() = runBlocking {
        val repository = repository()
        repository.sync()
        assertTrue(repository.sync(clearCache = true).people.single().avatarFile!!.isFile)
        assertEquals(2, jsonCalls.get())
        assertEquals(2, avatarCalls.get())
    }

    @Test fun externalCacheDeletionTriggersNewSyncInSameProcess() = runBlocking {
        val repository = repository()
        repository.sync()
        assertTrue(directory.deleteRecursively())
        assertTrue(repository.sync().succeeded)
        assertEquals(2, jsonCalls.get())
    }

    @Test fun missingCachedAvatarTriggersSync() = runBlocking {
        val repository = repository()
        assertTrue(repository.sync().people.single().avatarFile!!.delete())
        assertTrue(repository.sync().people.single().avatarFile!!.isFile)
        assertEquals(2, avatarCalls.get())
    }

    @Test fun corruptCacheTriggersSync() = runBlocking {
        val repository = repository()
        repository.sync()
        File(directory, "wall.json").writeText("broken")
        assertEquals("Alice", repository.sync().people.single().name)
        assertEquals(2, jsonCalls.get())
    }

    @Test fun changedEndpointDoesNotReuseAnotherEndpointsCache() = runBlocking {
        repository().sync()
        response = "[]"
        assertTrue(repository("https://new.example.com/wall.json").sync().people.isEmpty())
        assertEquals(2, jsonCalls.get())
    }

    @Test fun networkFailureKeepsCachedPeopleAndAvatarsAcrossRestarts() = runBlocking {
        val repository = repository()
        val cached = repository.sync()
        date = date.plusDays(1)
        offline = true
        val failed = repository.sync()
        assertFalse(failed.succeeded)
        assertEquals(cached.people, failed.people)
        assertTrue(failed.people.single().avatarFile!!.isFile)
        assertEquals(cached.syncedAt, failed.syncedAt)
        assertEquals(failed, repository().sync())
        assertEquals(2, jsonCalls.get())
        offline = false
        assertTrue(repository.sync(force = true).succeeded)
        assertEquals(3, jsonCalls.get())
    }

    @Test fun initialFailureDoesNotKeepRequestingOnEveryVisit() = runBlocking {
        offline = true
        assertTrue(repository().sync().people.isEmpty())
        repository().sync()
        assertEquals(1, jsonCalls.get())
        date = date.plusDays(1)
        offline = false
        assertEquals("Alice", repository().sync().people.single().name)
        assertEquals(2, jsonCalls.get())
    }

    @Test fun invalidJsonKeepsThePreviousWallAndPersistsTheFailure() = runBlocking {
        val repository = repository()
        repository.sync()
        response = "{broken"
        assertEquals("Alice", repository.sync(force = true).people.single().name)
        assertFalse(repository.state.value.succeeded)
        assertEquals("Alice", repository().sync().people.single().name)
        assertEquals(2, jsonCalls.get())
    }

    @Test fun emptyOrUnusableListsHaveNoWall() = runBlocking {
        response = "[]"
        assertTrue(repository().sync().people.isEmpty())
        response = """[{"name":"No role"},{"role":0}]"""
        assertTrue(repository().sync(force = true).people.isEmpty())
    }

    @Test fun avatarFailureKeepsPersonWithDefaultAvatarAndDoesNotRetryUntilSync() = runBlocking {
        avatarFails = true
        val repository = repository()
        val result = repository.sync()
        assertTrue(result.succeeded)
        assertEquals("Alice", result.people.single().name)
        assertNull(result.people.single().avatarFile)
        repository().sync()
        assertEquals(1, avatarCalls.get())
        avatarFails = false
        assertNotNull(repository.sync(force = true).people.single().avatarFile)
    }

    @Test fun sharedAvatarsDownloadOnlyOnceAndMissingAvatarsMakeNoRequest() = runBlocking {
        response = """[
            {"name":"Alice","avatar":"avatars/a.png","role":0},
            {"name":"Bob","avatar":"avatars/a.png","role":1},
            {"name":"Carol","role":2}
        ]"""
        val result = repository().sync()
        assertEquals(3, result.people.size)
        assertEquals(result.people[0].avatarFile, result.people[1].avatarFile)
        assertNull(result.people[2].avatarFile)
        assertEquals(1, avatarCalls.get())
    }

    @Test fun parallelAutomaticRequestsShareDailySync() = runBlocking {
        val repository = repository()
        coroutineScope { List(6) { async { repository.sync() } }.awaitAll() }
        assertEquals(1, jsonCalls.get())
        assertFalse(repository.state.value.syncing)
    }

    @Test fun absentOrInvalidEndpointDoesNotFetchOrShowAnything() = runBlocking {
        for (url in listOf("", "bad url", "file:///a.json", "https:///wall.json")) {
            val repository = repository(url)
            assertFalse(repository.configured)
            assertTrue(repository.sync().people.isEmpty())
        }
        assertEquals(0, jsonCalls.get())
    }

    @Test fun existingLocalFilePathIsRecognized() {
        val file = temporary.newFile("avatar with spaces.png")
        assertEquals(file.toURI().toString(), resolveWallAvatar("https://example.com/wall.json", file.absolutePath))
    }
}
