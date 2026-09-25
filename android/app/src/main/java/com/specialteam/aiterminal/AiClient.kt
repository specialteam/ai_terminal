package com.specialteam.aiterminal

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AiClient(private val apiKey: String, private val model: String = "gpt-4o-mini") {

    /** Blocking; call from a background thread. Returns a single shell command. */
    fun generateCommand(prompt: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(JSONObject().put("role", "user").put("content", prompt)))

        val conn = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) throw RuntimeException("HTTP ${conn.responseCode}: $text")

            return JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
                .replace("```shell", "").replace("```bash", "").replace("```", "")
                .trim()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "You are a Linux shell assistant. " +
            "Convert the user's natural language request into a single safe shell command. " +
            "Output **only** the raw command, no explanation."
    }
}
