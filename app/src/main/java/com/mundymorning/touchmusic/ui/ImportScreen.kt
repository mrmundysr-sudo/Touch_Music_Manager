package com.mundymorning.touchmusic.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mundymorning.touchmusic.data.AudioMode
import com.mundymorning.touchmusic.data.ImportJobEntity
import com.mundymorning.touchmusic.data.ItemKind
import com.mundymorning.touchmusic.data.ItemStatus
import com.mundymorning.touchmusic.data.JobStatus
import com.mundymorning.touchmusic.importer.ImportProgress
import com.mundymorning.touchmusic.importer.SourceSpec
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private data class PendingImport(val label: String, val specs: List<SourceSpec>, val ignoredFiles: Int = 0) {
    val zips: Int get() = specs.count { it.kind == ItemKind.ZIP }
    val audio: Int get() = specs.count { it.kind == ItemKind.AUDIO }
}

private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

private fun persistRead(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: Exception) {
        // Not persistable (or the per-app limit was reached); access then lasts until the app is closed.
    }
}

@Composable
fun ImportScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val progress by c.importer.progress.collectAsStateWithLifecycle()
    val scan by c.scanner.state.collectAsStateWithLifecycle()
    val jobs by remember { c.db.imports().jobs() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val folders by remember { c.db.folders().all() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val missingCount by remember { c.repo.tracks.missingCount() }.collectAsStateWithLifecycle(initialValue = 0)
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    var permissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) permissionAction?.invoke() else c.toast("Permission is needed to find music already on this phone")
        permissionAction = null
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun withAudioPermission(action: () -> Unit) {
        if (hasAudioPermission(context)) {
            action()
        } else {
            permissionAction = action
            permissionLauncher.launch(audioPermission())
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistRead(context, uri)
            c.appScope.launch {
                val listing = c.scanner.listFolder(uri)
                if (listing != null) {
                    if (listing.audio.isEmpty() && listing.zips.isEmpty()) {
                        c.toast("No audio files or ZIP archives were found in “${listing.name}”")
                    } else {
                        pending = PendingImport("Folder: ${listing.name}", listing.zips + listing.audio, listing.otherFiles)
                    }
                }
            }
        }
    }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { persistRead(context, it) }
            c.appScope.launch {
                val specs = c.scanner.lister.describe(uris).filter { it.kind == ItemKind.ZIP }
                if (specs.isEmpty()) {
                    c.toast("None of the selected files is a ZIP archive")
                } else {
                    pending = PendingImport(if (specs.size == 1) specs[0].name else "${specs.size} ZIP archives", specs, uris.size - specs.size)
                }
            }
        }
    }
    val songLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { persistRead(context, it) }
            c.appScope.launch {
                val specs = c.scanner.lister.describe(uris)
                pending = PendingImport(if (specs.size == 1) specs[0].name else "${specs.size} selected files", specs)
            }
        }
    }

    val busy = scan.running
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle("Import") }

        progress?.let { p -> item { ProgressCard(p, onCancel = { c.importer.cancel() }) } }

        val interrupted = progress == null && jobs.any { it.status == JobStatus.PENDING || it.status == JobStatus.RUNNING }
        if (interrupted) {
            item {
                InfoCard(
                    Icons.Rounded.Warning,
                    "An import was interrupted",
                    "Unfinished sources are processed again from their start; songs that were already added are skipped as duplicates.",
                    "Resume",
                ) { c.importer.start() }
            }
        }
        if (scan.running || scan.message != null) {
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (scan.running) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(scan.message ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (!scan.running) {
                        IconButton(onClick = { c.scanner.clearMessage() }) { Icon(Icons.Rounded.Close, contentDescription = "Dismiss") }
                    }
                }
            }
        }

        item { SectionHeader("Find music") }
        item {
            OptionCard(Icons.Rounded.PhoneAndroid, "Scan Entire Phone", "Finds music Android already knows about. Files stay where they are.", !busy) {
                withAudioPermission { c.appScope.launch { c.scanner.scanPhone(false) } }
            }
        }
        item {
            OptionCard(
                Icons.Rounded.Download, "Scan Downloads",
                "Adds songs from Downloads. Android hides ZIP files there from apps, so pick those with “Import ZIP Files”.", !busy,
            ) {
                withAudioPermission { c.appScope.launch { c.scanner.scanPhone(true) } }
            }
        }
        item {
            OptionCard(Icons.Rounded.Folder, "Choose Folder", "Scans a folder and its sub-folders for songs and ZIP archives.", !busy) {
                folderLauncher.launch(null)
            }
        }
        item {
            OptionCard(Icons.Rounded.FolderZip, "Import ZIP Files", "Select one or many archives. Songs are extracted into Music/Artist/Album.", !busy) {
                zipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
        }
        item {
            OptionCard(Icons.Rounded.AudioFile, "Import Songs", "Select individual audio files.", !busy) {
                songLauncher.launch(arrayOf("audio/*", "application/ogg"))
            }
        }

        item { SectionHeader("Library maintenance") }
        item {
            OptionCard(Icons.Rounded.Refresh, "Rescan Library", "Picks up new, changed, moved and deleted files without creating duplicates.", !busy) {
                val canRead = hasAudioPermission(context)
                c.appScope.launch { c.scanner.rescanAll(canRead) }
            }
        }
        if (missingCount > 0) {
            item {
                InfoCard(
                    Icons.Rounded.ErrorOutline,
                    "$missingCount songs are unavailable",
                    "Their files were deleted, moved or are on storage that is not connected. They are hidden and return automatically if the files come back.",
                    "Remove them",
                ) {
                    c.appScope.launch {
                        val n = c.repo.removeMissing()
                        c.toast("Removed $n unavailable songs from the library")
                    }
                }
            }
        }
        if (folders.isNotEmpty()) {
            item { SectionHeader("Folders you added") }
            items(folders, key = { it.treeUri }) { f ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(f.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { c.appScope.launch { c.scanner.forgetFolder(f) } }) { Text("Forget") }
                }
            }
        }

        if (jobs.isNotEmpty()) {
            item { SectionHeader("History") }
            items(jobs, key = { "job${it.id}" }) { job -> JobCard(job, importRunning = progress != null) }
        }
    }

    pending?.let { p ->
        ImportOptionsDialog(
            p,
            onDismiss = { pending = null },
            onStart = { mode, deleteZips ->
                pending = null
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                c.appScope.launch { c.importer.enqueue(p.label, p.specs, mode, deleteZips) }
            },
        )
    }
}

