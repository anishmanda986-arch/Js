@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jarvis.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Debug
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jarvis.ai.data.AppDatabase
import com.jarvis.ai.data.ConversationEntity
import com.jarvis.ai.data.MessageEntity
import com.jarvis.ai.provider.LocalAIProvider
import com.jarvis.ai.voice.VoiceManager
import com.jarvis.ai.voice.VoiceState
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private const val JARVIS_SYSTEM_PROMPT =
    "You are JARVIS, a sharp, concise, and unfailingly helpful AI assistant running fully offline on the user's device. " +
    "Speak naturally and get to the point. Keep answers tight unless the user asks for depth."

private data class ChatMessage(val id: String, val text: String, val user: Boolean)

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val provider = remember { LocalAIProvider(context.applicationContext) }
    val dao = remember { AppDatabase.getDatabase(context.applicationContext).chatDao() }
    var powerMode by remember { mutableStateOf("BALANCED") }
    var voiceState by remember { mutableStateOf(VoiceState.IDLE) }
    var screen by remember { mutableStateOf("HOME") }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var lastTps by remember { mutableDoubleStateOf(0.0) }
    var status by remember { mutableStateOf("Ready") }
    var modelReady by remember { mutableStateOf(false) }

    // A single ongoing conversation persisted across app restarts.
    val conversationId = remember { "default" }
    LaunchedEffect(Unit) {
        dao.insertConversation(ConversationEntity(id = conversationId, title = "JARVIS Session"))
        val saved = dao.getRecentMessages(conversationId, limit = 200).sortedBy { it.timestamp }
        if (saved.isNotEmpty()) {
            messages = saved.map { ChatMessage(it.id, it.text, it.isUser) }
        }
    }

    fun persist(msg: ChatMessage) {
        scope.launch {
            dao.insertMessage(
                MessageEntity(
                    id = msg.id,
                    conversationId = conversationId,
                    sender = if (msg.user) "user" else "jarvis",
                    text = msg.text,
                    isUser = msg.user
                )
            )
        }
    }

    fun sendMessage(text: String) {
        val userMsg = ChatMessage(java.util.UUID.randomUUID().toString(), text, true)
        messages = messages + userMsg
        persist(userMsg)
        scope.launch {
            generate(provider, text, messages) { updated, tps -> messages = updated; lastTps = tps }
            messages.lastOrNull { !it.user }?.let { persist(it) }
        }
    }

    val voice = remember {
        VoiceManager(context, { voiceState = it }, { text ->
            screen = "CHAT"
            sendMessage(text)
        }, { status = it })
    }

    DisposableEffect(Unit) {
        onDispose { voice.destroy(); provider.unloadModel() }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.startListening() else status = "Microphone permission denied"
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("JARVIS", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = { screen = "MODEL" }) { Text("MODEL") }
                    TextButton(onClick = { screen = "DIAG" }) { Text("DIAG") }
                })
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (screen) {
                "CHAT" -> ChatScreen(messages, modelReady, status, onBack = { screen = "HOME" }, onSend = ::sendMessage, onStop = provider::stopGeneration, onClear = {
                    messages = emptyList()
                    scope.launch { dao.clearConversation(conversationId) }
                })
                "MODEL" -> ModelScreen(modelReady, provider.loadedModelUri, status, powerMode, { powerMode = it }, { uri ->
                    val ctx = when (powerMode) { "PERFORMANCE" -> 2048; "BATTERY SAVER" -> 1024; else -> 1536 }
                    scope.launch {
                        status = "Loading model…"
                        val ok = provider.initialize(uri, ctx)
                        modelReady = ok
                        status = if (ok) "LOCAL MODEL READY" else "Model load failed — check it's a valid .gguf file"
                    }
                }, { provider.unloadModel(); modelReady = false; status = "Model unloaded" }, { screen = "HOME" })
                "DIAG" -> Diagnostics(modelReady, lastTps, voice.isRecognitionAvailable(), onBack = { screen = "HOME" })
                else -> HomeScreen(modelReady, powerMode, voiceState, status,
                    onChat = { screen = "CHAT" },
                    onVoice = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.startListening()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }, onModel = { screen = "MODEL" })
            }
        }
    }
}

