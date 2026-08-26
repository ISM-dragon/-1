package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import com.example.ui.theme.OpusGold
import com.example.ui.theme.OpusPrimaryViolet
import com.example.ui.theme.OpusTextPrimary
import com.example.ui.theme.OpusTextSecondary
import com.example.ui.theme.OpusVioletGlow

enum class OpusNavTab(val label: String, val subtitle: String, val testTag: String) {
    HOME("الرئيسية", "محرك القص الذكي", "nav_tab_home"),
    STUDIO("الاستوديو", "تحرير المقاطع", "nav_tab_studio"),
    PROJECTS("المكتبة", "مشاريعك المحفوظة", "nav_tab_projects"),
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
    Row(
        modifier = modifier.fillMaxWidth().background(OpusDarkSurface).border(1.dp, OpusBorder).padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem(OpusNavTab.HOME, currentTab, Icons.Default.AutoAwesome, OpusElectricCyan, onTabSelected)
        NavItem(OpusNavTab.STUDIO, currentTab, Icons.Default.SlowMotionVideo, OpusVioletGlow, onTabSelected)
        NavItem(OpusNavTab.PROJECTS, currentTab, Icons.Default.FolderSpecial, OpusElectricCyan, onTabSelected)
        NavItem(OpusNavTab.GATEWAY, currentTab, Icons.Default.Cloud, OpusGold, onTabSelected)
        NavItem(OpusNavTab.TOOLS, currentTab, Icons.Default.GridView, OpusVioletGlow, onTabSelected)
    }
}

@Composable
private fun NavItem(
    tab: OpusNavTab,
    currentTab: OpusNavTab,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    onTabSelected: (OpusNavTab) -> Unit
) {
    val selected = currentTab == tab || (tab == OpusNavTab.TOOLS && currentTab in setOf(OpusNavTab.DASHBOARD, OpusNavTab.BENCHMARK, OpusNavTab.SETTINGS))
    Column(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).testTag(tab.testTag),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = { onTabSelected(tab) }) {
            Icon(icon, contentDescription = tab.label, tint = if (selected) accent else OpusTextSecondary)
        }
        Text(tab.label, color = if (selected) accent else OpusTextSecondary, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}
