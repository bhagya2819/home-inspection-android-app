package com.griha.inspection.core.report

import com.griha.inspection.core.model.FindingStatus
import com.griha.inspection.core.model.Severity

// Plain data for one report, already resolved from the database (PRD §13 structure).

data class ReportPhoto(val image: JpegImage, val caption: String?)

data class ReportFinding(
    val title: String,
    val severity: Severity?,
    val category: String?,
    val status: FindingStatus,
    val observation: String?,
    val implication: String?,
    val recommendation: String?,
    val photos: List<ReportPhoto>,
)

/** [commonPhotos]: the room's overview photos, printed under its heading before the findings. */
data class ReportRoom(val name: String, val findings: List<ReportFinding>, val commonPhotos: List<ReportPhoto> = emptyList())

data class ReportDocument(
    val reportNumber: String,
    /** "Version 2", or null for a draft. */
    val version: Int?,
    val clientName: String,
    val clientPhone: String,
    val propertyAddress: String,
    val unitNumber: String?,
    val propertyType: String?,
    val inspectionDate: String,
    val inspectorName: String,
    /** Overview paragraph written for the client; null = none. */
    val summary: String? = null,
    val rooms: List<ReportRoom>,
    val logo: JpegImage?,
) {
    val versionLabel get() = version?.let { "Version $it" } ?: "DRAFT"
    val propertyLine get() = listOfNotNull(unitNumber, propertyAddress).joinToString(", ")
}

/**
 * Severities listed in the report summary as "significant findings". Ordered most severe
 * first, which is also the order the summary lists them in.
 */
val SignificantSeverities = listOf(Severity.URGENT, Severity.MAJOR, Severity.MODERATE)

/**
 * Lays a [ReportDocument] out on A4 pages. Pure Kotlin (no Android types), so pagination
 * is unit-testable on the JVM. Only page content (a few KB per page) is held in memory;
 * photos are referenced by file and streamed in by [PdfWriter].
 */
class ReportLayout(private val doc: ReportDocument) {
    private val pages = ArrayList<PageContent>()
    private lateinit var page: PageContent
    private var y = 0f
    private var photoNumber = 0

    fun build(): List<PageContent> {
        newPage(first = true)
        coverHeader()
        detailsSection()
        summarySection()
        newPage()
        heading("Room-by-room findings")
        doc.rooms.forEachIndexed { index, room -> roomSection(index + 1, room) }
        scopeSection()
        pages.forEachIndexed { index, p -> chrome(p, index + 1, pages.size) }
        return pages
    }

    // ---- Page furniture --------------------------------------------------------------------

    private fun newPage(first: Boolean = false) {
        page = PageContent(PAGE_H)
        pages += page
        if (doc.version == null) {
            page.rotatedText(PAGE_W / 2, PAGE_H / 2, 52.0, "DRAFT", PdfFont.BOLD, 110f, WATERMARK)
        }
        y = if (first) MARGIN_TOP else RUNNING_HEADER_BOTTOM + 18f
    }

    /** Running header (pages 2+) and footer, added once the page count is known. */
    private fun chrome(p: PageContent, number: Int, total: Int) {
        val ref = "Report ${doc.reportNumber} · ${doc.versionLabel}"
        if (number > 1) {
            doc.logo?.let { p.image(it, MARGIN, 22f, 22f, 22f) }
            p.text(MARGIN + 30f, 37f, "Griha Home Inspection", PdfFont.BOLD, 9.5f, DEEP)
            rightText(p, 37f, ref, PdfFont.REGULAR, 8.5f, MUTED)
            p.line(MARGIN, RUNNING_HEADER_BOTTOM, PAGE_W - MARGIN, RUNNING_HEADER_BOTTOM, LINE)
        }
        p.line(MARGIN, PAGE_H - 44f, PAGE_W - MARGIN, PAGE_H - 44f, LINE)
        p.text(MARGIN, PAGE_H - 30f, "Griha Home Inspection · $ref", PdfFont.REGULAR, 8f, MUTED)
        rightText(p, PAGE_H - 30f, "Page $number of $total", PdfFont.REGULAR, 8f, MUTED)
    }

