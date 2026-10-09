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

    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var focusRequest: AudioFocusRequest? = null
    private var abandonJob: Job? = null

    // Флаг для отслеживания первой проверки после запуска службы (игнорируем накопившуюся офлайн-историю)
    private var isFirstCheckAfterStart = true

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this).apply {
            val attr = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            setAudioAttributes(attr)

            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    requestAudioFocus()
                }

                override fun onDone(utteranceId: String?) {
                    scheduleAbandonAudioFocus()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    scheduleAbandonAudioFocus()
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
        abandonJob?.cancel() // Отменяем отложенный сброс фокуса, если началось следующее сообщение
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        abandonAudioFocus()
                    }
                }
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { _ -> },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun scheduleAbandonAudioFocus() {
        abandonJob?.cancel()
        abandonJob = serviceScope.launch {
            delay(500L) // Ждем полсекунды на случай, если в очереди есть следующее сообщение
            abandonAudioFocus()
        }
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
                    val latestPost = posts.maxByOrNull { it.id.toLongOrNull() ?: 0L } ?: posts.last()

                    if (isFirstCheckAfterStart) {
                        // При запуске службы полностью инициализируем стартовый ID,
                        // чтобы старые сообщения не озвучивались.
                        ch.lastPostId = latestPost.id
                        ch.lastPostText = latestPost.text
                        updated = true
                    } else if (ch.lastPostId.isEmpty() && ch.lastPostText.isEmpty()) {
                        ch.lastPostId = latestPost.id
                        ch.lastPostText = latestPost.text
                        updated = true
                    } else {
                        val lastIdNum = ch.lastPostId.toLongOrNull() ?: 0L
                        val newPosts = posts.filter { post ->
                            val postIdNum = post.id.toLongOrNull() ?: 0L
                            postIdNum > lastIdNum
                        }.sortedBy { it.id.toLongOrNull() ?: 0L }

                        if (newPosts.size > 3) {
                            // Защита от зачитывания большой "истории" (если накопилось больше 3 постов)
                            val absoluteLatest = posts.maxByOrNull { it.id.toLongOrNull() ?: 0L } ?: newPosts.last()
                            ch.lastPostId = absoluteLatest.id
                            ch.lastPostText = absoluteLatest.text
                            updated = true
                        } else {
                            val prefs = applicationContext.getSharedPreferences("tg_tts_prefs", Context.MODE_PRIVATE)
                            val speakChannel = prefs.getBoolean("speak_channel_name", true)
                            val speakTime = prefs.getBoolean("speak_time", true)

                            for (p in newPosts) {
                                val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                                val channelName = p.channelName.ifBlank { ch.url }

                                val textToRead = when {
                                    speakChannel && speakTime -> "Канал $channelName, в $currentTime. ${p.text}"
                                    speakChannel && !speakTime -> "Канал $channelName. ${p.text}"
                                    !speakChannel && speakTime -> "В $currentTime. ${p.text}"
                                    else -> p.text
                                }
                                speakText(textToRead, p.id)
                            }

                            if (newPosts.isNotEmpty()) {
                                val absoluteLatest = posts.maxByOrNull { it.id.toLongOrNull() ?: 0L } ?: newPosts.last()
                                ch.lastPostId = absoluteLatest.id
                                ch.lastPostText = absoluteLatest.text
                                updated = true
                            }
                        }
                    }
                }
            }
        }

        if (isFirstCheckAfterStart) {
            isFirstCheckAfterStart = false
            updated = true
        }

        if (updated) {
            ChannelStorage.saveChannels(applicationContext, channels)
        }
    }

    private fun speakText(text: String, postId: String = "") {
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

        val utteranceId = "msg_${postId}_${System.nanoTime()}"
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