private suspend fun generate(provider: LocalAIProvider, prompt: String, current: List<ChatMessage>, update: (List<ChatMessage>, Double) -> Unit) {
    if (!provider.isInitialized) return
    val history = current.takeLast(8).joinToString("\n") { if (it.user) "User: ${it.text}" else "JARVIS: ${it.text}" }
    val fullPrompt = "$JARVIS_SYSTEM_PROMPT\n\n$history\nJARVIS:"
    var answer = ""
    var start = System.nanoTime()
    var count = 0
    val replyId = java.util.UUID.randomUUID().toString()
    provider.generateResponse(fullPrompt).catch { }.collect { token ->
        if (count == 0) start = System.nanoTime()
        answer += token
        count++
        val sec = (System.nanoTime() - start) / 1_000_000_000.0
        val tps = if (sec > 0) count / sec else 0.0
        val base = current.filterNot { !it.user && it.text == "" }
        update(base + ChatMessage(replyId, answer, false), tps)
    }
}

@Composable
private fun HomeScreen(ready: Boolean, mode: String, state: VoiceState, status: String, onChat: () -> Unit, onVoice: () -> Unit, onModel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(28.dp))
            Text(if (ready) "LOCAL AI READY" else "NO MODEL LOADED", color = if (ready) Color(0xFF00E676) else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(30.dp))
            OrbView(state, mode)
            Text("$mode • $status", color = Color.Gray, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = onChat) { Text("Chat") }
            Button(onClick = onVoice) { Text("Voice") }
            OutlinedButton(onClick = onModel) { Text("Model") }
        }
    }
}

@Composable
private fun ChatScreen(messages: List<ChatMessage>, ready: Boolean, status: String, onBack: () -> Unit, onSend: (String) -> Unit, onStop: () -> Unit, onClear: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Home") }
            Text(if (ready) "LOCAL" else "NO MODEL", fontSize = 12.sp)
            TextButton(onClick = onClear) { Text("Clear") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { msg ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.user) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (msg.user) Color(0xFF1E2638) else Color(0xFF161B26), shape = RoundedCornerShape(14.dp)) {
                        Text(msg.text, Modifier.padding(12.dp), color = Color.White)
                    }
                }
            }
        }
        Text(status, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Command JARVIS…") })
            Spacer(Modifier.width(6.dp))
            Button(onClick = { if (input.isNotBlank()) { onSend(input.trim()); input = "" } }) { Text("Send") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))) { Text("Stop") }
        }
    }
}

@Composable
private fun ModelScreen(ready: Boolean, loadedUri: Uri?, status: String, mode: String, onMode: (String) -> Unit, onPick: (Uri) -> Unit, onUnload: () -> Unit, onBack: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(onPick) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Home") }
        Text("Model & Power", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("BATTERY SAVER", "BALANCED", "PERFORMANCE").forEach { m -> FilterChip(mode == m, { onMode(m) }, label = { Text(m, fontSize = 10.sp) }) }
        }
        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("GGUF Local Model", fontWeight = FontWeight.Bold)
                Text(if (ready) "Ready: $loadedUri" else "Select a .gguf model from storage", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                Row {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Select GGUF") }
                    if (ready) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onUnload) { Text("Unload") }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Battery optimisation: model is memory-mapped, inference is CPU-only arm64, and the UI animation is reduced in Battery Saver.", color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun Diagnostics(ready: Boolean, tps: Double, stt: Boolean, onBack: () -> Unit) {
    val info = remember { Debug.MemoryInfo() }
    Debug.getMemoryInfo(info)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Home") }
        Text("Diagnostics", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Metric("Native model", if (ready) "READY" else "NOT LOADED")
        Metric("Process PSS", "${info.totalPss / 1024} MB")
        Metric("Measured generation", "%.2f tok/s".format(tps))
        Metric("Offline-capable STT", if (stt) "AVAILABLE" else "UNAVAILABLE")
        Metric("ABI", "arm64-v8a")
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, color = Color.Gray, fontSize = 11.sp)
            Text(value, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
    }
}