    private fun coverHeader() {
        page.rect(0f, 0f, PAGE_W, 124f, CREAM)
        doc.logo?.let { page.image(it, MARGIN - 6f, 26f, 72f, 72f) }
        val x = MARGIN + 80f
        val rightW = 120f
        page.text(x, 42f, "PROPERTY INSPECTION REPORT", PdfFont.BOLD, 8f, FRESH)
        var ty = 62f
        wrap(doc.propertyLine, PdfFont.BOLD, 16f, PAGE_W - MARGIN - rightW - x).take(2).forEach {
            page.text(x, ty, it, PdfFont.BOLD, 16f, DEEP); ty += 19f
        }
        page.text(x, ty + 2f, "Inspected on ${doc.inspectionDate}", PdfFont.REGULAR, 10f, MUTED)

        rightText(page, 42f, "REPORT NO.", PdfFont.BOLD, 7.5f, MUTED)
        rightText(page, 56f, doc.reportNumber, PdfFont.BOLD, 11f, DEEP)
        rightText(page, 76f, "VERSION", PdfFont.BOLD, 7.5f, MUTED)
        if (doc.version == null) rightText(page, 90f, "Draft – not final", PdfFont.BOLD, 11f, WARN)
        else rightText(page, 90f, doc.version.toString(), PdfFont.BOLD, 11f, DEEP)
        page.rect(0f, 124f, PAGE_W, 2.5f, GREEN)
        y = 152f
    }

    private fun detailsSection() {
        heading("Prepared for")
        val half = (CONTENT_W - GUTTER) / 2
        fun row(a: Pair<String, String?>, b: Pair<String, String?>?) {
            val h1 = field(a.first, a.second, MARGIN, if (b == null) CONTENT_W else half, measureOnly = true)
            val h2 = b?.let { field(it.first, it.second, MARGIN + half + GUTTER, half, measureOnly = true) } ?: 0f
            val h = maxOf(h1, h2)
            ensure(h)
            field(a.first, a.second, MARGIN, if (b == null) CONTENT_W else half)
            b?.let { field(it.first, it.second, MARGIN + half + GUTTER, half) }
            y += h + 8f
        }
        row("Client" to doc.clientName, "Phone" to doc.clientPhone)
        row("Property address" to doc.propertyAddress, null)
        row("Unit / flat" to doc.unitNumber, "Property type" to doc.propertyType)
        row("Inspection date" to doc.inspectionDate, "Inspector" to doc.inspectorName)
        y += 6f
    }

    /** Label above a wrapped value. Returns its height; draws nothing if [measureOnly]. */
    private fun field(label: String, value: String?, x: Float, w: Float, measureOnly: Boolean = false): Float {
        val lines = wrap(value?.ifBlank { null } ?: "—", PdfFont.REGULAR, 10.5f, w)
        if (!measureOnly) {
            page.text(x, y + 8f, label.uppercase(), PdfFont.BOLD, 7.5f, MUTED)
            lines.forEachIndexed { i, line -> page.text(x, y + 23f + i * 14f, line, PdfFont.REGULAR, 10.5f, TEXT) }
        }
        return 14f + lines.size * 14f
    }

