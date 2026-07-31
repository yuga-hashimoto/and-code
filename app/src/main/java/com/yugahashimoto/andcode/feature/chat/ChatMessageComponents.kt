package com.yugahashimoto.andcode.feature.chat

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.PermissionRequest
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.ui.theme.AndCodeWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.filled.Image as ImageIcon

private val TOOL_CALL_ECHO_REGEX =
    Regex("""Called the [A-Za-z][A-Za-z ]*? tool with the following input: \{(?:[^{}]|\{[^{}]*\})*\}""")

private fun String.hideToolCallEcho(): String = TOOL_CALL_ECHO_REGEX.replace(this, "").replace(Regex("[ \t]+\n"), "\n").trim()

@Composable
fun MessageBubble(message: ChatMessage) {
    val displayText = remember(message.text) { message.text.hideToolCallEcho() }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = formatClockTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Surface(
                modifier = Modifier.widthIn(max = 340.dp),
                shape = RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    message.imagePreviews.forEach { preview ->
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = stringResource(R.string.cd_image_preview),
                            modifier =
                                Modifier
                                    .widthIn(max = 280.dp)
                                    .heightIn(max = 220.dp)
                                    .padding(bottom = 8.dp),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    message.attachments.forEach { attachment ->
                        if (attachment.mime.startsWith("image/")) return@forEach
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 8.dp),
                        ) {
                            Icon(Icons.Default.Description, contentDescription = stringResource(R.string.cd_attachment))
                            Text(
                                text = attachment.filename,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (displayText.isNotBlank()) {
                        LinkedText(
                            text = displayText,
                            linkColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TimelineEntryRow(
    entry: TimelineEntry,
    onOpenActivity: (String) -> Unit = {},
) {
    when (entry) {
        is TimelineEntry.UserMessage -> MessageBubble(entry.message)
        is TimelineEntry.Body -> MarkdownText(entry.part.text)
        is TimelineEntry.Image -> ImagePartView(entry.part)
        is TimelineEntry.Activity ->
            AssistantActivityRow(
                parts = entry.parts,
                onClick = { onOpenActivity(entry.id) },
            )
        is TimelineEntry.Todo -> TodoTimelineCard(entry.todos)
        is TimelineEntry.Footer -> MessageFooter(entry)
    }
}

@Composable
fun PermissionCard(
    permission: PermissionRequest,
    onPermission: (String, PermissionResponse, Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AndCodeWarning.copy(alpha = 0.10f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = stringResource(R.string.cd_permission), tint = AndCodeWarning)
                Spacer(Modifier.padding(horizontal = 5.dp))
                Text(stringResource(R.string.permission_required), fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = stringResource(R.string.permission_chat_confirmation),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = permission.permission,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            permission.patterns.forEach { pattern ->
                Text(
                    text = pattern,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onPermission(permission.id, PermissionResponse.REJECT, false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.reject))
                }
                FilledTonalButton(
                    onClick = { onPermission(permission.id, PermissionResponse.ONCE, false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.allow_once))
                }
                Button(
                    onClick = { onPermission(permission.id, PermissionResponse.ALWAYS, true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.always_allow))
                }
            }
        }
    }
}

@Composable
private fun InlineText(
    inlines: List<MarkdownInline>,
    style: TextStyle,
    linkColor: Color,
    codeBackground: Color,
    onFilePathClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val annotated =
        remember(inlines, linkColor, codeBackground) {
            annotateFilePaths(renderInline(inlines, codeBackground, linkColor), linkColor)
        }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    SelectionContainer {
        Text(
            text = annotated,
            style = style,
            modifier =
                Modifier.pointerInput(annotated) {
                    detectTapGestures { offset ->
                        layoutResult?.let { layout ->
                            val position = layout.getOffsetForPosition(offset)
                            annotated.getStringAnnotations("link", position, position)
                                .firstOrNull()?.let { ann ->
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ann.item)))
                                    }
                                } ?: annotated.getStringAnnotations("filepath", position, position)
                                .firstOrNull()?.let { onFilePathClick(it.item) }
                        }
                    }
                },
            onTextLayout = { layoutResult = it },
        )
    }
}

@Composable
private fun LinkedText(
    text: String,
    linkColor: Color,
) {
    val context = LocalContext.current
    val style = LocalTextStyle.current
    val inlines = remember(text) { MarkdownLite.parseInline(text) }
    val annotated =
        remember(inlines, linkColor) {
            buildAnnotatedString {
                inlines.forEach { inline ->
                    if (inline is MarkdownInline.Link) {
                        val start = length
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append(inline.text)
                        }
                        addStringAnnotation("link", inline.url, start, length)
                    } else {
                        append(inline.text)
                    }
                }
            }
        }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = style,
        modifier =
            Modifier.pointerInput(annotated) {
                detectTapGestures { offset ->
                    layoutResult?.let { layout ->
                        val position = layout.getOffsetForPosition(offset)
                        annotated.getStringAnnotations("link", position, position)
                            .firstOrNull()?.let { ann ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ann.item)))
                                }
                            }
                    }
                }
            },
        onTextLayout = { layoutResult = it },
    )
}

