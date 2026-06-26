package com.nexus.browser.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Divider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.nexus.browser.theme.Spacing
import com.nexus.browser.viewmodel.BrowserViewModel

// ─── Downloads Screen ─────────────────────────────────────────────────────────
@Composable
fun DownloadsScreen(viewModel: BrowserViewModel, onBack: () -> Unit = {}) {
    val downloads by viewModel.downloads.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // Header with back button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7FAFC))
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ArrowBack,
                "Back",
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onBack() },
                tint = Color(0xFF2D3748)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    "Downloads",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2D3748)
                )
                Text(
                    "${downloads.size} files",
                    fontSize = 12.sp,
                    color = Color(0xFF718096)
                )
            }
        }

        if (downloads.isEmpty()) {
            EmptyStateScreen(
                icon = Icons.Default.Download,
                title = "No Downloads",
                subtitle = "Your downloads will appear here"
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                items(downloads) { download ->
                    DownloadItemCard(
                        download = download,
                        onDelete = { viewModel.deleteDownload(download.id) }
                    )
                }

                item {
                    Button(
                        onClick = { viewModel.clearAllDownloads() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFE0E0)
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            "Clear",
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 8.dp)
                        )
                        Text("Clear All Downloads", color = Color(0xFFE53E3E))
                    }
                }
            }
        }
    }
}

// ─── Download Item Card ───────────────────────────────────────────────────────
@Composable
fun DownloadItemCard(
    download: com.nexus.browser.viewmodel.Download,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7FAFC))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF5B7FFF).copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Download,
                "File",
                modifier = Modifier.size(24.dp),
                tint = Color(0xFF5B7FFF)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                download.fileName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF2D3748),
                maxLines = 1
            )
            Text(
                "${(download.fileSize / 1024 / 1024)}MB",
                fontSize = 12.sp,
                color = Color(0xFF718096)
            )
            LinearProgressIndicator(
                progress = download.progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = if (download.isComplete) Color(0xFF48BB78) else Color(0xFF5B7FFF)
            )
        }

        if (!download.isComplete) {
            Icon(
                Icons.Default.Close,
                "Cancel",
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onDelete() },
                tint = Color(0xFF718096)
            )
        } else {
            Icon(
                Icons.Default.Check,
                "Done",
                modifier = Modifier.size(24.dp),
                tint = Color(0xFF48BB78)
            )
        }
    }
}

// ─── Bookmarks Screen ─────────────────────────────────────────────────────────
@Composable
fun BookmarksScreen(viewModel: BrowserViewModel, onNavigate: (String) -> Unit = {}) {
    val bookmarks by viewModel.bookmarks.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var bookmarkTitle by remember { mutableStateOf("") }
    var bookmarkUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7FAFC))
                .padding(Spacing.lg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Bookmarks",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2D3748)
                    )
                    Text(
                        "${bookmarks.size} bookmarks",
                        fontSize = 12.sp,
                        color = Color(0xFF718096)
                    )
                }

                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = Color(0xFF5B7FFF),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, "Add")
                }
            }
        }

        if (bookmarks.isEmpty()) {
            EmptyStateScreen(
                icon = Icons.Default.Bookmark,
                title = "No Bookmarks",
                subtitle = "Save your favorite sites here"
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                items(bookmarks) { bookmark ->
                    BookmarkItemCard(
                        bookmark = bookmark,
                        onDelete = { viewModel.removeBookmark(bookmark.id) },
                        onNavigate = { onNavigate(bookmark.url) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Bookmark") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    TextField(
                        value = bookmarkTitle,
                        onValueChange = { bookmarkTitle = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = bookmarkUrl,
                        onValueChange = { bookmarkUrl = it },
                        label = { Text("URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (bookmarkTitle.isNotEmpty() && bookmarkUrl.isNotEmpty()) {
                            viewModel.addBookmark(bookmarkTitle, bookmarkUrl)
                            bookmarkTitle = ""
                            bookmarkUrl = ""
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ─── Bookmark Item Card ───────────────────────────────────────────────────────
@Composable
fun BookmarkItemCard(
    bookmark: com.nexus.browser.viewmodel.Bookmark,
    onDelete: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7FAFC))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
            .clickable { onNavigate(bookmark.url) }
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Bookmark,
            "Bookmark",
            modifier = Modifier.size(24.dp),
            tint = Color(0xFF5B7FFF)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                bookmark.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF2D3748)
            )
            Text(
                bookmark.url,
                fontSize = 12.sp,
                color = Color(0xFF718096),
                maxLines = 1
            )
        }

        Icon(
            Icons.Default.Close,
            "Delete",
            modifier = Modifier
                .size(24.dp)
                .clickable { onDelete() },
            tint = Color(0xFF718096)
        )
    }
}

// ─── History Screen ───────────────────────────────────────────────────────────
@Composable
fun HistoryScreen(viewModel: BrowserViewModel, onNavigate: (String) -> Unit = {}) {
    val searchHistory by viewModel.searchHistory.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7FAFC))
                .padding(Spacing.lg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "History",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2D3748)
                    )
                }

                Button(
                    onClick = { viewModel.clearSearchHistory() },
                    modifier = Modifier.height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFE0E0)
                    )
                ) {
                    Text("Clear", fontSize = 12.sp, color = Color(0xFFE53E3E))
                }
            }
        }

        if (searchHistory.isEmpty()) {
            EmptyStateScreen(
                icon = Icons.Default.History,
                title = "No History",
                subtitle = "Your search history appears here"
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(searchHistory) { item ->
                    HistoryItemCard(
                        item = item,
                        onDelete = { viewModel.removeFromSearchHistory(item) },
                        onNavigate = { onNavigate(item) }
                    )
                }
            }
        }
    }
}

