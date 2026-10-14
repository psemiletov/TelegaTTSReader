package com.semiletov.telegattsreader

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.semiletov.telegattsreader.ui.theme.TelegaTTSReaderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startTtsService()
        setContent {
            TelegaTTSReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onStartService = { startTtsService() },
                        onStopService = { stopTtsService() }
                    )
                }
            }
        }
    }

    private fun startTtsService() {
        val intent = Intent(this, TtsForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopTtsService() {
        val intent = Intent(this, TtsForegroundService::class.java)
        stopService(intent)
    }
}

fun formatChannelUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        return trimmed
    }
    if (trimmed.startsWith("t.me/") || trimmed.startsWith("tg.me/")) {
        return "https://$trimmed"
    }
    val clean = trimmed.removePrefix("@")
    return "https://tg.me/$clean"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onStartService: () -> Unit, onStopService: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val channels = remember { mutableStateListOf<Channel>().apply { addAll(ChannelStorage.loadChannels(context)) } }
    var newUrl by remember { mutableStateOf("") }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val loaded = ChannelStorage.loadChannels(context)
                channels.clear()
                channels.addAll(loaded)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title_main)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    label = { Text(stringResource(R.string.hint_url)) },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            if (!focusState.isFocused) {
                                newUrl = formatChannelUrl(newUrl)
                            }
                        },
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    val formatted = formatChannelUrl(newUrl)
                    if (formatted.isNotBlank()) {
                        val ch = Channel(url = formatted)
                        channels.add(ch)
                        ChannelStorage.saveChannels(context, channels)
                        newUrl = ""
                    }
                }) {
                    Text(stringResource(R.string.btn_add))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = onStartService) {
                    Text(stringResource(R.string.btn_start_service))
                }
                OutlinedButton(onClick = onStopService) {
                    Text(stringResource(R.string.btn_stop_service))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            var speakChannelName by remember {
                mutableStateOf(
                    context.getSharedPreferences("tg_tts_prefs", Context.MODE_PRIVATE)
                        .getBoolean("speak_channel_name", true)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.switch_speak_channel), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = speakChannelName,
                    onCheckedChange = { checked ->
                        speakChannelName = checked
                        context.getSharedPreferences("tg_tts_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("speak_channel_name", checked)
                            .apply()
                    }
                )
            }

            var speakTime by remember {
                mutableStateOf(
                    context.getSharedPreferences("tg_tts_prefs", Context.MODE_PRIVATE)
                        .getBoolean("speak_time", true)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.switch_speak_time), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = speakTime,
                    onCheckedChange = { checked ->
                        speakTime = checked
                        context.getSharedPreferences("tg_tts_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("speak_time", checked)
                            .apply()
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(channels, key = { it.id }) { channel ->
                    ChannelItem(
                        channel = channel,
                        onToggle = { isChecked ->
                            channel.isEnabled = isChecked
                            if (isChecked) {
                                channel.lastPostId = ""
                                channel.lastPostText = ""
                            }
                            ChannelStorage.saveChannels(context, channels)
                        },
                        onUrlChanged = { updatedUrl ->
                            channel.url = updatedUrl.trim()
                            channel.lastPostId = ""
                            channel.lastPostText = ""
                            ChannelStorage.saveChannels(context, channels)
                        },
                        onDelete = {
                            channels.remove(channel)
                            ChannelStorage.saveChannels(context, channels)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ChannelItem(
    channel: Channel,
    onToggle: (Boolean) -> Unit,
    onUrlChanged: (String) -> Unit,
    onDelete: () -> Unit
) {
    var checked by remember { mutableStateOf(channel.isEnabled) }
    var urlText by remember(channel.id) { mutableStateOf(channel.url) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            OutlinedTextField(
                value = urlText,
                onValueChange = {
                    urlText = it
                    onUrlChanged(it)
                },
                label = { Text(stringResource(R.string.hint_url)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused) {
                            val formatted = formatChannelUrl(urlText)
                            if (formatted != urlText) {
                                urlText = formatted
                                onUrlChanged(formatted)
                            }
                        }
                    },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (channel.lastPostText.isNotEmpty()) stringResource(R.string.status_active) else stringResource(R.string.status_waiting),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = checked,
                        onCheckedChange = {
                            checked = it
                            onToggle(it)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onDelete) {
                        Text("❌")
                    }
                }
            }
        }
    }
}