@Composable
private fun MarkdownText(
    text: String,
    onFilePathClick: (String) -> Unit = {},
) {
    val blocks = remember(text) { MarkdownLite.parse(text) }
    val codeInlineBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val bodyStyle =
        MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading ->
                    InlineText(
                        inlines = block.inlines,
                        style =
                            when (block.level) {
                                1 -> MaterialTheme.typography.titleLarge
                                2 -> MaterialTheme.typography.titleMedium
                                else -> MaterialTheme.typography.titleSmall
                            }.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface),
                        linkColor = linkColor,
                        codeBackground = codeInlineBackground,
                        onFilePathClick = onFilePathClick,
                    )
                is MarkdownBlock.Paragraph ->
                    InlineText(
                        inlines = block.inlines,
                        style = bodyStyle,
                        linkColor = linkColor,
                        codeBackground = codeInlineBackground,
                        onFilePathClick = onFilePathClick,
                    )
                is MarkdownBlock.CodeBlock ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Text(
                            text = block.code,
                            modifier =
                                Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                is MarkdownBlock.BulletList ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items.forEach { item ->
                            Row {
                                Text("•  ", color = MaterialTheme.colorScheme.onSurface)
                                InlineText(item, bodyStyle, linkColor, codeInlineBackground, onFilePathClick)
                            }
                        }
                    }
                is MarkdownBlock.OrderedList ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items.forEachIndexed { index, item ->
                            Row {
                                Text("${index + 1}.  ", color = MaterialTheme.colorScheme.onSurface)
                                InlineText(item, bodyStyle, linkColor, codeInlineBackground, onFilePathClick)
                            }
                        }
                    }
                is MarkdownBlock.Blockquote ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Box(
                            modifier =
                                Modifier.padding(
                                    start = 10.dp,
                                    end = 8.dp,
                                    top = 6.dp,
                                    bottom = 6.dp,
                                ),
                        ) {
                            InlineText(
                                inlines = block.inlines,
                                style = bodyStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                linkColor = linkColor,
                                codeBackground = codeInlineBackground,
                                onFilePathClick = onFilePathClick,
                            )
                        }
                    }
                is MarkdownBlock.Table ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                block.headers.forEach { header ->
                                    Text(
                                        text = header,
                                        modifier =
                                            Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp),
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                            block.rows.forEach { row ->
                                val padded =
                                    row.take(block.headers.size) +
                                        List((block.headers.size - row.size).coerceAtLeast(0)) { "" }
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    padded.forEach { cell ->
                                        Text(
                                            text = cell,
                                            modifier =
                                                Modifier
                                                    .weight(1f)
                                                    .padding(end = 8.dp),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                is MarkdownBlock.HorizontalRule ->
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    )
            }
        }
    }
}

internal data class DataImage(val mime: String, val base64: String)

internal fun parseDataImageUri(url: String): DataImage? {
    if (!url.startsWith("data:")) return null
    val payload = url.removePrefix("data:")
    val comma = payload.indexOf(',')
    if (comma < 0) return null
    val meta = payload.substring(0, comma)
    if (!meta.endsWith(";base64")) return null
    val mime = meta.removeSuffix(";base64")
    if (!mime.startsWith("image/")) return null
    return DataImage(mime, payload.substring(comma + 1))
}

private fun decodeDataImage(url: String): android.graphics.Bitmap? {
    val data = parseDataImageUri(url) ?: return null
    return runCatching {
        val bytes = android.util.Base64.decode(data.base64, android.util.Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

@Composable
private fun ImagePartView(part: ChatPart.Image) {
    val bitmap = remember(part.url) { decodeDataImage(part.url) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = part.filename ?: stringResource(R.string.cd_image_preview),
            modifier =
                Modifier
                    .widthIn(max = 320.dp)
                    .heightIn(max = 320.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(10.dp),
            ) {
                Icon(Icons.Default.ImageIcon, contentDescription = null)
                Text(
                    text = part.filename ?: part.mime,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val FILE_PATH_REGEX = Regex("[\\w./-]+\\.\\w+")

private fun annotateFilePaths(
    source: AnnotatedString,
    linkColor: Color,
): AnnotatedString {
    val excludeRanges =
        source.getStringAnnotations("link", 0, source.text.length) +
            source.getStringAnnotations("code", 0, source.text.length)
    return buildAnnotatedString {
        append(source)
        FILE_PATH_REGEX.findAll(source.text).forEach { match ->
            val overlapsProtected =
                excludeRanges.any { ann ->
                    match.range.first < ann.end && match.range.last + 1 > ann.start
                }
            if (!overlapsProtected) {
                addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    match.range.first,
                    match.range.last + 1,
                )
                addStringAnnotation("filepath", match.value, match.range.first, match.range.last + 1)
            }
        }
    }
}

private fun renderInline(
    inlines: List<MarkdownInline>,
    codeBackground: Color,
    linkColor: Color,
): AnnotatedString =
    buildAnnotatedString {
        inlines.forEach { inline ->
            when (inline) {
                is MarkdownInline.Plain -> append(inline.text)
                is MarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline.text) }
                is MarkdownInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline.text) }
                is MarkdownInline.Strikethrough ->
                    withStyle(
                        SpanStyle(textDecoration = TextDecoration.LineThrough),
                    ) { append(inline.text) }
                is MarkdownInline.Code -> {
                    val start = length
                    withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground),
                    ) { append(inline.text) }
                    addStringAnnotation("code", inline.text, start, length)
                }
                is MarkdownInline.Link -> {
                    val start = length
                    withStyle(
                        SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    ) { append(inline.text) }
                    addStringAnnotation("link", inline.url, start, length)
                }
            }
        }
    }

private val clockTimeFormat = SimpleDateFormat("HH:mm", Locale.US)

private fun formatClockTime(epochMs: Long): String = clockTimeFormat.format(Date(epochMs))

@Composable
private fun MessageFooter(entry: TimelineEntry.Footer) {
    val minutes = entry.durationMs / 60_000L
    val durationLabel =
        when {
            entry.durationMs <= 0L -> null
            minutes < 1L -> stringResource(R.string.chat_response_duration_under_minute)
            else -> stringResource(R.string.chat_response_duration, minutes)
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
    ) {
        if (durationLabel != null) {
            Text(
                text = durationLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.completedAt > 0L) {
            Text(
                text = formatClockTime(entry.completedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
