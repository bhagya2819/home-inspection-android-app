package com.homeinspection.app.feature.inspections

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.homeinspection.app.feature.attendance.DutyChip
import com.homeinspection.app.feature.attendance.DutyPanel
import com.homeinspection.app.feature.attendance.rememberDutyUi
import com.homeinspection.app.feature.tracking.SharingChip
import com.homeinspection.app.feature.tracking.SharingPanel
import com.homeinspection.app.feature.tracking.rememberSharingUi

private enum class Open { DUTY, SHARING }

/**
 * Top of Home: duty and location sharing as two small pills. Tapping one opens its details and
 * buttons below (one at a time), so the inspections list starts right underneath.
 */
@Composable
fun WorkStatus(onCheckIn: () -> Unit, onCheckOut: () -> Unit, onSetUpFace: () -> Unit, modifier: Modifier = Modifier) {
    val duty = rememberDutyUi()
    val sharing = rememberSharingUi()
    var open by rememberSaveable { mutableStateOf<Open?>(null) }
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DutyChip(duty, open == Open.DUTY, onClick = { open = if (open == Open.DUTY) null else Open.DUTY }, modifier = Modifier.weight(1f, fill = false))
            SharingChip(sharing, open == Open.SHARING, onClick = { open = if (open == Open.SHARING) null else Open.SHARING },
                modifier = Modifier.weight(1f, fill = false))
        }
        AnimatedContent(
            targetState = open,
            transitionSpec = { (fadeIn() + expandVertically()) togetherWith (fadeOut() + shrinkVertically()) },
            label = "work-panel",
        ) { which ->
            when (which) {
                Open.DUTY -> Column(Modifier.padding(top = 8.dp)) { DutyPanel(
                    duty,
                    onCheckIn = { open = null; onCheckIn() },
                    onCheckOut = { open = null; onCheckOut() },
                    onSetUpFace = { open = null; onSetUpFace() },
                ) }
                Open.SHARING -> Column(Modifier.padding(top = 8.dp)) { SharingPanel(sharing) }
                null -> {}
            }
        }
    }
}
