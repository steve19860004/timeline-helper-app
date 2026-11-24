package tw.local.timeline.feature

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import tw.local.timeline.App
import tw.local.timeline.core.Prefs
import tw.local.timeline.core.Shell
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 時間軸助手的 Shizuku 版：不用無障礙、不用點設定頁，直接下指令。
 *   wm density（＝開發人員選項的最小寬度）調成 640 dp
 *   → am force-stop Google 地圖、Google Play 服務
 *   → 在 640 dp 停留幾秒讓 Play 服務以平板身分重新啟動
 *   → 還原原本的 density 並核對
 * 原始寬度會自動偵測，不必手動記下。
 */
object TimelineShizuku {
    const val K_MODE = "tl_mode"           // shizuku | a11y
    const val K_HOLD = "tl_sh_hold"        // 停止後在 640 dp 停留秒數
    private const val K_SAVED = "rt_tl_sh_saved"     // 執行前的 override density，"reset" = 原本沒有 override
    private const val K_ATTEMPT = "rt_tl_sh_attempt"

    private const val MAP = "com.google.android.apps.maps"
    private const val GMS = "com.google.android.gms"

    @Volatile var running = false
        private set

    fun isMode() = Prefs.str(K_MODE, "shizuku") == "shizuku"

    private fun msg(s: String) = Prefs.put(TimelineFlow.K_STATUS, s)

    private data class Disp(val physical: Int, val override: Int?, val shortPx: Int)

    /** 最近一次讀取失敗時的原始輸出，顯示給使用者診斷 */
    private var lastRaw = ""

