package com.griha.inspection.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.griha.inspection.GrihaApp
import com.griha.inspection.core.model.FindingKind
import com.griha.inspection.feature.lock.LockedScreen
import com.griha.inspection.feature.admin.AiAdminScreen
import com.griha.inspection.feature.admin.RetentionPolicyScreen
import com.griha.inspection.feature.admin.AdminInspectionsScreen
import com.griha.inspection.feature.admin.AuditScreen
import com.griha.inspection.feature.admin.DevicesScreen
import com.griha.inspection.feature.admin.EmployeesScreen
import com.griha.inspection.feature.admin.StorageScreen
import com.griha.inspection.feature.attendance.AdminAttendanceScreen
import com.griha.inspection.feature.attendance.FaceCaptureScreen
import com.griha.inspection.feature.attendance.FaceEnrollmentsScreen
import com.griha.inspection.feature.attendance.FaceMode
import com.griha.inspection.feature.attendance.MyAttendanceScreen
import com.griha.inspection.feature.camera.CameraScreen
import com.griha.inspection.feature.findings.FindingScreen
import com.griha.inspection.feature.findings.PhotoViewerScreen
import com.griha.inspection.feature.findings.RoomScreen
import com.griha.inspection.feature.inspections.DashboardScreen
import com.griha.inspection.feature.leave.AdminLeaveScreen
import com.griha.inspection.feature.leave.HolidaysScreen
import com.griha.inspection.feature.leave.MyLeaveScreen
import com.griha.inspection.feature.leave.PersonCalendarScreen
import com.griha.inspection.feature.leave.TeamCalendarScreen
import com.griha.inspection.feature.inspections.InspectionScreen
import com.griha.inspection.feature.inspections.PropertyDetailsScreen
import com.griha.inspection.feature.report.ReportScreen
import com.griha.inspection.feature.auth.ChangePasswordScreen
import com.griha.inspection.feature.auth.LoginScreen
import com.griha.inspection.feature.sync.SyncScreen
import com.griha.inspection.feature.profile.AdminLinks
import com.griha.inspection.feature.tracking.EngineerDayScreen
import com.griha.inspection.feature.tracking.TeamScreen
import com.griha.inspection.feature.tracking.TrackingPrompts
import com.griha.inspection.feature.profile.PhoneStorageScreen
import com.griha.inspection.feature.profile.ProfileScreen
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import com.griha.inspection.core.auth.AuthState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable

@Serializable data object DashboardRoute
@Serializable data object NewInspectionRoute
@Serializable data class EditDetailsRoute(val inspectionId: String)
@Serializable data class InspectionRoute(val inspectionId: String)
@Serializable data class RoomRoute(val inspectionId: String, val roomId: String)
@Serializable data class FindingRoute(val findingId: String)
@Serializable data class ReportRoute(val inspectionId: String)
@Serializable data object ProfileRoute
@Serializable data object SyncRoute
@Serializable data object PhoneStorageRoute
@Serializable data object ChangePasswordRoute
@Serializable data object AdminRoute
@Serializable data object AdminEmployeesRoute
@Serializable data object AdminInspectionsRoute
@Serializable data object AdminDevicesRoute
@Serializable data object AdminAuditRoute
@Serializable data object AdminStorageRoute
@Serializable data object AdminAiRoute
/** Face check: [mode] is a [FaceMode] name (set-up, check-in, check-out). */
@Serializable data class FaceRoute(val mode: String)
@Serializable data object MyAttendanceRoute
@Serializable data object AdminAttendanceRoute
@Serializable data object AdminFaceEnrollmentsRoute
@Serializable data object TeamRoute
@Serializable data object TeamCalendarRoute
@Serializable data class PersonCalendarRoute(val userId: String, val name: String)
@Serializable data object AdminLeaveRoute
@Serializable data object HolidaysRoute
@Serializable data object MyLeaveRoute
@Serializable data class EngineerDayRoute(val userId: String, val name: String)
@Serializable data class PhotoViewerRoute(val findingId: String, val startIndex: Int)
/**
 * [findingId] null = start a new finding; it is created when its first photo saves. [roomPhotos] makes
 * that new finding the room's common photos instead of an issue.
 */
