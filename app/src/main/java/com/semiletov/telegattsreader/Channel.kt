package com.semiletov.telegattsreader

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class Channel(
    val id: String = java.util.UUID.randomUUID().toString(),
    var url: String = "",
    var isEnabled: Boolean = true,
    var lastPostId: String = ""
)

object ChannelStorage {
    private const val PREFS_NAME = "tg_tts_prefs"
    private const val KEY_CHANNELS = "channels"

    fun saveChannels(context: Context, channels: List<Channel>) {
        val jsonArray = JSONArray()
        for (channel in channels) {
            val obj = JSONObject().apply {
                put("id", channel.id)
                put("url", channel.url)
                put("isEnabled", channel.isEnabled)
                put("lastPostId", channel.lastPostId)
            }
            jsonArray.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_CHANNELS, jsonArray.toString()) }
    }

    fun loadChannels(context: Context): MutableList<Channel> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_CHANNELS, null) ?: return mutableListOf()
        val channels = mutableListOf<Channel>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                channels.add(
                    Channel(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        url = obj.optString("url", ""),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        lastPostId = obj.optString("lastPostId", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return channels
    }
}