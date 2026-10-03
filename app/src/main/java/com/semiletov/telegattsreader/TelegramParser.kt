package com.semiletov.telegattsreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class TelegramPost(
    val id: String,
    val text: String
)

object TelegramParser {

    suspend fun getLatestPosts(channelUrl: String): List<TelegramPost> = withContext(Dispatchers.IO) {
        val posts = mutableListOf<TelegramPost>()
        try {
            // Формируем URL формата https://t.me/s/channel_name для веб-просмотра
            val cleanUrl = channelUrl.trim()
                .replace("https://t.me/", "")
                .replace("t.me/", "")
                .replace("@", "")
                .replace("s/", "")

            val targetUrl = "https://t.me/s/$cleanUrl"
            val doc: Document = Jsoup.connect(targetUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .timeout(10000)
                .get()

            val postElements = doc.select(".tgme_widget_message")
            for (element in postElements) {
                val dataPost = element.attr("data-post") // формат: "channel_name/123"
                val postId = dataPost.substringAfter("/")

                val textElement = element.selectFirst(".tgme_widget_message_text")
                val text = textElement?.text() ?: ""

                if (postId.isNotEmpty() && text.isNotEmpty()) {
                    posts.add(TelegramPost(id = postId, text = text))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext posts
    }
}
