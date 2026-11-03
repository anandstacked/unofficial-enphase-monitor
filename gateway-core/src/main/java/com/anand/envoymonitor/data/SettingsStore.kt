package com.anand.envoymonitor.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ConnectionSettings(
    val host: String = "", val serial: String = "", val fingerprint: String = "",
    val lowVoltage: Double? = null, val highVoltage: Double? = null
) { val paired get() = fingerprint.isNotBlank() }

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    private val tokenMutable = MutableStateFlow(readTokenState())
    val tokenState = tokenMutable.asStateFlow()
    private fun readTokenState(): StoredTokenState = token()?.let { StoredTokenState(true, tokenMetadata(it)) } ?: StoredTokenState()
    private fun load() = ConnectionSettings(
        preferences.getString("host", "")!!, preferences.getString("serial", "")!!,
        preferences.getString("fingerprint", "")!!, preferences.getString("low", null)?.toDoubleOrNull(),
        preferences.getString("high", null)?.toDoubleOrNull()
    )
    fun pair(host: String, serial: String, fingerprint: String) {
        preferences.edit().putString("host", host).putString("serial", serial).putString("fingerprint", fingerprint).remove("token").apply()
        saveAlertState(AlertState())
        mutable.value = load()
        tokenMutable.value = StoredTokenState()
    }
    fun saveThresholds(low: Double?, high: Double?) {
        require((low == null && high == null) || (low != null && high != null && low.isFinite() && high.isFinite() && low > 0 && low < high))
        preferences.edit().putString("low", low?.toString()).putString("high", high?.toString()).apply()
        saveAlertState(AlertState())
        mutable.value = load()
    }
    fun alertState() = AlertState(preferences.getLong("alertTimestamp", 0),
        runCatching { preferences.getString("alertBand", null)?.let(VoltageBand::valueOf) }.getOrNull())
    fun saveAlertState(state: AlertState) {
        preferences.edit().putLong("alertTimestamp", state.timestamp).putString("alertBand", state.band?.name).commit()
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("envoy_token", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("envoy_token", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun saveToken(token: String) {
        val clean = normalizeLocalToken(token)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString("token", encoded).commit()) { "Could not save the encrypted token." }
        tokenMutable.value = StoredTokenState(true, tokenMetadata(clean))
    }
    fun token(): String? = runCatching {
        val encoded = preferences.getString("token", null) ?: return null
        val parts = encoded.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()
    fun hasToken() = token() != null
}