@Serializable data class CameraRoute(val inspectionId: String, val roomId: String, val findingId: String? = null, val roomPhotos: Boolean = false)

/** ViewModel factory without a DI framework: hands the creator the app and its container. */
@Composable
inline fun <reified VM : ViewModel> grihaViewModel(crossinline create: (GrihaApp) -> VM): VM =
    viewModel(factory = viewModelFactory { initializer { create(this[APPLICATION_KEY] as GrihaApp) } })

/** Gate: sign-in (and a forced password change) before anything else (PRD §5.1). */
@Composable
fun GrihaRoot() {
    val app = LocalContext.current.applicationContext as GrihaApp
    val auth by app.container.authRepository.state.collectAsStateWithLifecycle()
    // The developer lock covers everything, including the login screen.
    val locked by app.container.appLock.message.collectAsStateWithLifecycle()
    locked?.let { LockedScreen(it); return }
    when (val a = auth) {
        is AuthState.SignedOut -> LoginScreen(a.message)
        is AuthState.SignedIn -> if (a.user.mustChangePassword) ChangePasswordScreen(forced = true) else {
            GrihaNavHost()
            TrackingPrompts() // consent, permissions, "why turn it off?" — over any screen
        }
    }
}

private const val NavMs = 240

private fun NavBackStackEntry.isTab() = destination.hasRoute<DashboardRoute>() || destination.hasRoute<ProfileRoute>()

/*
 * Screen changes. The new screen fades and slides in ON TOP of the old one, which stays fully
 * opaque until the animation ends (the "exit" is an instant fade after a NavMs delay). Fading both
 * at once let the window show through — that was the white flash on every tap. Back reverses it:
 * the previous screen is shown at once underneath and the closing one slides away. Switching
 * between the two bottom-bar tabs is a quick crossfade instead of a slide.
 */
private val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    if (initialState.isTab() && targetState.isTab()) fadeIn(tween(150))
    else fadeIn(tween(NavMs, easing = FastOutSlowInEasing)) + slideInHorizontally(tween(NavMs, easing = FastOutSlowInEasing)) { it / 10 }
}
private val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    if (initialState.isTab() && targetState.isTab()) fadeOut(tween(1, delayMillis = 150))
    else fadeOut(tween(1, delayMillis = NavMs))
}
private val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = { EnterTransition.None }
private val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(tween(NavMs, easing = FastOutSlowInEasing)) + slideOutHorizontally(tween(NavMs, easing = FastOutSlowInEasing)) { it / 10 }
}

