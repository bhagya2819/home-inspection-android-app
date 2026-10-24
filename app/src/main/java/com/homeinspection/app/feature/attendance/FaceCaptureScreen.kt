package com.homeinspection.app.feature.attendance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homeinspection.app.core.face.FaceResult
import com.homeinspection.app.ui.homeInspectionViewModel
import com.homeinspection.app.ui.theme.HomeInspectionFresh
import com.homeinspection.app.ui.theme.HomeInspectionWarn
import java.util.Locale

/**
 * Full-screen front camera with an oval guide. One instruction at a time; the oval turns green
 * while a usable face is inside it. Used for face set-up and for check-in/out.
 */
@Composable
fun FaceCaptureScreen(mode: FaceMode, onClose: () -> Unit, onSetUpFace: () -> Unit) {
    val context = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var asked by remember { mutableStateOf(false) }
    val vmHolder = remember { mutableStateOf<FaceCaptureViewModel?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        asked = true
        cameraGranted = result[Manifest.permission.CAMERA] == true || granted(Manifest.permission.CAMERA)
        vmHolder.value?.startLocation()
    }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (!granted(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (mode != FaceMode.ENROLL && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (wanted.isNotEmpty()) launcher.launch(wanted.toTypedArray()) else asked = true
    }

    if (!cameraGranted) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (asked) "The front camera is needed to check your face." else "Asking for camera access…",
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            )
            if (asked) {
                Button(onClick = { launcher.launch(arrayOf(Manifest.permission.CAMERA)) }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Allow camera")
                }
            }
            TextButton(onClick = onClose) { Text("Back") }
        }
        return
    }

    val vm = homeInspectionViewModel {
        val c = it.container
        FaceCaptureViewModel(
            mode, c.faceEngine, c.faceModels, c.faceRepository, c.attendanceRepository, c.locationFix, it.cacheDir, it.appScope,
        )
    }
    vmHolder.value = vm
    val ui by vm.ui.collectAsStateWithLifecycle()
    val surfaceRequest by vm.surfaceRequest.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) { vm.bindToCamera(context.applicationContext, lifecycleOwner) }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
        OvalGuide(ok = ui.faceOk)

        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            Text(mode.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xCC000000))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepPanel(ui, vm, mode, onClose, onSetUpFace)
            if (mode != FaceMode.ENROLL) LocationLine(ui)
        }
    }
}

/** Darkens everything outside a face-shaped oval; the oval's border shows whether the face is usable. */
@Composable
private fun OvalGuide(ok: Boolean) {
    val border by animateColorAsState(if (ok) HomeInspectionFresh else Color.White, label = "oval")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width * 0.68f
        val h = w * 1.3f
        val topLeft = Offset((size.width - w) / 2, size.height * 0.36f - h / 2)
        val oval = Path().apply { addOval(androidx.compose.ui.geometry.Rect(topLeft, Size(w, h))) }
        clipPath(oval, clipOp = ClipOp.Difference) { drawRect(Color(0x99000000)) }
        drawOval(border, topLeft, Size(w, h), style = Stroke(width = 4.dp.toPx()))
    }
}

