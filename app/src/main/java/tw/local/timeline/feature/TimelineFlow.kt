package tw.local.timeline.feature

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import tw.local.timeline.App
import tw.local.timeline.core.TimelineA11yService
import tw.local.timeline.core.Prefs
import tw.local.timeline.ui.MainActivity
import java.time.LocalDateTime
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.min

/**
 * 時間軸助手（移植自 Timeline-NoPair 0.26，免 root、免 Shizuku）：
 * 開發人員選項把最小寬度改成 640 dp → 強制停止 Google 地圖 → 強制停止 Google Play 服務
 * → 把最小寬度改回原值並核對 → 回桌面（自動）或回本 App（手動）。
 * 含小米 HyperOS 的寬度輸入處理、開機解鎖後自動執行、每日定時執行。
 */
object TimelineFlow {
    private const val GMS = "com.google.android.gms"
    private const val MAP = "com.google.android.apps.maps"

    // 設定（會備份）
    const val K_WIDTH = "tl_user_width"
    const val K_AUTO = "tl_auto"
    const val K_TESTED = "tl_tested"
    const val K_DAILY = "tl_daily_enabled"
    const val K_DAILY_MIN = "tl_daily_minute"
    // 執行期狀態（不備份）
    private const val K_PENDING = "rt_tl_pending"
    private const val K_ATTEMPT = "rt_tl_attempt"
    const val K_STATUS = "rt_tl_status"
    private const val K_TRACE = "rt_tl_mitrace"
    private const val K_DAILY_DAY = "rt_tl_daily_day"
    const val DAILY_DEFAULT = 480

    private val WIDTH = arrayOf("最小寬度", "最小宽度", "最小幅寬", "Smallest width", "Minimum width")
    private val STOP = arrayOf("強制停止", "強行停止", "强制停止", "强行停止", "Force stop")
    private val OK = arrayOf("確定", "確認", "确定", "确认", "OK", "Force stop", "強制停止", "强行停止")

    private var svc: TimelineA11yService? = null
    private var registered = false

    @Volatile var step = ""
        private set
    private var deadline = 0L
    private var settingsPkg = ""
    private var target = ""
    private var targetLabel = ""
    private var error = ""
    private var restore = false
    private var restoreOnly = false
    private var scrolls = 0
    private var listWindow = 0
    private var rowSince = 0L
    private var taps = 0
    /** 由 App 畫面按下的手動測試：結束後回到本 App，否則回桌面 */
    private var manual = false

    private val h get() = App.main

    private val tick = object : Runnable {
        override fun run() {
            if (step.isEmpty()) return
            try {
                if (SystemClock.elapsedRealtime() > deadline) { fail("逾時：$step"); return }
                advance()
                if (step.isEmpty()) return
                h.postDelayed(this, 650)
            } catch (e: Exception) {
                fail(e.toString())
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_USER_PRESENT) h.postDelayed({ check() }, 900)
            else dailyCheck()
        }
    }

    // ---------------- 服務生命週期 ----------------

    fun attach(s: TimelineA11yService) {
        svc = s
        if (!registered) {
            val f = IntentFilter(Intent.ACTION_USER_PRESENT).apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            s.registerReceiver(receiver, f)
            registered = true
        }
        h.postDelayed({ check() }, 1800)
    }

    fun detach(s: TimelineA11yService) {
        h.removeCallbacks(tick)
        if (registered) runCatching { s.unregisterReceiver(receiver) }
        registered = false
        svc = null
        step = ""
    }

    fun onEvent(e: AccessibilityEvent) {
        // 小米：記錄有沒有收到設定頁事件，失敗時給診斷用
        if (step.startsWith("尋找") && "com.android.settings".contentEquals(e.packageName?.toString() ?: "")) sawEvent = true
    }

    // ---------------- 寬度讀取 ----------------

    private fun pick(cur: Int, a: Int, b: Int, c: Int): Int = when {
        a > 0 && abs(cur - a) <= 2 -> a
        b > 0 && abs(cur - b) <= 2 -> b
        c > 0 && abs(cur - c) <= 2 -> c
        else -> -1
    }

