package com.smartreimburse.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartreimburse.ui.theme.AccentCyan
import com.smartreimburse.ui.theme.AccentTeal
import com.smartreimburse.ui.theme.CardDark
import com.smartreimburse.ui.theme.CardDarkAlt
import com.smartreimburse.ui.theme.GlowPurple
import com.smartreimburse.ui.theme.TechBlack
import com.smartreimburse.ui.theme.TextBright
import com.smartreimburse.ui.theme.TextMuted

@Composable
fun GradientCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(CardDark, CardDarkAlt)
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(listOf(AccentCyan.copy(alpha = 0.45f), GlowPurple.copy(alpha = 0.28f))),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(padding)
    ) {
        content()
    }
}

@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(TechBlack.copy(alpha = 0.72f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = if (onTitleClick == null) {
                    Modifier
                } else {
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onTitleClick)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (navigationIcon != null && onNavigationClick != null) {
                    IconButton(onClick = onNavigationClick) {
                        Icon(navigationIcon, contentDescription = null, tint = AccentCyan)
                    }
                }
                Text(
                    text = title,
                    color = TextBright,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable
fun GlowButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (enabled) AccentCyan else TextMuted.copy(alpha = 0.3f),
        label = "glowButtonColor"
    )
    Button(
        modifier = modifier
            .height(48.dp)
            .border(
                BorderStroke(1.dp, AccentCyan.copy(alpha = if (enabled) 0.65f else 0.15f)),
                RoundedCornerShape(16.dp)
            ),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = TechBlack,
            disabledContainerColor = TextMuted.copy(alpha = 0.18f),
            disabledContentColor = TextMuted
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        onClick = onClick
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SmartTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    leadingIcon: ImageVector? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        leadingIcon = leadingIcon?.let {
            {
                Icon(it, contentDescription = null, tint = AccentTeal)
            }
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextBright,
            unfocusedTextColor = TextBright,
            focusedBorderColor = AccentCyan,
            unfocusedBorderColor = TextMuted.copy(alpha = 0.45f),
            focusedLabelColor = AccentCyan,
            unfocusedLabelColor = TextMuted,
            cursorColor = AccentCyan,
            focusedContainerColor = CardDark.copy(alpha = 0.45f),
            unfocusedContainerColor = CardDark.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun StatusPill(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (active) AccentCyan.copy(alpha = 0.14f) else Color.Transparent,
        border = BorderStroke(1.dp, if (active) AccentCyan else TextMuted.copy(alpha = 0.35f))
    ) {
        Text(
            text = text,
            color = if (active) AccentCyan else TextMuted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null) {
    Column {
        Text(
            text = title,
            color = TextBright,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (subtitle != null) {
            Spacer(Modifier.height(3.dp))
            Text(text = subtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