@Composable
private fun StepPanel(ui: FaceUi, vm: FaceCaptureViewModel, mode: FaceMode, onClose: () -> Unit, onSetUpFace: () -> Unit) {
    val action = if (mode == FaceMode.CHECK_OUT) "Check out" else "Check in"
    when (val step = ui.step) {
        is FaceStep.Preparing -> Busy(step.text)
        FaceStep.DownloadingModel -> ModelDownload(vm)
        is FaceStep.AlreadySetUp -> {
            Icon(Icons.Filled.CheckCircle, null, tint = if (step.approved) HomeInspectionFresh else Color.White, modifier = Modifier.size(36.dp))
            Text(
                when {
                    step.pending -> "Your face set-up is waiting for your admin"
                    else -> "Your face is set up and approved"
                },
                color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            Text(
                if (step.pending) "Nothing more to do: you can check in meanwhile. Set up again only if the photos were bad."
                else "Check in from the home screen. Set up again only if check-ins often fail to match (new glasses, beard…).",
                color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onClose) { Text("Done") }
                OutlinedButton(onClick = vm::startEnrollment) { Text("Set up again", color = Color.White) }
            }
        }
        FaceStep.FindFace -> Instruction("Look straight at the camera", ui.hint)
        FaceStep.TurnHead -> Instruction("Slowly turn your head to one side", ui.hint ?: "This shows it's really you, not a photo")
        FaceStep.LookBack -> Instruction("Now look straight at the camera again", ui.hint)
        is FaceStep.Collecting -> Instruction("Hold still and move your head a little", ui.hint ?: "Photo ${step.shots + 1} of ${step.of}")
        is FaceStep.Working -> Busy(step.text)
        is FaceStep.Decide -> {
            val (title, detail) = when (step.result) {
                FaceResult.WEAK -> "Weak match" to "The light or angle may be off. Try again, or ${action.lowercase()} and your admin will check the photo."
                FaceResult.NO_MATCH -> "Face did not match" to if (step.canProceed) {
                    "You can still ${action.lowercase()}; it will be marked for your admin to review."
                } else "Face the light, remove sunglasses or a mask, and try again."
                FaceResult.NOT_ENROLLED -> if (step.setUpPending) {
                    "Face set-up waiting for approval" to "Your photo and place are saved; your admin can compare the photo. Face matching starts once they approve your set-up."
                } else {
                    "Face check not set up" to "Your photo and place are still saved. Set up your face so future check-ins are verified."
                }
                FaceResult.UNAVAILABLE -> "Face check unavailable offline" to "The face model hasn't downloaded yet. Your photo and place are saved for your admin."
                FaceResult.MATCH -> "" to ""
            }
            Icon(Icons.Filled.Warning, null, tint = HomeInspectionWarn, modifier = Modifier.size(32.dp))
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            step.score?.let { Text("Similarity ${String.format(Locale.US, "%.2f", it)}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall) }
            Text(detail, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step.result == FaceResult.WEAK || step.result == FaceResult.NO_MATCH) {
                    OutlinedButton(onClick = vm::restart) { Text("Try again", color = Color.White) }
                }
                if (step.canProceed) Button(onClick = vm::proceedAnyway) { Text("$action anyway") }
            }
            if (step.result == FaceResult.NOT_ENROLLED && !step.setUpPending) TextButton(onClick = onSetUpFace) { Text("Set up face check-in", color = Color.White) }
        }
        is FaceStep.Done -> {
            Icon(Icons.Filled.CheckCircle, null, tint = HomeInspectionFresh, modifier = Modifier.size(40.dp))
            Text(step.message, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Button(onClick = onClose) { Text("Done") }
        }
        is FaceStep.Failed -> {
            Text(step.message, color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onClose) { Text("Close", color = Color.White) }
                if (step.canRetry) Button(onClick = vm::restart) { Text("Try again") }
            }
        }
    }
}

/** The one-time model download: a real bar with size, speed and time left, not just a spinner. */
@Composable
private fun ModelDownload(vm: FaceCaptureViewModel) {
    val p by vm.download.collectAsStateWithLifecycle()
    Text("Downloading the face model", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    val progress = p
    if (progress == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Connecting…", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    } else {
        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
        val mb = { b: Long -> (b / 1_000_000).toString() }
        val left = progress.secondsLeft.let { if (it < 0) "" else if (it < 60) " · about $it s left" else " · about ${it / 60} min left" }
        Text("${mb(progress.done)} of ${mb(progress.total)} MB$left", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "Only once per phone; then face checks work offline. You can leave this screen, the download continues.",
        color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
    )
}

@Composable
private fun Instruction(text: String, hint: String?) {
    Text(text, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    Text(hint ?: " ", color = Color(0xFFFFD27A), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
}

@Composable
private fun Busy(text: String) {
    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
    Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

@Composable
private fun LocationLine(ui: FaceUi) {
    val context = LocalContext.current
    val place = ui.place
    Surface(color = Color(0x33FFFFFF), contentColor = Color.White, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocationOn, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                when {
                    place != null -> "Location found (±${place.accuracyM.toInt()} m)" + if (ui.locating) " · improving…" else ""
                    ui.locating -> "Finding your location…"
                    else -> ui.locationNote ?: "No location"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (place == null && ui.locationNote?.startsWith("Location is off") == true) {
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
            Text("Turn on location", color = Color.White)
        }
    }
    Spacer(Modifier.height(4.dp))
}
