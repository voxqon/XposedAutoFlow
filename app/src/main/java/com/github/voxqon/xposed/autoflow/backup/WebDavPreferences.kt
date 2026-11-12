package com.github.voxqon.xposed.autoflow.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class WebDavPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("webdav_private", Context.MODE_PRIVATE)

    fun load(): WebDavConfig {
        val encrypted = prefs.getString("credentials", null)
        val credentials = if (encrypted == null) null else JSONObject(decrypt(encrypted))
        return WebDavConfig(prefs.getString("url", "").orEmpty(),
            credentials?.getString("username").orEmpty(), credentials?.getString("password").orEmpty())
    }

    fun save(config: WebDavConfig) {
        val credentials = if (config.username.isEmpty() && config.password.isEmpty()) null else
            encrypt(JSONObject().put("username", config.username).put("password", config.password).toString())
        check(prefs.edit().putString("url", config.url).putString("credentials", credentials).commit()) {
            "无法保存 WebDAV 配置"
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    companion object {
        private const val KEY_ALIAS = "autotasklite.webdav.credentials"
    }
}
