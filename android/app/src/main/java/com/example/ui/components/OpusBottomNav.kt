package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.OpusBorder
import com.example.ui.theme.OpusDarkCanvas
import com.example.ui.theme.OpusDarkSurface
import com.example.ui.theme.OpusElectricCyan
import com.example.ui.theme.OpusPrimaryViolet
import com.example.ui.theme.OpusTextPrimary
import com.example.ui.theme.OpusTextSecondary
import com.example.ui.theme.OpusVioletGlow

/** Primary workflow destinations. Secondary tools remain reachable only from existing internal routes. */
enum class OpusNavTab(val label: String, val subtitle: String, val testTag: String) {
    HOME("الرئيسية", "ابدأ من فيديو", "nav_tab_home"),
    STUDIO("النتائج", "راجع المقاطع", "nav_tab_studio"),
    PROJECTS("المكتبة", "مشاريعك المحفوظة", "nav_tab_projects"),
    GATEWAY("النشر", "بوابة المنصات", "nav_tab_gateway"),
    TOOLS("المزيد", "الأدوات والإعدادات", "nav_tab_tools"),
    DASHBOARD("الاستخدام", "لوحة البيانات", "nav_tab_dashboard"),
    BENCHMARK("المقارنة", "معيار المنافسين", "nav_tab_benchmark"),
    SETTINGS("الإعدادات", "إعدادات المحرك", "nav_tab_settings")
}

@Composable
fun OpusBottomNav(
    currentTab: OpusNavTab,
    onTabSelected: (OpusNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .background(OpusDarkCanvas)
            .border(width = 1.dp, color = OpusBorder.copy(alpha = 0.75f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        containerColor = OpusDarkSurface,
        contentColor = OpusTextPrimary,
        tonalElevation = 0.dp
    ) {
        WorkflowItem(OpusNavTab.HOME, currentTab, Icons.Default.AutoAwesome, OpusElectricCyan, onTabSelected)
        WorkflowItem(OpusNavTab.STUDIO, currentTab, Icons.Default.VideoLibrary, OpusVioletGlow, onTabSelected)
        WorkflowItem(OpusNavTab.SETTINGS, currentTab, Icons.Default.Settings, OpusTextSecondary, onTabSelected)
    }
}

@Composable
private fun WorkflowItem(
    tab: OpusNavTab,
    currentTab: OpusNavTab,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    onTabSelected: (OpusNavTab) -> Unit
) {
    val selected = currentTab == tab
    NavigationBarItem(
        selected = selected,
        onClick = { onTabSelected(tab) },
        icon = { Icon(imageVector = icon, contentDescription = tab.label) },
        label = {
            Text(
                text = tab.label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = accent,
            selectedTextColor = accent,
            indicatorColor = OpusPrimaryViolet.copy(alpha = 0.28f),
            unselectedIconColor = OpusTextSecondary,
            unselectedTextColor = OpusTextSecondary
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .testTag(tab.testTag)
    )
}