// ─── History Item Card ────────────────────────────────────────────────────────
@Composable
fun HistoryItemCard(item: String, onDelete: () -> Unit, onNavigate: (String) -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7FAFC))
            .clickable { onNavigate(item) }
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.History,
                "History",
                modifier = Modifier.size(20.dp),
                tint = Color(0xFF718096)
            )
            Text(
                item,
                fontSize = 14.sp,
                color = Color(0xFF2D3748),
                maxLines = 1
            )
        }

        Icon(
            Icons.Default.Close,
            "Delete",
            modifier = Modifier
                .size(20.dp)
                .clickable { onDelete() },
            tint = Color(0xFF718096)
        )
    }
}

// ─── Empty State Screen ───────────────────────────────────────────────────────
@Composable
fun EmptyStateScreen(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF0F4FF)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                title,
                modifier = Modifier.size(40.dp),
                tint = Color(0xFF5B7FFF)
            )
        }

        Spacer(modifier = Modifier.height(Spacing.lg))

        Text(
            title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2D3748)
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        Text(
            subtitle,
            fontSize = 14.sp,
            color = Color(0xFF718096)
        )
    }
}

// ─── Statistics Screen ────────────────────────────────────────────────────────
@Composable
fun StatisticsScreen(viewModel: BrowserViewModel) {
    val tabs by viewModel.tabs.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val downloads by viewModel.downloads.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        item {
            Text(
                "Statistics",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2D3748)
            )
        }

        item {
            StatCard(
                icon = Icons.Default.Tab,
                title = "Open Tabs",
                value = tabs.size.toString(),
                color = Color(0xFF5B7FFF)
            )
        }

        item {
            StatCard(
                icon = Icons.Default.Bookmark,
                title = "Bookmarks",
                value = bookmarks.size.toString(),
                color = Color(0xFF48BB78)
            )
        }

        item {
            StatCard(
                icon = Icons.Default.Download,
                title = "Downloaded",
                value = downloads.size.toString(),
                color = Color(0xFFF6AD55)
            )
        }

        item {
            StatCard(
                icon = Icons.Default.Speed,
                title = "Loading Speed",
                value = "Fast",
                color = Color(0xFF38B6FF)
            )
        }
    }
}

