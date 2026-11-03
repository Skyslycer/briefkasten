package de.skyslycer.briefkasten

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProtocolTest {
    @Test fun `decrypts binary images with URL derived key and rejects tampering`() {
        val url = "https://briefasive-git.dhl.de/preview/test%2Fimage-123?token=rotating"
        val digest = MessageDigest.getInstance("SHA-512").digest("test%2Fimage-123".toByteArray())
        val image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0, 1, 2, 3, 0xff.toByte(), 0xd9.toByte())
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(digest.copyOfRange(0, 32), "AES"), GCMParameterSpec(128, digest.copyOfRange(32, 44)))
            updateAAD(digest.copyOfRange(44, 56)); doFinal(image)
        }
        assertArrayEquals(image, LetterCrypto.decrypt(url, encrypted))
        assertArrayEquals(image, LetterCrypto.decrypt(url.substringBefore('?') + "?token=new", encrypted))
        encrypted[0] = (encrypted[0].toInt() xor 1).toByte()
        assertThrows(javax.crypto.AEADBadTagException::class.java) { LetterCrypto.decrypt(url, encrypted) }
    }

    @Test fun `parses current and historical letters without discarding duplicates with different URLs`() {
        val response = protocolJson.decodeFromString<AdviceResponse>("""{
          "serviceState":{"contractState":"ACTIVE","recognitionDegree":3},
          "currentAdvice":[{"date":"02.11.2026","advices":[{"image_url":"https://www.dhl.de/images/a"},{"image_url":"https://www.dhl.de/images/b"}]}],
          "oldAdvices":[{"date":"02.11.2026","advices":[{"image_url":"https://www.dhl.de/images/a"}]},{"date":"01.11.2026","advices":[{"image_url":"https://www.dhl.de/images/c","futureField":true}]}],
          "grantToken":"grant","accessTokenUrl":"https://www.dhl.de/token","futureField":{}
        }""")
        assertEquals(3, response.candidates().size)
        assertTrue(response.candidates()[0].current)
        assertFalse(response.candidates()[2].current)
    }

    @Test fun `missing account state is never treated as an empty mailbox`() {
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            protocolJson.decodeFromString<AdviceResponse>("{}")
        }
    }

    @Test fun `does not forward grants to unsafe URLs`() {
        listOf("http://www.dhl.de/a", "https://dhl.de.attacker.example/a", "https://evil-dhl.de/a", "https://www.dhl.de:8443/a", "https://user:pass@www.dhl.de/a").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { trustedUrl(url) }
        }
        assertEquals("www.dhl.de", trustedUrl("/int-aviseanzeigen/advices").host)
        assertEquals("briefasive-git.dhl.de", trustedUrl("https://briefasive-git.dhl.de/a").host)
    }

    @Test fun `archive identity isolates accounts dates and duplicate envelopes`() {
        val id = archiveId("account", "date", "digest", 1)
        assertNotEquals(id, archiveId("account", "date", "digest", 2))
        assertNotEquals(id, archiveId("other", "date", "digest", 1))
        assertNotEquals(id, archiveId("account", "other", "digest", 1))
        assertEquals(".._.._letter", safeDate("../../letter"))
    }

    @Test fun `download size is bounded even for streams without content length`() {
        assertArrayEquals(byteArrayOf(1, 2), byteArrayOf(1, 2).inputStream().readBounded(2))
        assertThrows(java.io.IOException::class.java) { ByteArray(1025).inputStream().readBounded(1024) }
    }
}
