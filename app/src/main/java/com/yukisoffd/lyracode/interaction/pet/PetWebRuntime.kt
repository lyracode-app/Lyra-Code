package com.yukisoffd.lyracode.interaction.pet

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** A private HTTPS origin, served entirely by the host. Never falls through to the network. */
internal class PetWebRuntime(private val context: Context, private val manifest: JSONObject, private val builtin: Boolean) {
    private val paths = DevicePetStore.files(context, builtin).toSet()
    private val source = DevicePetStore.resourceSource(context, builtin)
    private val soundEnabled = DevicePetStore.effectiveOptions(context, manifest, builtin).optBoolean("soundEnabled", false)
    private val csp = "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src ${if (soundEnabled) "'self' blob:" else "'none'"}; connect-src 'self'; font-src 'self'; frame-src 'none'; object-src 'none'; worker-src 'none'; form-action 'none'; base-uri 'none'"
    fun intercept(request: WebResourceRequest?): WebResourceResponse {
        fun response(mime: String, bytes: ByteArray, code: Int = 200) = WebResourceResponse(mime, "UTF-8", code,
            if (code == 200) "OK" else "Not Found", mapOf("Content-Security-Policy" to csp, "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(bytes))
        return runCatching {
            val uri = request?.url ?: error("Missing URL")
            require(request.method == "GET" && uri.scheme == "https" && uri.host == "lyra-pet.invalid" && uri.port == -1)
            val path = PetArchive.path(uri.path.orEmpty().removePrefix("/"))
            when (path) {
                "__lyra__/index.html" -> response("text/html", document().toByteArray())
                "__lyra__/runtime.js" -> response("text/javascript", context.assets.open("desktop-pet/runtime.js").use { it.readBytes() })
                else -> {
                    require(path in paths)
                    val mime = when (path.substringAfterLast('.').lowercase()) {
                        "js", "mjs" -> "text/javascript"
                        "json" -> "application/json"
                        "css" -> "text/css"
                        "html" -> "text/html"
                        "png", "apng" -> "image/png"
                        "gif" -> "image/gif"
                        "jpg", "jpeg" -> "image/jpeg"
                        "webp" -> "image/webp"
                        "svg" -> "image/svg+xml"
                        "woff2" -> "font/woff2"
                        "mp3" -> "audio/mpeg"
                        "wav" -> "audio/wav"
                        "ogg" -> "audio/ogg"
                        "m4a" -> "audio/mp4"
                        else -> "application/octet-stream"
                    }
                    require(!mime.startsWith("audio/") || soundEnabled)
                    WebResourceResponse(mime, null, 200, "OK", mapOf("Content-Security-Policy" to csp, "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), source(path))
                }
            }
        }.getOrElse { response("text/plain", ByteArray(0), 404) }
    }
    private fun document(): String {
        val boot = JSONObject().put("manifest", manifest).put("files", JSONArray(paths.sorted()))
            .toString().replace("<", "\\u003c")
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1"><style>html,body,#pet-root{margin:0;width:100%;height:100%;overflow:hidden;background:transparent;touch-action:none}img{width:100%;height:100%;object-fit:contain}*{box-sizing:border-box}</style></head><body><div id="pet-root"></div><script>window.__petBoot=$boot;</script><script type="module" src="/__lyra__/runtime.js"></script></body></html>"""
    }
}
