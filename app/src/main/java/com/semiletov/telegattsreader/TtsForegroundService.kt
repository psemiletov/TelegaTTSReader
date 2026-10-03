package com.semiletov.telegattsreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class TtsForegroundService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var searchJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        createNotificationChannel()

        val notification = createNotification("Служба чтения Telegram запущена")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        searchJob?.cancel()
        searchJob = serviceScope.launch {
            while (isActive) {
                checkAndReadChannels()
                delay(60000L)
            }
        }
    }

    private suspend fun checkAndReadChannels() {
        val channels: MutableList<Channel> = ChannelStorage.loadChannels(applicationContext)

        for (ch in channels) {
            if (ch.isEnabled) {
                val posts = TelegramParser.getLatestPosts(ch.url)
                val newPosts = if (ch.lastPostId.isEmpty()) {
                    posts.take(1)
                } else {
                    posts.filter { it.id > ch.lastPostId }
                }
                for (p in newPosts) {
                    val channelPrefix = if (p.channelName.isNotBlank()) "Канал ${p.channelName}" else ""
                    val timePrefix = if (p.time.isNotBlank()) " в ${p.time}" else ""
                    val header = buildString {
                        if (channelPrefix.isNotBlank()) append(channelPrefix)
                        if (timePrefix.isNotBlank()) append(timePrefix)
                    }
                    val textToRead = if (header.isNotBlank()) "$header. ${p.text}" else p.text
                    speakText(textToRead)
                }
                if (posts.isNotEmpty()) {
                    val lastPost = posts.last()
                    ch.lastPostId = lastPost.id
                }
            }
        }

        ChannelStorage.saveChannels(applicationContext, channels)
    }

    private fun speakText(text: String) {
        if (!isTtsReady || text.isBlank()) return

        // Динамически определяем язык конкретного поста
        val targetLocale = when {
            text.contains(Regex("[іїєґІЇЄҐ]")) -> Locale.forLanguageTag("uk")
            text.contains(Regex("[ыэъёЫЭЪЁ]")) -> Locale.forLanguageTag("ru")
            else -> tts?.defaultVoice?.locale ?: Locale.getDefault()
        }

        val langResult = tts?.setLanguage(targetLocale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w("TtsService", "Локаль $targetLocale не поддерживается TTS, сброс на дефолт")
            tts?.setLanguage(Locale.getDefault())
        }

        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, System.currentTimeMillis().toString())
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val defaultLang = tts?.defaultVoice?.locale ?: Locale.getDefault()
            val result = tts?.setLanguage(defaultLang)

            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
                Log.d("TtsService", "TTS готов к работе")
            } else {
                // Если дефолтный язык не завелся, делаем фоллбэк на русскую локаль
                val fallbackResult = tts?.setLanguage(Locale.forLanguageTag("ru"))
                if (fallbackResult != TextToSpeech.LANG_MISSING_DATA && fallbackResult != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isTtsReady = true
                }
            }
        } else {
            Log.e("TtsService", "Ошибка инициализации TTS: $status")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "tts_channel",
                "Telegram TTS Reader",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, "tts_channel")
            .setContentTitle("TelegaTTSReader")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    override fun onDestroy() {
        searchJob?.cancel()
        serviceScope.cancel()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}