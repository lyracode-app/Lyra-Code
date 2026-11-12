package com.yukisoffd.lyracode.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yukisoffd.lyracode.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit

internal object DeveloperWallManager {
    @Volatile private var instance: DeveloperWallRepository? = null

    fun get(context: Context): DeveloperWallRepository = instance ?: synchronized(this) {
        instance ?: DeveloperWallRepository(
            cacheDirectory = File(context.applicationContext.cacheDir, "developer_wall"),
            sourceUrl = BuildConfig.LYRA_DEVELOPER_WALL_URL.trim(),
            fetchJson = { url -> fetch(url, 1024 * 1024).toString(Charsets.UTF_8) },
            fetchAvatar = { source ->
                val bytes = if (source.startsWith("file:", ignoreCase = true)) {
                    File(URI(source)).inputStream().use { it.readLimited(5 * 1024 * 1024) }
                } else fetch(source, 5 * 1024 * 1024)
                normalizeAvatar(bytes)
            },
        ).also { instance = it }
    }

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).build()
    }

    private fun fetch(url: String, maxBytes: Int): ByteArray =
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Developer wall HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty developer wall response")
            if (body.contentLength() > maxBytes) throw IOException("Developer wall response too large")
            body.byteStream().use { it.readLimited(maxBytes) }
        }

    private fun InputStream.readLimited(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > maxBytes) throw IOException("Developer wall response too large")
            output.write(buffer, 0, count)
        }
    }

    private fun normalizeAvatar(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 384) inSampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val ratio = minOf(1f, 192f / maxOf(decoded.width, decoded.height))
        val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1), true)
        return try {
            ByteArrayOutputStream().use { output ->
                if (scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }
}
