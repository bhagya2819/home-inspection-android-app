package com.homeinspection.app.core.report

import com.homeinspection.app.core.model.FindingStatus
import com.homeinspection.app.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class ReportLayoutTest {
    private val fakeJpeg: File = Files.createTempFile("photo", ".jpg").toFile().apply {
        writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte()))
        deleteOnExit()
    }

    private fun doc(rooms: Int, findingsPerRoom: Int, photosPerFinding: Int, version: Int? = 1) = ReportDocument(
        reportNumber = "GRH-261020-ABCD",
        version = version,
        clientName = "Priya Reddy",
        clientPhone = "98480 00000",
        propertyAddress = "Lakshmi Residency, Guntur",
        unitNumber = "Flat 302",
        propertyType = "Apartment",
        inspectionDate = "20 October 2026",
        inspectorName = "Ronik",
        rooms = List(rooms) { r ->
            ReportRoom("Room ${r + 1}", List(findingsPerRoom) { f ->
                ReportFinding(
                    title = "Hairline crack near window $f",
                    severity = Severity.entries[f % Severity.entries.size],
                    category = "Walls & finishes",
                    status = FindingStatus.OPEN,
                    observation = "Observed a hairline crack about 30 cm long running diagonally from the window corner. ".repeat(3),
                    implication = "May allow moisture in.",
                    recommendation = "Seal with flexible filler and monitor.",
                    photos = List(photosPerFinding) { p -> ReportPhoto(JpegImage(fakeJpeg, if (p % 2 == 0) 1600 else 1200, if (p % 2 == 0) 1200 else 1600), "Caption $p") },
                )
            })
        },
        logo = JpegImage(fakeJpeg, 192, 192),
    )

    private fun render(d: ReportDocument): Pair<List<PageContent>, String> {
        val pages = ReportLayout(d).build()
        val out = ByteArrayOutputStream()
        PdfWriter(out, ReportLayout.PAGE_W, ReportLayout.PAGE_H).apply { pages.forEach(::addPage); finish("t") }
        return pages to out.toByteArray().toString(Charsets.ISO_8859_1)
    }

    @Test
    fun everyPageHasFooterWithTotal() {
        val pages = ReportLayout(doc(6, 3, 3)).build()
        assertTrue(pages.size > 5)
        pages.forEachIndexed { i, p ->
            val text = String(p.bytes(), Charsets.ISO_8859_1)
            assertTrue("page ${i + 1}", text.contains("(Page ${i + 1} of ${pages.size})"))
        }
    }

    @Test
    fun roomCommonPhotosPrintUnderTheRoomAndCountAsPhotosNotFindings() {
        val base = doc(2, 1, 1)
        val withCommon = base.copy(rooms = base.rooms.mapIndexed { i, r ->
            if (i == 0) r.copy(commonPhotos = List(4) { ReportPhoto(JpegImage(fakeJpeg, 1600, 1200), null) }) else r
        })
        val text = ReportLayout(withCommon).build().joinToString("") { String(it.bytes(), Charsets.ISO_8859_1) }
        assertTrue(text.contains("Findings recorded: 2"))
        assertTrue(text.contains("Photos: 6"))
        assertEquals(1, Regex("ROOM PHOTOS").findAll(text).count())
        // Numbering runs through the whole report: the room's photos (1-4) come first, then its finding's (5).
        assertTrue(text.indexOf("ROOM PHOTOS") < text.indexOf("Photo 1"))
        assertTrue(text.indexOf("Photo 4") in 0 until text.indexOf("Photo 5"))
        assertTrue(text.contains("Photo 6") && !text.contains("Photo 7"))
    }

    @Test
    fun morePhotosMeansMorePagesButEachImageEmbeddedOnce() {
        val (small, _) = render(doc(2, 1, 1))
        val (large, pdf) = render(doc(2, 1, 12))
        assertTrue(large.size > small.size)
        // One fake JPEG file used for every photo and the logo: embedded exactly once.
        assertEquals(1, Regex("/Subtype /Image").findAll(pdf).count())
    }

    @Test
    fun xrefOffsetsPointAtObjects() {
        val (_, pdf) = render(doc(3, 2, 2))
        val xrefAt = pdf.substringAfterLast("startxref\n").substringBefore('\n').trim().toInt()
        assertTrue(pdf.startsWith("xref", xrefAt))
        val entries = pdf.substring(xrefAt).lines().drop(3).takeWhile { it.endsWith(" n ") }
        entries.forEachIndexed { i, line ->
            val offset = line.take(10).toInt()
            assertTrue("object ${i + 1}", pdf.startsWith("${i + 1} 0 obj", offset))
        }
        assertTrue(pdf.trimEnd().endsWith("%%EOF"))
    }

    @Test
    fun outputIsDeterministic() {
        assertEquals(render(doc(3, 2, 2)).second, render(doc(3, 2, 2)).second)
    }

    @Test
    fun draftHasWatermarkAndFinalDoesNot() {
        val draft = ReportLayout(doc(1, 1, 0, version = null)).build().first()
        val final = ReportLayout(doc(1, 1, 0, version = 1)).build().first()
        assertTrue(String(draft.bytes(), Charsets.ISO_8859_1).contains("(DRAFT) Tj"))
        assertTrue(!String(final.bytes(), Charsets.ISO_8859_1).contains("(DRAFT) Tj"))
    }

    @Test
    fun summaryParagraphIsPrintedOnTheFirstPageOnlyWhenSet() {
        val with = ReportLayout(doc(1, 1, 0).copy(summary = "The property is in fair condition overall.")).build().first()
        val without = ReportLayout(doc(1, 1, 0)).build().first()
        assertTrue(String(with.bytes(), Charsets.ISO_8859_1).contains("(The property is in fair condition overall.) Tj"))
        assertTrue(!String(without.bytes(), Charsets.ISO_8859_1).contains("fair condition"))
    }

    @Test
    fun wrapRespectsWidthAndBreaksLongWords() {
        val lines = wrap("word ".repeat(50) + "x".repeat(200), PdfFont.REGULAR, 10f, 200f)
        assertTrue(lines.size > 3)
        lines.forEach { assertTrue(it, PdfFont.REGULAR.width(it, 10f) <= 200f) }
        assertEquals(listOf("a", "", "b"), wrap("a\n\nb", PdfFont.REGULAR, 10f, 200f))
    }

    @Test
    fun unsupportedCharactersAreReplacedNotDropped() {
        assertEquals("Rs.500 ? ok – “q”", WinAnsi.normalize("₹500 😀 ok – “q”"))
        val bytes = WinAnsi.encode("–")
        assertEquals(0x96, bytes.single().toInt() and 0xFF)
    }

    @Test
    fun helveticaMetricsMatchKnownValues() {
        // From the Adobe AFM: "Home Inspection" = G778 r333 i222 h556 a556 = 2445 units.
        assertEquals(24.45f, PdfFont.REGULAR.width("Home Inspection", 10f), 0.001f)
        assertEquals(2.78f, PdfFont.BOLD.width(" ", 10f), 0.001f)
    }
}
