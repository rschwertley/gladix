package dev.brahmkshatriya.echo.extension

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Utils.BlowfishStripeDecryptor (one Cipher per stream, ANR 54bbb97d) must give the same bytes as the per-stripe
 * Utils.decryptBlowfish it replaced: a new Cipher and init for every stripe.
 */
class BlowfishStripeDecryptorTest {

    private val block = 2048
    private val key = Utils.createBlowfishKey("3135556")

    private fun stripes(n: Int, seed: Int) = Random(seed).let { r -> List(n) { r.nextBytes(block) } }

    // The cipher on its own, many stripes in a row into ONE reused buffer, under the JDK default provider and
    // BouncyCastle (Android's Blowfish provider, and the one whose doFinal reset was read).
    @Test
    fun reusedCipherMatchesPerStripe() {
        for (provider in listOf(null, BouncyCastleProvider())) {
            val decryptor = Utils.BlowfishStripeDecryptor(key, provider)
            val out = ByteArray(block)
            stripes(500, seed = 1).forEachIndexed { i, stripe ->
                val expected = Utils.decryptBlowfish(stripe, key)
                assertEquals(block, decryptor.decrypt(stripe, out))
                assertArrayEquals("stripe $i, provider ${provider?.name ?: "default"}", expected, out)
            }
        }
    }

    // The real stream loop: every third full stripe from the FILE start is encrypted, a short last stripe never is.
    // Expected bytes come from the old per-stripe path. Odd read sizes cross stripe boundaries.
    private fun expectedFile(file: ByteArray, firstIndex: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var index = firstIndex
        var pos = 0
        while (pos < file.size) {
            val end = minOf(pos + block, file.size)
            val stripe = file.copyOfRange(pos, end)
            if (stripe.size == block) {
                out.write(if (index % 3L == 0L) Utils.decryptBlowfish(stripe, key) else stripe)
                index++
            } else out.write(stripe)
            pos = end
        }
        return out.toByteArray()
    }

    private fun readAll(file: ByteArray, firstIndex: Long, readSize: Int): ByteArray {
        val stream = AudioStreamProvider.StripeDecryptingInputStream(
            ByteArrayInputStream(file), key, firstIndex, onClose = {},
        )
        val out = ByteArrayOutputStream()
        val buf = ByteArray(readSize)
        stream.use {
            while (true) {
                val n = it.read(buf, 0, buf.size)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    @Test
    fun streamMatchesPerStripeWithShortLastStripe() {
        val file = Random(2).nextBytes(block * 301 + 777)   // 301 full stripes and a short one
        for (firstIndex in listOf(0L, 1L, 2L, 3000L))         // 3000: a range open past the start
            for (readSize in listOf(1, 333, block, 5000))
                assertArrayEquals(
                    "firstIndex=$firstIndex readSize=$readSize",
                    expectedFile(file, firstIndex), readAll(file, firstIndex, readSize),
                )
    }

    @Test
    fun streamMatchesWhenLengthIsExactStripes() {
        val file = Random(3).nextBytes(block * 9)
        assertArrayEquals(expectedFile(file, 0L), readAll(file, 0L, 4096))
    }
}
