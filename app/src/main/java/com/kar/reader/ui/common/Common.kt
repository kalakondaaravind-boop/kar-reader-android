package com.kar.reader.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun bytes(b: Long?): String {
        if (b == null) return "—"
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb)
        val mb = kb / 1024
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024)
    }

    fun relative(time: Long?, now: Long = System.currentTimeMillis()): String {
        if (time == null) return "never"
        val diff = now - time
        val min = diff / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            min < 2 * 24 * 60 -> "yesterday"
            min < 30L * 24 * 60 -> "${min / (24 * 60)} days ago"
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
        }
    }

    fun date(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

    fun minutes(ms: Long): String {
        val m = ms / 60_000
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }
}

/** Standard content padding for screens, wider gutters on tablets. */
val ScreenPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, icon: ImageVector? = null) {
    SettingRow(title = title, subtitle = subtitle, icon = icon, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
