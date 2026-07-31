package com.yugahashimoto.andcode.feature.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yugahashimoto.andcode.R

private val TerminalBackground = Color(0xFF1A1A2E)
private val InputColor = Color(0xFF4EC9B0)
private val OutputColor = Color(0xFFD4D4D4)
private val ErrorColor = Color(0xFFF44747)
private val SystemColor = Color(0xFFDCDCAA)

@Composable
fun TerminalScreen(
    state: TerminalUiState,
    onCommand: (String) -> Unit,
    onInputChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) {
            listState.animateScrollToItem(state.lines.size - 1)
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(TerminalBackground)
                .navigationBarsPadding()
                .imePadding(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = stringResource(R.string.cd_clear_terminal),
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            state = listState,
        ) {
            items(state.lines) { line ->
                Text(
                    text = line.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color =
                        when (line.type) {
                            TerminalLineType.INPUT -> InputColor
                            TerminalLineType.OUTPUT -> OutputColor
                            TerminalLineType.ERROR -> ErrorColor
                            TerminalLineType.SYSTEM -> SystemColor
                        },
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${state.workingDirectory} $",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = InputColor,
            )
            TextField(
                value = state.currentInput,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color.White,
                    ),
                enabled = !state.isRunning,
            )
            IconButton(
                onClick = { onCommand(state.currentInput) },
                enabled = state.currentInput.isNotBlank() && !state.isRunning,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.cd_send_command),
                    tint = InputColor,
                )
            }
        }
    }
}
