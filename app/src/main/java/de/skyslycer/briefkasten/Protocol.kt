package de.skyslycer.briefkasten

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

val protocolJson = Json { ignoreUnknownKeys = true }
@Serializable data class ServiceState(val contractState: String, val recognitionDegree: Int = 0)
@Serializable data class Advice(
    @SerialName("image_url") val imageUrl: String,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null
)
@Serializable data class AdviceGroup(val date: String, val advices: List<Advice> = emptyList())
@Serializable data class AdviceResponse(
    val serviceState: ServiceState,
    val currentAdvice: List<AdviceGroup> = emptyList(),
    val oldAdvices: List<AdviceGroup> = emptyList(),
    val grantToken: String? = null,
    val accessTokenUrl: String? = null,
    val basicAuth: String? = null
)

object LetterCrypto {
    fun identifier(url: String) = url.substringAfterLast('/').substringBefore('?')
    fun decrypt(url: String, bytes: ByteArray): ByteArray {
        val identifier = identifier(url)
        require(identifier.isNotEmpty()) { "Missing image identifier." }
        val hash = MessageDigest.getInstance("SHA-512").digest(identifier.toByteArray(Charsets.UTF_8))
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(hash.copyOfRange(0, 32), "AES"),
                GCMParameterSpec(128, hash.copyOfRange(32, 44)))
            updateAAD(hash.copyOfRange(44, 56))
            doFinal(bytes)
        }
    }
}

/** Never forward a session, grant or Basic credentials to an arbitrary server-returned URL. */
fun trustedUrl(raw: String): HttpUrl {
    val url = (if (raw.startsWith('/')) "https://www.dhl.de$raw" else raw).toHttpUrl()
    require(url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()) { "Unsupported service URL." }
    require(listOf("dhl.de", "dhl.com", "deutschepost.de").any { url.host == it || url.host.endsWith(".$it") }) {
        "DHL returned an unrecognized image host. Update the app before downloading."
    }
    return url
}

data class Candidate(val date: String, val advice: Advice, val current: Boolean)
fun AdviceResponse.candidates(): List<Candidate> = buildList {
    currentAdvice.forEach { group -> group.advices.forEach { add(Candidate(group.date, it, true)) } }
    oldAdvices.forEach { group -> group.advices.forEach { add(Candidate(group.date, it, false)) } }
}.distinctBy { it.date to it.advice.imageUrl }

fun archiveId(account: String, date: String, digest: String, occurrence: Int): String =
    sha256("$account\u0000$date\u0000$digest\u0000$occurrence".toByteArray())

fun safeDate(date: String): String = date.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(48).ifBlank { "undated" }
