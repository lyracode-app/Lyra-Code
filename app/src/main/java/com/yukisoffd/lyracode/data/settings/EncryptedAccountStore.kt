package com.yukisoffd.lyracode.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore encryption with no plaintext fallback; noBackupFilesDir excludes OAuth sessions from exports. */
internal class EncryptedAccountStore(
    context: Context,
    directoryName: String,
    private val keyAlias: String,
    associatedData: String,
) : AccountStateStore {
    private val directory = File(context.noBackupFilesDir, directoryName)
    private val file = AtomicFile(File(directory, "accounts.enc"))
    private val aad = associatedData.toByteArray(Charsets.UTF_8)

    override fun <T> transaction(block: (JSONObject) -> T): T = synchronized(processLock) {
        check(directory.mkdirs() || directory.isDirectory) { "Unable to access account storage" }
        RandomAccessFile(File(directory, "accounts.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val state = if (file.baseFile.exists() || File(directory, "accounts.enc.bak").exists()) {
                    val encrypted = file.readFully()
                    require(encrypted.size > 12)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
                    cipher.updateAAD(aad)
                    JSONObject(String(cipher.doFinal(encrypted.copyOfRange(12, encrypted.size)), Charsets.UTF_8))
                } else JSONObject()
                val before = state.toString()
                val result = block(state)
                if (state.toString() != before) {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, key())
                    cipher.updateAAD(aad)
                    val bytes = cipher.iv + cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
                    val output = file.startWrite()
                    try { output.write(bytes); file.finishWrite(output) }
                    catch (error: Throwable) { file.failWrite(output); throw error }
                }
                result
            }
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private companion object {
        val processLock = Any()
    }
}

internal interface AccountStateStore {
    fun <T> transaction(block: (JSONObject) -> T): T
}
