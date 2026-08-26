package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.ui.theme.OpusViralEmerald

@Composable
fun OpusHeader(
    onApiKeyClick: () -> Unit,
    hasCustomApiKey: Boolean,
    remainingCreditsMinutes: Int = 0,
    activeProvidersCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val isReady = hasCustomApiKey || activeProvidersCount > 0 || remainingCreditsMinutes > 0
    Surface(
        color = OpusDarkSurface,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, OpusBorder.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("brand_header")) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Brush.linearGradient(listOf(OpusPrimaryViolet, OpusElectricCyan))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "ISM", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "ISM Studio",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = OpusTextPrimary,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.4.sp
                        )
                    )
                    Text("preview · edit · export", color = OpusTextSecondary, fontSize = 10.sp)
                }
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isReady) OpusViralEmerald.copy(alpha = 0.12f) else OpusDarkCanvas)
                    .border(1.dp, if (isReady) OpusViralEmerald.copy(alpha = 0.55f) else OpusGold.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                    .clickable(onClick = onApiKeyClick)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .testTag("api_key_header_button"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isReady) Icons.Default.CheckCircle else Icons.Default.Key,
                    contentDescription = "إعدادات الاتصال",
                    tint = if (isReady) OpusViralEmerald else OpusGold,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (isReady) "جاهز" else "إعداد",
                    color = if (isReady) OpusViralEmerald else OpusGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
