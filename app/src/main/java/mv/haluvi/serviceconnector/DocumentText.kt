package mv.haluvi.serviceconnector

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Reads the text of an attached file: plain text, Word (.docx) or a PDF with real text. */
object DocumentText {
    class Result(val text: String?, val problem: String?, val kind: String)

    val MIME_TYPES = arrayOf(
        "text/plain", "application/pdf", "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    )

    fun read(context: Context, uri: Uri): Result {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }.orEmpty().lowercase()
        val type = resolver.getType(uri).orEmpty()
        return try {
            when {
                name.endsWith(".docx") || type.contains("wordprocessingml") ->
                    resolver.openInputStream(uri)!!.use { Result(docx(it), null, "Word document") }
                name.endsWith(".doc") || type == "application/msword" ->
                    Result(null, "Old Word .doc files cannot be read. In Word, save it as .docx, then attach it again.", "Word 97–2003")
                name.endsWith(".pdf") || type == "application/pdf" -> pdf(context, uri)
                else -> resolver.openInputStream(uri)!!.use { Result(plain(it.readBytes()), null, "text file") }
            }
        } catch (e: Exception) {
            Result(null, "The file could not be read: ${e.message ?: e.javaClass.simpleName}.", "file")
        }
    }

    private fun plain(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        else -> String(bytes, Charsets.UTF_8)
    }

    /** A .docx is a zip; its text is in word/document.xml, one <w:p> per paragraph. */
    fun docx(input: InputStream): String {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") return documentXml(zip)
            }
        }
        throw IllegalArgumentException("this is not a Word document")
    }

    fun documentXml(input: InputStream): String {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        parser.setInput(input, "UTF-8")
        val out = StringBuilder()
        var inText = false
        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (parser.name) {
                    "w:t" -> inText = true
                    "w:tab" -> out.append('\t')
                    "w:br", "w:cr" -> out.append('\n')
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "w:t" -> inText = false
                    "w:p" -> out.append('\n')
                }
                XmlPullParser.TEXT -> if (inText) out.append(parser.text)
            }
        }
        // Blank lines between paragraphs, so long text splits between paragraphs.
        return out.toString().lines().map { it.trimEnd() }.filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun pdf(context: Context, uri: Uri): Result {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
        val document = try {
            context.contentResolver.openInputStream(uri)!!.use { com.tom_roush.pdfbox.pdmodel.PDDocument.load(it) }
        } catch (e: Exception) {
            val locked = e.javaClass.simpleName.contains("Password", true) || e.message.orEmpty().contains("password", true)
            return Result(null, if (locked) "This PDF is password protected. Remove the password, then attach it again."
                else "The PDF could not be opened: ${e.message ?: e.javaClass.simpleName}.", "PDF")
        }
        document.use { doc ->
            val text = com.tom_roush.pdfbox.text.PDFTextStripper().apply { sortByPosition = true }.getText(doc)
            val cleaned = text.lines().map { it.trimEnd() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
            if (cleaned.length < 5) return Result(null, "This PDF has no readable text; it is probably scanned pages.", "PDF")
            return Result(cleaned, null, "PDF with ${doc.numberOfPages} pages")
        }
    }
}
