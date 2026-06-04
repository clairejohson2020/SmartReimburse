package com.smartreimburse.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartreimburse.data.ExpenseEntity
import com.smartreimburse.data.ProjectEntity
import com.smartreimburse.share.ShareManager
import com.smartreimburse.ui.components.GlassTopBar
import com.smartreimburse.ui.components.GradientCard
import com.smartreimburse.ui.components.SectionHeader
import com.smartreimburse.ui.components.SmartTextField
import com.smartreimburse.ui.components.StatusPill
import com.smartreimburse.ui.components.formatCurrency
import com.smartreimburse.ui.components.formatDate
import com.smartreimburse.ui.theme.AccentCyan
import com.smartreimburse.ui.theme.AccentTeal
import com.smartreimburse.ui.theme.CardDark
import com.smartreimburse.ui.theme.TechBlack
import com.smartreimburse.ui.theme.TextBright
import com.smartreimburse.ui.theme.TextMuted
import com.smartreimburse.ui.theme.WarningRed
import com.smartreimburse.viewmodel.DashboardUiState
import com.smartreimburse.viewmodel.ExpenseFilterUiState
import com.smartreimburse.viewmodel.InvoiceFilter
import com.smartreimburse.viewmodel.ProjectSelectionUiState
import com.smartreimburse.viewmodel.SmartReimburseViewModel

@Composable
fun HomeScreen(
    viewModel: SmartReimburseViewModel,
    onAddClick: () -> Unit,
    onExpenseClick: (Long) -> Unit
) {
    val dashboard by viewModel.dashboardState.collectAsStateWithLifecycle()
    val expenses by viewModel.expenses.collectAsStateWithLifecycle()
    val filter by viewModel.filterState.collectAsStateWithLifecycle()
    val projectState by viewModel.projectState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showProjectDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = TechBlack,
        topBar = {
            GlassTopBar(
                title = projectState.currentProject?.let { "项目：${it.name}" } ?: "项目加载中",
                onTitleClick = { showProjectDialog = true },
                actions = {
                    IconButton(
                        enabled = projectState.currentProject != null,
                        onClick = {
                        viewModel.exportExcel { file ->
                            ShareManager.shareExcel(context, file)
                        }
                    }) {
                        Icon(Icons.Outlined.Download, contentDescription = "导出 Excel", tint = AccentCyan)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddClick,
                containerColor = AccentCyan,
                contentColor = TechBlack
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "新增支出")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(TechBlack, TechBlack, CardDark.copy(alpha = 0.32f))
                    )
                )
                .padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { DashboardPanel(dashboard = dashboard, onSetFund = viewModel::setAdvanceFund) }
            item {
                FilterPanel(
                    filter = filter,
                    onChange = { transform -> viewModel.updateFilter(transform) }
                )
            }
            item {
                SectionHeader(
                    title = "支出明细",
                    subtitle = "${expenses.size} 条记录，按日期倒序"
                )
            }
            items(expenses, key = { it.id }) { expense ->
                ExpenseListCard(expense = expense, onClick = { onExpenseClick(expense.id) })
            }
            item { Spacer(Modifier.height(64.dp)) }
        }
    }

    if (showProjectDialog) {
        ProjectManagerDialog(
            projectState = projectState,
            onDismiss = {
                viewModel.dismissProjectMessage()
                showProjectDialog = false
            },
            onSelect = { projectId ->
                viewModel.selectProject(projectId)
                showProjectDialog = false
            },
            onCreate = viewModel::createProject,
            onRename = viewModel::renameProject,
            onDelete = viewModel::deleteProject,
            onDismissMessage = viewModel::dismissProjectMessage
        )
    }
}

@Composable
private fun ProjectManagerDialog(
    projectState: ProjectSelectionUiState,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismissMessage: () -> Unit
) {
    var newProjectName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ProjectEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ProjectEntity?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = AccentCyan)
                Text("报销项目", modifier = Modifier.padding(start = 8.dp))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                projectState.message?.let { message ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(TechBlack.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message,
                            color = TextBright,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        IconButton(
                            modifier = Modifier.size(32.dp),
                            onClick = onDismissMessage
                        ) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = "知道了", tint = AccentCyan)
                        }
                    }
                }

                if (projectState.projects.isEmpty()) {
                    Text("正在准备项目...", color = TextMuted)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(projectState.projects, key = { it.id }) { project ->
                            ProjectRow(
                                project = project,
                                selected = project.id == projectState.currentProjectId,
                                canDelete = projectState.canDeleteProject,
                                onSelect = { onSelect(project.id) },
                                onRename = {
                                    renameTarget = project
                                    renameInput = project.name
                                },
                                onDelete = { deleteTarget = project }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmartTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = "新项目名称",
                        modifier = Modifier.weight(1f),
                        leadingIcon = Icons.Outlined.FolderOpen
                    )
                    TextButton(onClick = {
                        onCreate(newProjectName)
                        newProjectName = ""
                    }) {
                        Text("新建")
                    }
                }
            }
        },
        containerColor = CardDark,
        titleContentColor = TextBright,
        textContentColor = TextBright
    )

    renameTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    onRename(project.id, renameInput)
                    renameTarget = null
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("取消")
                }
            },
            title = { Text("重命名项目") },
            text = {
                SmartTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = "项目名称",
                    leadingIcon = Icons.Outlined.Edit
                )
            },
            containerColor = CardDark,
            titleContentColor = TextBright,
            textContentColor = TextBright
        )
    }

    deleteTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(project.id)
                    deleteTarget = null
                }) {
                    Text("删除", color = WarningRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
            title = { Text("删除项目") },
            text = { Text("“${project.name}”下的支出和附件图片会从本机删除。") },
            containerColor = CardDark,
            titleContentColor = TextBright,
            textContentColor = TextBright
        )
    }
}