    private fun dispSw(ctx: Context): Int = runCatching {
        val d = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(0) ?: return -1
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") d.getRealMetrics(m)
        if (m.densityDpi > 0) min(m.widthPixels, m.heightPixels) * 160 / m.densityDpi else -1
    }.getOrDefault(-1)

    private fun cfgSw(ctx: Context) = ctx.resources.configuration.smallestScreenWidthDp

    /** 目前最小寬度；容許 1～2 dp 誤差（榮耀 365/366 等） */
    fun sw(ctx: Context = svc ?: App.app): Int {
        val cfg = cfgSw(ctx)
        var disp = dispSw(ctx)
        val user = Prefs.int(K_WIDTH, -1)
        val pending = Prefs.int(K_PENDING, user)
        val (a, b, c) = if (restore) Triple(pending, user, 640) else Triple(640, pending, user)
        if (disp > 0) { val p = pick(disp, a, b, c); if (p > 0) return p }
        val p2 = pick(cfg, a, b, c)
        if (p2 > 0) return p2
        if (disp <= 0) disp = cfg
        if (restore || user <= 0 || disp < user + 20 || !(step == "檢查 640 dp" || step == MI_CONFIRM)) return disp
        return 640
    }

    private fun boot(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, "boot_count", -1)

    private fun unlocked(ctx: Context): Boolean =
        !ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            ctx.getSystemService(UserManager::class.java).isUserUnlocked

    fun hasPending() = Prefs.has(K_PENDING)
    fun pendingWidth() = Prefs.int(K_PENDING, 360)

    // ---------------- 啟動 ----------------

    /** 開機解鎖後自動執行（每次開機只試一次） */
    private fun check() {
        val s = svc ?: return
        if (TimelineShizuku.isMode()) return
        if (!dailyCheck() && step.isEmpty() && unlocked(s) && !hasPending() && Prefs.bool(K_AUTO) &&
            boot(s) >= 0 && Prefs.int(K_ATTEMPT, -1) != boot(s)
        ) begin(false)
    }

    /** 給 App 畫面用：restoreOnly=true 只還原寬度 */
    fun runManual(ctx: Context, restoreOnlyReq: Boolean) {
        if (svc == null) {
            Toast.makeText(ctx, "請先開啟本 App 的無障礙服務", Toast.LENGTH_LONG).show()
            return
        }
        manual = true
        begin(restoreOnlyReq)
    }

    private fun begin(restoreOnlyReq: Boolean) {
        val s = svc ?: return
        if (step.isNotEmpty()) return
        if (!unlocked(s)) { msg("請先解鎖"); return }
        var ro = restoreOnlyReq
        if (!ro && hasPending()) {
            // 上次中斷留下的待還原值：小米若寬度其實已還原就清掉，否則先只做還原
            val pend = Prefs.int(K_PENDING, -1)
            val cleared = mi() && pend > 0 && pend == Prefs.int(K_WIDTH, -1) && pend == sw(s) && Prefs.removeNow(K_PENDING)
            if (!cleared) ro = true
        }
        if (ro && !hasPending()) { msg("沒有待還原的寬度"); return }
        if (!ro) {
            val orig = Prefs.int(K_WIDTH, -1)
            if (orig <= 0 || orig == 640) { msg("請先保存日常寬度。若目前已是 640 dp，請先恢復原來大小。"); return }
            if (sw(s) != orig) { msg("目前最小寬度 ${sw(s)} dp，請先還原日常顯示大小，再記下並保存原值。"); return }
            Prefs.putNow(K_ATTEMPT, boot(s))
        }
        restoreOnly = ro
        restore = ro
        error = ""
        try {
            widthPage()
            h.postDelayed(tick, 650)
        } catch (e: Exception) {
            fail(e.toString())
        }
    }

    // ---------------- 每日執行 ----------------

    private fun minuteOf(t: LocalDateTime) = t.hour * 60 + t.minute
    private fun firstDay(t: LocalDateTime, m: Int) = t.toLocalDate().toEpochDay() + if (minuteOf(t) >= m) 1 else 0
    private fun due(t: LocalDateTime, day: Long, m: Int): Boolean {
        val today = t.toLocalDate().toEpochDay()
        return if (today <= day) today == day && minuteOf(t) >= m else true
    }

