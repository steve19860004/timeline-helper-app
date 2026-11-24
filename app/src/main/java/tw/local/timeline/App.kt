package tw.local.timeline

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.lsposed.hiddenapibypass.HiddenApiBypass
import tw.local.timeline.core.Shell
import tw.local.timeline.feature.TimelineShizuku
import java.util.concurrent.Executors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        Shell.init()
        bg { if (Shell.ready()) TimelineShizuku.onShizukuReady(this) }
        runCatching {
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky {
                bg { if (Shell.ready()) TimelineShizuku.onShizukuReady(this) }
            }
        }
    }

    companion object {
        lateinit var app: App
            private set
        const val CH_SERVICE = "timeline_service"
        val main = Handler(Looper.getMainLooper())
        private val pool = Executors.newFixedThreadPool(3)
        fun bg(block: () -> Unit) { pool.execute { runCatching(block) } }
        fun ui(block: () -> Unit) { main.post(block) }
        fun ui(delayMs: Long, block: () -> Unit) { main.postDelayed(block, delayMs) }
    }
}
