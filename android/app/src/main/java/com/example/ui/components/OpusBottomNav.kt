package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class OpusNavTab(val label: String, val subtitle: String, val testTag: String) {
    HOME("الرئيسية", "محرك القص الذكي", "nav_tab_home"),
    STUDIO("الاستوديو", "تحرير المقاطع", "nav_tab_studio"),
    PROJECTS("المكتبة", "مشاريعك المحفوظة", "nav_tab_projects"),
    GATEWAY("النشر", "بوابة المنصات", "nav_tab_gateway"),
    TOOLS("المزيد", "الأدوات والإعدادات", "nav_tab_tools"),
    DASHBOARD("الاستخدام", "لوحة البيانات", "nav_tab_dashboard"),
    BENCHMARK("المقارنة", "معيار المنافسين", "nav_tab_benchmark"),
    SETTINGS("الإعدادات", "الإعدادات", "nav_tab_settings")
}

/** Deprecated compatibility shell. The active app uses ContractApp's three-destination navigation. */
@Composable
fun OpusBottomNav(currentTab: OpusNavTab, onTabSelected: (OpusNavTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        listOf(OpusNavTab.HOME, OpusNavTab.STUDIO, OpusNavTab.PROJECTS, OpusNavTab.GATEWAY, OpusNavTab.TOOLS).forEach { tab ->
            Text(
                text = tab.label,
                color = if (currentTab == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onTabSelected(tab) }.padding(8.dp)
            )
        }
    }
}
