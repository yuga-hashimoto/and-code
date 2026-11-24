package com.yugahashimoto.andcode.feature.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.CodeFontFamily
import com.yugahashimoto.andcode.ui.theme.SyntaxTheme
import com.yugahashimoto.andcode.ui.theme.syntaxThemeFor
import kotlinx.coroutines.launch

// The code surface stays dark in every app theme: the syntax palettes are all dark-oriented and
// an editor that flips to white would need a full light palette set. Everything drawn on it must
// therefore carry an explicit dark-surface color - unstyled text falls back to onSurface, which
// is near-black in the light theme and vanished against this background.
private val CodeBackground = Color(0xFF282C34)
private val CodeForeground = Color(0xFFDCDFE4)
private val LineNumberColor = Color(0xFF6B7280)
private val MatchHighlight = Color(0x99FFD54F)
private val MatchHighlightForeground = Color(0xFF1A1A1A)

private val TOKEN_REGEX =
    Regex(
        "(//[^\\n]*|/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/)" +
            "|(\"(?:[^\"\\\\]|\\\\.)*\")" +
            "|\\b(val|var|fun|class|import|package|return|if|else|when|for|while)\\b" +
            "|\\b(\\d+(?:\\.\\d+)?[fFL]?)\\b",
    )

/** What to do once the user has agreed to throw away unsaved edits. */
private enum class DiscardThen {
    LEAVE_SCREEN,
    LEAVE_EDITOR,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeViewerScreen(
    state: CodeViewerUiState,
    onBack: () -> Unit,
    syntaxThemeKey: String? = null,
    onStartEditing: () -> Unit = {},
    onDraftChange: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onStopEditing: () -> Unit = {},
) {
    val theme = remember(syntaxThemeKey) { syntaxThemeFor(syntaxThemeKey) }
    val lines = remember(state.content) { state.content.split("\n") }

    var wrap by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var currentMatch by rememberSaveable { mutableIntStateOf(-1) }
    // Not saveable: the draft it asks about lives in the view model, which does not survive the
    // process being killed, so a restored dialog would ask about changes that no longer exist.
    var pendingDiscard by remember { mutableStateOf<DiscardThen?>(null) }

    val codeListState = rememberLazyListState()
    val numbersListState = rememberLazyListState()
    val hScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    val matchLines =
        remember(searchQuery, lines) {
            if (searchQuery.isEmpty()) {
                emptyList()
            } else {
                lines.mapIndexedNotNull { index, line ->
                    index.takeIf { line.contains(searchQuery) }
                }
            }
        }

    LaunchedEffect(codeListState) {
        snapshotFlow { codeListState.firstVisibleItemIndex to codeListState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                numbersListState.scrollToItem(index, offset)
            }
    }

    LaunchedEffect(searchQuery) { currentMatch = -1 }

    // Leaving with edits that were never saved would lose them without a word, so both the system
    // back gesture and the toolbar's back arrow stop for a confirmation first. While the save is in
    // flight neither does anything: the write cannot be taken back, so offering to "discard" then
    // would claim the opposite of what happens.
    BackHandler(enabled = state.hasUnsavedChanges || state.isSaving) {
        if (!state.isSaving) pendingDiscard = DiscardThen.LEAVE_SCREEN
    }
    val leaveScreen: () -> Unit = {
        if (!state.isSaving) {
            if (state.hasUnsavedChanges) {
                pendingDiscard = DiscardThen.LEAVE_SCREEN
            } else {
                onBack()
            }
        }
    }
    val leaveEditor: () -> Unit = {
        if (state.hasUnsavedChanges) {
            pendingDiscard = DiscardThen.LEAVE_EDITOR
        } else {
            onStopEditing()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.filePath.substringAfterLast('/') + if (state.hasUnsavedChanges) UNSAVED_MARK else "") },
                navigationIcon = {
                    IconButton(onClick = leaveScreen) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (state.isEditing) {
                        IconButton(onClick = onSave, enabled = state.hasUnsavedChanges && !state.isSaving) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.file_edit_save))
                        }
                        IconButton(onClick = leaveEditor, enabled = !state.isSaving) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.file_edit_stop))
                        }
                    } else {
                        if (state.canEdit && !state.isLoading) {
                            IconButton(onClick = onStartEditing, enabled = !state.isPreparingEdit) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.file_edit_action))
                            }
                        }
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search))
                        }
                        IconButton(onClick = { wrap = !wrap }) {
                            Icon(Icons.AutoMirrored.Filled.WrapText, contentDescription = stringResource(R.string.cd_toggle_wrap))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.error != null || state.isBinary) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    text = state.error ?: stringResource(R.string.binary_file_no_preview),
                    color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(CodeBackground),
            ) {
                state.editError?.let { EditErrorBanner(it) }
                if (state.isEditing) {
                    CodeEditor(
                        text = state.draft,
                        readOnly = state.isSaving,
                        onTextChange = onDraftChange,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .imePadding()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                } else {
                    if (showSearch) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text(stringResource(R.string.search_label)) },
                                colors =
                                    OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = CodeForeground,
                                        unfocusedTextColor = CodeForeground,
                                        cursorColor = CodeForeground,
                                        focusedLabelColor = LineNumberColor,
                                        unfocusedLabelColor = LineNumberColor,
                                        focusedBorderColor = LineNumberColor,
                                        unfocusedBorderColor = LineNumberColor,
                                    ),
                            )
                            IconButton(
                                onClick = {
                                    if (matchLines.isNotEmpty()) {
                                        currentMatch = (currentMatch + 1) % matchLines.size
                                        val target = matchLines[currentMatch]
                                        scope.launch { codeListState.animateScrollToItem(target) }
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.cd_next_match),
                                    tint = CodeForeground,
                                )
                            }
                        }
                        if (searchQuery.isNotEmpty()) {
                            Text(
                                text =
                                    if (matchLines.isEmpty()) {
                                        stringResource(
                                            R.string.code_no_matches,
                                        )
                                    } else {
                                        stringResource(R.string.code_match_counter, currentMatch + 1, matchLines.size)
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                color = LineNumberColor,
                                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = numbersListState,
                            userScrollEnabled = false,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                        ) {
                            items(lines.size) { index ->
                                Text(
                                    text = (index + 1).toString(),
                                    color = LineNumberColor,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    fontFamily = CodeFontFamily,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.width(40.dp),
                                )
                            }
                        }
                        LazyColumn(
                            state = codeListState,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .then(if (wrap) Modifier else Modifier.horizontalScroll(hScrollState)),
                        ) {
                            items(lines.size) { index ->
                                Text(
                                    text = highlightLine(lines[index], theme, searchQuery),
                                    color = CodeForeground,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    fontFamily = CodeFontFamily,
                                    softWrap = wrap,
                                    maxLines = if (wrap) Int.MAX_VALUE else 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDiscard?.let { action ->
        DiscardChangesDialog(
            onKeepEditing = { pendingDiscard = null },
            onDiscard = {
                pendingDiscard = null
                onStopEditing()
                if (action == DiscardThen.LEAVE_SCREEN) onBack()
            },
        )
    }
}

/**
 * The editing surface: one text field over the whole file.
 *
 * It holds its own [TextFieldValue] and only reports the text upward. Handing the field a plain
 * string from state that arrives a frame late loses the cursor and any IME composition in progress
 * (Japanese input, for one), so selection and composition stay local and the view model sees text.
 * Wrapping is always on: the text field has no unwrapped multi-line mode.
 */
@Composable
private fun CodeEditor(
    text: String,
    readOnly: Boolean,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var field by remember { mutableStateOf(TextFieldValue(text)) }
    BasicTextField(
        value = field,
        onValueChange = { next ->
            // Against what this field showed a moment ago, not the view model's copy: that one is a
            // frame behind, and an edit that happens to restore it would never be reported.
            val changed = next.text != field.text
            field = next
            if (changed) onTextChange(next.text)
        },
        modifier = modifier,
        readOnly = readOnly,
        // Code, paths and secrets: autocorrect and auto-capitalisation would quietly rewrite them.
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        textStyle =
            TextStyle(
                color = CodeForeground,
                fontFamily = CodeFontFamily,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
        cursorBrush = SolidColor(CodeForeground),
    )
}

@Composable
private fun EditErrorBanner(message: String) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.bodySmall,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun DiscardChangesDialog(
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.file_edit_discard_title)) },
        text = { Text(stringResource(R.string.file_edit_discard_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.file_edit_discard_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) { Text(stringResource(R.string.file_edit_keep_editing)) }
        },
    )
}

/**
 * Longest line that is syntax-highlighted.
 *
 * Lines are highlighted while composing, on the main thread, and a minified file can put a megabyte
 * on one line. A Crashlytics ANR had the main thread in `TOKEN_REGEX.findAll(line)`; that the line
 * was this long is inferred from the stack, not reproduced. The regex scans the whole line, and a
 * string or block comment that is never closed (an unterminated string with many escaped quotes, or
 * many comment openers) makes it rescan to the end of the line from each start, and every match then
 * becomes a styled span. A longer line is shown unstyled, with neither the regex nor the spans.
 * Search matches on it are still marked, up to [MAX_SEARCH_MARKS_ON_LONG_LINE].
 */
internal const val MAX_HIGHLIGHTED_LINE_CHARS = 2_000

/**
 * Most search matches marked on a line past [MAX_HIGHLIGHTED_LINE_CHARS]. Each mark is a span, and
 * a one-character query on a megabyte line has hundreds of thousands of matches.
 *
 * It equals the line limit, which a one-character query already reaches on an ordinary line, so the
 * number of marks does not step down as a line grows past the limit. Matches beyond it are not
 * marked, wrapped or not; the line still counts as a match for navigation.
 */
internal const val MAX_SEARCH_MARKS_ON_LONG_LINE = MAX_HIGHLIGHTED_LINE_CHARS

internal fun highlightLine(
    line: String,
    theme: SyntaxTheme,
    searchQuery: String,
): AnnotatedString =
    buildAnnotatedString {
        if (line.length <= MAX_HIGHLIGHTED_LINE_CHARS) appendHighlighted(line, theme) else append(line)
        if (searchQuery.isNotEmpty()) {
            val markLimit = if (line.length > MAX_HIGHLIGHTED_LINE_CHARS) MAX_SEARCH_MARKS_ON_LONG_LINE else Int.MAX_VALUE
            var marks = 0
            var idx = line.indexOf(searchQuery)
            while (idx >= 0 && marks < markLimit) {
                addStyle(
                    SpanStyle(background = MatchHighlight, color = MatchHighlightForeground),
                    idx,
                    idx + searchQuery.length,
                )
                marks++
                idx = line.indexOf(searchQuery, idx + searchQuery.length)
            }
        }
    }

private fun AnnotatedString.Builder.appendHighlighted(
    line: String,
    theme: SyntaxTheme,
) {
    var last = 0
    for (match in TOKEN_REGEX.findAll(line)) {
        if (match.range.first > last) append(line.substring(last, match.range.first))
        val style =
            when {
                match.groups[1] != null -> SpanStyle(color = theme.comment)
                match.groups[2] != null -> SpanStyle(color = theme.string)
                match.groups[3] != null -> SpanStyle(color = theme.keyword)
                else -> SpanStyle(color = theme.number)
            }
        withStyle(style) { append(match.value) }
        last = match.range.last + 1
    }
    if (last < line.length) append(line.substring(last))
}

/** Appended to the file name while there are edits that have not been saved. */
private const val UNSAVED_MARK = " •"
