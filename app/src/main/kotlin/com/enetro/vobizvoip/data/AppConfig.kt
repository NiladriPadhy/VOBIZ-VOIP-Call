package com.enetro.vobizvoip.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.enetro.vobizvoip.BuildConfig
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppConfig(
    val sipUsername: String = "",
    val sipPassword: String = "",
    val registrarUrl: String = BuildConfig.DEFAULT_REGISTRAR_URL,
    val sipDomain: String = BuildConfig.DEFAULT_SIP_DOMAIN,
    val backendUrl: String = "",
    val backendToken: String = "",
    val callerId: String = "",
    val recordingEnabled: Boolean = true,
) {
    val isComplete: Boolean
        get() = sipUsername.isNotBlank() &&
            sipPassword.isNotBlank() &&
            registrarUrl.startsWith("wss://") &&
            sipDomain.isNotBlank() &&
            backendUrl.startsWith("https://") &&
            backendToken.isNotBlank() &&
            callerId.startsWith("+")
}

class SecureConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): AppConfig {
        val encrypted = preferences.getString(KEY_PAYLOAD, null) ?: return defaultConfig()
        val iv = preferences.getString(KEY_IV, null) ?: return defaultConfig()
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val json = JSONObject(
                cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
                    .toString(Charsets.UTF_8),
            )
            AppConfig(
                sipUsername = json.optString("sipUsername"),
                sipPassword = json.optString("sipPassword"),
                registrarUrl = json.optString("registrarUrl", BuildConfig.DEFAULT_REGISTRAR_URL),
                sipDomain = json.optString("sipDomain", BuildConfig.DEFAULT_SIP_DOMAIN),
                backendUrl = json.optString("backendUrl"),
                backendToken = json.optString("backendToken"),
                callerId = json.optString("callerId"),
                recordingEnabled = json.optBoolean("recordingEnabled", true),
            )
        }.getOrElse { defaultConfig() }
    }

    // Fresh-install fallback. In debug builds this pre-fills the POC test
    // credentials so the app connects without manual setup; release builds get a
    // blank config (the debug fields are empty outside the debug build type).
    private fun defaultConfig(): AppConfig =
        if (BuildConfig.DEBUG) {
            AppConfig(
                sipUsername = BuildConfig.DEBUG_SIP_USERNAME,
                sipPassword = BuildConfig.DEBUG_SIP_PASSWORD,
                backendUrl = BuildConfig.DEBUG_BACKEND_URL,
                backendToken = BuildConfig.DEBUG_BACKEND_TOKEN,
                callerId = BuildConfig.DEBUG_CALLER_ID,
            )
        } else {
            AppConfig()
        }

    fun save(config: AppConfig) {
        val json = JSONObject()
            .put("sipUsername", config.sipUsername)
            .put("sipPassword", config.sipPassword)
            .put("registrarUrl", config.registrarUrl)
            .put("sipDomain", config.sipDomain)
            .put("backendUrl", config.backendUrl.removeSuffix("/"))
            .put("backendToken", config.backendToken)
            .put("callerId", config.callerId)
            .put("recordingEnabled", config.recordingEnabled)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_PAYLOAD, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "secure_config"
        const val KEY_PAYLOAD = "payload"
        const val KEY_IV = "iv"
        const val KEY_ALIAS = "vobiz_poc_config"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
