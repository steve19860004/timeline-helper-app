package tw.local.timeline.core

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.InputStream

/** Shizuku 包裝：以 adb shell 身分執行指令（免 root）。 */
object Shell {
    data class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
        override fun toString() = (out + (if (err.isNotBlank()) "\n" + err else "")).trim()
    }

    const val REQ_CODE = 7001
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    private val permListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQ_CODE) {
            val granted = result == PackageManager.PERMISSION_GRANTED
            listeners.toList().forEach { it(granted) }
            listeners.clear()
        }
    }

    fun init() {
        runCatching { Shizuku.addRequestPermissionResultListener(permListener) }
    }

    /** Shizuku 服務是否在跑 */
    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Shizuku 已在跑且已授權本 App */
    fun ready(): Boolean = running() && runCatching {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun statusText(): String = when {
        !running() -> "Shizuku 狀態：未連線（請先開啟 Shizuku App 並啟動服務）"
        !ready() -> "Shizuku 狀態：已連線（尚未授權本 App）"
        else -> "Shizuku 狀態：已連線並授權"
    }

    fun request(cb: (Boolean) -> Unit) {
        if (!running()) { cb(false); return }
        if (ready()) { cb(true); return }
        listeners.add(cb)
        runCatching { Shizuku.requestPermission(REQ_CODE) }.onFailure { listeners.remove(cb); cb(false) }
    }

    private fun newProcess(cmd: String): Process {
        // Shizuku API 13 將 newProcess 設為 private，官方建議用 UserService；這裡用反射呼叫最簡單
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    }

    /**
     * 等待程序結束。不能用 Process.waitFor(timeout, unit)：它的預設實作靠 exitValue() 丟
     * IllegalThreadStateException 判斷「還在跑」，但 Shizuku 遠端程序跨 binder 丟的是別種例外，
     * 會導致每個指令都被當成失敗。改用阻塞的 waitFor() 放在另一條執行緒，逾時就砍掉。
     * 回傳結束碼；逾時回傳 null。
     */
    private fun waitExit(p: Process, timeoutSec: Long): Int? {
        var code: Int? = null
        val t = Thread { code = runCatching { p.waitFor() }.getOrNull() }
        t.start()
        t.join(timeoutSec * 1000)
        if (t.isAlive) {
            runCatching { p.destroy() }
            return null
        }
        return code
    }

    /** 同步執行（請在背景執行緒呼叫） */
    fun exec(cmd: String, timeoutSec: Long = 20): Result {
        if (!ready()) return Result(-1, "", "Shizuku 未連線或未授權")
        return try {
            val p = newProcess(cmd)
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }
            val t2 = Thread { runCatching { err.append(p.errorStream.bufferedReader().readText()) } }
            t1.start(); t2.start()
            val code = waitExit(p, timeoutSec)
            t1.join(2000); t2.join(2000)
            Result(code ?: -2, out.toString().trim(), err.toString().trim().ifEmpty { if (code == null) "逾時" else "" })
        } catch (e: Throwable) {
            Result(-1, "", e.toString())
        }
    }

    /** 把資料串流餵給指令的 stdin（用於 pm install -S） */
    fun execWithStdin(cmd: String, input: InputStream, timeoutSec: Long = 180): Result {
        if (!ready()) return Result(-1, "", "Shizuku 未連線或未授權")
        return try {
            val p = newProcess(cmd)
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread { runCatching { out.append(p.inputStream.bufferedReader().readText()) } }
            val t2 = Thread { runCatching { err.append(p.errorStream.bufferedReader().readText()) } }
            t1.start(); t2.start()
            p.outputStream.use { os -> input.use { it.copyTo(os, 64 * 1024) } }
            val code = waitExit(p, timeoutSec)
            t1.join(2000); t2.join(2000)
            Result(code ?: -2, out.toString().trim(), err.toString().trim().ifEmpty { if (code == null) "逾時" else "" })
        } catch (e: Throwable) {
            Result(-1, "", e.toString())
        }
    }

    /** 讀取指令的原始二進位輸出（例如 screencap -p） */
    fun execBytes(cmd: String, timeoutSec: Long = 10): ByteArray? {
        if (!ready()) return null
        return try {
            val p = newProcess(cmd)
            var bytes: ByteArray? = null
            val t = Thread { bytes = runCatching { p.inputStream.readBytes() }.getOrNull() }
            t.start()
            t.join(timeoutSec * 1000)
            if (t.isAlive) runCatching { p.destroy() }
            bytes
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 常駐的 Shizuku shell：連續送指令不用每次開新程序。
     * run() 會等指令跑完（用結尾標記同步）。
     */
    class Session {
        private var p: Process? = null
        private var w: java.io.Writer? = null
        private var r: java.io.BufferedReader? = null

        @Synchronized
        fun run(cmd: String, timeoutMs: Long = 5000): Boolean {
            if (p == null) {
                if (!ready()) return false
                val np = newProcess("sh")
                p = np
                w = np.outputStream.bufferedWriter()
                r = np.inputStream.bufferedReader()
            }
            return try {
                val mark = "__done_${System.nanoTime()}__"
                w!!.write("$cmd >/dev/null 2>&1; echo $mark\n")
                w!!.flush()
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val line = r!!.readLine() ?: break
                    if (line.trim() == mark) return true
                }
                close(); false
            } catch (e: Throwable) {
                close(); false
            }
        }

        @Synchronized
        fun close() {
            runCatching { w?.close() }
            runCatching { p?.destroy() }
            p = null; w = null; r = null
        }
    }

    /** 單引號跳脫，避免套件名等參數被 shell 解讀 */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
