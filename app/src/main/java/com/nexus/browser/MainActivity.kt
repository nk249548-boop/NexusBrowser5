package com.nexus.browser

import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexus.browser.theme.NexusColors
import com.nexus.browser.theme.NexusTheme
import com.nexus.browser.viewmodel.BrowserViewModel
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NexusBrowserApp()
        }
    }
}

@Composable
fun NexusBrowserApp() {
    val isDarkMode = remember { mutableStateOf(false) }
    val backgroundColor = if (isDarkMode.value) Color(0xFF1a1a1a) else Color(0xFFF5F5F5)
    
    MaterialTheme(
        colorScheme = if (isDarkMode.value) darkColorScheme() else lightColorScheme()
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = backgroundColor
        ) {
            NexusBrowserUI(isDarkMode = isDarkMode.value)
        }
    }
}

@Composable
fun NexusBrowserUI(
    viewModel: BrowserViewModel = viewModel(),
    isDarkMode: Boolean = false
) {
    val tabs by viewModel.tabs.collectAsState()
    val activeTabId by viewModel.activeTabId.collectAsState()
    val selectedTab by viewModel.selectedBottomTab.collectAsState()
    
    NexusTheme(darkTheme = isDarkMode) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NexusColors.backgroundGradient)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Main Content Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                ) {
                    when (selectedTab) {
                        0 -> HomeScreen(viewModel)
                        1 -> FilesScreen(viewModel)
                        2 -> TabsScreen(viewModel)
                        3 -> ProfileScreen(viewModel)
                    }
                }

                // Bottom Navigation
                GlassBottomNav {
                    BottomNavItem(
                        icon = Icons.Default.Home,
                        label = "Home",
                        isSelected = selectedTab == 0,
                        onClick = { viewModel.selectBottomTab(0) }
                    )
                    
                    BottomNavItem(
                        icon = Icons.Default.Folder,
                        label = "Files",
                        isSelected = selectedTab == 1,
                        onClick = { viewModel.selectBottomTab(1) }
                    )
                    
                    BottomNavItem(
                        icon = Icons.Default.Tab,
                        label = "Tabs",
                        isSelected = selectedTab == 2,
                        onClick = { viewModel.selectBottomTab(2) },
                        badgeCount = tabs.size
                    )
                    
                    BottomNavItem(
                        icon = Icons.Default.Person,
                        label = "Me",
                        isSelected = selectedTab == 3,
                        onClick = { viewModel.selectBottomTab(3) }
                    )
                }
            }

            // Floating Search Bar
            NexusSearchBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                query = "",
                onQueryChange = { /* TODO */ },
                onSearch = { /* TODO */ }
            )
        }
    }
}

@Composable
fun TopStatusBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF8F9FA))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "10:40",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.Black
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.SignalCellularAlt,
                "Signal",
                modifier = Modifier.size(14.dp),
                tint = Color.Black
            )
            Icon(
                Icons.Default.Favorite,
                "Battery",
                modifier = Modifier.size(14.dp),
                tint = Color.Black
            )
        }
    }
}

@Composable
fun HomeScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            NexusBrowserLogo()
        }

        item {
            SearchBar()
        }

        item {
            QuickLinksSection()
        }

        item {
            FeaturesSection()
        }
    }
}

@Composable
fun NexusBrowserLogo() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Nexus Logo Icon
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF5B7FFF),
                            Color(0xFF7B9FFF)
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "N",
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "NexusBrowser",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2D3748)
        )

        Text(
            "Fast, Secure, Smart",
            fontSize = 14.sp,
            color = Color(0xFF718096)
        )
    }
}

@Composable
fun SearchBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(
                color = Color.White,
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = 1.dp,
                color = Color(0xFFE2E8F0),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.Search,
            "Search",
            modifier = Modifier.size(20.dp),
            tint = Color(0xFF718096)
        )

        TextField(
            value = "",
            onValueChange = {},
            placeholder = { Text("Search or type web address") },
            modifier = Modifier
                .weight(1f)
                .background(Color.Transparent),
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )

        Icon(
            Icons.Default.Mic,
            "Voice Search",
            modifier = Modifier
                .size(20.dp)
                .clickable { },
            tint = Color(0xFF718096)
        )

        Icon(
            Icons.Default.Lock,
            "Secure",
            modifier = Modifier.size(20.dp),
            tint = Color(0xFF718096)
        )
    }
}

