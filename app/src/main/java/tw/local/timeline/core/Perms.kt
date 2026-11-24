package tw.local.timeline.core

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

object Perms {
    private fun a11yComponent(ctx: Context) = ComponentName(ctx, TimelineA11yService::class.java)

    fun a11y(ctx: Context): Boolean {
        if (TimelineA11yService.instance != null) return true
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val c = a11yComponent(ctx)
        return s.split(':').any { ComponentName.unflattenFromString(it) == c }
    }

    fun writeSecure(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun openA11y(ctx: Context) = start(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    fun openAppDetails(ctx: Context) =
        start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)))
    fun openDev(ctx: Context) = start(ctx, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

    private fun start(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** 透過 Shizuku 一次授予無障礙＋寫入安全設定（之後保活、Shizuku 版都不再需要每次授權） */
    fun grantViaShizuku(ctx: Context): String {
        val pkg = ctx.packageName
        val log = StringBuilder()
        fun run(label: String, cmd: String) {
            val r = Shell.exec(cmd)
            log.append(if (r.ok) "✓ " else "✗ ").append(label)
            if (!r.ok && r.toString().isNotBlank()) log.append("：").append(r.toString().take(100))
            log.append('\n')
        }
        run("解除受限制設定", "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
        run("寫入安全設定", "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        val comp = a11yComponent(ctx).flattenToString()
        val cur = Shell.exec("settings get secure enabled_accessibility_services").out.trim()
        val list = cur.split(':').filter { it.isNotBlank() && it != "null" }.toMutableList()
        if (list.none { ComponentName.unflattenFromString(it) == a11yComponent(ctx) }) list.add(comp)
        run("無障礙服務", "settings put secure enabled_accessibility_services ${Shell.q(list.joinToString(":"))}")
        run("無障礙總開關", "settings put secure accessibility_enabled 1")
        return log.toString().trim()
    }
}
