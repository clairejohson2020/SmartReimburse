package com.smartreimburse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.smartreimburse.data.AttachmentEntity
import com.smartreimburse.data.AttachmentType
import com.smartreimburse.data.ExpenseWithAttachments
import com.smartreimburse.share.ShareManager
import com.smartreimburse.ui.components.GlassTopBar
import com.smartreimburse.ui.components.GradientCard
import com.smartreimburse.ui.components.SectionHeader
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
import com.smartreimburse.viewmodel.SmartReimburseViewModel
import java.io.File

@Composable
fun ExpenseDetailScreen(
    expenseId: Long,
    viewModel: SmartReimburseViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit
) {
    val detail by viewModel.observeExpenseDetail(expenseId).collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    var selectedImagePath by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = TechBlack,
        topBar = {
            GlassTopBar(
                title = "支出详情",
                navigationIcon = Icons.Outlined.ArrowBack,
                onNavigationClick = onBack,
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑", tint = AccentCyan)
                    }
                    IconButton(onClick = {
                        viewModel.prepareExpenseShare(expenseId) {
                            ShareManager.shareExpense(context, it)
                        }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = "分享", tint = AccentCyan)
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = WarningRed)
                    }
                }
            )
        }
    ) { padding ->
        if (detail == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("记录不存在", color = TextMuted)
            }
        } else {
            ExpenseDetailContent(
                detail = detail!!,
                modifier = Modifier.padding(padding),
                onImageClick = { selectedImagePath = it }
            )
        }
    }

    selectedImagePath?.let { path ->
        ImageZoomDialog(imagePath = path, onDismiss = { selectedImagePath = null })
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteExpense(expenseId, onDeleted)
                }) {
                    Text("删除", color = WarningRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
            title = { Text("删除记录") },
            text = { Text("该支出及关联附件图片会从本机删除。") },
            containerColor = CardDark,
            titleContentColor = TextBright,
            textContentColor = TextBright
        )
    }
}

@Composable
private fun ExpenseDetailContent(
    detail: ExpenseWithAttachments,
    modifier: Modifier = Modifier,
    onImageClick: (String) -> Unit
) {
    val expense = detail.expense
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(TechBlack, TechBlack, CardDark.copy(alpha = 0.3f)))),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            GradientCard(modifier = Modifier.fillMaxWidth(), padding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = expense.name,
                                color = TextBright,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(expense.model.ifBlank { "未填型号" }, color = TextMuted)
                        }
                        StatusPill(text = if (expense.hasInvoice) "有发票" else "无发票", active = expense.hasInvoice)
                    }
                    Text(
                        text = formatCurrency(expense.totalAmount),
                        color = AccentCyan,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailChip("数量", "x${expense.quantity}", Icons.Outlined.Numbers, Modifier.weight(1f))
                        DetailChip("单价", formatCurrency(expense.price), Icons.Outlined.Paid, Modifier.weight(1f))
                    }
                }
            }
        }

        item {
            GradientCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("详细字段")
                    DetailLine("日期", formatDate(expense.date))
                    DetailLine("发票号码", expense.invoiceNumber.orEmpty().ifBlank { if (expense.hasInvoice) "未识别/未填写" else "无发票" })
                    DetailLine("网购链接", expense.onlineLink.orEmpty().ifBlank { "未填写" }, icon = Icons.Outlined.Link)
                    DetailLine("报销状态", if (expense.isReimbursed) "已报销" else "未报销")
                    DetailLine("备注", expense.notes.orEmpty().ifBlank { "无" })
                }
            }
        }

        item {
            AttachmentGallery(
                title = "发票附件",
                attachments = detail.attachments.filter { it.type == AttachmentType.INVOICE },
                onImageClick = onImageClick
            )
        }
        item {
            AttachmentGallery(
                title = "付款截图",
                attachments = detail.attachments.filter { it.type == AttachmentType.PAYMENT_SCREENSHOT },
                onImageClick = onImageClick
            )
        }
        item {
            AttachmentGallery(
                title = "收据/送货单",
                attachments = detail.attachments.filter { it.type == AttachmentType.RECEIPT },
                onImageClick = onImageClick
            )
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun DetailChip(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(TechBlack.copy(alpha = 0.45f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AccentTeal, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(title, color = TextMuted, style = MaterialTheme.typography.labelSmall)
            Text(value, color = TextBright, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DetailLine(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(0.42f)) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
            }
            Text(
                text = label,
                color = TextMuted,
                modifier = Modifier.padding(start = if (icon != null) 6.dp else 0.dp)
            )
        }
        Text(
            text = value,
            color = TextBright,
            modifier = Modifier.weight(0.58f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 3
        )
    }
}

@Composable
private fun AttachmentGallery(
    title: String,
    attachments: List<AttachmentEntity>,
    onImageClick: (String) -> Unit
) {
    GradientCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(title, "${attachments.size} 张")
            if (attachments.isEmpty()) {
                Text("暂无附件", color = TextMuted)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(attachments, key = { it.id }) { attachment ->
                        Box(
                            modifier = Modifier
                                .size(width = 128.dp, height = 150.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(TechBlack.copy(alpha = 0.5f))
                                .clickable { onImageClick(attachment.filePath) }
                        ) {
                            AsyncImage(
                                model = File(attachment.filePath),
                                contentDescription = attachment.type.label,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .background(TechBlack.copy(alpha = 0.7f))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.ZoomIn, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                                Text(
                                    File(attachment.filePath).name,
                                    color = TextBright,
                                    modifier = Modifier.padding(start = 6.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageZoomDialog(
    imagePath: String,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TechBlack.copy(alpha = 0.96f))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset += pan
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = File(imagePath),
                contentDescription = "附件原图",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = ContentScale.Fit
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(TechBlack.copy(alpha = 0.72f), CircleShape)
            ) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "关闭", tint = AccentCyan)
            }
        }
    }
}
