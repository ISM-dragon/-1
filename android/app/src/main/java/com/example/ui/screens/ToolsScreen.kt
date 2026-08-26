package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.OpusBorder
import com.example.ui.theme.OpusDarkCanvas
import com.example.ui.theme.OpusDarkSurface
import com.example.ui.theme.OpusDarkSurfaceHighlight
import com.example.ui.theme.OpusElectricCyan
import com.example.ui.theme.OpusGold
import com.example.ui.theme.OpusPrimaryViolet
import com.example.ui.theme.OpusTextPrimary
import com.example.ui.theme.OpusTextSecondary
import com.example.ui.theme.OpusVioletGlow

@Composable
fun ToolsScreen(
    onOpenDashboard: () -> Unit,
    onOpenBenchmark: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCaptionEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(OpusDarkCanvas)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Column {
                Text(
                    text = "مركز التحكم",
                    color = OpusTextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = "كل ما تحتاجه لضبط محرك القص ومراجعة أدائه.",
                    color = OpusTextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        item {
            ToolCard(
                title = "مختبر الكابشن المتقدم",
                description = "حرّر كل كلمة، راقب الكاريوكي لحظياً، واضبط بداية ونهاية المقطع.",
                icon = Icons.Default.AutoAwesome,
                accent = OpusVioletGlow,
                onClick = onOpenCaptionEditor
            )
        }
        item {
            ToolCard(
                title = "لوحة الأداء",
                description = "تابع الدقائق المعالجة، المقاطع المنتجة، وسجل استخدام الذكاء الاصطناعي.",
                icon = Icons.Default.Analytics,
                accent = OpusElectricCyan,
                onClick = onOpenDashboard
            )
        }
        item {
            ToolCard(
                title = "مختبر المقارنة",
                description = "قارن أفضل المقاطع واكتشف لماذا يملك أحدها فرصة انتشار أعلى.",
                icon = Icons.Default.CompareArrows,
                accent = OpusGold,
                onClick = onOpenBenchmark
            )
        }
        item {
            ToolCard(
                title = "مركز الذكاء الاصطناعي",
                description = "أدر المزودين والمفاتيح ونظام التبديل التلقائي بين النماذج.",
                icon = Icons.Default.Key,
                accent = OpusVioletGlow,
                onClick = onOpenSettings
            )
        }
        item {
            EnginePrinciplesCard()
        }
    }
}

@Composable
private fun ToolCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(OpusDarkSurface)
            .border(1.dp, OpusBorder, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(accent.copy(alpha = 0.14f))
                .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(16.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, color = OpusTextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                description,
                color = OpusTextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Text("فتح", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EnginePrinciplesCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(OpusPrimaryViolet.copy(alpha = 0.28f), OpusDarkSurfaceHighlight)
                )
            )
            .border(1.dp, OpusVioletGlow.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Speed, contentDescription = "المحرك", tint = OpusElectricCyan, modifier = Modifier.size(20.dp))
            Text("المحرك يعمل بوضوح", color = OpusTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            "كل مهمة تمر عبر تحقق المصدر، ثم تُوجّه إلى Pipeline محلي أو Gateway بعيد. التقدم الحقيقي محفوظ ويمكن استئناف المهام بعد الانقطاع.",
            color = OpusTextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