    fun setDaily(enabled: Boolean, minute: Int) {
        Prefs.put(K_DAILY, enabled)
        Prefs.put(K_DAILY_MIN, minute)
        Prefs.put(K_DAILY_DAY, firstDay(LocalDateTime.now(), minute))
    }

    fun dailyLabel(m: Int) = String.format(Locale.TAIWAN, "每日執行時間　%02d:%02d", m / 60, m % 60)

    private fun dailyCheck(): Boolean {
        val s = svc ?: return false
        if (TimelineShizuku.isMode()) return false
        if (!Prefs.bool(K_DAILY) || step.isNotEmpty() || hasPending()) return false
        val now = LocalDateTime.now()
        if (!due(now, Prefs.long(K_DAILY_DAY, Long.MAX_VALUE), Prefs.int(K_DAILY_MIN, DAILY_DEFAULT))) return false
        if (!s.getSystemService(PowerManager::class.java).isInteractive || !unlocked(s)) return false
        if (!Prefs.putNow(K_DAILY_DAY, now.toLocalDate().toEpochDay() + 1)) return false
        begin(false)
        return true
    }

    // ---------------- 流程 ----------------

    private fun msg(s: String) = Prefs.put(K_STATUS, s)

    private fun go(s: String) {
        step = s
        deadline = SystemClock.elapsedRealtime() + 35000
        msg("執行中：$s")
    }

    private fun open(intent: Intent) {
        val s = svc ?: return
        val ri = s.packageManager.resolveActivity(intent, 0x10000)
        settingsPkg = ri?.activityInfo?.packageName ?: "com.android.settings"
        val main = MainActivity.focused
        if (main != null) {
            main.startActivity(intent)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            s.startActivity(intent)
        }
    }

    private fun widthPage() {
        miBeginWidthPage()
        scrolls = 0
        go(if (restore) "尋找原本的寬度設定" else "尋找最小寬度")
        val i = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        i.putExtra(":settings:fragment_args_key", "density")
        i.putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", "density") })
        open(i)
    }

    private fun appPage(pkg: String) {
        val s = svc ?: return
        target = pkg
        targetLabel = s.packageManager.getApplicationLabel(s.packageManager.getApplicationInfo(pkg, 0)).toString()
        go("停止 $targetLabel")
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    private fun done(s: String) {
        step = ""
        msg(s)
        finish()
    }

    private fun fail(reason: String) {
        var why = miFailure(reason)
        if (!restore && hasPending()) {
            // 中途失敗：先把寬度還原
            restore = true
            error = why
            try {
                widthPage()
                h.removeCallbacks(tick)
                h.postDelayed(tick, 800)
                return
            } catch (_: Exception) {
            }
        }
        if (restore) why = "還原未確認：原值 ${Prefs.int(K_PENDING, -1)} dp，目前 ${sw()} dp。請手動確認"
        done(why + if (hasPending()) "。請在開發人員選項將最小寬度還原為 ${pendingWidth()} dp" else "")
    }

    /** 結束：手動測試回本 App；自動執行回桌面 */
    private fun finish() {
        val s = svc ?: return
        val m = manual
        manual = false
        if (m || !hasPending()) {
            h.postDelayed({
                runCatching {
                    val li = if (m) s.packageManager.getLaunchIntentForPackage(s.packageName) else null
                    if (li != null) s.startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }
            }, 900)
        }
    }

    private fun find(n: AccessibilityNodeInfo?, ok: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isVisibleToUser && ok(n)) return n
        for (i in 0 until n.childCount) find(n.getChild(i), ok)?.let { return it }
        return null
    }

    private fun eq(cs: CharSequence?, vararg words: String): Boolean {
        if (cs == null) return false
        val t = cs.toString().trim()
        return words.any { it.equals(t, ignoreCase = true) }
    }

    private fun word(n: AccessibilityNodeInfo?, vararg words: String) =
        find(n) { eq(it.text, *words) || eq(it.contentDescription, *words) }

