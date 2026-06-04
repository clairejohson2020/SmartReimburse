package com.smartreimburse.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.smartreimburse.data.AttachmentType
import com.smartreimburse.ui.components.GlassTopBar
import com.smartreimburse.ui.components.GlowButton
import com.smartreimburse.ui.components.GradientCard
import com.smartreimburse.ui.components.SectionHeader
import com.smartreimburse.ui.components.SmartTextField
import com.smartreimburse.ui.components.formatDate
import com.smartreimburse.ui.theme.AccentCyan
import com.smartreimburse.ui.theme.AccentTeal
import com.smartreimburse.ui.theme.CardDark
import com.smartreimburse.ui.theme.TechBlack
import com.smartreimburse.ui.theme.TextBright
import com.smartreimburse.ui.theme.TextMuted
import com.smartreimburse.ui.theme.WarningRed
import com.smartreimburse.viewmodel.AttachmentDraft
import com.smartreimburse.viewmodel.ExpenseFormUiState
import com.smartreimburse.viewmodel.SmartReimburseViewModel
import java.io.File

private data class PendingMediaPick(
    val type: AttachmentType,
    val runOcr: Boolean
)

@Composable
fun ExpenseFormScreen(
    expenseId: Long?,
    viewModel: SmartReimburseViewModel,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    onOpenCamera: (AttachmentType, Boolean) -> Unit
) {
    val state by viewModel.formState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingPick by remember { mutableStateOf(PendingMediaPick(AttachmentType.RECEIPT, true)) }
    var pendingCamera by remember { mutableStateOf<PendingMediaPick?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var permissionDialogText by remember { mutableStateOf("请允许相机或图片权限后继续。") }

    LaunchedEffect(expenseId) {
        if (expenseId == null) {
            viewModel.startNewExpense()
        } else {
            viewModel.loadExpenseForEdit(expenseId)
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        viewModel.onImagesSelected(uris, pendingPick.type, pendingPick.runOcr)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCamera?.let { onOpenCamera(it.type, it.runOcr) }
        } else {
            permissionDialogText = "请允许相机权限后再拍摄发票、付款截图或收据。也可以先从相册选择图片。"
            showPermissionDialog = true
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            permissionDialogText = "请允许图片读取权限后从相册导入，或改用系统拍照功能。"
            showPermissionDialog = true
        }
    }

    fun openGallery(type: AttachmentType, runOcr: Boolean) {
        pendingPick = PendingMediaPick(type, runOcr)
        val legacyPermissions = galleryPermissionsForCurrentDevice()
        val allGranted = legacyPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (legacyPermissions.isEmpty() || allGranted) {
            mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            storagePermissionLauncher.launch(legacyPermissions)
        }
    }

    fun openCamera(type: AttachmentType, runOcr: Boolean) {
        pendingCamera = PendingMediaPick(type, runOcr)
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onOpenCamera(type, runOcr)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        containerColor = TechBlack,
        topBar = {
            GlassTopBar(
                title = if (expenseId == null) "新增支出" else "编辑支出",
                navigationIcon = Icons.Outlined.ArrowBack,
                onNavigationClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(TechBlack, TechBlack, CardDark.copy(alpha = 0.28f))))
                .padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                OcrStatusBanner(state = state, onDismiss = viewModel::dismissMessage)
            }
            item {
                BasicInfoSection(state = state, viewModel = viewModel)
            }
            item {
                InvoiceModeSection(state = state, viewModel = viewModel)
            }
            item {
                if (state.hasInvoice) {
                    AttachmentSection(
                        title = "发票原图",
                        subtitle = "上传后会自动 OCR 提取发票号码",
                        attachments = state.invoiceAttachments,
                        required = true,
                        onCamera = { openCamera(AttachmentType.INVOICE, true) },
                        onGallery = { openGallery(AttachmentType.INVOICE, true) },
                        onRemove = viewModel::removeAttachment
                    )
                } else {
                    NoInvoiceSection(
                        state = state,
                        viewModel = viewModel,
                        onPaymentCamera = { openCamera(AttachmentType.PAYMENT_SCREENSHOT, false) },
                        onPaymentGallery = { openGallery(AttachmentType.PAYMENT_SCREENSHOT, false) },
                        onRemove = viewModel::removeAttachment
                    )
                }
            }
            item {
                AttachmentSection(
                    title = "收据/送货单",
                    subtitle = "可多张上传，OCR 会尝试填入名称、型号、数量和金额",
                    attachments = state.receiptAttachments,
                    required = false,
                    onCamera = { openCamera(AttachmentType.RECEIPT, true) },
                    onGallery = { openGallery(AttachmentType.RECEIPT, true) },
                    onRemove = viewModel::removeAttachment
                )
            }
            item {
                GlowButton(
                    text = if (state.isSaving) "保存中..." else "保存记录",
                    icon = Icons.Outlined.Save,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.saveExpense(onSaved) }
                )
            }
            item { Spacer(Modifier.height(36.dp)) }
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            confirmButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("知道了")
                }
            },
            title = { Text("需要权限") },
            text = { Text(permissionDialogText) },
            containerColor = CardDark,
            titleContentColor = TextBright,
            textContentColor = TextBright
        )
    }
}

@Composable
private fun OcrStatusBanner(
    state: ExpenseFormUiState,
    onDismiss: () -> Unit
) {
    if (state.message == null && !state.isOcrRunning) return
    GradientCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.isOcrRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = AccentCyan,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = AccentTeal)
            }
            Text(
                text = state.message ?: "正在识别图片文字...",
                color = TextBright,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = TextMuted)
            }
        }
    }
}

