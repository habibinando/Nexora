package com.example.nexora.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * Multifaceted Gem Logo from zegamingx/Neural with cyan, purple, and blue facets and radial glow.
 */
@Composable
fun GemLogo(dimension: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(dimension)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(
            brush = Brush.radialGradient(listOf(Cyan.copy(alpha = 0.35f), Color.Transparent)),
            radius = w * 0.65f
        )
        fun facet(color: Color, vararg points: Pair<Float, Float>) {
            val path = Path()
            points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.first * w, point.second * h) else path.lineTo(point.first * w, point.second * h)
            }
            path.close()
            drawPath(path = path, color = color)
        }
        facet(Cyan, 0.5f to 0.04f, 0.78f to 0.35f, 0.78f to 0.70f, 0.5f to 0.96f, 0.22f to 0.70f, 0.22f to 0.35f)
        facet(Color(0xFF5D75F7), 0.5f to 0.04f, 0.78f to 0.35f, 0.5f to 0.50f)
        facet(Purple, 0.5f to 0.04f, 0.22f to 0.35f, 0.5f to 0.50f)
        facet(Color(0xFF188FCA), 0.22f to 0.35f, 0.5f to 0.50f, 0.22f to 0.70f)
        facet(Color(0xFF7D55E8), 0.78f to 0.35f, 0.5f to 0.50f, 0.78f to 0.70f)
        facet(Cyan, 0.22f to 0.70f, 0.5f to 0.50f, 0.5f to 0.96f)
        facet(Purple, 0.78f to 0.70f, 0.5f to 0.50f, 0.5f to 0.96f)
        drawLine(
            color = WhiteText.copy(alpha = 0.85f),
            start = Offset(0.5f * w, 0.26f * h),
            end = Offset(0.5f * w, 0.74f * h),
            strokeWidth = w * 0.014f
        )
    }
}

/**
 * Glow Chip from zegamingx/Neural with cyan to purple glowing outline when selected.
 */
@Composable
fun GlowChip(
    emoji: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    val outline = if (selected) {
        Brush.linearGradient(listOf(Cyan, Purple))
    } else {
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.06f)))
    }
    Column(
        modifier = modifier
            .width(78.dp)
            .height(82.dp)
            .clip(shape)
            .background(if (selected) Color(0xFF12283A) else GlassFill)
            .border(if (selected) 1.5.dp else 1.dp, outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(emoji, fontSize = 20.sp)
        Text(
            text = label,
            color = if (selected) WhiteText else MutedText,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * Gradient Button with BrandBrush from zegamingx/Neural.
 */
@Composable
fun GradientButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .height(50.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(26.dp))
            .background(BrandBrush)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = AppBg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Panel Card with GlassFill and GlassBorder from zegamingx/Neural.
 */
@Composable
fun PanelCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassFill)
            .border(1.dp, GlassBorder, shape)
            .padding(14.dp)
    ) {
        Text(title, color = WhiteText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                color = MutedText,
                fontSize = 10.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
            )
        } else {
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

@Composable
fun ScreenIntro(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Text(title, color = WhiteText, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MutedText, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun SmallStat(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(GlassFill).border(1.dp, GlassBorder, shape).padding(10.dp)) {
        Text(label, color = MutedText, fontSize = 8.sp, letterSpacing = 0.8.sp, maxLines = 1)
        Text(
            value,
            color = accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

@Composable
fun DarkField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true,
    hint: String = "",
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = WhiteText, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 5.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 2,
            placeholder = if (hint.isNotBlank()) ({ Text(hint, color = MutedText, fontSize = 12.sp) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = WhiteText,
                unfocusedTextColor = WhiteText,
                focusedContainerColor = FieldBg,
                unfocusedContainerColor = FieldBg,
                focusedBorderColor = Cyan,
                unfocusedBorderColor = BorderColor,
                cursorColor = Cyan,
                focusedPlaceholderColor = MutedText,
                unfocusedPlaceholderColor = MutedText
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
        )
    }
}

@Composable
fun ChoiceRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option, fontSize = 11.sp) },
                shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF153841),
                    selectedLabelColor = Cyan,
                    containerColor = FieldBg,
                    labelColor = MutedText
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = option == selected,
                    borderColor = BorderColor,
                    selectedBorderColor = Cyan
                )
            )
        }
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Cyan,
            contentColor = AppBg,
            disabledContainerColor = PanelRaised,
            disabledContentColor = MutedText
        )
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = WhiteText)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PipelineStep(number: String, title: String, description: String, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(29.dp).clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            Text(number, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, color = WhiteText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = MutedText, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
fun StatusBadge(
    text: String,
    color: Color = NexoraCyan,
    modifier: Modifier = Modifier
) {
    Surface(
        color = color.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = text,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    subtitle: String? = null,
    icon: ImageVector,
    accentColor: Color = NexoraCyan,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = PanelBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexoraHeader(
    title: String,
    activeModelName: String,
    isOffline: Boolean,
    onBack: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusBadge(
                        text = if (isOffline) "100% On-Device" else "Local Core",
                        color = Cyan
                    )
                    Text(
                        text = activeModelName,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        },
        navigationIcon = {
            if (onMenuClick != null) {
                // Iconic 3-lines menu icon from zegamingx/Neural (baris 3 atas layar kiri)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onMenuClick)
                        .testTag("nav_drawer_menu_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(3) {
                            Box(
                                Modifier
                                    .width(20.dp)
                                    .height(2.5.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(WhiteText)
                            )
                        }
                    }
                }
            } else if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("nav_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = AppBg,
            titleContentColor = TextPrimary
        )
    )
}
