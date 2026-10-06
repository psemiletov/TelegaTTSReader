package com.semiletov.telegattsreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.Instant

data class TelegramPost(
    val id: String,
    val text: String,
    val time: String = "",
    val timestamp: Long = 0L,
    val channelName: String = ""
)

object TelegramParser {

    suspend fun getLatestPosts(channelUrl: String): List<TelegramPost> = withContext(Dispatchers.IO) {
        val posts = mutableListOf<TelegramPost>()
        try {
            // Формируем URL формата https://t.me/s/channel_name для веб-просмотра с cache-busting параметром
            val cleanUrl = channelUrl.trim()
                .replace("https://t.me/", "")
                .replace("t.me/", "")
                .replace("@", "")
                .replace("s/", "")

            val targetUrl = "https://t.me/s/$cleanUrl?_=${System.currentTimeMillis()}"
            val doc: Document = Jsoup.connect(targetUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .timeout(10000)
                .get()

            val channelTitle = doc.selectFirst(".tgme_channel_info_header_title")?.text()
                ?: doc.selectFirst(".tgme_channel_info_title")?.text()
                ?: doc.selectFirst(".tgme_page_title")?.text()
                ?: cleanUrl

            val postElements = doc.select(".tgme_widget_message")
            for (element in postElements) {
                val dataPost = element.attr("data-post") // формат: "channel_name/123"
                val postId = dataPost.substringAfter("/")

                val textElement = element.selectFirst(".tgme_widget_message_text")
                val text = textElement?.text() ?: ""

                val timeElement = element.selectFirst(".tgme_widget_message_date time")
                val datetimeAttr = timeElement?.attr("datetime") ?: ""
                val displayTime = timeElement?.text() ?: ""

                val timestamp = if (datetimeAttr.isNotEmpty()) {
                    try {
                        Instant.parse(datetimeAttr).toEpochMilli()
                    } catch (e: Exception) {
                        0L
                    }
                } else {
                    0L
                }

                if (postId.isNotEmpty() && text.isNotEmpty()) {
                    posts.add(
                        TelegramPost(
                            id = postId,
                            text = text,
                            time = displayTime,
                            timestamp = timestamp,
                            channelName = channelTitle
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext posts
    }
}
