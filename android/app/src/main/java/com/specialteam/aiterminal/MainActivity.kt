package com.specialteam.aiterminal

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var cmdInput: EditText
    private lateinit var sendBtn: Button
    private lateinit var aiMode: CheckBox
    private lateinit var aiSuggestion: TextView

    private lateinit var log: SessionLog
    private var ssh: SshSession? = null
    private var pendingAiCommand: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        log = SessionLog(File(filesDir, "session_log.jsonl"))

        output = findViewById(R.id.outputView)
        scroll = findViewById(R.id.outputScroll)
        cmdInput = findViewById(R.id.cmdInput)
        sendBtn = findViewById(R.id.sendBtn)
        aiMode = findViewById(R.id.aiMode)
        aiSuggestion = findViewById(R.id.aiSuggestion)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val host = findViewById<EditText>(R.id.hostInput)
        val port = findViewById<EditText>(R.id.portInput)
        val user = findViewById<EditText>(R.id.userInput)
        val pass = findViewById<EditText>(R.id.passInput)
        val apiKey = findViewById<EditText>(R.id.apiKeyInput)
        host.setText(prefs.getString("host", ""))
        port.setText(prefs.getString("port", "22"))
        user.setText(prefs.getString("user", "root"))
        apiKey.setText(prefs.getString("apiKey", ""))

        findViewById<Button>(R.id.connectBtn).setOnClickListener {
            prefs.edit()
                .putString("host", host.text.toString())
                .putString("port", port.text.toString())
                .putString("user", user.text.toString())
                .putString("apiKey", apiKey.text.toString())
                .apply()
            connect(
                host.text.toString().trim(),
                port.text.toString().toIntOrNull() ?: 22,
                user.text.toString().trim(),
                pass.text.toString(),
            )
        }

        aiMode.setOnCheckedChangeListener { _, checked ->
            cmdInput.hint = if (checked) "Describe what you want" else "Command"
            sendBtn.text = if (checked) "Generate" else "Send"
            clearSuggestion()
        }
        sendBtn.setOnClickListener { onSend() }
        cmdInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { onSend(); true } else false
        }
        aiSuggestion.setOnClickListener { confirmAiCommand() }
    }

    private fun connect(host: String, port: Int, user: String, password: String) {
        if (host.isEmpty() || user.isEmpty()) {
            toast("Host and user are required")
            return
        }
        ssh?.close()
        append("Connecting to $user@$host:$port ...\n")
        val session = SshSession(
            log,
            onOutput = { runOnUiThread { append(it) } },
            onDisconnected = { msg -> runOnUiThread { onDisconnected(msg) } },
        )
        ssh = session
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { session.connect(host, port, user, password) }
                append("Connected.\n")
                findViewById<View>(R.id.loginPanel).visibility = View.GONE
                setInputEnabled(true)
            } catch (e: Exception) {
                append("Connection failed: ${e.message}\n")
            }
        }
    }

    private fun onDisconnected(msg: String) {
        append("\nDisconnected: $msg\n")
        setInputEnabled(false)
        findViewById<View>(R.id.loginPanel).visibility = View.VISIBLE
    }

    private fun onSend() {
        val text = cmdInput.text.toString()
        if (text.isBlank()) return
        if (aiMode.isChecked) generate(text.trim()) else sendToServer(text)
    }

    private fun sendToServer(cmd: String) {
        val session = ssh ?: return
        cmdInput.text.clear()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { session.send(cmd) }
                .onFailure { e -> withContext(Dispatchers.Main) { append("Send failed: ${e.message}\n") } }
        }
    }

    private fun generate(prompt: String) {
        val key = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("apiKey", "").orEmpty()
        if (key.isBlank()) {
            toast("Set the OpenAI API key on the login screen")
            return
        }
        sendBtn.isEnabled = false
        showSuggestion("Thinking...", null)
        lifecycleScope.launch {
            try {
                val cmd = withContext(Dispatchers.IO) {
                    log.log("AI_PROMPT", prompt)
                    AiClient(key).generateCommand(prompt).also { log.log("AI_COMMAND", it) }
                }
                showSuggestion("$ $cmd\n(tap to send to server)", cmd)
            } catch (e: Exception) {
                showSuggestion("Error: ${e.message}", null)
            } finally {
                sendBtn.isEnabled = ssh?.isConnected == true
            }
        }
    }

    private fun confirmAiCommand() {
        val cmd = pendingAiCommand ?: return
        sendToServer(cmd)
        clearSuggestion()
    }

    private fun showSuggestion(text: String, cmd: String?) {
        pendingAiCommand = cmd
        aiSuggestion.text = text
        aiSuggestion.visibility = View.VISIBLE
    }

    private fun clearSuggestion() {
        pendingAiCommand = null
        aiSuggestion.visibility = View.GONE
    }

    private fun setInputEnabled(enabled: Boolean) {
        cmdInput.isEnabled = enabled
        sendBtn.isEnabled = enabled
    }

    private fun append(text: String) {
        output.append(text)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        val session = ssh
        Thread { session?.close() }.start()
        super.onDestroy()
    }
}
