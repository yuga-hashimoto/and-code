package com.yugahashimoto.andcode.feature.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.ui.theme.SyntaxTheme
import com.yugahashimoto.andcode.ui.theme.syntaxThemeFor
import kotlinx.coroutines.launch

private val CodeBackground = Color(0xFF282C34)
private val LineNumberColor = Color(0xFF6B7280)
private val MatchHighlight = Color(0x99FFD54F)

private val TOKEN_REGEX =
    Regex(
        "(//[^\\n]*|/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/)" +
            "|(\"(?:[^\"\\\\]|\\\\.)*\")" +
            "|\\b(val|var|fun|class|import|package|return|if|else|when|for|while)\\b" +
            "|\\b(\\d+(?:\\.\\d+)?[fFL]?)\\b",
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeViewerScreen(
    state: CodeViewerUiState,
    onBack: () -> Unit,
    syntaxThemeKey: String? = null,
) {
    val theme = remember(syntaxThemeKey) { syntaxThemeFor(syntaxThemeKey) }
    val lines = remember(state.content) { state.content.split("\n") }

    var wrap by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var currentMatch by rememberSaveable { mutableIntStateOf(-1) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.filePath.substringAfterLast('/')) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search))
                    }
                    IconButton(onClick = { wrap = !wrap }) {
                        Icon(Icons.AutoMirrored.Filled.WrapText, contentDescription = stringResource(R.string.cd_toggle_wrap))
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
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.cd_next_match))
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
                                fontFamily = FontFamily.Monospace,
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
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = FontFamily.Monospace,
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

private fun highlightLine(
    line: String,
    theme: SyntaxTheme,
    searchQuery: String,
): AnnotatedString =
    buildAnnotatedString {
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
        if (searchQuery.isNotEmpty()) {
            var idx = line.indexOf(searchQuery)
            while (idx >= 0) {
                addStyle(SpanStyle(background = MatchHighlight), idx, idx + searchQuery.length)
                idx = line.indexOf(searchQuery, idx + searchQuery.length)
            }
        }
    }