    private fun read(): Disp? {
        val dr = Shell.exec("wm density")
        val sr = Shell.exec("wm size")
        lastRaw = "wm density → [${dr.code}] $dr｜wm size → [${sr.code}] $sr".take(300)
        val d = dr.out + "\n" + dr.err
        val s = sr.out + "\n" + sr.err
        fun num(text: String, key: String) = Regex("$key\\s*density:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toInt()
        val phys = num(d, "Physical") ?: return null
        val over = num(d, "Override")
        fun size(key: String) = Regex("$key size:\\s*(\\d+)x(\\d+)", RegexOption.IGNORE_CASE).find(s)?.groupValues?.let { min(it[1].toInt(), it[2].toInt()) }
        val shortPx = size("Override") ?: size("Physical") ?: return null
        return Disp(phys, over, shortPx)
    }

    private fun swOf(d: Disp) = d.shortPx * 160 / (d.override ?: d.physical)

    /** 目前最小寬度（給畫面顯示），背景執行緒呼叫 */
    fun currentText(): String {
        if (!Shell.ready()) return "Shizuku 未連線，無法讀取目前寬度"
        val d = read() ?: return "讀不到顯示設定：$lastRaw"
        return "目前最小寬度約 ${swOf(d)} dp（density ${d.override ?: d.physical}" +
            (if (d.override != null) "，已自訂；原廠 ${d.physical}" else "，原廠值") + "）"
    }

    fun hasPending() = Prefs.has(K_SAVED)

    /** 主流程（背景執行緒） */
    @Synchronized
    fun run(ctx: Context, restoreOnly: Boolean = false): String {
        if (running) return "正在執行中"
        if (!Shell.ready()) return "Shizuku 未連線或未授權".also { msg(it) }
        running = true
        try {
            if (hasPending() || restoreOnly) {
                val r = restore()
                if (restoreOnly || r.isNotEmpty()) return (r.ifEmpty { "沒有待還原的寬度" }).also { msg(it) }
            }
            val d = read() ?: return "讀不到顯示設定：$lastRaw".also { msg(it) }
            if (abs(swOf(d) - 640) <= 2) return "目前已經是 640 dp，請先還原原本的大小".also { msg(it) }
            if (!Installed.ok(ctx, GMS)) return "找不到 Google Play 服務".also { msg(it) }

            val target = (d.shortPx * 160f / 640f).roundToInt()
            // 先存好原值再動，流程中斷也能還原
            Prefs.putNowStr(K_SAVED, d.override?.toString() ?: "reset")
            msg("執行中：最小寬度改為 640 dp")
            val set = Shell.exec("wm density $target")
            if (!set.ok) { restore(); return ("無法調整顯示大小：$set（小米需開啟「USB 偵錯（安全設定）」）").also { msg(it) } }
            Thread.sleep(1500)
            val now = read()
            if (now == null || abs(swOf(now) - 640) > 2) {
                restore(); return "寬度沒有變成 640 dp，已還原".also { msg(it) }
            }
            msg("執行中：停止 Google 地圖")
            Shell.exec("am force-stop $MAP")
            Thread.sleep(800)
            msg("執行中：停止 Google Play 服務")
            Shell.exec("am force-stop $GMS")
            val hold = Prefs.int(K_HOLD, 8)
            msg("執行中：在 640 dp 停留 $hold 秒")
            Thread.sleep(hold * 1000L)
            val r = restore()
            val result = if (r.isEmpty()) "已完成停止流程，畫面已還原至原始寬度。" else r
            if (r.isEmpty()) Prefs.put(TimelineFlow.K_TESTED, true)
            msg(result)
            return result
        } catch (e: Exception) {
            restore()
            return ("執行失敗：$e").also { msg(it) }
        } finally {
            running = false
        }
    }

    /** 還原；成功回傳空字串 */
    private fun restore(): String {
        val saved = Prefs.str(K_SAVED)
        if (saved.isEmpty()) return ""
        msg("執行中：還原原本的寬度")
        val cmd = if (saved == "reset") "wm density reset" else "wm density $saved"
        repeat(3) {
            Shell.exec(cmd)
            Thread.sleep(1200)
            val d = read()
            val ok = d != null && (if (saved == "reset") d.override == null || d.override == d.physical else d.override == saved.toInt())
            if (ok) { Prefs.removeNow(K_SAVED); return "" }
        }
        return "還原未確認，請到開發人員選項確認最小寬度（指令：$cmd）"
    }

    // ---------------- 自動執行 ----------------

    private fun boot(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, "boot_count", -1)

    /** Shizuku 連上時呼叫：補還原、開機自動執行（每次開機一次） */
    fun onShizukuReady(ctx: Context) {
        if (!isMode()) return
        App.bg {
            if (hasPending()) { run(ctx, restoreOnly = true); return@bg }
            val b = boot(ctx)
            if (Prefs.bool(TimelineFlow.K_AUTO) && b >= 0 && Prefs.int(K_ATTEMPT, -1) != b) {
                Prefs.putNow(K_ATTEMPT, b)
                Thread.sleep(15000)  // 開機後等系統與 Play 服務穩定
                run(ctx)
            }
        }
    }

    /** 每日排程：用 AlarmManager 定時喚醒（不需無障礙） */
    fun scheduleDaily(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 41, Intent(ctx, DailyReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.cancel(pi)
        if (!isMode() || !Prefs.bool(TimelineFlow.K_DAILY)) return
        val m = Prefs.int(TimelineFlow.K_DAILY_MIN, TimelineFlow.DAILY_DEFAULT)
        var t = LocalDateTime.now().withHour(m / 60).withMinute(m % 60).withSecond(0).withNano(0)
        if (!t.isAfter(LocalDateTime.now())) t = t.plusDays(1)
        val at = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    class DailyReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val pending = goAsync()
            App.bg {
                try {
                    if (isMode() && Prefs.bool(TimelineFlow.K_DAILY)) {
                        // 鬧鐘把 App 叫醒時，Shizuku 連線要一點時間才會送到，最多等 20 秒
                        var waited = 0
                        while (!Shell.ready() && waited < 20000) { Thread.sleep(500); waited += 500 }
                        if (Shell.ready()) run(ctx) else msg("每日執行略過：Shizuku 未啟動")
                    }
                } finally {
                    scheduleDaily(ctx)   // 排下一次
                    pending.finish()
                }
            }
        }
    }

    private object Installed {
        fun ok(ctx: Context, pkg: String) =
            runCatching { ctx.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)
    }
}
