package com.roylet.photos.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roylet.photos.ui.components.BrutalButton
import com.roylet.photos.ui.components.BrutalCard
import com.roylet.photos.ui.components.BrutalSheet
import com.roylet.photos.ui.components.ButtonStyle
import com.roylet.photos.ui.components.IconBadge
import com.roylet.photos.ui.theme.Palette
import com.roylet.photos.ui.theme.Type

/**
 * "Photos closed unexpectedly": the last crash report (selectable), with COPY DETAILS, SHARE and
 * DISMISS. Shown once on the launch after a crash, and from Settings → About → Last crash report.
 */
@Composable
fun CrashReportSheet(report: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember(report) { mutableStateOf(false) }
    BrutalSheet(visible = report != null, onDismiss = onDismiss) {
        val text = report.orEmpty()
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.BugReport, fill = Palette.red, size = 40.dp, tint = Palette.onStrong)
            Spacer(Modifier.width(12.dp))
            Text("Photos closed unexpectedly", style = Type.Headline, color = Palette.ink)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Sorry about that. COPY DETAILS and paste them into your bug report: they say what went wrong and where.",
            style = Type.Small, color = Palette.mutedInk,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(max = 300.dp)
                .background(Palette.card, RoundedCornerShape(6.dp))
                .border(2.dp, Palette.border, RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
                .testTag("crash-report"),
        ) {
            SelectionContainer { Text(text, style = Type.Small.copy(fontFamily = FontFamily.Monospace), color = Palette.ink, softWrap = false) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrutalButton(
                if (copied) "Copied" else "Copy details",
                { copyCrashReport(context, text); copied = true },
                Modifier.weight(1f), icon = Icons.Rounded.ContentCopy,
            )
            BrutalButton("Share", { shareCrashReport(context, text) }, style = ButtonStyle.Outline, icon = Icons.Rounded.Share)
            BrutalButton("Dismiss", onDismiss, style = ButtonStyle.Ghost)
        }
    }
}

/** Settings → About: the app version and the last crash report (if there is one). */
@Composable
internal fun AboutCard(appVersion: String?, lastCrash: String?, onShowCrash: () -> Unit) {
    BrutalCard(Modifier.fillMaxWidth(), onClick = if (lastCrash != null) onShowCrash else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.BugReport, fill = if (lastCrash != null) Palette.peach else Palette.card, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Last crash report", style = Type.Title, color = Palette.ink)
                Text(
                    lastCrash?.let(::crashSummary) ?: "None recorded",
                    style = Type.Small, color = Palette.mutedInk, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (appVersion != null) {
            Spacer(Modifier.height(8.dp))
            Text("Photos $appVersion", style = Type.Small, color = Palette.mutedInk)
        }
    }
}

/** "2026-11-09 06:49:23 UTC · java.lang.OutOfMemoryError" from a report. */
internal fun crashSummary(report: String): String {
    val lines = report.lines()
    val time = lines.firstOrNull { it.startsWith("Time: ") }?.removePrefix("Time: ")
    // The first line after the blank one that ends the header is the exception.
    val blank = lines.indexOfFirst { it.isBlank() }
    val error = lines.getOrNull(blank + 1)?.takeIf { blank >= 0 }?.substringBefore(':')?.trim()
    return listOfNotNull(time, error).joinToString(" · ").ifEmpty { "Tap to see it" }
}

private fun copyCrashReport(context: Context, report: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Photos crash report", report))
}

private fun shareCrashReport(context: Context, report: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Photos crash report")
        .putExtra(Intent.EXTRA_TEXT, report)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
