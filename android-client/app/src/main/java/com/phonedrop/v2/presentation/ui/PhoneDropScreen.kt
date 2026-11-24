package com.phonedrop.v2.presentation.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.phonedrop.v2.data.mirror.DecoderStatus
import com.phonedrop.v2.data.protocol.InputConstants
import com.phonedrop.v2.data.protocol.InputEventPayload
import com.phonedrop.v2.data.protocol.RemoteFileEntry
import com.phonedrop.v2.domain.model.ConnectionState
import com.phonedrop.v2.domain.model.LocalReceivedFile
import com.phonedrop.v2.domain.model.MirrorMode
import com.phonedrop.v2.domain.model.TransferDirection
import com.phonedrop.v2.domain.model.TransferState
import com.phonedrop.v2.domain.model.TransferStatus
import com.phonedrop.v2.presentation.MainViewModel
import java.util.Locale
import kotlin.math.roundToInt

// PhoneDrop v1 Deep Botanical + Roasted Espresso + Champagne Brass palette
private val BgDeep = Color(0xFF0B110E)
private val CardSurface = Color(0xFF161E19)
private val InnerSurface = Color(0xFF0E1511)
private val CardBorder = Color(0xFF332E24)
private val Brass = Color(0xFFC8A97E)
private val BrassLight = Color(0xFFDFC49B)
private val TextWarm = Color(0xFFF3EFE6)
private val TextMuted = Color(0xFFA39C90)
private val SuccessGreen = Color(0xFF5EAB82)
private val DangerCoral = Color(0xFFD46A5E)

@Composable
fun PhoneDropScreen(
    viewModel: MainViewModel,
    onPickFiles: () -> Unit,
    onOpenFile: (uriString: String, mimeType: String) -> Unit,
    onShareFile: (uriString: String, mimeType: String) -> Unit,
    onReadPhoneClipboard: () -> String,
    onCopyToPhoneClipboard: (String) -> Unit,
    onStartPhoneMirror: () -> Unit,
    onStopPhoneMirror: () -> Unit,
    onSurfaceReady: (SurfaceView) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    val connState by viewModel.connectionState.collectAsState()
    val transfers by viewModel.transferList.collectAsState()
    val latestClip by viewModel.latestClipboard.collectAsState()
    val mirrorMode by viewModel.mirrorMode.collectAsState()
    val remoteFiles by viewModel.remoteFiles.collectAsState()
    val localFiles by viewModel.localFiles.collectAsState()
    val decoderStatus by viewModel.decoderStatus.collectAsState()
    val toastMsg by viewModel.statusToast.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showConnDialog by remember { mutableStateOf(false) }
    var hostInput by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(toastMsg) {
        val msg = toastMsg
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusToast()
        }
    }

    Scaffold(
        containerColor = BgDeep,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            BotanicalTopBar(
                state = connState,
                onOpenSettings = { showConnDialog = true }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF101713)) {
                val navColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = BrassLight,
                    selectedTextColor = BrassLight,
                    indicatorColor = Color(0xFF28221A),
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                )
                val tabs = listOf(
                    "File" to "📁",
                    "Clipboard" to "📋",
                    "Remote" to "🖱️",
                    "Layar" to "🖥️"
                )
                tabs.forEachIndexed { idx, (label, icon) ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        icon = { Text(icon, fontSize = 18.sp) },
                        label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                        colors = navColors
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            when (selectedTab) {
                0 -> FilesTab(
                    transfers = transfers,
                    remoteFiles = remoteFiles,
                    localFiles = localFiles,
                    onPickFiles = onPickFiles,
                    onRefreshFiles = { viewModel.requestFileList() },
                    onPullRemoteFile = { viewModel.pullRemoteFile(it) },
                    onDeleteRemoteFile = { viewModel.deleteRemoteFile(it) },
                    onOpenLocalFile = onOpenFile,
                    onShareLocalFile = onShareFile,
                    onDeleteLocalFile = { viewModel.deleteLocalFile(it) }
                )
                1 -> ClipboardTab(
                    latestClip = latestClip,
                    onSendClip = { viewModel.sendClipboardText(it) },
                    onPasteFromPhone = {
                        val txt = onReadPhoneClipboard()
                        if (txt.isNotBlank()) {
                            viewModel.sendClipboardText(txt)
                        }
                        txt
                    },
                    onCopyToPhone = onCopyToPhoneClipboard
                )
                2 -> RemoteControlTab(
                    onSendInput = { viewModel.sendInputEvent(it) }
                )
                3 -> ScreenMirrorTab(
                    mirrorMode = mirrorMode,
                    decoderStatus = decoderStatus,
                    onStartDesktopView = { viewModel.startDesktopViewing() },
                    onStopDesktopView = { viewModel.stopDesktopViewing() },
                    onLaunchPhoneScrcpy = { viewModel.launchPhoneScrcpyOnLaptop() },
                    onStartPhoneCast = onStartPhoneMirror,
                    onStopPhoneCast = onStopPhoneMirror,
                    onSurfaceReady = onSurfaceReady,
                    onSurfaceDestroyed = onSurfaceDestroyed,
                    onSendInput = { viewModel.sendInputEvent(it) }
                )
            }
        }
    }

    if (showConnDialog) {
        AlertDialog(
            onDismissRequest = { showConnDialog = false },
            containerColor = CardSurface,
            titleContentColor = TextWarm,
            textContentColor = TextMuted,
            title = { Text("Koneksi Laptop", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Biarkan kosong untuk deteksi otomatis USB (adb reverse) atau Wi-Fi mDNS.",
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = hostInput,
                        onValueChange = { hostInput = it },
                        label = { Text("IP Laptop (opsional)") },
                        placeholder = { Text("contoh: 192.168.1.8") },
                        singleLine = true,
                        colors = botanicalTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = BgDeep),
                    onClick = {
                        viewModel.startServiceAndConnect(hostInput)
                        showConnDialog = false
                    }
                ) {
                    Text("Hubungkan", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row {
                    if (connState.isConnected) {
                        TextButton(onClick = {
                            viewModel.disconnect()
                            showConnDialog = false
                        }) {
                            Text("Putuskan", color = DangerCoral)
                        }
                    }
                    TextButton(onClick = { showConnDialog = false }) {
                        Text("Tutup", color = TextMuted)
                    }
                }
            }
        )
    }
}

