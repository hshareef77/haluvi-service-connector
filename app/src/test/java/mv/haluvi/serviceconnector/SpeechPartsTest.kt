package mv.haluvi.serviceconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class SpeechPartsTest {
    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    @Test fun shortTextIsOnePart() {
        assertEquals(listOf("ހެލޯ"), SpeechParts.split("  ހެލޯ  ", 100))
    }

    @Test fun splitsBetweenParagraphsFirst() {
        val a = "ފުރަތަމަ ޕެރެގްރާފް. " + "ހ".repeat(50)
        val b = "ދެވަނަ ޕެރެގްރާފް. " + "ށ".repeat(50)
        val parts = SpeechParts.split("$a\n\n$b", 100)
        assertEquals(listOf(a.trim(), b.trim()), parts)
    }

    @Test fun splitsAfterSentencesThenSpacesNeverInsideWords() {
        val sentence = "މިއީ ޖުމްލައެކެވެ. "
        val text = sentence.repeat(40)
        val parts = SpeechParts.split(text, 120)
        assertTrue(parts.all { it.length <= 120 })
        assertTrue(parts.dropLast(1).all { it.endsWith(".") })
        assertEquals(words(text), parts.flatMap { words(it) })

        val noStops = "ބަސް ".repeat(200)
        val spaced = SpeechParts.split(noStops, 50)
        assertTrue(spaced.all { it.length <= 50 })
        assertEquals(words(noStops), spaced.flatMap { words(it) })
    }

    @Test fun longRealisticTextKeepsEveryWordWithinTheLimit() {
        val paragraph = ("ދިވެހި ބަހުން ލިޔެވިފައިވާ ޖުމްލައެއް. " ).repeat(60)
        val text = (1..12).joinToString("\n\n") { "$it. $paragraph" }
        val parts = SpeechParts.split(text)
        assertTrue(text.length > SpeechParts.MAX_PART)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= SpeechParts.MAX_PART })
        assertEquals(words(text), parts.flatMap { words(it) })
    }

    private fun wav(file: File, samples: ByteArray, rate: Int = 8000) {
        val out = ByteArrayOutputStream()
        fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); le32(36 + samples.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(rate); le32(rate); le16(1); le16(8)
        out.write("data".toByteArray()); le32(samples.size); out.write(samples)
        file.writeBytes(out.toByteArray())
    }

    @Test fun joinsWavPartsIntoOneValidWav() {
        val dir = createTempDir()
        val a = File(dir, "a.wav").also { wav(it, ByteArray(100) { 1 }) }
        val b = File(dir, "b.wav").also { wav(it, ByteArray(60) { 2 }) }
        val out = File(dir, "out.wav")
        assertNull(SpeechParts.join(listOf(a, b), out))
        val bytes = out.readBytes()
        assertEquals(44 + 160, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        val dataSize = (bytes[40].toInt() and 0xFF) or ((bytes[41].toInt() and 0xFF) shl 8)
        assertEquals(160, dataSize)
        assertEquals(1.toByte(), bytes[44]); assertEquals(2.toByte(), bytes[44 + 100])
        dir.deleteRecursively()
    }

    @Test fun refusesWavPartsWithDifferentFormats() {
        val dir = createTempDir()
        val a = File(dir, "a.wav").also { wav(it, ByteArray(10), 8000) }
        val b = File(dir, "b.wav").also { wav(it, ByteArray(10), 16000) }
        assertEquals("the parts have different audio formats", SpeechParts.join(listOf(a, b), File(dir, "o.wav")))
        dir.deleteRecursively()
    }
}
