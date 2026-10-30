package com.cipherpath.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM using a non-exportable key in the Android Keystore (hardware-backed where
 * available). Used to wrap the SQLCipher passphrase, encrypt resume files at rest, and
 * protect the backend API key. Ciphertext format: [1 byte version][12 byte IV][ciphertext+tag].
 */
object KeystoreCrypto {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "cipherpath_master_v1"
    private const val VERSION: Byte = 1
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        aad?.let { c.updateAAD(it) }
        val ct = c.doFinal(plain)
        val iv = c.iv
        require(iv.size == IV_LEN)
        return byteArrayOf(VERSION) + iv + ct
    }

    fun decrypt(blob: ByteArray, aad: ByteArray? = null): ByteArray {
        require(blob.size > 1 + IV_LEN && blob[0] == VERSION) { "bad ciphertext" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 1, IV_LEN))
        aad?.let { c.updateAAD(it) }
        return c.doFinal(blob, 1 + IV_LEN, blob.size - 1 - IV_LEN)
    }

    fun deleteKey() {
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }
}

/** Random 32-byte SQLCipher passphrase, stored only in Keystore-wrapped form in no-backup storage. */
object DatabaseKey {
    private const val FILE = "db.key.enc"

    fun get(context: Context): ByteArray {
        val f = File(context.noBackupFilesDir, FILE)
        if (f.exists()) return KeystoreCrypto.decrypt(f.readBytes(), "db".toByteArray())
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        f.writeBytes(KeystoreCrypto.encrypt(raw, "db".toByteArray()))
        return raw
    }

    fun delete(context: Context) {
        File(context.noBackupFilesDir, FILE).delete()
    }
}

/** Encrypted resume file store under app-private storage. */
class SecureFileStore(context: Context) {
    private val dir = File(context.filesDir, "resumes").apply { mkdirs() }

    fun write(id: String, data: ByteArray) {
        require(id.matches(Regex("[A-Za-z0-9-]{1,64}")))
        val tmp = File(dir, "$id.tmp")
        tmp.writeBytes(KeystoreCrypto.encrypt(data, id.toByteArray()))
        tmp.renameTo(File(dir, "$id.enc"))
    }

    fun read(id: String): ByteArray? {
        require(id.matches(Regex("[A-Za-z0-9-]{1,64}")))
        val f = File(dir, "$id.enc")
        return if (f.exists()) KeystoreCrypto.decrypt(f.readBytes(), id.toByteArray()) else null
    }

    fun delete(id: String) {
        if (id.matches(Regex("[A-Za-z0-9-]{1,64}"))) File(dir, "$id.enc").delete()
    }

    fun wipe() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

/** Small encrypted secret store (e.g. backend API key) in no-backup storage. */
class SecretStore(private val context: Context) {
    private fun file(name: String) = File(context.noBackupFilesDir, "secret_$name.enc")

    fun put(name: String, value: String?) {
        require(name.matches(Regex("[a-z_]{1,32}")))
        if (value.isNullOrEmpty()) { file(name).delete(); return }
        file(name).writeBytes(KeystoreCrypto.encrypt(value.toByteArray(), name.toByteArray()))
    }

    fun get(name: String): String? {
        val f = file(name)
        if (!f.exists()) return null
        return runCatching { String(KeystoreCrypto.decrypt(f.readBytes(), name.toByteArray())) }.getOrNull()
    }

    fun wipe() {
        context.noBackupFilesDir.listFiles { f -> f.name.startsWith("secret_") }?.forEach { it.delete() }
    }
}