    private fun click(start: AccessibilityNodeInfo?): Boolean {
        var n = start
        while (n != null) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    private fun isButton1(n: AccessibilityNodeInfo) = n.viewIdResourceName == "android:id/button1"

    private fun positive(n: AccessibilityNodeInfo) = find(n) { isButton1(it) } ?: word(n, *OK)

    private fun setText(n: AccessibilityNodeInfo, v: String): Boolean {
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, v)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    private fun advance() {
        val s = svc ?: return
        if (miWidthReached()) return
        val orig = Prefs.int(K_WIDTH, -1)
        val pend = Prefs.int(K_PENDING, orig)

        if (step == "檢查還原") {
            if (sw() == pend) {
                Prefs.removeNow(K_PENDING)
                if (error.isEmpty() && !restoreOnly) Prefs.put(K_TESTED, true)
                done(if (error.isEmpty()) (if (restoreOnly) "畫面已還原" else "已完成停止流程，畫面已還原至原始寬度。") else "$error。畫面已還原")
            }
            return
        }
        if (step == "檢查 640 dp") {
            if (sw() == 640) appPage(MAP)
            return
        }

        val root = miWidthRoot(s.rootInActiveWindow) ?: return
        if (miHandle(root)) return
        val rootPkg = root.packageName ?: return
        if (!settingsPkg.contentEquals(rootPkg)) return

        when {
            step.startsWith("尋找") -> {
                if (restore && sw() == pend) { go("檢查還原"); return }
                if (click(word(root, *WIDTH))) { go("編輯最小寬度"); return }
                if (!restore || scrolls < 12) {
                    val sc = find(root) { it.isScrollable } ?: return
                    if (scrolls < 50 && sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { scrolls++; return }
                    if (!restore) { fail("已停止搜尋，請確認開發人員選項中的最小寬度"); return }
                }
                if (scrolls < 13) {
                    scrolls = 13
                    Toast.makeText(s, "已停止自動滑動。請滑到最小寬度，助手會繼續還原。", Toast.LENGTH_LONG).show()
                }
            }
            step == "編輯最小寬度" -> {
                val edit = find(root) { it.isEditable } ?: return
                if ((!mi() || scan(root, false) == null) && word(root, *WIDTH) == null) return
                val cur = edit.text?.toString()?.trim() ?: ""
                if (!restore && cur != orig.toString()) { fail("寬度欄位與日常值不符：$cur"); return }
                if (restore && cur != "640" && cur != pend.toString()) { fail("寬度已由使用者更改，請手動確認"); return }
                if (!restore && !Prefs.putNow(K_PENDING, orig)) throw Exception("無法保存原值")
                if (!setText(edit, if (restore) pend.toString() else "640")) throw Exception("無法輸入寬度")
                go("確認最小寬度")
            }
            step == "確認最小寬度" -> {
                val edit = find(root) { it.isEditable } ?: return
                if (eq(edit.text, if (restore) pend.toString() else "640") && click(positive(root))) {
                    go(if (restore) "檢查還原" else "檢查 640 dp")
                }
            }
            step.startsWith("停止 ") -> {
                if (word(root, targetLabel) == null) return
                val btn = word(root, *STOP) ?: return
                if (click(btn)) { go("確認停止 $targetLabel"); return }
                if (btn.isEnabled) return
                // 按鈕灰掉：已經是停止狀態才算數
                val stopped = (s.packageManager.getApplicationInfo(target, 0).flags and 0x200000) != 0
                if (!stopped) throw IllegalStateException("$step：按鈕不可用，無法確認已停止")
                nextApp()
            }
            step.startsWith("確認停止 ") -> {
                val dialog = find(root) { isButton1(it) } != null || word(root, "取消", "Cancel") != null
                if (dialog && click(positive(root))) { go("離開確認視窗"); return }
                val btn = word(root, *STOP)
                if (word(root, targetLabel) == null || btn == null || btn.isEnabled) return
                nextApp()
            }
            step == "離開確認視窗" -> {
                if (find(root) { isButton1(it) } != null) return
                nextApp()
            }
        }
    }

    private fun nextApp() {
        if (target == MAP) appPage(GMS)
        else { restore = true; widthPage() }
    }

    // ================= 小米 HyperOS 寬度處理 =================

    private const val MI_CONFIRM = "核對小米寬度並按確定"
    private const val MI_WAIT = "等待小米開啟最小寬度輸入框"
    private val LABEL = Pattern.compile(
        "^(?:最小[寬宽]度|最小(?:屏幕|螢幕|屏)[寬宽]度|最小幅寬|smallest width|minimum width)(?:[\\s,，:：]+[0-9]{2,4}\\s*(?:dp|dip))?$",
        Pattern.CASE_INSENSITIVE
    )
    private val DIGITS = Pattern.compile("^([0-9]{2,4})(?:\\s*(?:dp|dip))?$", Pattern.CASE_INSENSITIVE)
    private var launched = 0L
    private var retried = false
    private var sawEvent = false
    private var sawRoot = false

    fun mi() = "xiaomi".equals(Build.MANUFACTURER, true) || "redmi".equals(Build.BRAND, true) || "poco".equals(Build.BRAND, true)

    private fun label(cs: CharSequence?): Boolean {
        if (cs == null) return false
        val t = cs.toString().replace(' ', ' ').replace("​", "").trim()
        if (LABEL.matcher(t).matches()) return true
        return t.split(Regex("[\\r\\n,，;；]+")).any { LABEL.matcher(it.trim()).matches() }
    }

    /** editable=true：找唯一的輸入框；false：找「最小寬度」標籤 */
    private fun scan(root: AccessibilityNodeInfo, editable: Boolean): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var found: AccessibilityNodeInfo? = null
        var n = 0
        while (q.isNotEmpty() && n < 700) {
            val x = q.removeFirst()
            if (x.isVisibleToUser && x.isEnabled) {
                if (editable && (x.isEditable || x.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT))) {
                    if (found != null) return null
                    found = x
                }
                if (!editable && (label(x.text) || label(x.contentDescription))) return x
            }
            for (i in 0 until x.childCount) x.getChild(i)?.let { q.add(it) }
            n++
        }
        return found
    }

