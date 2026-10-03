package com.semiletov.telegattsreader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TtsForegroundService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var searchJob: Job? = null

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var focusRequest: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this).apply {
            val attr = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .build()
            setAudioAttributes(attr)

            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    requestAudioFocus()
                }

                override fun onDone(utteranceId: String?) {
                    abandonAudioFocus()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    abandonAudioFocus()
                }
            })
        }
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

    private fun requestAudioFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener {}
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
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
        var updated = false

        for (ch in channels) {
            if (ch.isEnabled) {
                val posts = TelegramParser.getLatestPosts(ch.url)
                if (posts.isNotEmpty()) {
                    val latestPost = posts.last()

                    if (ch.lastPostText.isEmpty()) {
                        ch.lastPostText = latestPost.text
                        updated = true
                    } else if (latestPost.text != ch.lastPostText) {
                        ch.lastPostText = latestPost.text
                        updated = true

                        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                        val channelName = latestPost.channelName.ifBlank { ch.url }

                        val textToRead = "Канал $channelName, в $currentTime. ${latestPost.text}"
                        speakText(textToRead)
                    }
                }
            }
        }

        if (updated) {
            ChannelStorage.saveChannels(applicationContext, channels)
        }
    }

    private fun speakText(text: String) {
        if (!isTtsReady || text.isBlank()) return

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

        val utteranceId = System.currentTimeMillis().toString()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val defaultLang = tts?.defaultVoice?.locale ?: Locale.getDefault()
            val result = tts?.setLanguage(defaultLang)

            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
                Log.d("TtsService", "TTS готов к работе")
            } else {
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
