package com.homeinspection.app

import android.content.Context
import com.homeinspection.app.core.admin.AdminRepository
import com.homeinspection.app.core.attendance.AttendanceRepository
import com.homeinspection.app.core.attendance.LocationFix
import com.homeinspection.app.core.face.FaceEngine
import com.homeinspection.app.core.face.FaceModelStore
import com.homeinspection.app.core.face.FaceRepository
import com.homeinspection.app.core.ai.AiRepository
import com.homeinspection.app.core.admin.ExportDownloads
import com.homeinspection.app.core.auth.AuthRepository
import com.homeinspection.app.core.auth.SessionStore
import com.homeinspection.app.core.data.InspectionRepository
import com.homeinspection.app.core.data.PhotoRepository
import com.homeinspection.app.core.database.HomeInspectionDatabase
import com.homeinspection.app.core.leave.LeaveRepository
import com.homeinspection.app.core.lock.AppLock
import com.homeinspection.app.core.report.JpegImage
import com.homeinspection.app.core.report.ReportImages
import com.homeinspection.app.core.report.ReportRepository
import com.homeinspection.app.core.network.ApiClient
import com.homeinspection.app.core.retention.RetentionManager
import com.homeinspection.app.core.storage.PhotoStore
import com.homeinspection.app.core.sync.SyncEngine
import com.homeinspection.app.core.sync.SyncScheduler
import com.homeinspection.app.core.tracking.TrackingRepository
import com.homeinspection.app.ui.theme.ThemeSettings
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Hand-written dependency wiring (no DI framework, to keep the APK and startup lean).
 * Everything is lazy so cold start only pays for what the first screen touches.
 */
class AppContainer(context: Context, private val appScope: CoroutineScope) {
    private val appContext = context.applicationContext

    val database by lazy { HomeInspectionDatabase.build(appContext) }
    private val photoStore by lazy { PhotoStore(appContext.filesDir, allowFreeSpaceOverride = BuildConfig.DEBUG) }

    val sessionStore by lazy { SessionStore(appContext.getSharedPreferences("home_inspection_session", Context.MODE_PRIVATE)) }
    val appLock by lazy { AppLock(appContext.getSharedPreferences("home_inspection_lock", Context.MODE_PRIVATE)) }
    private val api: ApiClient by lazy {
        ApiClient(BuildConfig.API_BASE_URL, token = { authRepository.token() }, onAppLocked = { appLock.onLockedByServer(it) })
    }

