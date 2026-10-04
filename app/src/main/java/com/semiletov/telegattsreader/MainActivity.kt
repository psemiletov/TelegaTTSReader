package com.semiletov.telegattsreader

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
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startTtsService()
        setContent {
            MaterialTheme {
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
        topBar = { TopAppBar(title = { Text("Telega TTS Reader") }) }
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
                    label = { Text("URL или @имя_канала") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    if (newUrl.isNotBlank()) {
                        val ch = Channel(url = newUrl.trim())
                        channels.add(ch)
                        ChannelStorage.saveChannels(context, channels)
                        newUrl = ""
                    }
                }) {
                    Text("Добавить")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = onStartService) {
                    Text("Запустить службу")
                }
                OutlinedButton(onClick = onStopService) {
                    Text("Остановить")
                }
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
                            ChannelStorage.saveChannels(context, channels)
                        },
                        onUrlChanged = { newUrl ->
                            channel.url = newUrl.trim()
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
                label = { Text("URL канала") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (channel.lastPostText.isNotEmpty()) "Активен" else "Ожидание...",
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