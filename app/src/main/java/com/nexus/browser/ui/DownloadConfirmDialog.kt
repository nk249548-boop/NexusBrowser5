package com.nexus.browser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * DownloadConfirmDialog — shown when WebView DownloadListener fires for a
 * direct downloadable file URL.
 *
 * Shows filename, MIME type, destination folder.
 * Confirm → routes to DownloadViewModel.enqueueFromWebView().
 * Cancel  → dismisses without downloading.
 *
 * Play-Store compliant: no fabricated quality labels, no stream ripping.
 */
@Composable
fun DownloadConfirmDialog(
    filename:  String,
    mimeType:  String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Card(
            shape  = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = mimeToIcon(mimeType),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text  = "Download File",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize   = 20.sp
                        )
                    )
                }

                HorizontalDivider()

                InfoRow(label = "File",    value = filename)
                InfoRow(label = "Type",    value = mimeType.ifBlank { "Unknown" })
                InfoRow(label = "Save to", value = "Downloads/NexusBrowser")

                Spacer(Modifier.height(4.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape   = RoundedCornerShape(12.dp)
                    ) { Text("Cancel") }

                    Button(
                        onClick = onConfirm,
                        shape   = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Download")
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                fontSize = 11.sp
            )
        )
        Text(
            text     = value,
            style    = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun mimeToIcon(mime: String): ImageVector = when {
    mime.startsWith("video/")       -> Icons.Default.VideoFile
    mime.startsWith("audio/")       -> Icons.Default.AudioFile
    mime == "application/pdf"       -> Icons.Default.PictureAsPdf
    mime.contains("zip") ||
    mime.contains("rar") ||
    mime.contains("7z")             -> Icons.Default.FolderZip
    mime.startsWith("image/")       -> Icons.Default.Image
    mime.startsWith("text/") ||
    mime.contains("document")       -> Icons.Default.Description
    else                            -> Icons.Default.Download
}
