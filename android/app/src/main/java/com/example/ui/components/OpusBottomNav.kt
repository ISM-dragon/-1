package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.SlowMotionVideo
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

/** Primary destinations for the focused mobile funnel. Secondary screens remain internal-only. */
enum class OpusNavTab(val label: String, val subtitle: String, val testTag: String) {
    HOME("الرئيسية", "ابدأ بفيديو", "nav_tab_home"),
    STUDIO("الاستوديو", "راجع وعدّل", "nav_tab_studio"),
    PROJECTS("المكتبة", "مشاريعك", "nav_tab_projects"),
    GATEWAY("النشر", "بوابة المنصات", "nav_tab_gateway"),
    TOOLS("المزيد", "الأدوات والإعدادات", "nav_tab_tools"),
    DASHBOARD("الاستخدام", "لوحة البيانات", "nav_tab_dashboard"),
    BENCHMARK("المقارنة", "معيار المنافسين", "nav_tab_benchmark"),
    SETTINGS("الإعدادات", "المفاتيح والمزودون", "nav_tab_settings")
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
            .border(1.dp, OpusBorder.copy(alpha = 0.75f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        containerColor = OpusDarkSurface,
        contentColor = OpusTextPrimary,
        tonalElevation = 0.dp
    ) {
        PrimaryItem(OpusNavTab.HOME, currentTab, Icons.Default.AutoAwesome, OpusElectricCyan, onTabSelected)
        PrimaryItem(OpusNavTab.STUDIO, currentTab, Icons.Default.SlowMotionVideo, OpusVioletGlow, onTabSelected)
        PrimaryItem(OpusNavTab.PROJECTS, currentTab, Icons.Default.FolderSpecial, OpusElectricCyan, onTabSelected)
    }
}

@Composable
private fun PrimaryItem(
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