@Composable
fun GrihaNavHost() {
    val nav = rememberNavController()
    fun openProfile() = nav.navigate(ProfileRoute) { popUpTo<DashboardRoute>(); launchSingleTop = true }
    fun openHome() { nav.popBackStack<DashboardRoute>(inclusive = false) }
    NavHost(
        navController = nav,
        startDestination = DashboardRoute,
        // Opaque, theme-coloured backdrop: nothing light can show through during a transition.
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        enterTransition = enter,
        exitTransition = exit,
        popEnterTransition = popEnter,
        popExitTransition = popExit,
    ) {
        composable<DashboardRoute> {
            DashboardScreen(
                onNewInspection = { nav.navigate(NewInspectionRoute) },
                onOpenInspection = { nav.navigate(InspectionRoute(it)) },
                onOpenRoom = { inspectionId, roomId ->
                    nav.navigate(InspectionRoute(inspectionId))
                    nav.navigate(RoomRoute(inspectionId, roomId))
                },
                onOpenSync = { nav.navigate(SyncRoute) },
                onOpenProfile = ::openProfile,
                onOpenStorage = { nav.navigate(AdminStorageRoute) },
                onFace = { mode -> nav.navigate(FaceRoute(mode.name)) },
                onOpenLeave = { nav.navigate(MyLeaveRoute) },
            )
        }
        composable<ProfileRoute> {
            ProfileScreen(
                onHome = ::openHome,
                onNewInspection = { nav.navigate(NewInspectionRoute) },
                onBackup = { nav.navigate(SyncRoute) },
                onPhoneStorage = { nav.navigate(PhoneStorageRoute) },
                onChangePassword = { nav.navigate(ChangePasswordRoute) },
                onMyAttendance = { nav.navigate(MyAttendanceRoute) },
                onMyLeave = { nav.navigate(MyLeaveRoute) },
                onSetUpFace = { nav.navigate(FaceRoute(FaceMode.ENROLL.name)) },
                admin = AdminLinks(
                    employees = { nav.navigate(AdminEmployeesRoute) },
                    inspections = { nav.navigate(AdminInspectionsRoute) },
                    devices = { nav.navigate(AdminDevicesRoute) },
                    audit = { nav.navigate(AdminAuditRoute) },
                    storage = { nav.navigate(AdminStorageRoute) },
                    policy = { nav.navigate(AdminRoute) },
                    ai = { nav.navigate(AdminAiRoute) },
                    attendance = { nav.navigate(AdminAttendanceRoute) },
                    faces = { nav.navigate(AdminFaceEnrollmentsRoute) },
                    team = { nav.navigate(TeamRoute) },
                    calendar = { nav.navigate(TeamCalendarRoute) },
                    leave = { nav.navigate(AdminLeaveRoute) },
                ),
            )
        }
        composable<SyncRoute> { SyncScreen(onBack = nav::popBackStack) }
        composable<FaceRoute> { entry ->
            FaceCaptureScreen(
                mode = FaceMode.valueOf(entry.toRoute<FaceRoute>().mode),
                onClose = { nav.popBackStack() },
                // From a check-in without a face set-up: replace it with the set-up screen.
                onSetUpFace = { nav.navigate(FaceRoute(FaceMode.ENROLL.name)) { popUpTo<FaceRoute> { inclusive = true } } },
            )
        }
        composable<MyAttendanceRoute> { MyAttendanceScreen(onBack = nav::popBackStack) }
        composable<AdminAttendanceRoute> {
            AdminAttendanceScreen(onBack = nav::popBackStack, onEnrollments = { nav.navigate(AdminFaceEnrollmentsRoute) })
        }
        composable<AdminFaceEnrollmentsRoute> { FaceEnrollmentsScreen(onBack = nav::popBackStack) }
        composable<TeamRoute> { TeamScreen(onBack = nav::popBackStack, onPerson = { id, name -> nav.navigate(EngineerDayRoute(id, name)) }) }
        composable<EngineerDayRoute> { entry ->
            val r = entry.toRoute<EngineerDayRoute>()
            EngineerDayScreen(r.userId, r.name, onBack = nav::popBackStack)
        }
        composable<TeamCalendarRoute> {
            TeamCalendarScreen(
                onBack = nav::popBackStack,
                onPerson = { id, name -> nav.navigate(PersonCalendarRoute(id, name)) },
                onLeave = { nav.navigate(AdminLeaveRoute) },
                onHolidays = { nav.navigate(HolidaysRoute) },
            )
        }
        composable<PersonCalendarRoute> { entry ->
            val r = entry.toRoute<PersonCalendarRoute>()
            PersonCalendarScreen(r.userId, r.name, onBack = nav::popBackStack)
        }
        composable<AdminLeaveRoute> { AdminLeaveScreen(onBack = nav::popBackStack) }
        composable<HolidaysRoute> { HolidaysScreen(onBack = nav::popBackStack) }
        composable<MyLeaveRoute> { MyLeaveScreen(onBack = nav::popBackStack) }
        composable<PhoneStorageRoute> { PhoneStorageScreen(onBack = nav::popBackStack) }
        composable<AdminRoute> {
            RetentionPolicyScreen(onBack = nav::popBackStack, onStorage = { nav.navigate(AdminStorageRoute) })
        }
        composable<AdminAiRoute> { AiAdminScreen(onBack = nav::popBackStack) }
        composable<AdminEmployeesRoute> { EmployeesScreen(onBack = nav::popBackStack) }
        composable<AdminInspectionsRoute> { AdminInspectionsScreen(onBack = nav::popBackStack) }
        composable<AdminDevicesRoute> { DevicesScreen(onBack = nav::popBackStack) }
        composable<AdminAuditRoute> { AuditScreen(onBack = nav::popBackStack) }
        composable<AdminStorageRoute> { StorageScreen(onBack = nav::popBackStack) }
        composable<ChangePasswordRoute> {
            ChangePasswordScreen(forced = false, onDone = { nav.popBackStack() })
        }
        composable<NewInspectionRoute> {
            PropertyDetailsScreen(
                inspectionId = null,
                onBack = nav::popBackStack,
                // Replace the form with the new inspection, so Back goes to the dashboard.
                onSaved = { id -> nav.navigate(InspectionRoute(id)) { popUpTo<NewInspectionRoute> { inclusive = true } } },
            )
        }
        composable<EditDetailsRoute> { entry ->
            PropertyDetailsScreen(
                inspectionId = entry.toRoute<EditDetailsRoute>().inspectionId,
                onBack = nav::popBackStack,
                onSaved = { nav.popBackStack() },
            )
        }
        composable<InspectionRoute> { entry ->
            val route = entry.toRoute<InspectionRoute>()
            InspectionScreen(
                inspectionId = route.inspectionId,
                onBack = nav::popBackStack,
                onOpenRoom = { roomId -> nav.navigate(RoomRoute(route.inspectionId, roomId)) },
                onEditDetails = { nav.navigate(EditDetailsRoute(route.inspectionId)) },
                onOpenReport = { nav.navigate(ReportRoute(route.inspectionId)) },
            )
        }
        composable<ReportRoute> { entry ->
            ReportScreen(
                inspectionId = entry.toRoute<ReportRoute>().inspectionId,
                onBack = nav::popBackStack,
                onOpenFinding = { nav.navigate(FindingRoute(it)) },
            )
        }
        composable<RoomRoute> { entry ->
            val route = entry.toRoute<RoomRoute>()
            RoomScreen(
                roomId = route.roomId,
                onBack = nav::popBackStack,
                onOpenFinding = { nav.navigate(FindingRoute(it)) },
                onCapture = { findingId -> nav.navigate(CameraRoute(route.inspectionId, route.roomId, findingId)) },
                onNewFinding = { nav.navigate(CameraRoute(route.inspectionId, route.roomId)) },
                onAddRoomPhotos = { findingId -> nav.navigate(CameraRoute(route.inspectionId, route.roomId, findingId, roomPhotos = true)) },
                onOpenPhoto = { findingId, index -> nav.navigate(PhotoViewerRoute(findingId, index)) },
            )
        }
        composable<FindingRoute> { entry ->
            val findingId = entry.toRoute<FindingRoute>().findingId
            FindingScreen(
                findingId = findingId,
                onBack = nav::popBackStack,
                onAddPhoto = { inspectionId, roomId -> nav.navigate(CameraRoute(inspectionId, roomId, findingId)) },
                onOpenPhoto = { index -> nav.navigate(PhotoViewerRoute(findingId, index)) },
            )
        }
        composable<PhotoViewerRoute> { entry ->
            val route = entry.toRoute<PhotoViewerRoute>()
            PhotoViewerScreen(findingId = route.findingId, startIndex = route.startIndex, onBack = nav::popBackStack)
        }
        composable<CameraRoute> { entry ->
            val route = entry.toRoute<CameraRoute>()
            CameraScreen(
                inspectionId = route.inspectionId,
                roomId = route.roomId,
                existingFindingId = route.findingId,
                newKind = if (route.roomPhotos) FindingKind.ROOM_PHOTOS else FindingKind.ISSUE,
                onDone = { openFindingId ->
                    nav.popBackStack()
                    // A new finding's first photos are in: go straight to describing it.
                    if (openFindingId != null) nav.navigate(FindingRoute(openFindingId))
                },
                onOpenPhoto = { findingId, index -> nav.navigate(PhotoViewerRoute(findingId, index)) },
            )
        }
    }
}