    private fun summarySection() {
        heading("Inspection summary")
        doc.summary?.takeIf { it.isNotBlank() }?.let { text ->
            paragraph(text.trim(), PdfFont.REGULAR, 10.5f, TEXT)
            y += 10f
        }
        val findings = doc.rooms.flatMap { r -> r.findings.map { r to it } }
        val photos = findings.sumOf { it.second.photos.size } + doc.rooms.sumOf { it.commonPhotos.size }
        ensure(20f)
        paragraphLine(
            "Areas inspected: ${doc.rooms.size}     Findings recorded: ${findings.size}     Photos: $photos",
            PdfFont.REGULAR, 10.5f, TEXT,
        )
        y += 6f

        // Count per severity, most severe first; findings without a rating are counted apart.
        val counts = Severity.entries.reversed().map { s -> s to findings.count { it.second.severity == s } }.filter { it.second > 0 }
        if (counts.isNotEmpty()) {
            ensure(24f)
            var x = MARGIN
            counts.forEach { (severity, count) -> x += chip(x, y, "${severity.label}  $count", severity) + 8f }
            y += 30f
        }

        subheading("Significant findings")
        val significant = findings
            .filter { it.second.severity in SignificantSeverities }
            .sortedBy { SignificantSeverities.indexOf(it.second.severity) } // stable: keeps room order within a severity
        if (significant.isEmpty()) {
            paragraph("No findings rated Moderate, High or Urgent were recorded.", PdfFont.REGULAR, 10f, MUTED)
        }
        significant.forEach { (room, finding) ->
            val chipW = chipWidth(finding.severity!!.label) + 10f
            val lines = wrap("${finding.title} — ${room.name}", PdfFont.REGULAR, 10f, CONTENT_W - chipW)
            ensure(lines.size * 14f + 6f)
            chip(MARGIN, y, finding.severity.label, finding.severity)
            lines.forEachIndexed { i, line -> page.text(MARGIN + chipW, y + 11f + i * 14f, line, PdfFont.REGULAR, 10f, TEXT) }
            y += lines.size * 14f + 6f
        }
    }

    private fun roomSection(number: Int, room: ReportRoom) {
        ensure(90f) // never strand a room heading at the bottom of a page
        page.rect(MARGIN, y, CONTENT_W, 26f, SAGE)
        page.text(MARGIN + 10f, y + 17f, "$number. ${room.name}", PdfFont.BOLD, 12f, DEEP)
        val count = when (room.findings.size) { 0 -> "No findings"; 1 -> "1 finding"; else -> "${room.findings.size} findings" }
        rightText(page, y + 17f, count, PdfFont.REGULAR, 9f, DEEP, inset = 10f)
        y += 38f
        if (room.commonPhotos.isNotEmpty()) {
            ensure(34f)
            page.text(MARGIN, y + 9f, "ROOM PHOTOS", PdfFont.BOLD, 7.5f, GREEN)
            y += 9f
            photoGrid(room.commonPhotos)
            y += 4f
        }
        if (room.findings.isEmpty()) {
            paragraph("No findings recorded for this area.", PdfFont.REGULAR, 10f, MUTED)
            y += 10f
        }
        room.findings.forEachIndexed { index, finding -> findingBlock("$number.${index + 1}", finding) }
    }

    private fun findingBlock(number: String, f: ReportFinding) {
        val chipW = f.severity?.let { chipWidth(it.label) + 10f } ?: 0f
        val titleLines = wrap("$number  ${f.title}", PdfFont.BOLD, 11.5f, CONTENT_W - chipW)
        ensure(titleLines.size * 15f + 60f) // title plus the start of its text
        f.severity?.let { chip(PAGE_W - MARGIN - chipW + 10f, y, it.label, it) }
        titleLines.forEachIndexed { i, line -> page.text(MARGIN, y + 11f + i * 15f, line, PdfFont.BOLD, 11.5f, DEEP) }
        y += titleLines.size * 15f + 2f
        val meta = listOfNotNull(f.category, f.status.takeIf { it != FindingStatus.OPEN }?.label).joinToString(" · ")
        if (meta.isNotEmpty()) paragraph(meta, PdfFont.REGULAR, 9f, MUTED)
        y += 4f

        labelled("Observation", f.observation)
        labelled("Implication", f.implication)
        labelled("Recommendation", f.recommendation)
        photoGrid(f.photos)

        y += 4f
        ensure(12f)
        page.line(MARGIN, y, PAGE_W - MARGIN, y, LINE)
        y += 16f
    }