    /** Asks the server whether the developer lock is on (app start, lock screen's "Check again"). */
    suspend fun refreshAppLock(): Boolean = appLock.refresh(api)
    val authRepository: AuthRepository by lazy {
        AuthRepository(sessionStore, api, database).also {
            // Sign-out ends sharing and uploads that while the session still works; otherwise the
            // engineer's "stopped" would wait on this phone until they sign in here again.
            it.beforeLogout = {
                trackingRepository.stop("SIGN_OUT")
                trackingRepository.pushQuietly()
            }
        }
    }
    val retention by lazy {
        RetentionManager(
            db = database,
            photoStore = photoStore,
            serverCheck = RetentionManager.serverCheckVia(api),
            prefs = appContext.getSharedPreferences("home_inspection_retention", Context.MODE_PRIVATE),
            deviceId = { sessionStore.deviceId },
            currentUserId = { authRepository.currentUser?.id },
        )
    }
    val themeSettings by lazy { ThemeSettings(appContext.getSharedPreferences("home_inspection_settings", Context.MODE_PRIVATE)) }
    val adminRepository by lazy { AdminRepository(api) }
    val aiRepository by lazy { AiRepository(api, database, appContext.getSharedPreferences("home_inspection_ai", Context.MODE_PRIVATE)) }
    val exportDownloads by lazy {
        ExportDownloads(appContext, adminRepository, appContext.getSharedPreferences("home_inspection_exports", Context.MODE_PRIVATE))
    }
    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            database, api, authRepository, sessionStore, photoStore, appContext.filesDir, retention,
            isAppLocked = { appLock.isLocked && appLock.refresh(api) },
            onSynced = {
                if (authRepository.currentUser?.isAdmin == true) adminRepository.refreshWarning()
                faceRepository.refresh()
                // A phone of an engineer who already enrolled elsewhere: fetch the model on Wi-Fi only.
                if (!faceModels.hasRecognizer && faceRepository.current()?.approved == true && !isMeteredNetwork()) faceModels.ensureRecognizer()
            },
            onSettings = { aiRepository.applyServerSettings(it) },
            extraPush = {
                attendanceRepository.push()
                trackingRepository.push()
                leaveRepository.push()
                leaveRepository.refreshDecisions()
            },
            // Location points have their own upload rhythm (the tracking service); these queues wake the job.
            extraNextDueAt = { listOfNotNull(attendanceRepository.nextDueAt(), leaveRepository.nextDueAt()).minOrNull() },
        )
    }

    // Attendance (feature 1): face check-in/out.
    val faceModels by lazy { FaceModelStore(appContext, api, appScope) }
    val faceEngine by lazy { FaceEngine(faceModels.detectorFile(), faceModels::recognizerFile) }
    val faceRepository by lazy {
        FaceRepository(api, appContext.getSharedPreferences("home_inspection_face", Context.MODE_PRIVATE), currentUserId = { authRepository.currentUser?.id })
    }
    val attendanceRepository by lazy { AttendanceRepository(appContext, database, api, currentUserId = { authRepository.currentUser?.id }) }
    val locationFix by lazy { LocationFix(appContext) }

    // Attendance calendar, leave requests and comp-offs (admin features; server migration 007).
    val leaveRepository by lazy {
        LeaveRepository(
            database, api, currentUserId = { authRepository.currentUser?.id },
            prefs = { appContext.getSharedPreferences("home_inspection_leave", Context.MODE_PRIVATE) },
        )
    }

    // Live location (feature 2): independent of attendance (see HomeInspectionApp for the check-in link).
    val trackingRepository by lazy {
        TrackingRepository(
            appContext, database, api, appContext.getSharedPreferences("home_inspection_tracking", Context.MODE_PRIVATE),
            currentUserId = { authRepository.currentUser?.id },
        )
    }

    private fun isMeteredNetwork(): Boolean =
        appContext.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
    val syncScheduler by lazy { SyncScheduler(appContext, syncEngine, appScope) }

    /** Who records are attributed to: the signed-in user, or a placeholder adopted at first sign-in. */
    private val currentUserId: () -> String = { authRepository.currentUser?.id ?: AuthRepository.PLACEHOLDER_USER }

    val inspectionRepository by lazy { InspectionRepository(database, currentUserId) }
    val photoRepository by lazy { PhotoRepository(database, photoStore, currentUserId) }
    val reportRepository by lazy {
        ReportRepository(
            db = database,
            photoStore = photoStore,
            filesDir = appContext.filesDir,
            cacheDir = appContext.cacheDir,
            images = ReportImages(appContext.cacheDir),
            logo = ::reportLogo,
            prefs = { appContext.getSharedPreferences("home_inspection_settings", Context.MODE_PRIVATE) },
            downloads = syncEngine,
            retention = retention,
        )
    }

    /** The PDF writer embeds JPEG files as-is, so the bundled logo is copied out to a file once. */
    @Synchronized
    private fun reportLogo(): JpegImage {
        val file = File(appContext.cacheDir, "report-logo-v1.jpg")
        if (!file.isFile || file.length() == 0L) {
            val tmp = File(appContext.cacheDir, "report-logo-v1.jpg.part")
            appContext.resources.openRawResource(R.raw.home_inspection_logo).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(file)
        }
        return JpegImage(file, LOGO_PX, LOGO_PX)
    }

    private companion object {
        const val LOGO_PX = 192 // res/raw/home_inspection_logo.jpg, cropped from home-inspection-icon.png
    }
}
