package com.lumina.reader.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    viewModel: AiChatViewModel,
    onBack: () -> Unit,
    onDownloadAction: (String) -> Unit,
    onOrganizeAction: (String, List<String>) -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.actionFlow.collectLatest { action ->
            when (action) {
                is AiAction.DownloadBook -> onDownloadAction(action.query)
                is AiAction.OrganizeSeries -> onOrganizeAction(action.seriesName, action.books)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ИИ Ассистент") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Напишите команду...") },
                    shape = RoundedCornerShape(20.dp),
                    maxLines = 3
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    },
                    enabled = inputText.isNotBlank() && !isLoading,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Отправить", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = true
            ) {
                // Ignore system prompt, show only user and assistant
                val displayMessages = messages.filter { it.role != "system" }.reversed()
                
                items(displayMessages) { msg ->
                    val isUser = msg.role == "user"
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.widthIn(max = 300.dp)
                        ) {
                            // Filter out internal commands from display
                            val displayText = msg.content
                                .replace(Regex("\\[DOWNLOAD:.*?\\]"), "")
                                .replace(Regex("\\[ORGANIZE:.*?\\]"), "")
                                .trim()

                            if (displayText.isNotEmpty()) {
                                Text(
                                    text = displayText,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
