package com.ailun.habitat.app.network

import com.ailun.habitat.app.auth.AuthStateManager
import okhttp3.Interceptor
import okhttp3.Response

object AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath

        if (path.startsWith("/api/v1/auth/") || path.startsWith("/actuator/health")) {
            return chain.proceed(original)
        }

        val token = AuthStateManager.token
        // 令牌同样不走明文公网（规则同登录凭据）
        if (token != null && HabitatApiClient.isCredentialTransportAllowed(original.url)) {
            val request = original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            val response = chain.proceed(request)
            if (response.code == 401) {
                AuthStateManager.logout()
            }
            return response
        }

        return chain.proceed(original)
    }
}
