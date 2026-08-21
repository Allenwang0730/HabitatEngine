package com.ailun.habitat.app.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AuthStateManager {
    private const val PREFS_NAME = "habitat_auth"
    private const val KEY_TOKEN = "jwt_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USERNAME = "username"

    private lateinit var prefs: SharedPreferences

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private var _token: String? = null
    val token: String? get() = _token

    private var _userId: String? = null
    val userId: String? get() = _userId

    private var _username: String? = null
    val username: String? get() = _username

    val isInitialized: Boolean
        get() = ::prefs.isInitialized

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        _token = prefs.getString(KEY_TOKEN, null)
        _userId = prefs.getString(KEY_USER_ID, null)
        _username = prefs.getString(KEY_USERNAME, null)
        _isLoggedIn.value = _token != null
    }

    fun setAuth(token: String, userId: String, username: String) {
        _token = token
        _userId = userId
        _username = username
        _isLoggedIn.value = true
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    fun logout() {
        _token = null
        _userId = null
        _username = null
        _isLoggedIn.value = false
        prefs.edit().clear().apply()
    }
}