    private fun digits(cs: CharSequence?): String {
        if (cs == null) return ""
        val m = DIGITS.matcher(cs.toString().trim())
        return if (m.matches()) m.group(1) ?: "" else ""
    }

    private fun isSettings(n: AccessibilityNodeInfo?): Boolean {
        val p = n?.packageName?.toString() ?: return false
        if (p == "com.android.settings") return true
        return p == settingsPkg && p != "android"
    }

    private fun miWidthReached(): Boolean {
        if (!mi()) return false
        if (!(step.startsWith("尋找") || step == MI_WAIT || step == MI_CONFIRM)) return false
        val i = Prefs.int(K_PENDING, Prefs.int(K_WIDTH, -1))
        if (i <= 0 || i == 640) return false
        if (sw() != (if (restore) i else 640)) return false
        if (!restore && !Prefs.putNow(K_PENDING, i)) { fail("無法保存原始寬度"); return true }
        go(if (restore) "檢查還原" else "檢查 640 dp")
        return true
    }

    private fun miBeginWidthPage() {
        sawEvent = false; sawRoot = false; retried = false
        launched = SystemClock.elapsedRealtime()
        if (mi()) note("等待開發人員選項")
    }

    private fun miWidthRoot(active: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val s = svc ?: return null
        if (!mi() || !(step.startsWith("尋找") || step == MI_WAIT || step == MI_CONFIRM)) return active
        if (isSettings(active)) { sawRoot = true; return active }
        val sb = StringBuilder(active?.packageName?.toString() ?: "無")
        for (w in runCatching { s.windows }.getOrDefault(emptyList())) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = w.root
            if (r != null && sb.length < 140) sb.append(" / ").append(r.packageName)
            if (isSettings(r) && r != null && (w.isActive || w.isFocused || (r.isVisibleToUser && scan(r, false) != null))) {
                sawRoot = true
                return r
            }
        }
        val main = MainActivity.focused
        if (sawRoot || retried || main == null || !main.hasWindowFocus() || active == null ||
            !s.packageName.contentEquals(active.packageName?.toString() ?: "") || !step.startsWith("尋找") ||
            SystemClock.elapsedRealtime() - launched <= 2500
        ) {
            note((if (sawRoot) "曾讀到設定頁" else if (sawEvent) "收到設定頁事件，但未讀到設定內容" else "尚未偵測到設定頁") +
                (if (retried) "，已重開設定一次" else "") + "；最後視窗 " + sb)
            return null
        }
        retried = true
        try {
            main.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).setPackage(settingsPkg))
        } catch (e: RuntimeException) {
            fail("再次開啟開發人員選項失敗 " + e.javaClass.simpleName)
        }
        return null
    }

    private fun touchBounds(node: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Rect {
        val r = Rect(); val rr = Rect(); val tmp = Rect()
        node.getBoundsInScreen(r)
        root.getBoundsInScreen(rr)
        if (!r.intersect(rr)) return Rect()
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isScrollable) {
            n.getBoundsInScreen(tmp)
            if (tmp.width() < rr.width() * 0.6 || tmp.height() > rr.height() / 8 || !tmp.contains(r.centerX(), r.centerY()) || !tmp.intersect(rr)) {
                n = n.parent
            } else return tmp
        }
        return r
    }

    private fun note(s: String) {
        if (s != Prefs.str(K_TRACE)) Prefs.put(K_TRACE, s)
    }

    private fun miFailure(reason: String): String {
        val t = Prefs.str(K_TRACE)
        if (!mi() || t.isEmpty()) return reason
        return if (step.startsWith("尋找") || step == MI_WAIT || step == MI_CONFIRM) "$reason｜$t" else reason
    }

    private fun miHandle(root: AccessibilityNodeInfo): Boolean {
        val s = svc ?: return false
        val waiting = step == MI_WAIT
        val confirming = step == MI_CONFIRM
        if (!mi() || (!waiting && !confirming && !step.startsWith("尋找"))) return false
        if (!isSettings(root)) return waiting || confirming
        var lbl = scan(root, false)
        val edit = scan(root, true)
        val pos = if (edit == null) null else positive(root)
        val onEditor = lbl != null || ((waiting || confirming) && root.windowId != listWindow)
        if (edit != null && pos != null && onEditor) {
            val user = Prefs.int(K_WIDTH, -1)
            val want = if (restore) Prefs.int(K_PENDING, user) else 640
            val cur = digits(edit.text)
            if (user <= 0 || want <= 0 || user == 640 || cur.isEmpty()) return true
            if (confirming) {
                if (cur == want.toString() && click(pos)) go(if (restore) "檢查還原" else "檢查 640 dp")
                return true
            }
            if (cur != user.toString() && !(restore && cur == "640")) {
                fail("小米寬度欄位目前是 $cur dp，與助手保存的原值不符"); return true
            }
            if (!restore && !Prefs.putNow(K_PENDING, user)) { fail("無法保存原始寬度"); return true }
            if (cur != want.toString()) {
                edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (!setText(edit, want.toString())) { fail("小米寬度輸入框未接受新數值"); return true }
            }
            go(MI_CONFIRM)
            return true
        }
        if (confirming || lbl == null) {
            if (lbl == null && !waiting && !confirming) note("設定頁可讀，尚未辨識到最小寬度標籤")
            return confirming || waiting
        }
        val now = SystemClock.elapsedRealtime()
        if (!waiting) {
            listWindow = root.windowId
            go(MI_WAIT)
            rowSince = now
            taps = 0
            note("已辨識最小寬度，嘗試點選設定列")
            while (lbl != null && !lbl.isScrollable && !(lbl.isClickable && lbl.isEnabled && lbl.performAction(AccessibilityNodeInfo.ACTION_CLICK))) {
                lbl = lbl.parent
            }
            return true
        }
        if (now - rowSince < (if (taps == 0) 2000 else 5500)) return true
        if (taps >= 2) { fail("已找到最小寬度，但沒有辨識到數字輸入框"); return true }
        if (root.windowId != listWindow || edit != null) return true
        val rect = touchBounds(lbl, root)
        if (rect.isEmpty) return true
        // 點選設定列沒反應時，改用手勢點中央／右側
        val path = Path()
        path.moveTo(if (taps == 0) rect.exactCenterX() else rect.left + rect.width() * 0.88f, rect.exactCenterY())
        rowSince = now
        taps++
        val stamp = rowSince
        val where = if (taps == 1) "設定列中央" else "設定列右側"
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build()
        val accepted = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            private fun record(r: String) { if (step == MI_WAIT && rowSince == stamp) note("$where 手勢$r") }
            override fun onCompleted(d: GestureDescription) = record("已送達，等候數字框")
            override fun onCancelled(d: GestureDescription) = record("遭系統取消")
        }, null)
        if (!accepted) { note("$where 手勢未獲接受"); fail("無法送出點按") }
        return true
    }
}
