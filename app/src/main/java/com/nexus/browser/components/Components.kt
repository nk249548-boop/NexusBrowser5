package com.nexus.browser.components

import androidx.compose.foundation.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.nexus.browser.theme.NexusColors
import com.nexus.browser.theme.Corners

// Glassmorphic Card Component
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Corners.medium,
    alpha: Float = 0.7f,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(NexusColors.glassSurface(alpha))
            .border(
                width = 1.dp,
                color = NexusColors.glassBorder,
                shape = RoundedCornerShape(cornerRadius)
            ),
        content = content
    )
}

// Glassmorphic Bottom Navigation
@Composable
fun GlassBottomNav(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(topStart = Corners.bottomNav, topEnd = Corners.bottomNav))
            .background(NexusColors.glassSurface())
            .border(
                width = 1.dp,
                color = NexusColors.glassBorder,
                shape = RoundedCornerShape(topStart = Corners.bottomNav, topEnd = Corners.bottomNav)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

// Pill-shaped Search Bar
@Composable
fun NexusSearchBar(
    modifier: Modifier = Modifier,
    query: String = "",
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        cornerRadius = Corners.searchBar
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Google logo
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(NexusColors.primary, CircleShape)
                    .padding(4.dp)
            ) {
                Text(
                    "G",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            // Search field
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    color = NexusColors.textPrimary
                ),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                "Search or type web address",
                                color = NexusColors.textTertiary
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // Voice search icon
            IconButton(onClick = onSearch) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Voice search",
                    tint = NexusColors.primary
                )
            }
        }
    }
}

// Icon Button with ripple effect
@Composable
fun IconButtonCompact(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = Color(0xFF718096),
    size: Dp = 24.dp,
    onClick: () -> Unit
) {
    Icon(
        icon,
        contentDescription,
        modifier = Modifier
            .size(size)
            .clickable(enabled = true, onClickLabel = contentDescription) { onClick() },
        tint = tint
    )
}

// Custom Text Input with clear button
@Composable
fun SearchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    leadingIcon: ImageVector? = Icons.Default.Search,
    onClear: () -> Unit = {}
) {
    var text by remember { mutableStateOf(value) }

    Row(
        modifier = modifier
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
        if (leadingIcon != null) {
            Icon(
                leadingIcon,
                placeholder,
                modifier = Modifier.size(20.dp),
                tint = Color(0xFF718096)
            )
        }

        TextField(
            value = text,
            onValueChange = {
                text = it
                onValueChange(it)
            },
            placeholder = { Text(placeholder, fontSize = 14.sp) },
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

        if (text.isNotEmpty()) {
            IconButtonCompact(
                icon = Icons.Default.Close,
                contentDescription = "Clear",
                tint = Color(0xFF718096),
                size = 20.dp,
                onClick = {
                    text = ""
                    onValueChange("")
                    onClear()
                }
            )
        }
    }
}

// Feature Card with icon
@Composable
fun FeatureCard(
    icon: ImageVector,
    title: String,
    description: String = "",
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xFFF7FAFC),
    onClick: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon,
            title,
            modifier = Modifier.size(32.dp),
            tint = Color(0xFF5B7FFF)
        )

        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF2D3748)
        )

        if (description.isNotEmpty()) {
            Text(
                description,
                fontSize = 12.sp,
                color = Color(0xFF718096)
            )
        }
    }
}

// List Item with icon and trailing element
@Composable
fun ListItemCompact(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    trailingContent: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7FAFC))
            .clickable { onClick() }
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
                icon,
                title,
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

                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = Color(0xFF718096)
                    )
                }
            }
        }

        if (trailingContent != null) {
            trailingContent()
        } else {
            Icon(
                Icons.Default.ChevronRight,
                "More",
                modifier = Modifier.size(24.dp),
                tint = Color(0xFFCBD5E0)
            )
        }
    }
}

// Toggle Switch with label
@Composable
fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = ""
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7FAFC))
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF2D3748)
            )

            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF718096)
                )
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.85f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF5B7FFF),
                checkedTrackColor = Color(0xFF5B7FFF).copy(alpha = 0.3f)
            )
        )
    }
}

// Tab Bar with active indicator
@Composable
fun TabBar(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selectedTabIndex

            Column(
                modifier = Modifier
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 12.dp)
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    tab,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) Color(0xFF5B7FFF) else Color(0xFF718096)
                )

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height(3.dp)
                            .background(
                                color = Color(0xFF5B7FFF),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                }
            }
        }
    }
}

// Progress Bar Card
@Composable
fun ProgressCard(
    title: String,
    progress: Float,
    subtitle: String = "",
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xFFF7FAFC)
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF2D3748)
                )

                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = Color(0xFF718096)
                    )
                }
            }

            Text(
                "${(progress * 100).toInt()}%",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF5B7FFF)
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = Color(0xFF5B7FFF),
            trackColor = Color(0xFFE2E8F0)
        )
    }
}

// Dialog with overlay
@Composable
fun DialogCompact(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String = "OK",
    dismissText: String = "Cancel",
    onConfirm: () -> Unit = onDismiss,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(content = content) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        },
        containerColor = Color.White
    )
}

// Floating Action Button with label
@Composable
fun FABWithLabel(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color(0xFF5B7FFF)
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = containerColor,
        contentColor = Color.White,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(icon, label, modifier = Modifier.size(20.dp))
            Text(label, fontSize = 8.sp)
        }
    }
}
