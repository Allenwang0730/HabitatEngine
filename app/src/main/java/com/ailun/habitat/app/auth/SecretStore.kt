package com.ailun.habitat.app.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API key / 访问令牌等机密的统一存储（EncryptedSharedPreferences）。
 *
 * - Keystore 密钥丢失（如备份恢复到新设备）时，create() 会抛异常：此时删掉旧文件重建，机密需重新填写，不崩溃。
 * - [get] 支持从旧的明文 SharedPreferences 迁移：读到旧值就写入加密存储并删除明文。
 */
object SecretStore {
    private const val TAG = "SecretStore"
    private const val FILE = "habitat_secrets"

    const val JEV_API_KEY = "jev_api_key"
    const val DEEPSEEK_API_KEY = "deepseek_api_key"
    const val AGENT_TOKEN = "agent_token"

    @Volatile private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val app = context.applicationContext
            val created = try {
                open(app)
            } catch (e: Exception) {
                Log.w(TAG, "加密存储不可用，重建（需重新填写密钥）: ${e.javaClass.simpleName}")
                app.deleteSharedPreferences(FILE)
                open(app)
            }
            prefs = created
            return created
        }
    }

    private fun open(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * 读机密。[legacyPrefs]/[legacyKey] 指向旧的明文存储位置，首次读取时迁移过来。
     */
    fun get(context: Context, key: String, legacyPrefs: String? = null, legacyKey: String = key): String? {
        val store = prefs(context)
        store.getString(key, null)?.takeIf { it.isNotBlank() }?.let { return it }
        if (legacyPrefs == null) return null
        val old = context.applicationContext.getSharedPreferences(legacyPrefs, 0)
        val value = old.getString(legacyKey, null)?.takeIf { it.isNotBlank() } ?: return null
        store.edit().putString(key, value).apply()
        old.edit().remove(legacyKey).apply()
        return value
    }

    fun put(context: Context, key: String, value: String?) {
        val edit = prefs(context).edit()
        if (value.isNullOrBlank()) edit.remove(key) else edit.putString(key, value.trim())
        edit.apply()
    }
}