@Composable
fun StatCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                title,
                modifier = Modifier.size(28.dp),
                tint = color
            )
        }

        Column {
            Text(
                title,
                fontSize = 14.sp,
                color = Color(0xFF718096)
            )
            Text(
                value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

// ─── About Settings Screen ────────────────────────────────────────────────────
@Composable
fun AboutSettingsScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7FAFC))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ArrowBack,
                "Back",
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onBack() },
                tint = Color(0xFF2D3748)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text("About", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF5B7FFF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "N",
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Nexus Browser", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
                    Text("Version 1.0.1", fontSize = 14.sp, color = Color(0xFF718096))
                    Text("Fast, Secure, Smart", fontSize = 12.sp, color = Color(0xFF718096))
                }
            }

            item {
                Divider(color = Color(0xFFE2E8F0))
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("About This Browser", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2D3748))
                    Text(
                        "Nexus Browser is a fast, secure, and privacy-focused web browser for Android. " +
                        "Built with Jetpack Compose and modern Android technologies for optimal performance.",
                        fontSize = 14.sp,
                        color = Color(0xFF718096)
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF7FAFC))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Features", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2D3748))
                    Spacer(modifier = Modifier.height(4.dp))
                    listOf(
                        "Fast browsing with optimized WebView engine",
                        "Built-in Ad Block with 100+ tracker domains",
                        "Privacy-focused Incognito mode",
                        "Video detection and download support",
                        "Bookmark management",
                        "Multi-tab browsing",
                        "Dark mode & Night mode"
                    ).forEach { feature ->
                        Text("• $feature", fontSize = 13.sp, color = Color(0xFF4A5568))
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF0F4FF))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Build Info", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF5B7FFF))
                    Text("Version: 1.0.1 (Build 2)", fontSize = 12.sp, color = Color(0xFF718096))
                    Text("Min SDK: Android 7.0 (API 24)", fontSize = 12.sp, color = Color(0xFF718096))
                    Text("Target SDK: Android 14 (API 34)", fontSize = 12.sp, color = Color(0xFF718096))
                }
            }
        }
    }
}

// ─── Helper Composables ───────────────────────────────────────────────────────

@Composable
fun SettingToggle(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7FAFC))
            .clickable { onToggle(!enabled) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2D3748))
            Text(subtitle, fontSize = 12.sp, color = Color(0xFF718096))
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF5B7FFF),
                checkedTrackColor = Color(0xFF5B7FFF).copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
fun SettingItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7FAFC))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2D3748))
            Text(subtitle, fontSize = 12.sp, color = Color(0xFF718096))
        }
        Icon(Icons.Default.ChevronRight, "Navigate", tint = Color(0xFF718096))
    }
}

// ─── Incognito Session Screen ─────────────────────────────────────────────────
@Composable
fun IncognitoSessionScreen(viewModel: BrowserViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7FAFC))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ArrowBack,
                "Back",
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onBack() },
                tint = Color(0xFF2D3748)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text("Incognito Mode", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF0F4FF))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            "Incognito",
                            tint = Color(0xFF5B7FFF),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("About Incognito", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2D3748))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Incognito mode doesn't save your browsing history, cookies, or site data. " +
                        "Your activity is private during this session only.",
                        fontSize = 13.sp,
                        color = Color(0xFF718096)
                    )
                }
            }

            item {
                // FIX: Toggle was empty before — now properly calls viewModel
                SettingToggle(
                    title = "Enable Incognito",
                    subtitle = if (settings.incognitoEnabled) "Private browsing is ON" else "Private browsing is OFF",
                    enabled = settings.incognitoEnabled,
                    onToggle = { enabled ->
                        viewModel.updateSetting { copy(incognitoEnabled = enabled) }
                    }
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF7FAFC))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("This session's data will NOT be saved:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2D3748))
                    Spacer(modifier = Modifier.height(4.dp))
                    listOf(
                        "Browsing history",
                        "Cookies and site data",
                        "Form data and passwords",
                        "Cache and downloads"
                    ).forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, "item", tint = Color(0xFF48BB78), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(item, fontSize = 12.sp, color = Color(0xFF4A5568))
                        }
                    }
                }
            }
        }
    }
}

