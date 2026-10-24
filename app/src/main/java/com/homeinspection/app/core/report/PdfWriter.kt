package com.homeinspection.app.core.report

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Minimal streaming PDF 1.4 writer: text in the standard Helvetica fonts, filled rectangles,
 * lines, and JPEG images embedded byte-for-byte (DCTDecode).
 *
 * Why not android.graphics.pdf.PdfDocument: measured on Android 17, it keeps every page's
 * bitmaps in native memory until writeTo() (~7.7 MB per 1600 px photo) and stores them
 * losslessly (~5x the JPEG size). A 30-photo report was 59 MB and needed 230 MB of RAM.
 * Here each page is written as soon as it is finished and a photo never gets decoded at all.
 *
 * Output is deterministic: identical input gives identical bytes (no timestamps, no ids),
 * so the content hash of a report only changes when its content does.
 */
class PdfWriter(out: OutputStream, private val pageWidth: Float, private val pageHeight: Float) : AutoCloseable {
    private val out = CountingStream(out)
    private val offsets = ArrayList<Long>() // offsets[n - 1] = byte offset of object n
    private val pageIds = ArrayList<Int>()
    private val imageIds = HashMap<String, Int>()
    private val pagesId: Int
    private val fontRegularId: Int
    private val fontBoldId: Int

