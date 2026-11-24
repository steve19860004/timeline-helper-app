package tw.local.timeline.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 開機保活（移植自 Timeline-Keep 0.26）：
 * 用 WRITE_SECURE_SETTINGS，在開機後與每 15 分鐘檢查一次，
 * 若本 App 的無障礙服務被系統關掉（OPPO／vivo 重開機常見），就自動重新開回來。
 * 只有在「寫入安全設定」已授權時才會運作。
 */
object Keep {
    const val K_KEEP = "tl_keep_enabled"
    private const val K_LOG = "rt_tl_keeplog"
    private const val ACTION = "tw.local.timeline.KEEP"

    fun ok(ctx: Context) = Perms.writeSecure(ctx)

    private fun svcId(ctx: Context) = ctx.packageName + "/tw.local.timeline.core.TimelineA11yService"

    private fun has(list: String?, id: String): Boolean =
        list?.split(':')?.any { it.trim().equals(id, true) } == true

    private fun strip(list: String?, id: String): String {
        if (list == null || list == "null") return ""
        return list.split(':').map { it.trim() }
            .filter { it.isNotEmpty() && it != "null" && !it.equals(id, true) }
            .joinToString(":")
    }

    /** 回傳：0 未授權 / 1 正常 / 2 已重新開啟 / 3 已重新連線 */
    fun ensure(ctx: Context, active: Boolean): Int {
        if (!ok(ctx)) return 0
        return try {
            val cr = ctx.contentResolver
            val id = svcId(ctx)
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val others = strip(cur, id)
            val full = if (others.isEmpty()) id else "$others:$id"
            if (!has(cur, id)) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, full)
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                return 2
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            if (!active || TimelineA11yService.instance != null) return 1
            // 清單裡有、但服務沒在跑：關掉再開，強制重新綁定
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others)
            SystemClock.sleep(800)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, full)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            3
        } catch (e: Throwable) {
            0
        }
    }

    fun desc(i: Int) = when (i) {
        1 -> "無障礙正常開著"; 2 -> "無障礙被系統關掉，已自動重新開啟"
        3 -> "服務沒在執行，已重新連線"; else -> "尚未授權，無法自動開啟無障礙"
    }

    fun note(ctx: Context, msg: String) {
        runCatching {
            val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val old = sp.getString(K_LOG, "") ?: ""
            val line = SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN).format(Date()) + " " + msg
            var merged = if (old.isEmpty()) line else "$line\n$old"
            if (merged.length > 220) merged = merged.substring(0, 220)
            sp.edit().putString(K_LOG, merged).commit()
        }
    }

    fun log(ctx: Context): String =
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString(K_LOG, "") ?: ""

    private fun pi(ctx: Context, code: Int): PendingIntent {
        val i = Intent(ctx, KeepReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pi(ctx, 0))
        if (!Prefs.bool(K_KEEP) || !ok(ctx)) return
        runCatching {
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 45_000, 900_000, pi(ctx, 0))
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 150_000, pi(ctx, 1))
        }
    }

    fun isKeep(action: String?) = action == ACTION
}

class KeepReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action
        if (Keep.isKeep(action)) {
            if (Prefs.bool(Keep.K_KEEP)) {
                val r = Keep.ensure(ctx, true)
                if (r >= 2) Keep.note(ctx, "背景檢查：" + Keep.desc(r))
            }
            return
        }
        // 開機 / 更新
        if (Prefs.bool(Keep.K_KEEP)) {
            val r = Keep.ensure(ctx, false)
            if (action != Intent.ACTION_MY_PACKAGE_REPLACED) Keep.note(ctx, "開機：" + Keep.desc(r))
        }
        Keep.schedule(ctx)
    }
}