@Composable
private fun ProjectRow(
    project: ProjectEntity,
    selected: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) AccentCyan.copy(alpha = 0.14f) else TechBlack.copy(alpha = 0.45f))
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.FolderOpen,
            contentDescription = null,
            tint = if (selected) AccentCyan else TextMuted
        )
        Text(
            text = project.name,
            color = TextBright,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
        IconButton(onClick = onRename) {
            Icon(Icons.Outlined.Edit, contentDescription = "重命名", tint = AccentCyan)
        }
        IconButton(enabled = canDelete, onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "删除",
                tint = if (canDelete) WarningRed else TextMuted.copy(alpha = 0.35f)
            )
        }
    }
}

@Composable
private fun DashboardPanel(
    dashboard: DashboardUiState,
    onSetFund: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var fundInput by remember(dashboard.totalFund) { mutableStateOf(if (dashboard.totalFund > 0) "%.2f".format(dashboard.totalFund) else "") }
    val progress by animateFloatAsState(targetValue = dashboard.spentProgress, label = "fundProgress")
    val remaining by animateFloatAsState(targetValue = dashboard.remaining.toFloat(), label = "remaining")

    GradientCard(modifier = Modifier.fillMaxWidth(), padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("备用金余额", color = TextMuted, style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = formatCurrency(remaining.toDouble()),
                        color = if (remaining >= 0) AccentCyan else WarningRed,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(92.dp),
                        color = AccentCyan,
                        trackColor = TextMuted.copy(alpha = 0.18f),
                        strokeWidth = 9.dp
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        color = TextBright,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricChip(title = "总备用金", value = formatCurrency(dashboard.totalFund), modifier = Modifier.weight(1f))
                MetricChip(title = "已支出", value = formatCurrency(dashboard.totalSpent), modifier = Modifier.weight(1f))
            }

            TextButton(onClick = { showDialog = true }) {
                Icon(Icons.Outlined.Edit, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                Text("设置备用金", color = AccentCyan, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    onSetFund(fundInput)
                    showDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("取消")
                }
            },
            title = { Text("总备用金") },
            text = {
                SmartTextField(
                    value = fundInput,
                    onValueChange = { fundInput = it },
                    label = "金额",
                    keyboardType = KeyboardType.Decimal,
                    leadingIcon = Icons.Outlined.Paid
                )
            },
            containerColor = CardDark,
            titleContentColor = TextBright,
            textContentColor = TextBright
        )
    }
}

@Composable
private fun MetricChip(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(TechBlack.copy(alpha = 0.45f))
            .padding(14.dp)
    ) {
        Text(title, color = TextMuted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(5.dp))
        Text(value, color = TextBright, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    filter: ExpenseFilterUiState,
    onChange: ((ExpenseFilterUiState) -> ExpenseFilterUiState) -> Unit
) {
    GradientCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Tune, contentDescription = null, tint = AccentCyan)
                Text(
                    text = "筛选搜索",
                    color = TextBright,
                    modifier = Modifier.padding(start = 8.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
            SmartTextField(
                value = filter.keyword,
                onValueChange = { value -> onChange { it.copy(keyword = value) } },
                label = "关键词、型号、发票号、备注",
                leadingIcon = Icons.Outlined.Search
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmartTextField(
                    value = filter.fromDateText,
                    onValueChange = { value -> onChange { it.copy(fromDateText = value) } },
                    label = "开始日期 yyyy-MM-dd",
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Outlined.FilterList
                )
                SmartTextField(
                    value = filter.toDateText,
                    onValueChange = { value -> onChange { it.copy(toDateText = value) } },
                    label = "结束日期",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmartTextField(
                    value = filter.minAmountText,
                    onValueChange = { value -> onChange { it.copy(minAmountText = value) } },
                    label = "最低金额",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Decimal
                )
                SmartTextField(
                    value = filter.maxAmountText,
                    onValueChange = { value -> onChange { it.copy(maxAmountText = value) } },
                    label = "最高金额",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Decimal
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InvoiceFilter.entries.forEach { option ->
                    Box(
                        modifier = Modifier.clickable {
                            onChange { it.copy(invoiceFilter = option) }
                        }
                    ) {
                        StatusPill(text = option.label, active = filter.invoiceFilter == option)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpenseListCard(
    expense: ExpenseEntity,
    onClick: () -> Unit
) {
    GradientCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        padding = 16.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(AccentCyan.copy(alpha = 0.13f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (expense.hasInvoice) Icons.Outlined.ReceiptLong else Icons.Outlined.Paid,
                    contentDescription = null,
                    tint = AccentCyan
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = expense.name,
                    color = TextBright,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = TextMuted, modifier = Modifier.size(15.dp))
                    Text(expense.model.ifBlank { "未填型号" }, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.Numbers, contentDescription = null, tint = TextMuted, modifier = Modifier.size(15.dp))
                    Text("x${expense.quantity}", color = TextMuted)
                }
                Spacer(Modifier.height(4.dp))
                Text(formatDate(expense.date), color = TextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatCurrency(expense.totalAmount),
                    color = AccentCyan,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                StatusPill(
                    text = if (expense.hasInvoice) "有发票" else "无发票",
                    active = expense.hasInvoice
                )
            }
        }
    }
}
