package com.griha.inspection.feature.findings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.griha.inspection.core.data.InspectionRepository
import com.griha.inspection.core.data.PhotoRepository
import com.griha.inspection.core.database.FindingEntity
import com.griha.inspection.core.database.PhotoEntity
import com.griha.inspection.core.model.FindingKind
import com.griha.inspection.core.model.FindingStatus
import com.griha.inspection.ui.components.PhotoThumb
import com.griha.inspection.ui.components.offPhoneLabel
import com.griha.inspection.ui.components.SeverityBadge
import com.griha.inspection.ui.grihaViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File

data class FindingItem(val finding: FindingEntity, val photos: List<Pair<PhotoEntity, File>>)

/**
 * A room's findings, with its common photos ([FindingKind.ROOM_PHOTOS]) apart from the issues.
 * [commonFindingId] is where new common photos go (null = none yet: the camera creates it).
 */
data class RoomContent(val common: List<FindingItem>, val issues: List<FindingItem>) {
    val commonFindingId get() = common.firstOrNull()?.finding?.id
}

class RoomViewModel(roomId: String, repo: InspectionRepository, photoRepo: PhotoRepository) : ViewModel() {
    val room = repo.observeRoom(roomId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val content = combine(repo.observeFindings(roomId), photoRepo.observeForRoom(roomId)) { findings, photos ->
        val byFinding = photos.groupBy { it.findingId }
        val items = findings.map { f -> FindingItem(f, byFinding[f.id].orEmpty().map { it to photoRepo.resolve(it) }) }
        val (common, issues) = items.partition { it.finding.kind == FindingKind.ROOM_PHOTOS }
        RoomContent(common, issues)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    roomId: String,
    onBack: () -> Unit,
    onOpenFinding: (findingId: String) -> Unit,
    onCapture: (findingId: String) -> Unit,
    onNewFinding: () -> Unit,
    /** Opens the camera for common photos; null = this room has none yet. */
    onAddRoomPhotos: (findingId: String?) -> Unit,
    onOpenPhoto: (findingId: String, index: Int) -> Unit,
) {
    val vm = grihaViewModel { RoomViewModel(roomId, it.container.inspectionRepository, it.container.photoRepository) }
    val room by vm.room.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(room?.name.orEmpty()) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Button(onClick = onNewFinding, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("New finding with photo", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
    ) { padding ->
        val c = content ?: return@Scaffold
        val list = c.issues
        LazyColumn(
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Always first: the overview shots that identify the room, before any finding.
            item(key = "common") {
                CommonPhotosCard(c.common, onAdd = { onAddRoomPhotos(c.commonFindingId) }, onOpenPhoto = onOpenPhoto)
            }
            if (list.isEmpty()) {
                item {
                    Text(
                        "No findings in this area yet. Tap “New finding with photo” to record one.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            itemsIndexed(list, key = { _, item -> item.finding.id }) { index, item ->
                FindingCard(
                    number = index + 1,
                    item = item,
                    onOpen = { onOpenFinding(item.finding.id) },
                    onAddPhoto = { onCapture(item.finding.id) },
                )
            }
        }
    }
}

@Composable
private fun CommonPhotosCard(common: List<FindingItem>, onAdd: () -> Unit, onOpenPhoto: (findingId: String, index: Int) -> Unit) {
    // Usually one finding; if two phones each started one, show their photos together.
    val photos = common.flatMap { item -> item.photos.mapIndexed { index, p -> Triple(item.finding.id, index, p) } }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (photos.isEmpty()) "Common photos" else "Common photos · ${photos.size}",
                style = MaterialTheme.typography.titleSmall,
            )
            if (photos.isEmpty()) {
                Text(
                    "Photos of the room from each corner, so it can be recognised. They appear above its findings in the report.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(photos, key = { it.third.first.id }) { (findingId, index, p) ->
                        val (photo, file) = p
                        PhotoThumb(
                            file, photo.uploadState, size = 64.dp,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onOpenPhoto(findingId, index) },
                            offPhone = photo.offPhoneLabel(),
                        )
                    }
                }
                FilledTonalButton(onClick = onAdd, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(if (photos.isEmpty()) "Add photos" else "Photo", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun FindingCard(number: Int, item: FindingItem, onOpen: () -> Unit, onAddPhoto: () -> Unit) {
    val f = item.finding
    Card(onClick = onOpen, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    f.title ?: "Finding $number",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (f.status != FindingStatus.OPEN) {
                    Text(f.status.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                f.severity?.let { SeverityBadge(it) }
            }
            f.category?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val preview = f.detailedObservation ?: f.roughNote.ifBlank { null }
            Text(
                preview ?: "No note yet — tap to describe this finding",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = if (preview == null) FontStyle.Italic else FontStyle.Normal,
                color = if (preview == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(item.photos, key = { it.first.id }) { (photo, file) -> PhotoThumb(file, photo.uploadState, size = 64.dp, offPhone = photo.offPhoneLabel()) }
                }
                FilledTonalButton(onClick = onAddPhoto, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Photo", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}