@Composable
private fun OptionCard(icon: ImageVector, title: String, body: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).clickable(enabled = enabled, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, body: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(actionLabel) }
    }
}

@Composable
private fun ProgressCard(p: ImportProgress, onCancel: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primaryContainer).padding(16.dp),
    ) {
        val onColor = MaterialTheme.colorScheme.onPrimaryContainer
        Text(if (p.cancelling) "Cancelling…" else "Importing", style = MaterialTheme.typography.titleMedium, color = onColor)
        Text(p.label, style = MaterialTheme.typography.bodySmall, color = onColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { if (p.totalSources > 0) p.finishedSources.toFloat() / p.totalSources else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Sources: ${p.completedSources} completed · ${p.failedSources} failed · ${p.remainingSources} remaining (of ${p.totalSources})",
            style = MaterialTheme.typography.bodySmall, color = onColor,
        )
        Text(
            "Songs: ${p.imported} added · ${p.skipped} skipped · ${p.failed} failed" +
                if (p.needsReview > 0) " · ${p.needsReview} need review" else "",
            style = MaterialTheme.typography.bodySmall, color = onColor,
        )
        val current = listOfNotNull(p.currentSource, p.currentEntry).joinToString(" › ")
        if (current.isNotEmpty()) {
            Text(current, style = MaterialTheme.typography.bodySmall, color = onColor.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onCancel, enabled = !p.cancelling, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
    }
}

@Composable
private fun ImportOptionsDialog(p: PendingImport, onDismiss: () -> Unit, onStart: (mode: String, deleteZips: Boolean) -> Unit) {
    var mode by remember { mutableStateOf(AudioMode.INDEX) }
    var deleteZips by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(p.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append("${p.audio} audio files, ${p.zips} ZIP archives")
                        if (p.ignoredFiles > 0) append(" (${p.ignoredFiles} other files ignored)")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (p.audio > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("Audio files", style = MaterialTheme.typography.labelLarge)
                    ModeOption("Leave in place", "Add to the library without copying anything.", mode == AudioMode.INDEX) { mode = AudioMode.INDEX }
                    ModeOption("Copy into library", "Copies into Music/Artist/Album. Originals are kept.", mode == AudioMode.COPY) { mode = AudioMode.COPY }
                    ModeOption("Move into library", "Copies, verifies, then deletes each original.", mode == AudioMode.MOVE) { mode = AudioMode.MOVE }
                }
                if (p.zips > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("ZIP archives", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Songs are extracted into Music/Artist/Album. Exact duplicates are skipped and nothing is overwritten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth().clickable { deleteZips = !deleteZips },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = deleteZips, onCheckedChange = { deleteZips = it })
                        Text("Delete each ZIP after it imports without errors", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(mode, deleteZips) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ModeOption(title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun JobCard(job: ImportJobEntity, importRunning: Boolean) {
    val c = LocalContainer.current
    var expanded by remember { mutableStateOf(false) }
    val (statusText, statusColor) = when (job.status) {
        JobStatus.COMPLETED -> "Completed" to MaterialTheme.colorScheme.tertiary
        JobStatus.COMPLETED_WITH_ERRORS -> "Completed with errors" to MaterialTheme.colorScheme.secondary
        JobStatus.CANCELLED -> "Cancelled" to MaterialTheme.colorScheme.secondary
        JobStatus.RUNNING -> (if (importRunning) "Running" else "Interrupted") to MaterialTheme.colorScheme.primary
        else -> (if (importRunning) "Waiting" else "Not finished") to MaterialTheme.colorScheme.primary
    }
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).clickable { expanded = !expanded }.padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(job.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    statusText + " • " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(job.createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                )
            }
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        job.summary?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        if (expanded) JobDetails(job, importRunning)
    }
}

@Composable
private fun JobDetails(job: ImportJobEntity, importRunning: Boolean) {
    val c = LocalContainer.current
    val items by remember(job.id) { c.db.imports().observeItems(job.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val logs by remember(job.id) { c.db.imports().logs(job.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val unfinished = items.count { it.status == ItemStatus.FAILED || it.status == ItemStatus.CANCELLED }

    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (items.isNotEmpty()) {
            Text(
                "Sources: ${items.count { it.status == ItemStatus.DONE }} completed · ${items.count { it.status == ItemStatus.FAILED }} failed · " +
                    "${items.count { it.status == ItemStatus.CANCELLED }} cancelled · " +
                    "${items.count { it.status == ItemStatus.PENDING || it.status == ItemStatus.RUNNING }} remaining",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (logs.isNotEmpty()) {
            Text("Skipped and failed files", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            logs.take(60).forEach { log ->
                val label = when (log.status) {
                    "SKIPPED_DUPLICATE" -> "Duplicate"
                    "SKIPPED_UNSUPPORTED" -> "Unsupported"
                    "FAILED" -> "Failed"
                    else -> "Note"
                }
                Text(
                    "$label: ${log.path}" + (log.message?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (log.status == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (logs.size > 60) {
                Text("…and more (showing the latest 60)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (unfinished > 0 && !importRunning) {
                TextButton(onClick = { c.appScope.launch { c.importer.retry(job.id) } }) { Text("Retry $unfinished unfinished") }
            }
            if (!importRunning) {
                TextButton(onClick = { c.appScope.launch { c.db.imports().deleteJob(job.id) } }) { Text("Remove from history") }
            }
        }
    }
}