@Composable
fun QuickLinksSection() {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Quick Links",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF2D3748)
        )

        val quickLinks = listOf(
            Triple("Google", "G", Color(0xFFEA4335)),
            Triple("YouTube", "▶", Color(0xFFFF0000)),
            Triple("Facebook", "f", Color(0xFF1877F2)),
            Triple("WhatsApp", "W", Color(0xFF25D366)),
            Triple("More", "⋯", Color(0xFF95A5A6))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            quickLinks.forEach { (name, icon, bgColor) ->
                QuickLinkCard(name, icon, bgColor)
            }
        }
    }
}

@Composable
fun QuickLinkCard(name: String, icon: String, bgColor: Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { }
            .padding(12.dp)
            .width(70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color.White, shape = RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                icon,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = bgColor
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            name,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun FeaturesSection() {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Features",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF2D3748)
        )

        val features = listOf(
            Pair("Bookmarks", Icons.Default.Bookmark),
            Pair("History", Icons.Default.History),
            Pair("Downloads", Icons.Default.Download),
            Pair("Settings", Icons.Default.Settings),
            Pair("Refresh", Icons.Default.Refresh),
            Pair("Night Mode", Icons.Default.DarkMode),
            Pair("Incognito", Icons.Default.PrivateConnectivity),
            Pair("Add tab", Icons.Default.Add),
            Pair("Desktop site", Icons.Default.DesktopMac),
            Pair("Find in page", Icons.Default.Search),
            Pair("Translate", Icons.Default.Translate),
            Pair("Save page", Icons.Default.Save),
            Pair("Ad Block", Icons.Default.Block),
            Pair("Data Saver", Icons.Default.DataUsage),
            Pair("Screenshot", Icons.Default.Screenshot),
            Pair("Exit", Icons.Default.PowerSettingsNew)
        )

        // Create a 4-column grid layout
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            for (i in features.indices step 4) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (j in 0..3) {
                        if (i + j < features.size) {
                            FeatureGridItem(features[i + j].first, features[i + j].second)
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FeatureGridItem(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
            .clickable { }
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = name,
            modifier = Modifier.size(24.dp),
            tint = Color(0xFF5B7FFF)
        )
        Text(
            name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF2D3748),
            maxLines = 2,
            textAlign = TextAlign.Center,
            lineHeight = 11.sp
        )
    }
}

@Composable
fun DownloadsScreen(viewModel: BrowserViewModel) {
    val downloads by viewModel.downloads.collectAsState()
    val currentFilter = remember { mutableStateOf("all") }
    
    NexusTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NexusColors.backgroundGradient)
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { /* Handle back */ },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        "Back",
                        tint = NexusColors.primary
                    )
                }
                
                Text(
                    "Downloads",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = NexusColors.textPrimary
                )
                
                Row {
                    IconButton(
                        onClick = { /* Search */ },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            "Search",
                            tint = NexusColors.primary
                        )
                    }
                    IconButton(
                        onClick = { /* Menu */ },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            "Menu",
                            tint = NexusColors.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Filter chips
            val filters = listOf("All", "Images", "Videos", "Docs", "Audio")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filters.forEach { filter ->
                    val isSelected = currentFilter.value.equals(filter, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { currentFilter.value = filter.lowercase() },
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            filter,
                            color = if (isSelected) NexusColors.primary else NexusColors.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Downloads list
            if (downloads.isEmpty()) {
                EmptyStateScreen(
                    icon = Icons.Default.Download,
                    title = "No Downloads",
                    subtitle = "Files you download will appear here"
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(downloads) { download ->
                        DownloadItem(
                            download = download,
                            onItemClick = { /* Open file */ },
                            onMenuClick = { /* Show menu */ }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadItem(
    download: Download,
    onItemClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onItemClick() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // File type icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = NexusColors.primary.copy(alpha = 0.1f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Download,
                    "Download",
                    tint = NexusColors.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            // File info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    download.fileName,
                    color = NexusColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    viewModel.formatFileSize(download.fileSize),
                    color = NexusColors.textSecondary,
                    fontSize = 12.sp
                )
                
                if (download.progress < 1f) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = download.progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = NexusColors.primary,
                        trackColor = NexusColors.textTertiary.copy(alpha = 0.2f)
                    )
                }
            }

            // Menu button
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    "Menu",
                    tint = NexusColors.textSecondary
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: BrowserViewModel) {
    val settings by viewModel.settings.collectAsState()
    
    NexusTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NexusColors.backgroundGradient)
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { /* Handle back */ },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        "Back",
                        tint = NexusColors.primary
                    )
                }
                
                Text(
                    "Settings",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = NexusColors.textPrimary
                )
                
                Spacer(modifier = Modifier.size(40.dp)) // For balance
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Settings sections
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        "General",
                        color = NexusColors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                }
                
                item {
                    GlassCard {
                        Column {
                            SettingItem(
                                icon = Icons.Default.Search,
                                title = "Search Engine",
                                value = settings.searchEngine,
                                onClick = { /* Show engine selection */ }
                            )
                            SettingItem(
                                icon = Icons.Default.Home,
                                title = "Homepage",
                                value = settings.homepage,
                                onClick = { /* Edit homepage */ }
                            )
                            SettingItem(
                                icon = Icons.Default.DarkMode,
                                title = "Dark Mode",
                                trailing = {
                                    Switch(
                                        checked = settings.isDarkMode,
                                        onCheckedChange = { viewModel.toggleDarkMode() },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = NexusColors.primary,
                                            checkedTrackColor = NexusColors.primary.copy(alpha = 0.3f)
                                        )
                                    )
                                }
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Privacy & Security",
                        color = NexusColors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                }
                
                item {
                    GlassCard {
                        Column {
                            SettingItem(
                                icon = Icons.Default.Block,
                                title = "Ad Block",
                                trailing = {
                                    Switch(
                                        checked = settings.adBlockEnabled,
                                        onCheckedChange = { viewModel.toggleAdBlock() },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = NexusColors.primary,
                                            checkedTrackColor = NexusColors.primary.copy(alpha = 0.3f)
                                        )
                                    )
                                }
                            )
                            SettingItem(
                                icon = Icons.Default.PrivateConnectivity,
                                title = "Incognito Mode",
                                trailing = {
                                    Switch(
                                        checked = settings.incognitoEnabled,
                                        onCheckedChange = { viewModel.toggleNightMode() },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = NexusColors.primary,
                                            checkedTrackColor = NexusColors.primary.copy(alpha = 0.3f)
                                        )
                                    )
                                }
                            )
                            SettingItem(
                                icon = Icons.Default.DataUsage,
                                title = "Data Storage",
                                trailing = {
                                    Switch(
                                        checked = settings.dataStorageEnabled,
                                        onCheckedChange = { /* Toggle */ },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = NexusColors.primary,
                                            checkedTrackColor = NexusColors.primary.copy(alpha = 0.3f)
                                        )
                                    )
                                }
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Advanced",
                        color = NexusColors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                }
                
                item {
                    GlassCard {
                        Column {
                            SettingItem(
                                icon = Icons.Default.Translate,
                                title = "Default Language",
                                value = "English",
                                onClick = { /* Show languages */ }
                            )
                            SettingItem(
                                icon = Icons.Default.DesktopMac,
                                title = "Desktop Mode",
                                trailing = {
                                    Switch(
                                        checked = false,
                                        onCheckedChange = { /* Toggle */ },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = NexusColors.primary,
                                            checkedTrackColor = NexusColors.primary.copy(alpha = 0.3f)
                                        )
                                    )
                                }
                            )
                            SettingItem(
                                icon = Icons.Default.Settings,
                                title = "Advanced Settings",
                                onClick = { /* Show advanced */ }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingItem(
    icon: ImageVector,
    title: String,
    value: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                icon,
                title,
                tint = NexusColors.primary,
                modifier = Modifier.size(24.dp)
            )
            
            Column {
                Text(
                    title,
                    color = NexusColors.textPrimary,
                    fontWeight = FontWeight.Medium
                )
                if (value != null) {
                    Text(
                        value,
                        color = NexusColors.textSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
        
        trailing?.invoke() ?: Icon(
            Icons.Default.ChevronRight,
            "More",
            tint = NexusColors.textTertiary,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun TabsScreen(viewModel: BrowserViewModel) {
    val tabs by viewModel.tabs.collectAsState()
    val activeTabId by viewModel.activeTabId.collectAsState()
    
    NexusTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NexusColors.backgroundGradient)
                .padding(16.dp)
        ) {
            // Header with Add and Menu buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Tabs (${tabs.size})",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = NexusColors.textPrimary
                )
                
                Row {
                    IconButton(
                        onClick = { viewModel.addNewTab() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            "Add Tab",
                            tint = NexusColors.primary
                        )
                    }
                    IconButton(
                        onClick = { /* TODO: Show tabs menu */ },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            "Menu",
                            tint = NexusColors.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tab cards in a staggered grid
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(tabs) { tab ->
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { viewModel.switchTab(tab.id) }
                        ) {
                            // Tab preview placeholder
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.LightGray.copy(alpha = 0.2f))
                            )
                            
                            // Tab info overlay
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .background(NexusColors.glassSurface(0.8f))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Favicon placeholder
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(Color.White, CircleShape)
                                            .padding(4.dp)
                                    ) {
                                        Text(
                                            tab.favicon ?: "🌐",
                                            modifier = Modifier.align(Alignment.Center)
                                        )
                                    }
                                    
                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            tab.title,
                                            color = NexusColors.textPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                        Text(
                                            tab.url,
                                            color = NexusColors.textSecondary,
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    }
                                    
                                    IconButton(
                                        onClick = { viewModel.closeTab(tab.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            "Close",
                                            tint = NexusColors.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingItem(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7FAFC))
            .clickable { }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(24.dp),
                tint = Color(0xFF5B7FFF)
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF2D3748)
                )

                Text(
                    value,
                    fontSize = 12.sp,
                    color = Color(0xFF718096)
                )
            }
        }

        Icon(
            Icons.Default.ChevronRight,
            "More",
            modifier = Modifier.size(24.dp),
            tint = Color(0xFFCBD5E0)
        )
    }
}

@Composable
fun BottomNavigationBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    tabCount: Int
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        color = Color.White,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavItem(
                icon = Icons.Default.Home,
                label = "Home",
                isSelected = selectedTab == 0,
                onClick = { onTabSelected(0) }
            )

            BottomNavItem(
                icon = Icons.Default.Folder,
                label = "Files",
                isSelected = selectedTab == 1,
                onClick = { onTabSelected(1) }
            )

            BottomNavItem(
                icon = Icons.Default.Tab,
                label = "Tabs",
                isSelected = selectedTab == 2,
                onClick = { onTabSelected(2) },
                badgeCount = tabCount
            )

            BottomNavItem(
                icon = Icons.Default.Person,
                label = "Me",
                isSelected = selectedTab == 3,
                onClick = { onTabSelected(3) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    badgeCount: Int? = null
) {
    Column(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(bounded = false),
                onClick = onClick
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                icon,
                label,
                modifier = Modifier.size(24.dp),
                tint = if (isSelected) Color(0xFF5B7FFF) else Color(0xFFCBD5E0)
            )

            if (badgeCount != null && badgeCount > 0) {
                Badge(
                    modifier = Modifier.offset(8.dp, (-8).dp),
                    containerColor = Color(0xFFFF4757)
                ) {
                    Text(
                        badgeCount.toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Text(
            label,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) Color(0xFF5B7FFF) else Color(0xFFCBD5E0)
        )
    }
}

@Composable
fun FloatingActionButtonBar(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FloatingActionButton(
            onClick = { },
            modifier = Modifier.size(48.dp),
            containerColor = Color(0xFF5B7FFF),
            contentColor = Color.White,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Add, "Add", modifier = Modifier.size(24.dp))
        }

        FloatingActionButton(
            onClick = { },
            modifier = Modifier.size(48.dp),
            containerColor = Color(0xFFE2E8F0),
            contentColor = Color(0xFF718096),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.MoreVert, "More", modifier = Modifier.size(24.dp))
        }
    }
}
