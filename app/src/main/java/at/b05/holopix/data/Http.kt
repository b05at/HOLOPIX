package at.b05.holopix.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val JSON = "application/json; charset=utf-8".toMediaType()
    val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
}

class HttpException(val code: Int, message: String) : IOException(message)

suspend fun Request.fetch(): String = withContext(Dispatchers.IO) {
    Http.client.newCall(this@fetch).execute().use { r ->
        val body = r.body?.string() ?: ""
        if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code}: ${body.take(200)}")
        body
    }
}