// ─── Ad Block Screen ──────────────────────────────────────────────────────────
@Composable
fun AdBlockScreen(viewModel: BrowserViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7FAFC))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ArrowBack,
                "Back",
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onBack() },
                tint = Color(0xFF2D3748)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text("Ad Block Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingToggle(
                    title = "Enable Ad Block",
                    subtitle = if (settings.adBlockEnabled) "Blocking ads and trackers" else "Ad blocking is disabled",
                    enabled = settings.adBlockEnabled,
                    onToggle = { viewModel.toggleAdBlock() }
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF7FAFC))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Protection Coverage", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2D3748))
                    Spacer(modifier = Modifier.height(4.dp))
                    listOf(
                        "Banner ads and display ads",
                        "Pop-ups and pop-unders",
                        "Tracking pixels and beacons",
                        "Auto-playing video ads",
                        "Social media trackers",
                        "Analytics scripts"
                    ).forEach { feature ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (settings.adBlockEnabled) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                "Status",
                                modifier = Modifier.size(16.dp),
                                tint = if (settings.adBlockEnabled) Color(0xFF48BB78) else Color(0xFF718096)
                            )
                            Text(
                                feature,
                                fontSize = 12.sp,
                                color = Color(0xFF4A5568)
                            )
                        }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF0F4FF))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Filter Lists", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF5B7FFF))
                    Text("Standard Filters — Active", fontSize = 12.sp, color = Color(0xFF718096))
                    Text("Privacy Filters — Active", fontSize = 12.sp, color = Color(0xFF718096))
                    Text("100+ blocked domains built-in", fontSize = 12.sp, color = Color(0xFF718096))
                }
            }
        }
    }
}

// ============ TOOLBAR CUSTOMIZATION SCREEN ============
@Composable
fun ToolbarCustomizationScreen(onBack: () -> Unit) {
    var showUrlBar by remember { mutableStateOf(true) }
    var showReloadButton by remember { mutableStateOf(true) }
    var showMenuButton by remember { mutableStateOf(true) }
    var toolbarPosition by remember { mutableStateOf("Top") }
    var urlDisplayMode by remember { mutableStateOf("Title + URL") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.ArrowBack, "Back",
                    modifier = Modifier.size(20.dp),
                    tint = Color(0xFF202124)
                )
            }
            Text(
                "Toolbar Customization",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF202124)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Toolbar Position ───────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Toolbar Position",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF2D3748)
                    )
                    listOf("Top", "Bottom").forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { toolbarPosition = option }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(option, fontSize = 13.sp, color = Color(0xFF4A5568))
                            RadioButton(
                                selected = toolbarPosition == option,
                                onClick = { toolbarPosition = option },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF5B7FFF))
                            )
                        }
                    }
                }
            }

            // ── URL Display Mode ───────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "URL Bar Display",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF2D3748)
                    )
                    listOf("Title + URL", "URL Only", "Title Only").forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { urlDisplayMode = option }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(option, fontSize = 13.sp, color = Color(0xFF4A5568))
                            RadioButton(
                                selected = urlDisplayMode == option,
                                onClick = { urlDisplayMode = option },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF5B7FFF))
                            )
                        }
                    }
                }
            }

            // ── Button Visibility Toggles ──────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Visible Buttons",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF2D3748),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    listOf(
                        Triple("URL / Search Bar", showUrlBar) { showUrlBar = !showUrlBar },
                        Triple("Reload Button", showReloadButton) { showReloadButton = !showReloadButton },
                        Triple("Menu (⋮) Button", showMenuButton) { showMenuButton = !showMenuButton }
                    ).forEach { (label, state, onToggle) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, fontSize = 13.sp, color = Color(0xFF4A5568))
                            Switch(
                                checked = state,
                                onCheckedChange = { onToggle() },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF5B7FFF))
                            )
                        }
                    }
                }
            }

            // ── Preview ────────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF0F4FF))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Preview",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF5B7FFF)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFF8F9FA))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowBack, "Back",
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFF5F6368)
                        )
                        if (showUrlBar) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White)
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    when (urlDisplayMode) {
                                        "Title Only" -> "Page Title"
                                        "URL Only" -> "https://example.com"
                                        else -> "Page Title — example.com"
                                    },
                                    fontSize = 11.sp,
                                    color = Color(0xFF202124)
                                )
                            }
                        }
                        if (showReloadButton) {
                            Icon(
                                Icons.Default.Refresh, "Reload",
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFF5F6368)
                            )
                        }
                        if (showMenuButton) {
                            Icon(
                                Icons.Default.MoreVert, "Menu",
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFF5F6368)
                            )
                        }
                    }
                    Text(
                        "Position: $toolbarPosition toolbar",
                        fontSize = 11.sp,
                        color = Color(0xFF718096)
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}
