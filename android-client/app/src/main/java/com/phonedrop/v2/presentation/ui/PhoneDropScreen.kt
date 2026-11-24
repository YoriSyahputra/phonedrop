package com.phonedrop.v2.presentation.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.phonedrop.v2.data.protocol.InputConstants
import com.phonedrop.v2.data.protocol.InputEventPayload
import com.phonedrop.v2.domain.model.ConnectionState
import com.phonedrop.v2.domain.model.MirrorMode
import com.phonedrop.v2.domain.model.TransferDirection
import com.phonedrop.v2.domain.model.TransferState
import com.phonedrop.v2.domain.model.TransferStatus
import com.phonedrop.v2.presentation.MainViewModel
import java.util.Locale

@Composable
fun PhoneDropScreen(
    viewModel: MainViewModel,
    onPickFile: () -> Unit,
    onStartPhoneMirror: () -> Unit,
    onStopPhoneMirror: () -> Unit,
    onSurfaceReady: (SurfaceView) -> Unit,
    onSurfaceDestroyed: () -> Unit
) {
    val connState by viewModel.connectionState.collectAsState()
    val transfers by viewModel.transferList.collectAsState()
    val clipText by viewModel.latestClipboard.collectAsState()
    val mirrorMode by viewModel.mirrorMode.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var hostInput by remember { mutableStateOf("") }
    var clipInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101318))
            .padding(14.dp)
    ) {
        Text(
            text = "PhoneDrop v2",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE6EDF3)
        )
        Spacer(Modifier.height(8.dp))

        ConnectionBanner(
            state = connState,
            hostInput = hostInput,
            onHostChange = { hostInput = it },
            onConnect = { viewModel.startServiceAndConnect(hostInput) },
            onDisconnect = { viewModel.disconnect() }
        )

        Spacer(Modifier.height(10.dp))

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Files & Clip") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Mirroring") })
        }

        Spacer(Modifier.height(10.dp))

        if (selectedTab == 0) {
            FilesAndClipboardTab(
                transfers = transfers,
                latestClip = clipText,
                clipInput = clipInput,
                onClipInputChange = { clipInput = it },
                onSendClipboard = {
                    val toSend = clipInput.ifBlank { clipText }
                    viewModel.sendClipboardText(toSend)
                },
                onPickFile = onPickFile
            )
        } else {
            MirroringTab(
                mirrorMode = mirrorMode,
                onStartPhoneMirror = onStartPhoneMirror,
                onStopPhoneMirror = onStopPhoneMirror,
                onToggleDesktopViewer = { active ->
                    viewModel.setMirrorMode(if (active) MirrorMode.RECEIVING_LINUX_DESKTOP else MirrorMode.IDLE)
                },
                onSurfaceReady = onSurfaceReady,
                onSurfaceDestroyed = onSurfaceDestroyed,
                onSendInput = { viewModel.sendInputEvent(it) }
            )
        }
    }
}

@Composable
private fun ConnectionBanner(
    state: ConnectionState,
    hostInput: String,
    onHostChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val badgeColor = when (state) {
        is ConnectionState.UsbConnected -> Color(0xFF2EA043)
        is ConnectionState.WifiConnected -> Color(0xFF388BFD)
        is ConnectionState.Connecting -> Color(0xFFD29922)
        is ConnectionState.Error -> Color(0xFFF85149)
        ConnectionState.Disconnected -> Color(0xFF6E7681)
    }
    val label = when (state) {
        ConnectionState.Disconnected -> "Disconnected (USB 127.0.0.1:28200 / LAN mDNS)"
        is ConnectionState.Connecting -> "Connecting to ${state.endpoint}..."
        is ConnectionState.UsbConnected -> "USB Tunnel Ready • ${state.endpoint}"
        is ConnectionState.WifiConnected -> "Wi-Fi LAN • ${state.endpoint}"
        is ConnectionState.Error -> "Error: ${state.message}"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171C24)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = label, color = badgeColor, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = onHostChange,
                    placeholder = { Text("Auto (USB/mDNS) or IP") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                if (state.isConnected) {
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                } else {
                    Button(onClick = onConnect) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun FilesAndClipboardTab(
    transfers: List<TransferState>,
    latestClip: String,
    clipInput: String,
    onClipInputChange: (String) -> Unit,
    onSendClipboard: () -> Unit,
    onPickFile: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF171C24)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Clipboard Sync", color = Color(0xFFE6EDF3), fontWeight = FontWeight.SemiBold)
                if (latestClip.isNotEmpty()) {
                    Text(
                        text = "Latest: ${latestClip.take(120)}",
                        color = Color(0xFF8B949E),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = clipInput,
                        onValueChange = onClipInputChange,
                        placeholder = { Text("Text to push to Linux") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onSendClipboard) { Text("Sync") }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Text("Send File (64KB Streaming + SHA-256)")
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(transfers, key = { it.transferIdHex }) { tr ->
                TransferRow(tr)
            }
        }
    }
}

@Composable
private fun TransferRow(tr: TransferState) {
    val dir = if (tr.direction == TransferDirection.SEND) "UP" else "DOWN"
    val statusColor = when (tr.status) {
        TransferStatus.IN_PROGRESS -> Color(0xFF58A6FF)
        TransferStatus.VERIFIED -> Color(0xFF3FB950)
        TransferStatus.FAILED -> Color(0xFFF85149)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171C24)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "[$dir] ${tr.fileName}",
                    color = Color(0xFFE6EDF3),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = String.format(Locale.US, "%.1f%% • %.1f MB/s", tr.percent, tr.speedMBps),
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (tr.percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            val footer = when (tr.status) {
                TransferStatus.IN_PROGRESS -> "${tr.chunkIndex}/${tr.totalChunks} chunks"
                TransferStatus.VERIFIED -> "SHA-256 Verified: ${tr.sha256Hex.take(16)}…"
                TransferStatus.FAILED -> tr.errorMessage ?: "Transfer failed"
            }
            Text(text = footer, color = Color(0xFF8B949E), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MirroringTab(
    mirrorMode: MirrorMode,
    onStartPhoneMirror: () -> Unit,
    onStopPhoneMirror: () -> Unit,
    onToggleDesktopViewer: (Boolean) -> Unit,
    onSurfaceReady: (SurfaceView) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onSendInput: (InputEventPayload) -> Unit
) {
    var boxSize by remember { mutableStateOf(IntSize(1, 1)) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (mirrorMode == MirrorMode.SENDING_PHONE_SCREEN) {
                OutlinedButton(onClick = onStopPhoneMirror, modifier = Modifier.weight(1f)) {
                    Text("Stop Phone Cast")
                }
            } else {
                Button(onClick = onStartPhoneMirror, modifier = Modifier.weight(1f)) {
                    Text("Cast Phone → Linux")
                }
            }
            val viewing = mirrorMode == MirrorMode.RECEIVING_LINUX_DESKTOP
            Button(
                onClick = { onToggleDesktopViewer(!viewing) },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (viewing) "Hide Desktop" else "View Linux Screen")
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black, RoundedCornerShape(8.dp))
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
                    detectDragGestures { _, dragAmount ->
                        onSendInput(
                            InputEventPayload(
                                eventType = InputConstants.INPUT_MOVE_REL,
                                x = dragAmount.x.toInt(),
                                y = dragAmount.y.toInt()
                            )
                        )
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
    }
}
