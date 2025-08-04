import sys, json, datetime, threading, queue, os
from pathlib import Path

from PyQt5.QtWidgets import (QApplication, QMainWindow, QVBoxLayout, QHBoxLayout,
                             QWidget, QTextEdit, QLineEdit, QPushButton, QLabel,
                             QCheckBox, QMessageBox, QSplitter)
from PyQt5.QtCore import Qt, pyqtSignal, QObject
import paramiko
import openai

##############################################################################
# تنظیمات اولیه
##############################################################################
LOG_FILE = Path("session_log.jsonl")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY") or "sk-XXX"
openai.api_key = OPENAI_API_KEY

##############################################################################
# کلاس انتقال سیگنال از Thread به GUI
##############################################################################
class Signaller(QObject):
    new_server_output = pyqtSignal(str)
    new_command = pyqtSignal(str)
    connected = pyqtSignal()
    disconnected = pyqtSignal(str)


##############################################################################
# SSH Handler در Thread جدا
##############################################################################
class SSHThread(threading.Thread):
    def __init__(self, host, user, pwd, port=22):
        super().__init__()
        self.daemon = True
        self.host, self.user, self.pwd, self.port = host, user, pwd, port
        self.client = paramiko.SSHClient()
        self.client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        self.chan = None
        self.in_q = queue.Queue()
        self.running = True
        self.signaller = Signaller()

    def run(self):
        try:
            self.client.connect(self.host, username=self.user,
                                password=self.pwd, port=self.port, timeout=10)
            self.chan = self.client.invoke_shell(term='xterm')
            self.signaller.connected.emit()
            self._io_loop()
        except Exception as e:
            self.signaller.disconnected.emit(str(e))
        finally:
            self.client.close()

    def _io_loop(self):
        while self.running:
            # ارسال هر چیزی که در صف ورودی است
            while not self.in_q.empty():
                cmd = self.in_q.get()
                self.chan.send(cmd.encode())
                # ذخیره دستور در log
                log("USER_COMMAND", cmd.strip())

            # خواندن خروجی سرور
            if self.chan.recv_ready():
                data = self.chan.recv(65535).decode(errors='ignore')
                if data:
                    self.signaller.new_server_output.emit(data)
                    log("SERVER_OUTPUT", data)

    def send_cmd(self, cmd: str):
        if not cmd.endswith("\n"):
            cmd += "\n"
        self.in_q.put(cmd)

    def close(self):
        self.running = False
        if self.client:
            self.client.close()


##############################################################################
# ثبت رویدادها در فایل JSON Lines
##############################################################################
def log(kind: str, text: str):
    with LOG_FILE.open("a", encoding="utf-8") as f:
        f.write(json.dumps({
            "timestamp": datetime.datetime.utcnow().isoformat(),
            "type": kind,
            "text": text
        }, ensure_ascii=False) + "\n")


