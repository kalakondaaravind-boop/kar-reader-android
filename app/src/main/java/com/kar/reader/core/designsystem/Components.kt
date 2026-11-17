package com.kar.reader.core.designsystem

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class GlossyKind { PRIMARY, ACCENT, SECONDARY, DANGER }

/** Primary, accent, secondary and danger buttons with soft depth. Renders flat with a black outline in e-ink mode. */
@Composable
fun GlossyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: GlossyKind = GlossyKind.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val style = LocalKarStyle.current
    val shape = RoundedCornerShape(if (compact) 12.dp else 15.dp)
    val (brush, content, border) = when {
        style.eink -> Triple(Brush.verticalGradient(listOf(Color.White, Color.White)), Color.Black, Color.Black)
        kind == GlossyKind.PRIMARY -> Triple(
            Brush.verticalGradient(listOf(if (style.dark) Color(0xFF4F8CFF) else Color(0xFF2E6BFF), if (style.dark) Color(0xFF2F6BF0) else KarColors.Primary)),
            Color.White, if (style.dark) Color(0xFF3D78F5) else KarColors.PrimaryDeep.copy(alpha = 0.6f),
        )
        kind == GlossyKind.ACCENT -> Triple(
            Brush.linearGradient(listOf(KarColors.Accent, KarColors.Primary)),
            Color.White, KarColors.AccentDeep.copy(alpha = 0.5f),
        )
        kind == GlossyKind.DANGER -> Triple(
            Brush.verticalGradient(listOf(if (style.dark) Color(0xFF2A1A22) else Color.White, if (style.dark) Color(0xFF2A1A22) else Color(0xFFFFF5F5))),
            if (style.dark) Color(0xFFFF8A8E) else Color(0xFFC62828), if (style.dark) Color(0xFF5A2A33) else Color(0xFFF3C7C9),
        )
        style.dark -> Triple(Brush.verticalGradient(listOf(KarColors.DarkSurfaceHigh, KarColors.DarkSurfaceHigh)), KarColors.DarkInk, KarColors.DarkBorder)
        else -> Triple(Brush.verticalGradient(listOf(Color.White, Color.White)), KarColors.Primary, KarColors.Border)
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = if (compact) 40.dp else 48.dp)
            .then(
                if (style.glossy && enabled) Modifier.shadow(
                    if (kind == GlossyKind.PRIMARY || kind == GlossyKind.ACCENT) (if (compact) 2.dp else 4.dp) else 1.dp, shape, clip = false,
                    ambientColor = KarColors.Primary.copy(alpha = 0.25f), spotColor = KarColors.Primary.copy(alpha = 0.3f),
                ) else Modifier,
            )
            .clip(shape)
            .background(brush)
            .border(BorderStroke(if (style.eink) 2.dp else 1.dp, border), shape)
            .then(if (style.glossy && (kind == GlossyKind.PRIMARY || kind == GlossyKind.ACCENT)) Modifier.background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.5f to Color.Transparent)) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = content, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 13.sp else 15.sp, maxLines = 1)
    }
}

/** Card on a soft shadow with a hairline border. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    contentPadding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LocalKarStyle.current
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        style.eink -> Brush.verticalGradient(listOf(Color.White, Color.White))
        style.dark -> Brush.verticalGradient(listOf(KarColors.DarkSurface, KarColors.DarkSurface))
        else -> Brush.verticalGradient(listOf(Color.White, Color.White))
    }
    Column(
        modifier = modifier
            .then(if (style.glossy && !style.dark) Modifier.shadow(6.dp, shape, ambientColor = Color(0x1A1A3A7A), spotColor = Color(0x261A3A7A)) else Modifier)
            .clip(shape)
            .background(bg)
            .border(if (style.eink) BorderStroke(2.dp, Color.Black) else BorderStroke(1.dp, scheme.outlineVariant), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** Blue hero panel, used for "continue reading" and status banners. */