    private fun labelled(label: String, text: String?) {
        if (text.isNullOrBlank()) return
        ensure(34f)
        page.text(MARGIN, y + 9f, label.uppercase(), PdfFont.BOLD, 7.5f, GREEN)
        y += 13f
        paragraph(text, PdfFont.REGULAR, 10f, TEXT)
        y += 6f
    }

    /** Two photos per row, aspect ratio kept, captions below. A row never splits across pages. */
    private fun photoGrid(photos: List<ReportPhoto>) {
        if (photos.isEmpty()) return
        val colW = (CONTENT_W - GUTTER) / 2
        y += 4f
        photos.chunked(2).forEach { row ->
            val cells = row.map { photo ->
                photoNumber++
                val scale = minOf(colW / photo.image.widthPx, MAX_PHOTO_H / photo.image.heightPx)
                val w = photo.image.widthPx * scale
                val h = photo.image.heightPx * scale
                val caption = "Photo $photoNumber" + (photo.caption?.let { " · $it" } ?: "")
                Cell(photo, w, h, wrap(caption, PdfFont.REGULAR, 8.5f, maxOf(w, 150f)).take(4))
            }
            val rowH = cells.maxOf { it.h + 6f + it.caption.size * 11f }
            ensure(rowH)
            cells.forEachIndexed { i, c ->
                val colX = MARGIN + i * (colW + GUTTER)
                val x = colX + (colW - c.w) / 2
                page.rect(x - 0.5f, y - 0.5f, c.w + 1f, c.h + 1f, LINE)
                page.image(c.photo.image, x, y, c.w, c.h)
                // Caption starts under the image's left edge (portrait photos are centred, narrower).
                val captionX = minOf(x, colX + colW - 150f)
                c.caption.forEachIndexed { li, line -> page.text(captionX, y + c.h + 13f + li * 11f, line, PdfFont.REGULAR, 8.5f, MUTED) }
            }
            y += rowH + 12f
        }
    }

    private class Cell(val photo: ReportPhoto, val w: Float, val h: Float, val caption: List<String>)

    private fun scopeSection() {
        ensure(120f)
        y += 6f
        heading("Scope and limitations")
        ScopeText.forEach { paragraph(it, PdfFont.REGULAR, 9.5f, TEXT, leading = 13f); y += 6f }
    }

    // ---- Primitives ------------------------------------------------------------------------

    private fun heading(text: String) {
        ensure(40f)
        page.text(MARGIN, y + 14f, text, PdfFont.BOLD, 14f, GREEN)
        y += 28f
    }

    private fun subheading(text: String) {
        ensure(34f)
        page.text(MARGIN, y + 12f, text, PdfFont.BOLD, 11f, DEEP)
        y += 22f
    }

    /** Wrapped text; lines flow onto the next page one at a time. */
    private fun paragraph(text: String, font: PdfFont, size: Float, color: Int, leading: Float = size * 1.4f) {
        wrap(text, font, size, CONTENT_W).forEach { paragraphLine(it, font, size, color, leading) }
    }

    private fun paragraphLine(line: String, font: PdfFont, size: Float, color: Int, leading: Float = size * 1.4f) {
        ensure(leading)
        page.text(MARGIN, y + size, line, font, size, color)
        y += leading
    }

    private fun chipWidth(label: String) = PdfFont.BOLD.width(label, 8f) + 14f

    /** Draws a severity chip with its top-left at ([x], [top]); returns its width. */
    private fun chip(x: Float, top: Float, label: String, severity: Severity): Float {
        val (bg, fg) = SeverityPdfColors.getValue(severity)
        val w = chipWidth(label)
        page.rect(x, top, w, 15f, bg)
        page.text(x + 7f, top + 10.8f, label, PdfFont.BOLD, 8f, fg)
        return w
    }