@Composable
private fun BotanicalTopBar(
    state: ConnectionState,
    onOpenSettings: () -> Unit
) {
    val (badgeText, badgeColor) = when (state) {
        is ConnectionState.UsbConnected -> "USB Aktif" to SuccessGreen
        is ConnectionState.WifiConnected -> "Wi-Fi Aktif" to SuccessGreen
        is ConnectionState.Connecting -> "Menghubungkan..." to BrassLight
        is ConnectionState.Error -> "Terputus" to DangerCoral
        ConnectionState.Disconnected -> "Terputus" to DangerCoral
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0E1511))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(BrassLight)
            )
            Text(
                text = "PhoneDrop",
                color = TextWarm,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(badgeColor.copy(alpha = 0.14f))
                    .border(1.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(badgeText, color = badgeColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = onOpenSettings,
                border = BorderStroke(1.dp, CardBorder),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("⚙️", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun FilesTab(
    transfers: List<TransferState>,
    remoteFiles: List<RemoteFileEntry>,
    localFiles: List<LocalReceivedFile>,
    onPickFiles: () -> Unit,
    onRefreshFiles: () -> Unit,
    onPullRemoteFile: (String) -> Unit,
    onDeleteRemoteFile: (String) -> Unit,
    onOpenLocalFile: (String, String) -> Unit,
    onShareLocalFile: (String, String) -> Unit,
    onDeleteLocalFile: (String) -> Unit
) {
    var folderMode by remember { mutableIntStateOf(0) } // 0 = Laptop, 1 = Diterima HP

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = BorderStroke(1.dp, Brass.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPickFiles() }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp, horizontal = 16.dp)
                ) {
                    Text("✦", color = BrassLight, fontSize = 24.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Pilih & Kirim File ke Laptop",
                        color = TextWarm,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Mendukung banyak file sekaligus • 0 Kuota • SHA-256",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (transfers.isNotEmpty()) {
            items(transfers.take(3), key = { it.transferIdHex }) { tr ->
                TransferProgressCard(
                    tr = tr,
                    onOpen = { uri -> onOpenLocalFile(uri, "*/*") }
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                border = BorderStroke(1.dp, CardBorder),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ruang File Bersama",
                            color = TextWarm,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        OutlinedButton(
                            onClick = onRefreshFiles,
                            border = BorderStroke(1.dp, CardBorder),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Segarkan", color = BrassLight, fontSize = 11.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SegmentPill(
                            text = "Di Laptop (${remoteFiles.size})",
                            selected = folderMode == 0,
                            onClick = { folderMode = 0 },
                            modifier = Modifier.weight(1f)
                        )
                        SegmentPill(
                            text = "Diterima di HP (${localFiles.size})",
                            selected = folderMode == 1,
                            onClick = { folderMode = 1 },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (folderMode == 0) {
            if (remoteFiles.isEmpty()) {
                item {
                    EmptyHintCard("Belum ada file di ~/Downloads/PhoneDrop Laptop. Ketuk Segarkan jika sudah terhubung.")
                }
            } else {
                items(remoteFiles, key = { "remote-${it.name}" }) { rf ->
                    RemoteFileRowCard(
                        entry = rf,
                        onPull = { onPullRemoteFile(rf.name) },
                        onDelete = { onDeleteRemoteFile(rf.name) }
                    )
                }
            }
        } else {
            if (localFiles.isEmpty()) {
                item {
                    EmptyHintCard("Belum ada file yang diterima di folder Downloads/PhoneDrop HP.")
                }
            } else {
                items(localFiles, key = { "local-${it.uriString}" }) { lf ->
                    LocalFileRowCard(
                        file = lf,
                        onOpen = { onOpenLocalFile(lf.uriString, lf.mimeType) },
                        onShare = { onShareLocalFile(lf.uriString, lf.mimeType) },
                        onDelete = { onDeleteLocalFile(lf.uriString) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferProgressCard(
    tr: TransferState,
    onOpen: (String) -> Unit
) {
    val dir = if (tr.direction == TransferDirection.SEND) "Kirim" else "Terima"
    val color = when (tr.status) {
        TransferStatus.IN_PROGRESS -> BrassLight
        TransferStatus.VERIFIED -> SuccessGreen
        TransferStatus.FAILED -> DangerCoral
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "[$dir] ${tr.fileName}",
                    color = TextWarm,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = String.format(Locale.US, "%.1f%% • %.1f MB/s", tr.percent, tr.speedMBps),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (tr.percent / 100f).coerceIn(0f, 1f) },
                color = color,
                trackColor = InnerSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val sub = when (tr.status) {
                    TransferStatus.IN_PROGRESS -> "${tr.chunkIndex}/${tr.totalChunks} blok • ${formatBytes(tr.bytesTransferred)}/${formatBytes(tr.totalBytes)}"
                    TransferStatus.VERIFIED -> "SHA-256 Terverifikasi ✓ (${formatBytes(tr.totalBytes)})"
                    TransferStatus.FAILED -> tr.errorMessage ?: "Gagal memverifikasi integritas"
                }
                Text(sub, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (tr.status == TransferStatus.VERIFIED && !tr.savedUri.isNullOrBlank()) {
                    TextButton(
                        onClick = { onOpen(tr.savedUri) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("Buka File", color = BrassLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteFileRowCard(
    entry: RemoteFileEntry,
    onPull: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = InnerSurface),
        border = BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    color = TextWarm,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp
                )
                Text(formatBytes(entry.size), color = TextMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onPull,
                colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = BgDeep),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Unduh", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = onDelete,
                border = BorderStroke(1.dp, DangerCoral.copy(alpha = 0.45f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("✕", color = DangerCoral, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun LocalFileRowCard(
    file: LocalReceivedFile,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = InnerSurface),
        border = BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = file.name,
                color = TextWarm,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp
            )
            Text(formatBytes(file.sizeBytes), color = TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpen,
                    colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = BgDeep),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Buka", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onShare,
                    border = BorderStroke(1.dp, CardBorder),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Bagikan", color = TextWarm, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = onDelete,
                    border = BorderStroke(1.dp, DangerCoral.copy(alpha = 0.45f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Hapus", color = DangerCoral, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ClipboardTab(
    latestClip: String,
    onSendClip: (String) -> Unit,
    onPasteFromPhone: () -> String,
    onCopyToPhone: (String) -> Unit
) {
    var clipInput by remember { mutableStateOf("") }
    LaunchedEffect(latestClip) {
        if (latestClip.isNotEmpty() && clipInput.isEmpty()) {
            clipInput = latestClip
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        border = BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(14.dp)
        ) {
            Text("Clipboard Universal (Laptop ⇄ HP)", color = TextWarm, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (latestClip.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(InnerSurface)
                        .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Terakhir disinkronkan: ${latestClip.take(240)}",
                        color = BrassLight,
                        fontSize = 12.sp
                    )
                }
            }
            OutlinedTextField(
                value = clipInput,
                onValueChange = { clipInput = it },
                placeholder = { Text("Tulis atau tempel teks untuk dikirim ke Laptop...") },
                minLines = 4,
                maxLines = 8,
                colors = botanicalTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onSendClip(clipInput.ifBlank { latestClip }) },
                    colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = BgDeep),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Kirim ke Laptop", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = {
                        val txt = clipInput.ifBlank { latestClip }
                        if (txt.isNotBlank()) onCopyToPhone(txt)
                    },
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Salin ke HP", color = TextWarm)
                }
            }
            OutlinedButton(
                onClick = {
                    val pasted = onPasteFromPhone()
                    if (pasted.isNotBlank()) clipInput = pasted
                },
                border = BorderStroke(1.dp, Brass.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📋 Tempel & Kirim dari Clipboard HP", color = BrassLight)
            }
        }
    }
}

@Composable
private fun RemoteControlTab(
    onSendInput: (InputEventPayload) -> Unit
) {
    var kbdText by remember { mutableStateOf("") }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            border = BorderStroke(1.dp, CardBorder),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(14.dp)) {
                Text("Remote Touchpad & Keyboard", color = TextWarm, fontWeight = FontWeight.Bold, fontSize = 15.sp)

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(InnerSurface)
                        .border(1.dp, Brass.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    onSendInput(
                                        InputEventPayload(
                                            eventType = InputConstants.INPUT_MOUSE_BUTTON,
                                            actionOrButton = 1,
                                            arg1 = 2
                                        )
                                    )
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { _, drag ->
                                val dx = (drag.x * 1.4f).roundToInt()
                                val dy = (drag.y * 1.4f).roundToInt()
                                if (dx != 0 || dy != 0) {
                                    onSendInput(
                                        InputEventPayload(
                                            eventType = InputConstants.INPUT_MOVE_REL,
                                            x = dx,
                                            y = dy
                                        )
                                    )
                                }
                            }
                        }
                ) {
                    Text(
                        text = "Geser 1 jari = Gerak Kursor  •  Ketuk = Klik Kiri",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            onSendInput(InputEventPayload(InputConstants.INPUT_MOUSE_BUTTON, actionOrButton = 1, arg1 = 2))
                        },
                        border = BorderStroke(1.dp, CardBorder),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Klik Kiri", color = TextWarm, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            onSendInput(InputEventPayload(InputConstants.INPUT_SCROLL, y = 1))
                        },
                        border = BorderStroke(1.dp, CardBorder),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("▲ Scroll", color = TextWarm, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            onSendInput(InputEventPayload(InputConstants.INPUT_SCROLL, y = -1))
                        },
                        border = BorderStroke(1.dp, CardBorder),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("▼ Scroll", color = TextWarm, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            onSendInput(InputEventPayload(InputConstants.INPUT_MOUSE_BUTTON, actionOrButton = 3, arg1 = 2))
                        },
                        border = BorderStroke(1.dp, CardBorder),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Klik Kanan", color = TextWarm, fontSize = 12.sp)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = kbdText,
                        onValueChange = { kbdText = it },
                        placeholder = { Text("Ketik teks ke Laptop...") },
                        singleLine = true,
                        colors = botanicalTextFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (kbdText.isNotEmpty()) {
                                onSendInput(InputEventPayload(InputConstants.INPUT_TEXT_UTF8, text = kbdText))
                                kbdText = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = BgDeep),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("Kirim", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            onSendInput(InputEventPayload(InputConstants.INPUT_KEY_CODE, text = "BackSpace"))
                        },
                        border = BorderStroke(1.dp, CardBorder),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text("⌫", color = TextWarm)
                    }
                    OutlinedButton(
                        onClick = {
                            onSendInput(InputEventPayload(InputConstants.INPUT_KEY_CODE, text = "Return"))
                        },
                        border = BorderStroke(1.dp, Brass),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text("↵", color = BrassLight)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenMirrorTab(
    mirrorMode: MirrorMode,
    decoderStatus: DecoderStatus,
    onStartDesktopView: () -> Unit,
    onStopDesktopView: () -> Unit,
    onLaunchPhoneScrcpy: () -> Unit,
    onStartPhoneCast: () -> Unit,
    onStopPhoneCast: () -> Unit,
    onSurfaceReady: (SurfaceView) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onSendInput: (InputEventPayload) -> Unit
) {
    var boxSize by remember { mutableStateOf(IntSize(1, 1)) }
    var quickText by remember { mutableStateOf("") }
    val viewingDesktop = mirrorMode == MirrorMode.RECEIVING_LINUX_DESKTOP

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CardSurface),
            border = BorderStroke(1.dp, CardBorder),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(12.dp)) {
                Text("Screen Mirroring Dua Arah", color = TextWarm, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { if (viewingDesktop) onStopDesktopView() else onStartDesktopView() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (viewingDesktop) SuccessGreen else Brass,
                            contentColor = BgDeep
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (viewingDesktop) "Stop Layar Laptop" else "Lihat Layar Laptop", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onLaunchPhoneScrcpy,
                        border = BorderStroke(1.dp, Brass.copy(alpha = 0.55f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Layar HP (scrcpy)", color = BrassLight, fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = {
                        if (mirrorMode == MirrorMode.SENDING_PHONE_SCREEN) onStopPhoneCast() else onStartPhoneCast()
                    },
                    border = BorderStroke(1.dp, CardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (mirrorMode == MirrorMode.SENDING_PHONE_SCREEN) "Hentikan Cast MediaProjection" else "Alternatif: Cast HP via MediaProjection",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }

                val statusStr = when (decoderStatus) {
                    DecoderStatus.Idle -> "Tekan 'Lihat Layar Laptop' untuk menampilkan layar Ubuntu."
                    DecoderStatus.WaitingForStream -> "Menunggu frame video H.264 dari Laptop..."
                    is DecoderStatus.Live -> "Live ${decoderStatus.width}×${decoderStatus.height} • Ketuk layar untuk klik, geser untuk kursor"
                    is DecoderStatus.Error -> "Status Codec: ${decoderStatus.message}"
                }
                Text(
                    text = statusStr,
                    color = if (decoderStatus is DecoderStatus.Live) SuccessGreen else TextMuted,
                    fontSize = 12.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                .onSizeChanged { boxSize = it }
                .pointerInput(boxSize) {
                    detectTapGestures(
                        onTap = { offset ->
                            val nx = ((offset.x / boxSize.width.coerceAtLeast(1)) * 65535f).toInt().coerceIn(0, 65535)
                            val ny = ((offset.y / boxSize.height.coerceAtLeast(1)) * 65535f).toInt().coerceIn(0, 65535)
                            onSendInput(
                                InputEventPayload(
                                    eventType = InputConstants.INPUT_TAP_ABS,
                                    actionOrButton = 1,
                                    x = nx,
                                    y = ny,
                                    arg1 = 2
                                )
                            )
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { _, drag ->
                        val dx = (drag.x * 1.4f).roundToInt()
                        val dy = (drag.y * 1.4f).roundToInt()
                        if (dx != 0 || dy != 0) {
                            onSendInput(InputEventPayload(InputConstants.INPUT_MOVE_REL, x = dx, y = dy))
                        }
                    }
                }
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onSurfaceReady(this@apply)
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onSurfaceDestroyed()
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Quick Mouse + Keyboard controls right under desktop mirror
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onSendInput(InputEventPayload(InputConstants.INPUT_MOUSE_BUTTON, actionOrButton = 1, arg1 = 2)) },
                border = BorderStroke(1.dp, CardBorder),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Klik Kiri", color = TextWarm, fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = { onSendInput(InputEventPayload(InputConstants.INPUT_MOUSE_BUTTON, actionOrButton = 3, arg1 = 2)) },
                border = BorderStroke(1.dp, CardBorder),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Klik Kanan", color = TextWarm, fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = { onSendInput(InputEventPayload(InputConstants.INPUT_SCROLL, y = 1)) },
                border = BorderStroke(1.dp, CardBorder),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("▲ Scroll", color = TextWarm, fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = { onSendInput(InputEventPayload(InputConstants.INPUT_SCROLL, y = -1)) },
                border = BorderStroke(1.dp, CardBorder),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("▼ Scroll", color = TextWarm, fontSize = 11.sp)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = quickText,
                onValueChange = { quickText = it },
                placeholder = { Text("Ketik ke layar Laptop...") },
                singleLine = true,
                colors = botanicalTextFieldColors(),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    if (quickText.isNotEmpty()) {
                        onSendInput(InputEventPayload(InputConstants.INPUT_TEXT_UTF8, text = quickText))
                        quickText = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = BgDeep),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text("Kirim", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = { onSendInput(InputEventPayload(InputConstants.INPUT_KEY_CODE, text = "Return")) },
                border = BorderStroke(1.dp, Brass),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Text("↵", color = BrassLight)
            }
        }
    }
}

@Composable
private fun SegmentPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Brass else InnerSurface)
            .border(1.dp, if (selected) BrassLight else CardBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = if (selected) BgDeep else TextMuted,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun EmptyHintCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = InnerSurface),
        border = BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = text, color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun botanicalTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextWarm,
    unfocusedTextColor = TextWarm,
    focusedBorderColor = Brass,
    unfocusedBorderColor = CardBorder,
    cursorColor = BrassLight,
    focusedContainerColor = InnerSurface,
    unfocusedContainerColor = InnerSurface,
    focusedPlaceholderColor = TextMuted,
    unfocusedPlaceholderColor = TextMuted,
    focusedLabelColor = BrassLight,
    unfocusedLabelColor = TextMuted
)

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}