@Composable
fun HeroPanel(modifier: Modifier = Modifier, colors: List<Color> = listOf(Color(0xFF2E6BFF), KarColors.Primary, KarColors.PrimaryDeep), content: @Composable RowScope.() -> Unit) {
    val style = LocalKarStyle.current
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = modifier
            .then(if (style.glossy) Modifier.shadow(10.dp, shape, ambientColor = KarColors.Primary.copy(alpha = 0.35f), spotColor = KarColors.Primary.copy(alpha = 0.4f)) else Modifier)
            .clip(shape)
            .background(if (style.eink) Brush.verticalGradient(listOf(Color.White, Color.White)) else Brush.linearGradient(colors))
            .then(if (style.glossy) Modifier.background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent), center = Offset(900f, -200f), radius = 900f)) else Modifier.border(2.dp, Color.Black, shape))
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Clean top bar on the page surface with a hairline divider; title in ink, actions in blue. Flat in e-ink mode. */
@Composable
fun KarTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val style = LocalKarStyle.current
    val scheme = MaterialTheme.colorScheme
    val fg = if (style.eink) Color.Black else scheme.onSurface
    val actionTint = if (style.eink) Color.Black else scheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (style.eink) Color.White else scheme.surface)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .defaultMinSize(minHeight = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null && backIcon != null) {
            IconButton(onClick = onBack) { Icon(backIcon, contentDescription = "Back", tint = actionTint) }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, color = fg, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = if (style.eink) Color.Black else scheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CompositionTint(actionTint) { actions() }
    }
    Box(Modifier.fillMaxWidth().height(if (style.eink) 2.dp else 1.dp).background(if (style.eink) Color.Black else scheme.outlineVariant))
}

@Composable
private fun RowScope.CompositionTint(color: Color, content: @Composable RowScope.() -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides color) { content() }
}

/** Rounded track with a blue fill. */
@Composable
fun GlossyProgress(progress: Float, modifier: Modifier = Modifier, height: Dp = 8.dp, onDark: Boolean = false, color: Color? = null) {
    val style = LocalKarStyle.current
    val p = progress.coerceIn(0f, 1f)
    val trackShape = RoundedCornerShape(50)
    Box(
        modifier
            .height(height)
            .clip(trackShape)
            .background(
                when {
                    style.eink -> Color.White
                    onDark -> Color.White.copy(alpha = 0.25f)
                    style.dark -> KarColors.DarkBorder
                    else -> Color(0xFFE3E9F3)
                },
            )
            .then(if (style.eink) Modifier.border(1.dp, Color.Black, trackShape) else Modifier)
            .semantics { contentDescription = "${(p * 100).toInt()} percent" },
    ) {
        if (p > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(p)
                    .clip(trackShape)
                    .background(
                        when {
                            style.eink -> Brush.verticalGradient(listOf(Color.Black, Color.Black))
                            color != null -> Brush.verticalGradient(listOf(color.copy(alpha = 0.75f), color))
                            onDark -> Brush.horizontalGradient(listOf(Color.White, KarColors.AccentLight))
                            else -> Brush.horizontalGradient(listOf(KarColors.Accent, KarColors.Primary))
                        },
                    ),
            )
        }
    }
}

/** Ring used for daily goals and focus timers. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, stroke: Dp = 12.dp, trackColor: Color = Color(0x1F1A5CFF), color: Color = KarColors.Primary) {
    val style = LocalKarStyle.current
    val strokePx = with(LocalDensity.current) { stroke.toPx() }
    Canvas(modifier) {
        val inset = strokePx / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - strokePx, size.height - strokePx)
        drawArc(if (style.eink) Color(0xFFDDDDDD) else trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(strokePx))
        drawArc(if (style.eink) Color.Black else color, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
    }
}

/** Gradient pairs for generated covers, picked by title so each book keeps its colour. */
private val CoverPalettes = listOf(
    Color(0xFF2E6BFF) to Color(0xFF0B3FCC), Color(0xFF00A3FF) to Color(0xFF0060B8), Color(0xFF3B4C7A) to Color(0xFF1B2440),
    Color(0xFF5B5BD6) to Color(0xFF2E2E8F), Color(0xFF0E9F9A) to Color(0xFF075E5B), Color(0xFF64748B) to Color(0xFF2B3444),
    Color(0xFF7C5CFF) to Color(0xFF3E2A99),
)

