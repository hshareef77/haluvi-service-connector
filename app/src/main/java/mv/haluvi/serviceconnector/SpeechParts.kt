package mv.haluvi.serviceconnector

import java.io.File
import java.io.RandomAccessFile

/** Splitting long text into parts and joining the generated audio parts. No Android dependencies. */
object SpeechParts {
    const val MAX_PART = 9_000

    private val sentenceEnd = Regex("[.!?؟。]+[\"'”’)\\]]*\\s")

    /**
     * Splits [text] into parts of at most [max] characters: between paragraphs first,
     * then after a sentence, then at a space. Words are never broken, and joining the
     * parts with whitespace gives back every word of the original.
     */
    fun split(text: String, max: Int = MAX_PART): List<String> {
        var rest = text.trim()
        val parts = mutableListOf<String>()
        val minimum = max / 3 // avoid tiny parts when a boundary sits near the start
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = window.lastIndexOf("\n\n").takeIf { it >= minimum }
                ?: window.lastIndexOf('\n').takeIf { it >= minimum }
                ?: sentenceEnd.findAll(window).lastOrNull()?.range?.last?.takeIf { it >= minimum }
                ?: window.lastIndexOf(' ').takeIf { it >= minimum }
                ?: window.lastIndexOf(' ').takeIf { it > 0 }
                ?: max
            val part = rest.substring(0, cut).trim()
            if (part.isNotEmpty()) parts += part
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    private class Wav(val format: ByteArray, val dataOffset: Long, val dataSize: Long)

    private fun readWav(file: File): Wav? = RandomAccessFile(file, "r").use { f ->
        if (f.length() < 12) return null
        val head = ByteArray(12).also { f.readFully(it) }
        if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var format: ByteArray? = null
        var pos = 12L
        while (pos + 8 <= f.length()) {
            f.seek(pos)
            val id = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
            val size = Integer.reverseBytes(f.readInt()).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            when (id) {
                "fmt " -> format = ByteArray(size.toInt()).also { f.readFully(it) }
                "data" -> {
                    val real = minOf(size, f.length() - body) // some servers write 0 or a too-large size
                    return format?.let { Wav(it, body, if (size == 0L || size == 0xFFFFFFFFL) f.length() - body else real) }
                }
            }
            pos = body + size + (size and 1)
        }
        null
    }

    /**
     * Joins audio parts into [out]. WAV parts with the same format are joined into one
     * valid WAV file; other formats (such as MP3) are joined end to end.
     * Returns null on success, or the reason it could not be joined.
     */
    fun join(parts: List<File>, out: File): String? {
        if (parts.isEmpty()) return "there were no parts"
        if (parts.size == 1) { parts[0].copyTo(out, overwrite = true); return null }
        val wavs = parts.map { readWav(it) }
        if (wavs.all { it != null }) {
            val first = wavs[0]!!
            if (wavs.any { !it!!.format.contentEquals(first.format) }) return "the parts have different audio formats"
            val total = wavs.sumOf { it!!.dataSize }
            RandomAccessFile(out, "rw").use { o ->
                o.setLength(0)
                fun le32(v: Long) = o.writeInt(Integer.reverseBytes(v.toInt()))
                o.writeBytes("RIFF"); le32(4 + 8 + first.format.size + 8 + total); o.writeBytes("WAVE")
                o.writeBytes("fmt "); le32(first.format.size.toLong()); o.write(first.format)
                o.writeBytes("data"); le32(total)
                val buffer = ByteArray(256 * 1024)
                parts.zip(wavs).forEach { (file, wav) ->
                    RandomAccessFile(file, "r").use { f ->
                        f.seek(wav!!.dataOffset)
                        var left = wav.dataSize
                        while (left > 0) {
                            val n = f.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (n < 0) break
                            o.write(buffer, 0, n); left -= n
                        }
                    }
                }
            }
            return null
        }
        if (wavs.any { it != null }) return "the parts have different audio formats"
        out.outputStream().use { o -> parts.forEach { p -> p.inputStream().use { it.copyTo(o) } } }
        return null
    }
}
