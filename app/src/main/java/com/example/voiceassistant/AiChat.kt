package com.example.voiceassistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to any OpenAI-compatible chat API (Gemini, Groq, OpenRouter...). Key stays on the phone. */
object AiChat {
    const val PREFS = "voice_prefs"
    const val KEY_API = "ai_key"
    const val KEY_URL = "ai_url"
    const val KEY_MODEL = "ai_model"
    const val DEFAULT_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    const val DEFAULT_MODEL = "gemini-2.5-flash"

    fun isConfigured(ctx: Context): Boolean {
        val k = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API, "") ?: ""
        return k.isNotBlank()
    }

    fun hasInternet(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return false
            val c = cm.getNetworkCapabilities(n) ?: return false
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
    }

    /** Blocking call. Run it on a worker thread. Throws on any error. */
    fun ask(ctx: Context, question: String, tamilScript: Boolean): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = (p.getString(KEY_API, "") ?: "").trim()
        if (key.isEmpty()) throw IllegalStateException("API key illa")
        var url = (p.getString(KEY_URL, "") ?: "").trim()
        if (url.isEmpty()) url = DEFAULT_URL
        var model = (p.getString(KEY_MODEL, "") ?: "").trim()
        if (model.isEmpty()) model = DEFAULT_MODEL

        val style = if (tamilScript) "Reply in Tamil script." else "Reply in Tanglish, which is Tamil written with English letters."
        val system = "You are MS, a friendly voice assistant for a Tamil speaker. " +
            "Answer in one to three short spoken sentences. " +
            "No markdown, no lists, no emojis, no asterisks. $style"

        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", system))
        msgs.put(JSONObject().put("role", "user").put("content", question))
        val body = JSONObject()
        body.put("model", model)
        body.put("messages", msgs)
        body.put("max_tokens", 500)
        body.put("temperature", 0.6)

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("AI error $code")

            val out = JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            val clean = out.replace("*", "").replace("#", "").trim()
            if (clean.isEmpty()) throw RuntimeException("AI badhil kaali")
            return clean
        } finally {
            conn.disconnect()
        }
    }
}
