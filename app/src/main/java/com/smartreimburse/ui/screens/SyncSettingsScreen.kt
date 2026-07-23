package com.smartreimburse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartreimburse.ui.components.GlassTopBar
import com.smartreimburse.ui.theme.TechBlack
import com.smartreimburse.viewmodel.SmartReimburseViewModel

@Composable
fun SyncSettingsScreen(
    viewModel: SmartReimburseViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.syncState.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = TechBlack,
        topBar = {
            GlassTopBar(
                title = "多端同步",
                navigationIcon = Icons.Outlined.ArrowBack,
                onNavigationClick = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = if (state.isPaired) "已连接统一账号" else "连接微信小程序账号",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Android 保持离线可用；联网时会同步项目、支出和附件。发生并发修改时会保留冲突状态，不会静默覆盖。",
                style = MaterialTheme.typography.bodyMedium
            )

            if (!state.isConfigured) {
                Text("当前安装包未配置同步服务地址，请使用正式 Release 包。")
            } else if (!state.isPaired) {
                if (state.pairingCode == null) {
                    Button(onClick = viewModel::createSyncPairing, enabled = !state.isBusy) {
                        Text("生成 6 位配对码")
                    }
                } else {
                    Text("配对码", style = MaterialTheme.typography.labelLarge)
                    Text(
                        state.pairingCode.orEmpty(),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black
                    )
                    Text("在微信小程序首页输入此码并点击“绑定”，然后回到这里确认。")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = viewModel::completeSyncPairing, enabled = !state.isBusy) {
                            Text("我已在小程序批准")
                        }
                        TextButton(onClick = viewModel::createSyncPairing, enabled = !state.isBusy) {
                            Text("重新生成")
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::syncNow, enabled = !state.isBusy) { Text("立即同步") }
                    OutlinedButton(onClick = viewModel::disconnectSync, enabled = !state.isBusy) { Text("解除本机连接") }
                }
                if (state.conflictCount > 0) {
                    Text("有 ${state.conflictCount} 条记录在两端同时修改，请选择采用哪个版本。")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = viewModel::keepLocalSyncConflicts, enabled = !state.isBusy) {
                            Text("保留本机")
                        }
                        OutlinedButton(onClick = viewModel::useCloudSyncConflicts, enabled = !state.isBusy) {
                            Text("采用云端")
                        }
                    }
                }
            }

            if (state.isBusy) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            state.message?.let {
                Text(it, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