    private fun rightText(p: PageContent, baseline: Float, text: String, font: PdfFont, size: Float, color: Int, inset: Float = 0f) {
        p.text(PAGE_W - MARGIN - inset - font.width(text, size), baseline, text, font, size, color)
    }

    /** Starts a new page unless [height] more points fit above the footer. */
    private fun ensure(height: Float) {
        if (y + height > CONTENT_BOTTOM) newPage()
    }

    companion object {
        const val PAGE_W = 595.28f // A4 in points
        const val PAGE_H = 841.89f
        private const val MARGIN = 44f
        private const val CONTENT_W = PAGE_W - 2 * MARGIN
        private const val GUTTER = 14f
        private const val MARGIN_TOP = 40f
        private const val RUNNING_HEADER_BOTTOM = 52f
        private const val CONTENT_BOTTOM = PAGE_H - 58f
        private const val MAX_PHOTO_H = 230f

        // Brand palette (ui/theme/Theme.kt) as RGB ints.
        private const val GREEN = 0x26552A
        private const val FRESH = 0x4F8F48 // brand "fresh" green darkened for small text on cream
        private const val DEEP = 0x1B392B
        private const val CREAM = 0xF8F6EA
        private const val SAGE = 0xDDE7D8
        private const val TEXT = 0x202A22
        private const val MUTED = 0x6D776E
        private const val LINE = 0xC9D0C5
        private const val WARN = 0xB26A00
        private const val WATERMARK = 0xEEEEEE

        /** Same pairs as the app's severity badges (ui/components/Common.kt). */
        private val SeverityPdfColors = mapOf(
            Severity.INFO to (0xE3ECF7 to 0x1F4E79),
            Severity.MINOR to (0xDDE7D8 to 0x1B392B),
            Severity.MODERATE to (0xFFF3D8 to 0x7A4B00),
            Severity.MAJOR to (0xFFE0CC to 0x8A3300),
            Severity.URGENT to (0xFFDAD6 to 0x93000A),
        )

        // P1 (PRD §5.7) makes disclaimers configurable by admins; until then, this default text.
        val ScopeText = listOf(
            "This report records the visible condition of the accessible parts of the property on the inspection " +
                "date. It is a non-invasive visual inspection, not a structural engineering survey, a valuation, " +
                "or a guarantee of future performance.",
            "Areas that were concealed, locked, furnished, covered or otherwise not accessible were not inspected. " +
                "Defects may exist that were not visible at the time of inspection.",
            "Severity ratings are the inspector's professional opinion. Urgent: needs immediate attention. " +
                "High: significant, repair soon. Moderate: plan a repair. Low: minor. Observation: noted for information.",
            "Recommended work should be carried out by suitably qualified professionals. Where the report suggests " +
                "specialist advice, obtain it before relying on this report for decisions.",
            "This report is prepared for the client named above and should be read in full.",
        )
    }
}

/** Greedy word wrap using the PDF font metrics. Explicit newlines start new lines. */
fun wrap(text: String, font: PdfFont, size: Float, maxWidth: Float): List<String> {
    val out = ArrayList<String>()
    for (para in WinAnsi.normalize(text).split('\n')) {
        val words = para.trim().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) { out += ""; continue }
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (font.width(candidate, size) <= maxWidth) { line = candidate; continue }
            if (line.isNotEmpty()) out += line
            var rest = word
            while (font.width(rest, size) > maxWidth) { // a single word wider than the line
                var k = rest.length - 1
                while (k > 1 && font.width(rest.substring(0, k), size) > maxWidth) k--
                out += rest.substring(0, k)
                rest = rest.substring(k)
            }
            line = rest
        }
        out += line
    }
    return out.dropLastWhile { it.isBlank() }.ifEmpty { listOf("") }
}
