package com.nexus.browser

import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
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
fun NexusBrowserUI() {
    val selectedTab = remember { mutableStateOf(0) }
    val showBottomSheet = remember { mutableStateOf(false) }
    
    // Wrap in our theme
    NexusTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NexusColors.backgroundGradient)
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Status Bar
            TopStatusBar()

            // Main Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTab.value) {
                    0 -> HomeScreen()
                    1 -> FilesScreen()
                    2 -> TabsScreen(tabCount.value)
                    3 -> ProfileScreen()
                }
            }

            // Bottom Navigation
            BottomNavigationBar(
                selectedTab = selectedTab.value,
                onTabSelected = { selectedTab.value = it },
                tabCount = tabCount.value
            )
        }

        // Floating Action Buttons
        FloatingActionButtonBar(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        )
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
fun FilesScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Files",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2D3748),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(5) { index ->
                FileItem("Document_${index + 1}.pdf", "${2.5 + index} MB")
            }
        }
    }
}

@Composable
fun FileItem(fileName: String, fileSize: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7FAFC))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
            .clickable { }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Description,
                "File",
                modifier = Modifier.size(24.dp),
                tint = Color(0xFF5B7FFF)
            )

            Column {
                Text(
                    fileName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF2D3748)
                )

                Text(
                    fileSize,
                    fontSize = 12.sp,
                    color = Color(0xFF718096)
                )
            }
        }

        Icon(
            Icons.Default.MoreVert,
            "More",
            modifier = Modifier
                .size(20.dp)
                .clickable { },
            tint = Color(0xFF718096)
        )
    }
}

@Composable
fun ProfileScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Me",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2D3748),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Profile Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF5B7FFF),
                            Color(0xFF7B9FFF)
                        )
                    )
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.AccountCircle,
                    "Profile",
                    modifier = Modifier.size(64.dp),
                    tint = Color.White
                )

                Text(
                    "Nexus Browser User",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    "user@nexusbrowser.com",
                    fontSize = 12.sp,
                    color = Color(0xFFE0E0E0)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Account Settings",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF2D3748)
        )

        SettingItem("Privacy", "High", Icons.Default.Lock)
        SettingItem("Notifications", "Enabled", Icons.Default.Notifications)
        SettingItem("Backup", "Auto", Icons.Default.CloudUpload)
        SettingItem("About", "Info", Icons.Default.Info)
    }
}

@Composable
fun TabsScreen(tabCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Tabs ($tabCount)",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2D3748)
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tabCount) { index ->
                TabItem(index + 1)
            }
        }
    }
}

@Composable
fun TabItem(tabNumber: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7FAFC))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
            .clickable { }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Tab $tabNumber",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2D3748)
            )

            Text(
                "https://example.com/page$tabNumber",
                fontSize = 12.sp,
                color = Color(0xFF718096),
                maxLines = 1
            )
        }

        Icon(
            Icons.Default.Close,
            "Close Tab",
            modifier = Modifier
                .size(20.dp)
                .clickable { },
            tint = Color(0xFF718096)
        )
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
