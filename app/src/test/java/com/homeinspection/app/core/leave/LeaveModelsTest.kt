package com.homeinspection.app.core.leave

import com.homeinspection.app.core.report.ReportContentQueue
import com.homeinspection.app.feature.leave.leaveWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LeaveModelsTest {
    private fun day(status: String, halfWork: String = "", leaveKind: String? = null) =
        CalendarDay(day = "2026-11-10", status = status, halfWork = halfWork, leave = leaveKind?.let { DayLeave("l", it, status = "APPROVED") })

    @Test
    fun marksMatchTheServersTeamStrip() {
        assertEquals("COMP_OFF", day("LEAVE", leaveKind = "COMP_OFF").mark)
        assertEquals("LEAVE", day("LEAVE", leaveKind = "SICK").mark)
        assertEquals("HALF_LEAVE_WORKED", day("HALF_LEAVE", halfWork = "WORKED", leaveKind = "CASUAL").mark)
        assertEquals("HALF_LEAVE", day("HALF_LEAVE", leaveKind = "CASUAL").mark)
        assertEquals("WORKED_ON_LEAVE", day("WORKED_ON_LEAVE", leaveKind = "CASUAL").mark)
    }

    @Test
    fun adminSeesWorkedDaysAndTheCompOffBalanceAfterApproval() {
        val r = LeaveRequest(id = "x", kind = "COMP_OFF", startDay = "2026-11-10", endDay = "2026-11-10", days = 1.0, status = "PENDING",
            workedDays = 1, compOff = CompOffBalance(earned = 0, balance = 0.0))
        val w = leaveWarnings(r)
        assertEquals(2, w.size)
        assertTrue(w[0], w[0].startsWith("Checked in on 1"))
        assertTrue(w[1], w[1].contains("0 days → -1 day") && w[1].contains("more than earned"))
        assertTrue(leaveWarnings(r.copy(kind = "SICK", compOff = null, workedDays = 0)).isEmpty())
    }

    @Test
    fun reportContentIsQueuedAsOneFilePerVersion() {
        val dir = Files.createTempDirectory("home-inspection-content").toFile()
        try {
            ReportContentQueue.write(dir, "r1", """{"format":1}""")
            val pending = ReportContentQueue.pending(dir)
            assertEquals(listOf("r1"), pending.map(ReportContentQueue::reportId))
            assertEquals("""{"format":1}""", pending[0].readText())
            ReportContentQueue.cleanupPartials(dir)
            assertEquals(1, ReportContentQueue.pending(dir).size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
