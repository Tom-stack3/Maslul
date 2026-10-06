package com.maslul.app.data

import com.maslul.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

class HttpException(val code: Int, message: String) : IOException(message)

object Http {
    /** Community services ask clients to identify themselves. */
    val USER_AGENT = "Maslul/${BuildConfig.VERSION_NAME} (+https://github.com/Tom-stack3/Maslul)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }

    suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    /** Reads the body off the main thread: callers run there, and several requests at once would queue on it. */
    suspend fun getString(url: HttpUrl): String {
        val response = execute(Request.Builder().url(url).build())
        return withContext(Dispatchers.IO) {
            response.use {
                if (!it.isSuccessful) throw HttpException(it.code, "HTTP ${it.code} for ${url.encodedPath}")
                it.body!!.string()
            }
        }
    }

    suspend inline fun <reified T> getJson(url: HttpUrl): T {
        val body = getString(url)
        // A trip's JSON (with its shape) can be large; parse it off the main thread too.
        return withContext(Dispatchers.Default) { AppJson.decodeFromString<T>(body) }
    }
}
