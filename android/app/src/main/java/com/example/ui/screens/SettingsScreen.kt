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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.repository.OpusRepository
import com.example.ui.components.ApiKeySettingsDialog
import com.example.ui.theme.OpusBorder
import com.example.ui.theme.OpusDarkCanvas
import com.example.ui.theme.OpusDarkSurface
import com.example.ui.theme.OpusDarkSurfaceVariant
import com.example.ui.theme.OpusElectricCyan
import com.example.ui.theme.OpusPrimaryViolet
import com.example.ui.theme.OpusTextPrimary
import com.example.ui.theme.OpusTextSecondary
import com.example.ui.theme.OpusViralEmerald

@Composable
fun SettingsScreen(
    repository: OpusRepository,
    modifier: Modifier = Modifier
) {
    var showConnectionDialog by remember { mutableStateOf(false) }
    val customApiKey by repository.customApiKey.collectAsState()
    val aiProviders by repository.aiProviders.collectAsState()
    val isConnected = customApiKey.isNotBlank() || aiProviders.any { it.isEnabled && it.apiKey.isNotBlank() }

    if (showConnectionDialog) {
        ApiKeySettingsDialog(
            repository = repository,
            onDismiss = { showConnectionDialog = false }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(OpusDarkCanvas)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "الإعدادات",
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = OpusTextPrimary,
                    fontWeight = FontWeight.Black
                )
            )
            Text(
                text = "إعدادات بسيطة قبل التوليد، لا حاجة لتعديلها كل مرة.",
                style = MaterialTheme.typography.bodyMedium.copy(color = OpusTextSecondary)
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("settings_connection_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = OpusDarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, OpusBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "اتصال المحرك",
                            tint = OpusElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "اتصال المحرك",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = OpusTextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = if (isConnected) "متصل وجاهز للتوليد" else "أضف اتصالاً لبدء التوليد",
                                style = MaterialTheme.typography.bodySmall.copy(color = OpusTextSecondary)
                            )
                        }
                        Icon(
                            imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Settings,
                            contentDescription = null,
                            tint = if (isConnected) OpusViralEmerald else OpusTextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(OpusDarkSurfaceVariant)
                            .clickable { showConnectionDialog = true }
                            .padding(horizontal = 12.dp, vertical = 11.dp)
                            .testTag("settings_manage_connection"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "إدارة الاتصال",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = OpusTextPrimary,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "فتح إدارة الاتصال",
                            tint = OpusElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("settings_clip_defaults_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = OpusDarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, OpusBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "افتراضات المقاطع",
                            tint = OpusPrimaryViolet,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "افتراضات المقاطع",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = OpusTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    SettingRow(label = "التأطير", value = "9:16 عمودي")
                    SettingRow(label = "الترجمة", value = "يختارها ISM تلقائياً")
                    SettingRow(label = "الحفظ", value = "المعرض بعد التصدير")
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(color = OpusTextSecondary))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = OpusTextPrimary,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}