@Composable
private fun BasicInfoSection(
    state: ExpenseFormUiState,
    viewModel: SmartReimburseViewModel
) {
    GradientCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("支出信息", "OCR 会自动填表，保存前可以手动修正")
            SmartTextField(
                value = state.name,
                onValueChange = viewModel::updateName,
                label = "名称",
                leadingIcon = Icons.Outlined.TextSnippet
            )
            SmartTextField(
                value = state.model,
                onValueChange = viewModel::updateModel,
                label = "型号",
                leadingIcon = Icons.Outlined.Inventory2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmartTextField(
                    value = state.quantity,
                    onValueChange = viewModel::updateQuantity,
                    label = "数量",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Number,
                    leadingIcon = Icons.Outlined.Numbers
                )
                SmartTextField(
                    value = state.price,
                    onValueChange = viewModel::updatePrice,
                    label = "单价",
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Decimal
                )
            }
            SmartTextField(
                value = state.totalAmount,
                onValueChange = viewModel::updateTotalAmount,
                label = "金额",
                keyboardType = KeyboardType.Decimal,
                leadingIcon = Icons.Outlined.Paid
            )
            Text(
                text = "记录日期：${formatDate(state.dateMillis)}",
                color = TextMuted,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("报销状态", color = TextBright, fontWeight = FontWeight.SemiBold)
                    Text(if (state.isReimbursed) "已报销" else "未报销", color = TextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = state.isReimbursed,
                    onCheckedChange = viewModel::updateReimbursed,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentCyan,
                        checkedTrackColor = AccentCyan.copy(alpha = 0.35f),
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = TextMuted.copy(alpha = 0.22f)
                    )
                )
            }
            SmartTextField(
                value = state.notes,
                onValueChange = viewModel::updateNotes,
                label = "备注",
                singleLine = false,
                leadingIcon = Icons.Outlined.AttachFile
            )
        }
    }
}

private fun galleryPermissionsForCurrentDevice(): Array<String> {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> emptyArray()
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

@Composable
private fun InvoiceModeSection(
    state: ExpenseFormUiState,
    viewModel: SmartReimburseViewModel
) {
    GradientCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("发票状态", color = TextBright, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.hasInvoice) "有发票：上传发票原图并提取号码" else "无发票：需付款截图和网购链接",
                        color = TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = state.hasInvoice,
                    onCheckedChange = viewModel::updateHasInvoice,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentCyan,
                        checkedTrackColor = AccentCyan.copy(alpha = 0.35f),
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = TextMuted.copy(alpha = 0.22f)
                    )
                )
            }
            if (state.hasInvoice) {
                SmartTextField(
                    value = state.invoiceNumber,
                    onValueChange = viewModel::updateInvoiceNumber,
                    label = "发票号码",
                    leadingIcon = Icons.Outlined.ReceiptLong
                )
            }
        }
    }
}

@Composable
private fun NoInvoiceSection(
    state: ExpenseFormUiState,
    viewModel: SmartReimburseViewModel,
    onPaymentCamera: () -> Unit,
    onPaymentGallery: () -> Unit,
    onRemove: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GradientCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("无发票信息", "付款截图为必填项")
                SmartTextField(
                    value = state.onlineLink,
                    onValueChange = viewModel::updateOnlineLink,
                    label = "网购链接",
                    leadingIcon = Icons.Outlined.Link
                )
            }
        }
        AttachmentSection(
            title = "付款截图",
            subtitle = "无发票记录必须上传，支持拍照或相册",
            attachments = state.paymentScreenshots,
            required = true,
            onCamera = onPaymentCamera,
            onGallery = onPaymentGallery,
            onRemove = onRemove
        )
    }
}

@Composable
private fun AttachmentSection(
    title: String,
    subtitle: String,
    attachments: List<AttachmentDraft>,
    required: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onRemove: (String) -> Unit
) {
    GradientCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                SectionHeader(title, subtitle)
                if (required) {
                    Text("必填", color = WarningRed, style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlowButton(
                    text = "拍照",
                    icon = Icons.Outlined.CameraAlt,
                    modifier = Modifier.weight(1f),
                    onClick = onCamera
                )
                GlowButton(
                    text = "相册",
                    icon = Icons.Outlined.PhotoLibrary,
                    modifier = Modifier.weight(1f),
                    onClick = onGallery
                )
            }
            AttachmentPreviewRow(attachments = attachments, onRemove = onRemove)
        }
    }
}

@Composable
private fun AttachmentPreviewRow(
    attachments: List<AttachmentDraft>,
    onRemove: (String) -> Unit
) {
    if (attachments.isEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(TechBlack.copy(alpha = 0.45f))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Image, contentDescription = null, tint = TextMuted)
            Text(
                "暂无图片",
                color = TextMuted,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(attachments, key = { it.localId }) { attachment ->
                Box(
                    modifier = Modifier
                        .size(width = 104.dp, height = 124.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(TechBlack.copy(alpha = 0.5f))
                ) {
                    AsyncImage(
                        model = File(attachment.filePath),
                        contentDescription = attachment.type.label,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    IconButton(
                        onClick = { onRemove(attachment.localId) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(30.dp)
                            .background(TechBlack.copy(alpha = 0.72f), CircleShape)
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "移除", tint = TextBright, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = attachment.type.label,
                        color = TextBright,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(TechBlack.copy(alpha = 0.65f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
