package de.skyslycer.briefkasten

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiFailure(val code: Int) : IOException("DHL returned HTTP $code. Please try again later.")

/** Ephemeral cookies: each sync obtains a fresh image grant; nothing is leaked between accounts. */
class SessionCookies : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { new ->
            this.cookies.removeAll { it.name == new.name && it.domain == new.domain && it.path == new.path }
            if (new.expiresAt > System.currentTimeMillis()) this.cookies.add(new)
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }
    fun account(session: Session) {
        val url = "https://www.dhl.de/".toHttpUrl()
        saveFromResponse(url, listOf(
            Cookie.Builder().name("dhla0").value(session.accessToken).hostOnlyDomain(url.host).path("/").secure().build(),
            Cookie.Builder().name("dhlcs").value(session.subject).hostOnlyDomain(url.host).path("/").secure().build()
        ))
    }
}

class DhlApi(session: Session) {
    private val jar = SessionCookies().apply { account(session) }
    private val client = OkHttpClient.Builder().cookieJar(jar).connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private fun request(url: HttpUrl) = Request.Builder().url(url)
        .header("Accept-Language", "de").header("Cache-Control", "no-cache,no-store,must-revalidate")
        .header("User-Agent", "Briefkasten/0.1 Android")

    suspend fun advices(): AdviceResponse = withContext(Dispatchers.IO) {
        client.newCall(request("https://www.dhl.de/int-aviseanzeigen/advices?width=1080".toHttpUrl())
            .header("Accept", "application/json").build()).execute().use { response ->
            if (response.code in listOf(401, 403)) throw LoginRequired()
            if (!response.isSuccessful) throw ApiFailure(response.code)
            val body = response.body ?: throw IOException("DHL returned an empty response.")
            val bytes = body.byteStream().use { it.readBounded(4 * 1024 * 1024) }
            try { protocolJson.decodeFromString<AdviceResponse>(bytes.toString(Charsets.UTF_8)) }
            catch (_: kotlinx.serialization.SerializationException) { throw IOException("DHL returned an unexpected response. Sign in again or update the app.") }
        }
    }

    suspend fun authorizeImages(response: AdviceResponse) = withContext(Dispatchers.IO) {
        val grant = response.grantToken?.takeIf { it.isNotBlank() } ?: throw IOException("DHL did not return an image grant.")
        val raw = response.accessTokenUrl ?: throw IOException("DHL did not return an image login URL.")
        val url = trustedUrl(raw.replace("briefankuendigung.git.enplify.dhl.de", "briefasive-git.dhl.de"))
        val builder = request(url).post(JSONObject().put("grant_token", grant).toString().toRequestBody("application/json".toMediaType()))
        response.basicAuth?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Basic $it") }
        client.newCall(builder.build()).execute().use { if (it.code != 204) throw ApiFailure(it.code) }
    }

    suspend fun image(rawUrl: String, basicAuth: String?): ByteArray = withContext(Dispatchers.IO) {
        val builder = request(trustedUrl(rawUrl))
        basicAuth?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Basic $it") }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw ApiFailure(response.code)
            val bytes = response.body?.byteStream()?.use { it.readBounded(24 * 1024 * 1024) }
                ?: throw IOException("Empty image download.")
            LetterCrypto.decrypt(rawUrl, bytes)
        }
    }
}

fun java.io.InputStream.readBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > limit) throw IOException("DHL response exceeded the download limit.")
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
