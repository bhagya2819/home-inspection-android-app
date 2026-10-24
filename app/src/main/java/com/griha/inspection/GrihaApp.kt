package com.griha.inspection

import android.app.Application
import android.os.StrictMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.griha.inspection.core.auth.AuthState

class GrihaApp : Application() {

    lateinit var container: AppContainer
        private set

    /**
     * Lives as long as the process. Work that must not be cancelled when a screen closes —
     * finishing a photo save above all — runs here instead of in a viewModelScope.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        val processStart = System.currentTimeMillis()
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build())
        }
        container = AppContainer(this, appScope)
        appScope.launch {
            container.photoRepository.recoverInterruptedCaptures(processStart)
            container.inspectionRepository.removeEmptyFindings()
            container.reportRepository.cleanupPartials()
            container.attendanceRepository.cleanupLeftovers(processStart)
            container.trackingRepository.cleanup()
            startSync()
            // Safety net: MIUI without Autostart drops the boot / app-updated broadcasts. Whenever the
            // process starts in the background (sync job, receiver) and sharing should be on but its
            // service isn't, tell the engineer ("Tap to resume") and the admin. A visible app resumes it instead.
            kotlinx.coroutines.delay(5_000)
            if (!MainActivity.visible && !com.griha.inspection.core.tracking.TrackingService.running) {
                container.trackingRepository.onServiceRefused()
            }
        }
        linkTrackingToAttendance()
    }

    /**
     * The only link between attendance and live location (user decision 2026-10-21): a new check-in
     * starts sharing, a new check-out stops it; signing out stops it too. Attendance knows nothing
     * of this, and sharing can also be switched on without a check-in.
     */
    private fun linkTrackingToAttendance() {
        val auth = container.authRepository
        val tracking = container.trackingRepository
        appScope.launch {
            auth.state.map { (it as? AuthState.SignedIn)?.user?.id }.distinctUntilChanged().collectLatest { userId ->
                if (userId == null) {
                    tracking.stop("SIGN_OUT")
                    return@collectLatest
                }
                container.attendanceRepository.observeLatest(userId).collect { row ->
                    if (row == null || row.id == tracking.handledAttendanceId) return@collect
                    val first = tracking.handledAttendanceId == null
                    tracking.handledAttendanceId = row.id
                    // First run after the update: don't act on an old check-in; only on new ones.
                    if (first || System.currentTimeMillis() - row.createdAt > 10 * 60_000L) return@collect
                    if (row.isIn) tracking.start("CHECK_IN") else tracking.stop("CHECK_OUT")
                }
            }
        }
    }

    /**
     * Sync follows the session: while signed in with a server token, any new outbox entry triggers
     * a sync a moment later (batching bursts of edits), plus an hourly pull. Signing out stops it.
     */
    private fun startSync() {
        val auth = container.authRepository
        val scheduler = container.syncScheduler
        appScope.launch {
            // Signed in (even offline-only: the engine completes the online sign-in when it can).
            auth.state.map { it is AuthState.SignedIn }.distinctUntilChanged().collect { signedIn ->
                if (signedIn) {
                    scheduler.schedulePeriodic()
                    scheduler.kick()
                } else {
                    scheduler.cancelAll()
                    container.adminRepository.clearWarning()
                }
            }
        }
        appScope.launch {
            // Only *new* work triggers a sync. (Room re-emits on every outbox write, including a sync's
            // own retry bookkeeping; reacting to those would loop forever.)
            var previous = Int.MAX_VALUE
            kotlinx.coroutines.flow.combine(
                container.database.outboxDao().observePendingCount(),
                container.attendanceRepository.pendingCount,
                container.trackingRepository.unsentEvents, // points alone wait for the service's own uploads
            ) { a, b, c -> a + b + c }.distinctUntilChanged().collect { count ->
                val grew = count > previous
                previous = count
                if (grew && auth.state.value is AuthState.SignedIn) {
                    kotlinx.coroutines.delay(3_000) // let a burst of edits land in one sync
                    scheduler.kick()
                }
            }
        }
    }
}
