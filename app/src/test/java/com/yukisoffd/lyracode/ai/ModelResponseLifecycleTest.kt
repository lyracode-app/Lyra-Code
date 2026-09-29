package com.yukisoffd.lyracode.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ModelResponseLifecycleTest {
    @Test fun stopCancelsARequestWaitingForHeaders() = checkCancellation(sendHeaders = false)
    @Test fun stopCancelsARequestBlockedReadingItsBody() = checkCancellation(sendHeaders = true)

    private fun checkCancellation(sendHeaders: Boolean) = runBlocking {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                if (sendHeaders) {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write("data: partial\n\n".toByteArray())
                    exchange.responseBody.flush()
                }
                received.countDown()
                release.await(5, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/").build())
        var retries = 0
        val job = launch(Dispatchers.IO) {
            executeModelRequestWithRetry(onRetry = { _, _, _ -> retries++ }) {
                call.useModelResponse { it.body!!.string() }
            }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { received.await(3, TimeUnit.SECONDS) })
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(call.isCanceled())
            assertEquals(0, retries)
        } finally {
            call.cancel()
            release.countDown()
            server.stop(0)
            job.cancelAndJoin()
            client.connectionPool.evictAll()
        }
    }

    @Test fun doneEventReturnsWithoutWaitingForConnectionCloseAndAllowsNextRequest() = runBlocking {
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        val requests = AtomicInteger()
        server.createContext("/") { exchange ->
            try {
                requests.incrementAndGet()
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write("data: result\n\ndata: [DONE]\n\n".toByteArray())
                exchange.responseBody.flush()
                release.await(5, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        val client = OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()
        try {
            repeat(2) {
                val lines = mutableListOf<String>()
                withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/").build()).useModelResponse { response ->
                        response.body!!.charStream().buffered().consumeModelStream { line ->
                            lines += line
                            line != "data: [DONE]"
                        }
                    }
                }
                assertEquals(listOf("data: result", "", "data: [DONE]"), lines)
            }
            assertEquals(2, requests.get())
        } finally {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test fun stalledResponseTimesOutAndUsesAutomaticRetry() = runBlocking {
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        val requests = AtomicInteger()
        server.createContext("/") { exchange ->
            try {
                if (requests.incrementAndGet() == 1) {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write("partial".toByteArray())
                    exchange.responseBody.flush()
                    release.await(5, TimeUnit.SECONDS)
                } else {
                    exchange.sendResponseHeaders(200, 2)
                    exchange.responseBody.write("ok".toByteArray())
                }
            } finally { exchange.close() }
        }
        server.start()
        val client = OkHttpClient.Builder().readTimeout(250, TimeUnit.MILLISECONDS).build()
        try {
            val result = withContext(Dispatchers.IO) {
                executeModelRequestWithRetry(maxRetries = 1, retryDelayMillis = 0) {
                    client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/").build())
                        .useModelResponse { it.body!!.string() }
                }
            }
            assertEquals("ok", result)
            assertEquals(2, requests.get())
        } finally {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