##############################################################################
# پنجره اصلی
##############################################################################
class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("AI Terminal Assistant")
        self.resize(1000, 700)
        self.ssh_thread = None
        self.init_ui()

    def init_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        vbox = QVBoxLayout(central)

        splitter = QSplitter(Qt.Vertical)
        vbox.addWidget(splitter)

        # ناحیه لاگ
        self.log_widget = QTextEdit()
        self.log_widget.setReadOnly(True)
        splitter.addWidget(self.log_widget)

        # ناحیه AI
        self.ai_group = QWidget()
        ai_layout = QVBoxLayout(self.ai_group)
        ai_layout.addWidget(QLabel("AI Mode (Describe what you want):"))
        self.ai_input = QLineEdit()
        self.ai_input.setPlaceholderText("e.g. list all files larger than 100MB in /var/log")
        ai_layout.addWidget(self.ai_input)

        h = QHBoxLayout()
        self.ai_btn = QPushButton("Generate Command")
        h.addWidget(self.ai_btn)
        self.ai_use = QCheckBox("Enable AI Mode")
        h.addWidget(self.ai_use)
        ai_layout.addLayout(h)

        self.ai_suggestion = QTextEdit()
        self.ai_suggestion.setMaximumHeight(100)
        self.ai_suggestion.setReadOnly(True)
        ai_layout.addWidget(self.ai_suggestion)

        self.ai_confirm = QPushButton("Send to Server")
        self.ai_confirm.setEnabled(False)
        ai_layout.addWidget(self.ai_confirm)
        splitter.addWidget(self.ai_group)

        # خط فرمان عادی
        cmd_layout = QHBoxLayout()
        self.cmd_input = QLineEdit()
        self.cmd_input.setEnabled(False)
        cmd_layout.addWidget(QLabel("Command:"))
        cmd_layout.addWidget(self.cmd_input)
        self.send_btn = QPushButton("Send")
        self.send_btn.setEnabled(False)
        cmd_layout.addWidget(self.send_btn)
        vbox.addLayout(cmd_layout)

        # اتصال سیگنال‌ها
        self.ai_btn.clicked.connect(self.handle_ai_generate)
        self.ai_confirm.clicked.connect(self.handle_ai_confirm)
        self.send_btn.clicked.connect(self.handle_send)
        self.cmd_input.returnPressed.connect(self.handle_send)

        # اتصال اولیه
        self.connect_ssh()

    def connect_ssh(self):
        # می‌توان پنجره لاگین ساخت، اینجا ساده ساخته می‌شود
        host = "localhost"
        user = "root"
        pwd, ok = QInputDialog.getText(self, "SSH Login", "Password:", QLineEdit.Password)
        if not ok:
            sys.exit(0)
        port = 22
        self.append_log("Connecting to {}@{} ...".format(user, host))
        self.ssh_thread = SSHThread(host, user, pwd, port)
        self.ssh_thread.signaller.new_server_output.connect(self.on_server_output)
        self.ssh_thread.signaller.connected.connect(self.on_connected)
        self.ssh_thread.signaller.disconnected.connect(self.on_disconnected)
        self.ssh_thread.start()

    # ---------- GUI Slots ----------
    def on_connected(self):
        self.append_log("Connected.")
        self.cmd_input.setEnabled(True)
        self.send_btn.setEnabled(True)

    def on_disconnected(self, msg):
        self.append_log("Disconnected: " + msg)

    def on_server_output(self, text):
        self.append_log(text, user=False)

    def append_log(self, text, user=True):
        self.log_widget.moveCursor(self.log_widget.textCursor().End)
        self.log_widget.insertPlainText(text)
        self.log_widget.moveCursor(self.log_widget.textCursor().End)

    def handle_send(self):
        cmd = self.cmd_input.text()
        if cmd.strip():
            self.ssh_thread.send_cmd(cmd)
            self.cmd_input.clear()

    def handle_ai_generate(self):
        prompt = self.ai_input.text().strip()
        if not prompt:
            QMessageBox.warning(self, "Warning", "Prompt is empty.")
            return
        self.ai_btn.setEnabled(False)
        self.ai_suggestion.clear()
        threading.Thread(target=self._async_ai_generate, args=(prompt,), daemon=True).start()

    def _async_ai_generate(self, prompt):
        try:
            system_prompt = (
                "You are a Linux shell assistant. "
                "Convert the user's natural language request into a single safe shell command. "
                "Output **only** the raw command, no explanation."
            )
            response = openai.ChatCompletion.create(
                model="gpt-3.5-turbo",
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": prompt}
                ],
                temperature=0
            )
            cmd = response["choices"][0]["message"]["content"].strip()
            self.ai_suggestion.setPlainText(cmd)
            self.ai_confirm.setEnabled(True)
        except Exception as e:
            self.ai_suggestion.setPlainText("Error: " + str(e))
            self.ai_confirm.setEnabled(False)
        finally:
            self.ai_btn.setEnabled(True)

    def handle_ai_confirm(self):
        cmd = self.ai_suggestion.toPlainText().strip()
        if cmd:
            self.ssh_thread.send_cmd(cmd)
            self.ai_suggestion.clear()
            self.ai_confirm.setEnabled(False)
            self.ai_input.clear()

    def closeEvent(self, event):
        if self.ssh_thread and self.ssh_thread.is_alive():
            self.ssh_thread.close()
        event.accept()


##############################################################################
# اجرای برنامه
##############################################################################
if __name__ == "__main__":
    app = QApplication(sys.argv)
    w = MainWindow()
    w.show()
    sys.exit(app.exec_())