    init {
        // Binary marker comment tells transfer tools the file is not plain text.
        this.out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        pagesId = reserve()
        fontRegularId = writeObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        fontBoldId = writeObject("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
    }

    /** Writes one finished page. [images] maps names used by [content] to JPEG files. */
    fun addPage(content: PageContent) {
        val xobjects = content.images.entries.joinToString(" ") { (name, image) -> "/$name ${imageObject(image)} 0 R" }
        val streamId = writeStream(content.bytes(), compress = true, dict = "")
        val pageId = writeObject(
            "<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 ${n(pageWidth)} ${n(pageHeight)}] " +
                "/Resources << /Font << /F1 $fontRegularId 0 R /F2 $fontBoldId 0 R >> /XObject << $xobjects >> >> " +
                "/Contents $streamId 0 R >>"
        )
        pageIds += pageId
    }

    /** Finishes the document (page tree, catalog, xref) and closes the stream. */
    fun finish(title: String) {
        val kids = pageIds.joinToString(" ") { "$it 0 R" }
        writeObject("<< /Type /Pages /Kids [$kids] /Count ${pageIds.size} >>", id = pagesId)
        val infoId = writeObject("<< /Producer (Home Inspection) /Title ${pdfString(title)} >>")
        val catalogId = writeObject("<< /Type /Catalog /Pages $pagesId 0 R >>")
        val xrefAt = out.count
        val sb = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", it)) }
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root $catalogId 0 R /Info $infoId 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    override fun close() = out.close()

    /** Each distinct JPEG is embedded once, however many pages use it (e.g. the logo). */
    private fun imageObject(image: JpegImage): Int = imageIds.getOrPut(image.file.path) {
        val length = image.file.length()
        val id = startObject()
        out.write(
            ("<< /Type /XObject /Subtype /Image /Width ${image.widthPx} /Height ${image.heightPx} " +
                "/ColorSpace /${if (image.gray) "DeviceGray" else "DeviceRGB"} /BitsPerComponent 8 " +
                "/Filter /DCTDecode /Length $length >>\nstream\n").toByteArray(Charsets.ISO_8859_1)
        )
        image.file.inputStream().use { it.copyTo(out, 64 * 1024) }
        out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        id
    }

    private fun reserve(): Int {
        offsets += -1L
        return offsets.size
    }

    private fun startObject(id: Int = reserve()): Int {
        offsets[id - 1] = out.count
        out.write("$id 0 obj\n".toByteArray(Charsets.ISO_8859_1))
        return id
    }

    private fun writeObject(body: String, id: Int = reserve()): Int {
        startObject(id)
        out.write(WinAnsi.encode("$body\nendobj\n"))
        return id
    }

    private fun writeStream(data: ByteArray, compress: Boolean, dict: String): Int {
        val bytes = if (compress) deflate(data) else data
        val filter = if (compress) "/Filter /FlateDecode " else ""
        val id = startObject()
        out.write("<< $dict$filter/Length ${bytes.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        return id
    }

    private fun deflate(data: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream(data.size / 3 + 64)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        DeflaterOutputStream(buffer, deflater).use { it.write(data) }
        deflater.end()
        return buffer.toByteArray()
    }

    private class CountingStream(private val target: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) { target.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { target.write(b, off, len); count += len }
        override fun flush() = target.flush()
        override fun close() = target.close()
    }
}

/** A JPEG to embed as-is. Dimensions are the stored pixel size (no EXIF rotation applied by PDF). */
data class JpegImage(val file: File, val widthPx: Int, val heightPx: Int, val gray: Boolean = false)

enum class PdfFont(val resource: String, internal val widths: IntArray) {
    REGULAR("F1", HelveticaWidths), BOLD("F2", HelveticaBoldWidths);

    /** Width in points of [text] at [size], using the same WinAnsi mapping the writer uses. */
    fun width(text: String, size: Float): Float {
        var units = 0
        for (b in WinAnsi.encode(text)) units += widths[b.toInt() and 0xFF]
        return units * size / 1000f
    }
}

/**
 * Builds one page's content stream. Coordinates are top-left based (y grows downwards),
 * like the rest of the layout code; conversion to PDF's bottom-left origin happens here.
 */
class PageContent(private val pageHeight: Float) {
    private val sb = StringBuilder()
    internal val images = LinkedHashMap<String, JpegImage>()

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        sb.append("${rgb(color)} rg ${n(x)} ${n(pageHeight - y - h)} ${n(w)} ${n(h)} re f\n")
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float = 0.6f) {
        sb.append("${rgb(color)} RG ${n(width)} w ${n(x1)} ${n(pageHeight - y1)} m ${n(x2)} ${n(pageHeight - y2)} l S\n")
    }

    /** [y] is the text baseline. */
    fun text(x: Float, y: Float, text: String, font: PdfFont, size: Float, color: Int) {
        if (text.isEmpty()) return
        sb.append("BT /${font.resource} ${n(size)} Tf ${rgb(color)} rg ${n(x)} ${n(pageHeight - y)} Td ")
            .append(pdfString(text)).append(" Tj ET\n")
    }

    /** Large rotated text, e.g. a DRAFT watermark, centred on ([cx], [cy]). */
    fun rotatedText(cx: Float, cy: Float, degrees: Double, text: String, font: PdfFont, size: Float, color: Int) {
        val r = Math.toRadians(degrees)
        val c = Math.cos(r).toFloat(); val s = Math.sin(r).toFloat()
        val half = font.width(text, size) / 2
        val px = cx - c * half + s * size * 0.35f
        val py = (pageHeight - cy) - s * half - c * size * 0.35f
        sb.append("BT /${font.resource} ${n(size)} Tf ${rgb(color)} rg ${n(c)} ${n(s)} ${n(-s)} ${n(c)} ${n(px)} ${n(py)} Tm ")
            .append(pdfString(text)).append(" Tj ET\n")
    }

    fun image(image: JpegImage, x: Float, y: Float, w: Float, h: Float) {
        val name = images.entries.firstOrNull { it.value.file == image.file }?.key
            ?: "Im${images.size + 1}".also { images[it] = image }
        sb.append("q ${n(w)} 0 0 ${n(h)} ${n(x)} ${n(pageHeight - y - h)} cm /$name Do Q\n")
    }

    internal fun bytes(): ByteArray = WinAnsi.encode(sb.toString())
}

// ---- Encoding helpers ---------------------------------------------------------------------

/** Numbers with at most 2 decimals and no exponent, as PDF requires. */
private fun n(v: Float): String {
    val r = Math.round(v * 100) / 100.0
    return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
}

private fun rgb(color: Int): String {
    fun c(shift: Int) = n(((color shr shift) and 0xFF) / 255f)
    return "${c(16)} ${c(8)} ${c(0)}"
}

/** A PDF literal string. Characters outside WinAnsi become '?' (see [WinAnsi]). */
private fun pdfString(text: String): String {
    val sb = StringBuilder("(")
    for (ch in WinAnsi.normalize(text)) {
        when (ch) {
            '(', ')', '\\' -> sb.append('\\').append(ch)
            else -> sb.append(ch)
        }
    }
    return sb.append(')').toString()
}

/**
 * The standard fonts only cover WinAnsi (Latin-1 plus typographic quotes/dashes). Text is
 * normalised so a char's position in the returned string maps 1:1 to a WinAnsi byte.
 */
internal object WinAnsi {
    private val special = mapOf(
        '€' to 0x80, '‚' to 0x82, 'ƒ' to 0x83, '„' to 0x84, '…' to 0x85, '†' to 0x86, '‡' to 0x87,
        'ˆ' to 0x88, '‰' to 0x89, 'Š' to 0x8A, '‹' to 0x8B, 'Œ' to 0x8C, 'Ž' to 0x8E, '‘' to 0x91,
        '’' to 0x92, '“' to 0x93, '”' to 0x94, '•' to 0x95, '–' to 0x96, '—' to 0x97, '˜' to 0x98,
        '™' to 0x99, 'š' to 0x9A, '›' to 0x9B, 'œ' to 0x9C, 'ž' to 0x9E, 'Ÿ' to 0x9F,
    )

    /** Replaces what the fonts cannot show: ₹ → "Rs.", tabs → space, other unknowns → '?'. */
    fun normalize(text: String): String {
        if (text.all { it.code in 0x20..0x7E }) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '₹' -> sb.append("Rs.")
                ch == '\t' || ch == ' ' -> sb.append(' ')
                ch == '\n' || ch.code in 0x20..0x7E || ch.code in 0xA1..0xFF || ch in special -> sb.append(ch)
                ch.isLowSurrogate() -> Unit // second half of an emoji: the high half already became '?'
                else -> sb.append('?')
            }
        }
        return sb.toString()
    }

