package com.pirorin215.fastrecmob.network

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private val tokenProvider: suspend () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()

        // This is a synchronous way to call a suspend function, which is not ideal.
        // However, since Interceptor.intercept is a synchronous function, we have to bridge the gap.
        // In a real-world scenario with more complex token refresh logic, a more robust solution
        // (like using an Authenticator) would be needed.
        val token = kotlinx.coroutines.runBlocking { tokenProvider() }

        builder.addHeader("Authorization", "Bearer $token")
        return chain.proceed(builder.build())
    }
}
