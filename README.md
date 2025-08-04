AI Terminal Assistant – English README
=====================================

A lightweight, GUI-based SSH client written in Python/PyQt5 that behaves like a minimal PuTTY clone, but adds an **AI-powered command generator** layer on top.

Key Features
------------
1. **Pure-Python SSH**  
   Uses Paramiko to open an interactive shell channel (`xterm`) and keeps the session alive in a background thread. All user commands and server responses are streamed into a scrollable QTextEdit.

2. **Persistent Session Log**  
   Every line typed by the user and every byte returned by the server is appended to `session_log.jsonl` (JSON Lines) with a UTC timestamp.  
   Example record:  
   ```
   {"timestamp":"2025-08-05T17:43:12Z","type":"USER_COMMAND","text":"ls -l /var/log\n"}
   {"timestamp":"2025-08-05T17:43:12Z","type":"SERVER_OUTPUT","text":"total 876\n-rw-r--r-- 1 root root  12345 Aug  5 17:40 syslog ..."}
   ```

3. **AI Mode**  
   • Activate the checkbox **“Enable AI Mode”**.  
   • Describe the task in plain English, e.g.  
     *“find every file larger than 100 MB under /var/log”*  
   • The assistant calls OpenAI’s Chat Completions API (`gpt-3.5-turbo`) with a system prompt that forces the model to return **only** a single, safe shell command.  
   • The generated command is displayed in a small preview box; the user must explicitly press **“Send to Server”** to execute it.  
   • Both the original prompt and the AI-generated command are also stored in the same JSONL file.

4. **Thread-Safe UI Updates**  
   All network I/O and AI calls run in separate threads; PyQt signals keep the GUI responsive.

Quick Start
-----------
1. Install dependencies  
   ```
   pip install PyQt5 paramiko openai
   ```

2. Export your OpenAI key (or edit the file)  
   ```
   export OPENAI_API_KEY="sk-XXX"
   ```

3. Run  
   ```
   python main.py
   ```

4. In the pop-up dialog enter the SSH password (defaults to localhost, user root; change code for other hosts).

5. Use the lower *Command* box for normal usage or switch to *AI Mode* for natural-language assistance.

Extending / Roadmap
-------------------
• Login dialog with host, port, key-file support  
• Colored terminal emulation with ANSI escape parsing  
• Tabbed sessions  
• Full-text search in the log viewer  
• Security gate: block destructive commands (`rm -rf /`, `dd`, etc.) before execution  
• Export session as HTML/PDF  
• Local LLM fallback (e.g. Ollama)

License
-------
MIT – do whatever you want.
