package com.yukisoffd.lyracode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yukisoffd.lyracode.webaccess.WebAccessHttp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ChatRuntimeWebViewInstrumentedTest {
    @Test fun applicationOwnedWebViewExecutesPageJavaScript() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("disposableTarget") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var runtime: ChatRuntime
        instrumentation.runOnMainSync { runtime = ChatRuntime.get(instrumentation.targetContext) }
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                try {
                    socket.use {
                        it.soTimeout = 5000
                        WebAccessHttp.readRequest(it.getInputStream().buffered())
                        val html = """<!doctype html><html><head><title>Runtime WebView fixture</title></head>
                            <body><article id="content">Static placeholder.</article><script>
                            document.getElementById('content').textContent = 'RUNTIME_WEBVIEW_JS_MARKER ' +
                            'Application-context WebView executed this local page. '.repeat(30);
                            </script></body></html>"""
                        // This fixture must permit its inline script. The companion HTTP helper
                        // intentionally adds a CSP that forbids inline scripts.
                        val body = html.toByteArray()
                        val output = it.getOutputStream()
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        output.write(body)
                        output.flush()
                    }
                } catch (_: java.io.IOException) { /* WebView may cancel an ancillary request. */ }
            }
        }
        try {
            val result = runBlocking {
                withTimeout(20_000) { runtime.webAgent.readPage("http://127.0.0.1:${server.localPort}/runtime") }
            }
            assertTrue(result, result.contains("RUNTIME_WEBVIEW_JS_MARKER"))
            assertTrue(result, result.contains("extraction: webview_"))
        } finally {
            server.close()
            worker.join(2000)
        }
    }
}
