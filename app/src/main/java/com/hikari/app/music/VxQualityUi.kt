package com.hikari.app.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Streaming + download quality picker (VxMusic options, no login). */
@Composable
fun VxQualityDialog(onClose: () -> Unit) {
    VxQualityDialogFull(onClose = onClose, onMore = onClose)
}

@Composable
fun VxQualityDialogFull(onClose: () -> Unit, onMore: () -> Unit) {
    val ctx = LocalContext.current
    var prefer by remember { mutableStateOf(VxQuality.preferLossless(ctx)) }
    var streamQ by remember { mutableStateOf(VxQuality.streamQuality(ctx)) }
    var dlQ by remember { mutableStateOf(VxQuality.downloadQuality(ctx)) }
    var bitPerfect by remember { mutableStateOf(VxQuality.bitPerfect(ctx)) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Audio quality") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Prefer lossless (FLAC)", style = MaterialTheme.typography.bodyLarge)
                        Text("Falls back to YouTube for remixes", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = prefer, onCheckedChange = {
                        prefer = it
                        VxQuality.setPreferLossless(ctx, it)
                    })
                }
                Spacer(Modifier.height(12.dp))
                Text("Streaming", style = MaterialTheme.typography.labelLarge)
                for (q in VxQuality.STREAM_OPTIONS) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            streamQ = q
                            VxQuality.setStreamQuality(ctx, q)
                        }.padding(vertical = 2.dp)) {
                        RadioButton(selected = streamQ == q, onClick = {
                            streamQ = q
                            VxQuality.setStreamQuality(ctx, q)
                        })
                        Spacer(Modifier.width(8.dp))
                        Text(VxQuality.labelFor(q))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Downloads", style = MaterialTheme.typography.labelLarge)
                for (q in VxQuality.DOWNLOAD_OPTIONS) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            dlQ = q
                            VxQuality.setDownloadQuality(ctx, q)
                        }.padding(vertical = 2.dp)) {
                        RadioButton(selected = dlQ == q, onClick = {
                            dlQ = q
                            VxQuality.setDownloadQuality(ctx, q)
                        })
                        Spacer(Modifier.width(8.dp))
                        Text(VxQuality.labelFor(q))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Bit-perfect (no EQ)", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = bitPerfect, onCheckedChange = {
                        bitPerfect = it
                        VxQuality.setBitPerfect(ctx, it)
                        VxAudioFx.apply(ctx)
                    })
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Videos tab is kept: unofficial remixes / slowed+reverb play via YouTube even on FLAC mode.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onMore) { Text("All settings") }
            TextButton(onClick = onClose) { Text("Done") }
        },
    )
}
