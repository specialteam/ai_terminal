package com.specialteam.aiterminal

import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Appends JSON Lines records, same format as the desktop session_log.jsonl. */
class SessionLog(private val file: File) {
    @Synchronized
    fun log(kind: String, text: String) {
        val record = JSONObject()
            .put("timestamp", Instant.now().toString())
            .put("type", kind)
            .put("text", text)
        file.appendText(record.toString() + "\n")
    }
}