    fun encode(text: String): ByteArray {
        val normalized = normalize(text)
        val bytes = ByteArray(normalized.length)
        normalized.forEachIndexed { i, ch -> bytes[i] = (special[ch] ?: ch.code).toByte() }
        return bytes
    }
}

// Glyph widths (1/1000 em) from the Adobe Core 14 AFM files, indexed by WinAnsi code.
// Non-ASCII Latin-1 letters use their base letter's width; the error is a fraction of a point.

private fun widthTable(ascii: IntArray, latin: Int, extras: Map<Int, Int>): IntArray {
    val t = IntArray(256) { latin }
    ascii.forEachIndexed { i, w -> t[32 + i] = w }
    extras.forEach { (code, w) -> t[code] = w }
    return t
}

private val HelveticaWidths = widthTable(
    intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, // space..'/'
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, // 0..?
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, // @..O
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, // P.._
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, // `..o
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584, // p..~
    ),
    latin = 556,
    extras = mapOf(
        0x85 to 1000, 0x91 to 222, 0x92 to 222, 0x93 to 333, 0x94 to 333, 0x95 to 350, 0x96 to 556,
        0x97 to 1000, 0x99 to 1000, 0xA9 to 737, 0xAE to 737, 0xB0 to 400, 0xB7 to 278, 0xD7 to 584,
        0xBD to 834, 0xBC to 834, 0xBE to 834,
    ),
)

private val HelveticaBoldWidths = widthTable(
    intArrayOf(
        278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
        975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
        333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
        611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584,
    ),
    latin = 611,
    extras = mapOf(
        0x85 to 1000, 0x91 to 278, 0x92 to 278, 0x93 to 500, 0x94 to 500, 0x95 to 350, 0x96 to 556,
        0x97 to 1000, 0x99 to 1000, 0xA9 to 737, 0xAE to 737, 0xB0 to 400, 0xB7 to 278, 0xD7 to 584,
        0xBD to 834, 0xBC to 834, 0xBE to 834,
    ),
)
