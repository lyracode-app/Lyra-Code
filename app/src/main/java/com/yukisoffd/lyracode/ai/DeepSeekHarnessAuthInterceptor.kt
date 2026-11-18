package com.yukisoffd.lyracode.ai

import com.yukisoffd.lyracode.data.ApiProfile
import com.yukisoffd.lyracode.data.DeepSeekHarnessAccountClient
import com.yukisoffd.lyracode.data.DeepSeekHarnessProtocol
import com.yukisoffd.lyracode.data.HarnessAuthExpired
import okhttp3.Interceptor
import okhttp3.Response
import java.net.ProtocolException

internal class DeepSeekHarnessAuthInterceptor(private val accounts: () -> DeepSeekHarnessAccountClient) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val profile = request.tag(ApiProfile::class.java)?.takeIf { it.isDeepSeekHarness } ?: return chain.proceed(request)
        val account = accounts()
        val response = chain.proceed(account.authorize(profile.id, request))
        if (response.code == 401) {
            response.close()
            account.reject(profile.id)
            throw HarnessAuthExpired()
        }
        return response
    }
}

/** OkHttp can reissue requests after receiving redirects. Stop them before custom credentials leave the route. */
internal class DeepSeekHarnessRouteGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.tag(ApiProfile::class.java)?.isDeepSeekHarness != true) return chain.proceed(request)
        DeepSeekHarnessProtocol.requireModelRoute(request)
        val response = chain.proceed(request)
        if (response.code in 300..399) {
            response.close()
            throw ProtocolException("DeepSeek Harness endpoint returned a redirect")
        }
        return response
    }
}