/**
 * A book cover with spine shading, a glossy sheen, perspective tilt and a cast shadow.
 * Shows the real cover image when available, otherwise a typographic cover.
 */
@Composable
fun BookCover3D(title: String, format: String, coverFile: File?, modifier: Modifier = Modifier, tilt: Boolean = true) {
    val style = LocalKarStyle.current
    val bitmap by rememberFileBitmap(coverFile)
    val (top, bottom) = CoverPalettes[(title.hashCode() and 0x7fffffff) % CoverPalettes.size]
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 10.dp, bottomEnd = 10.dp)
    val density = LocalDensity.current.density
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .graphicsLayer {
                if (style.glossy && tilt) {
                    rotationY = -7f
                    cameraDistance = 14f * density
                }
            }
            .then(if (style.glossy) Modifier.shadow(12.dp, shape) else Modifier.border(2.dp, Color.Black, shape))
            .clip(shape)
            .background(if (style.eink) Brush.verticalGradient(listOf(Color.White, Color.White)) else Brush.linearGradient(listOf(top, bottom))),
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)) {
                Text(format, color = if (style.eink) Color.Black else Color.White.copy(alpha = 0.85f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    title, color = if (style.eink) Color.Black else Color.White, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (style.glossy) {
            Box(Modifier.fillMaxHeight().width(10.dp).background(Brush.horizontalGradient(listOf(Color(0x6B000000), Color(0x38FFFFFF), Color(0x1F000000)))))
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(0f to Color.White.copy(alpha = 0.45f), 0.36f to Color.White.copy(alpha = 0.08f), 0.37f to Color.Transparent, start = Offset.Zero, end = Offset(600f, 900f))))
        }
    }
}

private object BitmapMemoryCache : LruCache<String, Bitmap>(24 shl 20) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** Loads a small image file off the main thread, with a shared memory cache. */
@Composable
fun rememberFileBitmap(file: File?) = produceState<Bitmap?>(initialValue = file?.let { BitmapMemoryCache.get(it.path + it.lastModified()) }, file?.path, file?.lastModified()) {
    if (file == null || !file.isFile) { value = null; return@produceState }
    val key = file.path + file.lastModified()
    value = BitmapMemoryCache.get(key) ?: withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()?.also { BitmapMemoryCache.put(key, it) }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondary) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmText, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RatingBar(rating: Int, onRate: ((Int) -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier) {
        for (i in 1..5) {
            val filled = i <= rating
            val icon = if (filled) Icons.Filled.Star else Icons.Outlined.StarOutline
            if (onRate != null) {
                IconButton(onClick = { onRate(if (rating == i) 0 else i) }) {
                    Icon(icon, contentDescription = "$i stars", tint = if (filled) KarColors.Warning else MaterialTheme.colorScheme.outline)
                }
            } else {
                Icon(icon, contentDescription = null, tint = if (filled) KarColors.Warning else MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Small glossy circle used as a colour swatch or avatar orb. */
@Composable
fun GlossyOrb(color: Color, modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable BoxScope.() -> Unit = {}) {
    val style = LocalKarStyle.current
    Box(
        modifier
            .then(if (style.glossy) Modifier.shadow(4.dp, CircleShape) else Modifier)
            .clip(CircleShape)
            .background(if (style.eink) Brush.radialGradient(listOf(Color.White, Color.White)) else Brush.radialGradient(listOf(color.copy(alpha = 0.55f).compositeOver(Color.White), color, color.copy(red = color.red * 0.6f, green = color.green * 0.6f, blue = color.blue * 0.6f))))
            .border(if (selected) 3.dp else if (style.eink) 1.dp else 0.dp, if (selected) KarColors.Primary else Color.Black, CircleShape),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a), 1f)
}
