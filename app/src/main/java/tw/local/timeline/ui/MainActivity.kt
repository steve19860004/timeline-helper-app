package tw.local.timeline.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import tw.local.timeline.App
import tw.local.timeline.core.Keep
import tw.local.timeline.core.Perms
import tw.local.timeline.core.Prefs
import tw.local.timeline.core.Shell
import tw.local.timeline.feature.TimelineFlow
import tw.local.timeline.feature.TimelineShizuku

class MainActivity : Activity() {
    companion object {
        @Volatile var focused: MainActivity? = null
    }

    private lateinit var box: LinearLayout
    private var statusTv: TextView? = null
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    private val dark get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val accent = Color.parseColor("#3A7BD5")
    private val cardBg get() = if (dark) Color.parseColor("#262626") else Color.WHITE
    private val pageBg get() = if (dark) Color.parseColor("#141414") else Color.parseColor("#F2F4F7")
    private val tMain get() = if (dark) Color.parseColor("#F2F2F2") else Color.parseColor("#1D1D1D")
    private val tSub get() = if (dark) Color.parseColor("#AAAAAA") else Color.parseColor("#666666")
    private val ok = Color.parseColor("#2E9E5B")
    private val bad = Color.parseColor("#D04545")

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == TimelineFlow.K_STATUS) statusTv?.text = Prefs.str(key, "尚未執行")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(pageBg); fitsSystemWindows = true }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(6)) }
        header.addView(TextView(this).apply { text = "時間軸助手"; textSize = 21f; setTypeface(typeface, Typeface.BOLD); setTextColor(tMain) })
        header.addView(TextView(this).apply { text = "免 root 開啟 Google 地圖時間軸"; textSize = 12.5f; setTextColor(tSub) })
        root.addView(header)
        val scroll = ScrollView(this)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(4), dp(14), dp(40)) }
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Keep.schedule(this)
        TimelineShizuku.scheduleDaily(this)
    }

    override fun onResume() { super.onResume(); focused = this; Prefs.listen(prefListener); render() }
    override fun onPause() { Prefs.unlisten(prefListener); if (focused === this) focused = null; super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) focused = this }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun showText(title: String, body: String) =
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("確定", null).show()

    // ---------- UI 小工具 ----------
    private fun round(c: Int, r: Int = 14) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun card(build: LinearLayout.() -> Unit): LinearLayout {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(cardBg); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(12)
        box.addView(c, lp); c.build(); return c
    }
    private fun LinearLayout.title(s: String) = addView(TextView(context).apply {
        text = s; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(accent); setPadding(0, 0, 0, dp(6))
    })
    private fun LinearLayout.txt(s: CharSequence, size: Float = 14f, bold: Boolean = false, color: Int? = null): TextView {
        val t = TextView(context).apply { text = s; textSize = size; setTextColor(color ?: tMain); if (bold) setTypeface(typeface, Typeface.BOLD); setLineSpacing(0f, 1.15f) }
        addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2); bottomMargin = dp(2) }); return t
    }
    private fun LinearLayout.hint(s: CharSequence) = txt(s, 12.5f, color = tSub)
    private fun LinearLayout.btn(label: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(context).apply {
            text = label; isAllCaps = false; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else tMain)
            background = round(if (primary) accent else (if (dark) Color.parseColor("#353535") else Color.parseColor("#E9EDF2")), 10)
            setPadding(dp(12), dp(10), dp(12), dp(10)); minHeight = dp(44); stateListAnimator = null
            setOnClickListener { onClick() }
        }
        addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5); bottomMargin = dp(5) }); return b
    }
    private fun LinearLayout.status(label: String, granted: Boolean, onFix: () -> Unit) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        row.addView(TextView(context).apply { text = label; textSize = 14f; setTextColor(tMain) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply { text = if (granted) "● 已授權" else "● 未授權"; textSize = 12.5f; setTextColor(if (granted) ok else bad) })
        if (!granted) row.setOnClickListener { onFix() }
        addView(row)
    }
    private fun LinearLayout.toggle(label: String, key: String, def: Boolean = false, onChange: (Boolean) -> Unit): Switch {
        val sw = Switch(context).apply {
            text = label; textSize = 14.5f; setTextColor(tMain); isChecked = Prefs.bool(key, def)
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); return sw
    }

    // ---------- 畫面 ----------
    private fun render() {
        box.removeAllViews()
        statusTv = null
        val shz = TimelineShizuku.isMode()

        card {
            title("Google 地圖時間軸")
            hint("把最小寬度暫時改成 640 dp（平板版面）→ 強制停止 Google 地圖與 Google Play 服務 → 還原原本寬度，即可開啟時間軸。")
            txt("執行方式", bold = true)
            val rg = RadioGroup(this@MainActivity)
            listOf("shizuku" to "Shizuku（推薦，可背景執行）", "a11y" to "無障礙服務（需解鎖螢幕）").forEach { (v, label) ->
                rg.addView(RadioButton(this@MainActivity).apply {
                    text = label; textSize = 14f; setTextColor(tMain); id = View.generateViewId(); tag = v
                    isChecked = v == Prefs.str(TimelineShizuku.K_MODE, "shizuku")
                })
            }
            rg.setOnCheckedChangeListener { g, id ->
                val v = g.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
                Prefs.put(TimelineShizuku.K_MODE, v); TimelineShizuku.scheduleDaily(this@MainActivity); render()
            }
            addView(rg)
            // 使用者要求的備註
            hint("・Shizuku 模式：免無障礙，手機鎖著、熄屏也能執行。建議裝 Shizuku-Next（Shizuku 的強化分支），開「開機自動啟動」後重開機會自動啟動並保活，不用再手動開。\n・無障礙模式：要在螢幕解鎖、亮著時才能跑（它得看著設定頁自動操作）。")
            txt("本機機型：${Build.MANUFACTURER} ${Build.MODEL}" + if (TimelineFlow.mi()) "（小米 HyperOS，寬度輸入會自動處理）" else "", 12.5f, color = tSub)
        }

        if (shz) shizukuCard() else a11yCard()
        autoCard(shz)
        if (!shz) keepCard()
        manualCard()
        aboutCard()
    }

    private fun shizukuCard() = card {
        title("Shizuku 執行")
        txt(Shell.statusText(), 13f, bold = true)
        if (!Shell.ready()) { btn("授權 Shizuku", true) { requestShizuku() }; return@card }
        val cur = txt("讀取目前寬度中…", 13f, color = tSub)
        App.bg { val t = TimelineShizuku.currentText(); App.ui { cur.text = t } }
        hint("原始寬度會自動偵測並在結束時還原，不用手動記下。")
        if (TimelineShizuku.hasPending()) txt("⚠ 上次未完成，按「僅還原」即可恢復", 13f, color = bad)
        btn("開始執行", true) {
            if (!Shell.ready()) { requestShizuku(); return@btn }
            toast("執行中，約 15 秒…")
            App.bg { val r = TimelineShizuku.run(this@MainActivity); App.ui { toast(r); render() } }
        }
        btn("僅還原畫面大小") { App.bg { val r = TimelineShizuku.run(this@MainActivity, restoreOnly = true); App.ui { toast(r); render() } } }
        val holdTv = txt("停止後在 640 dp 停留：${Prefs.int(TimelineShizuku.K_HOLD, 8)} 秒", 13f)
        val sb = SeekBar(this@MainActivity).apply {
            max = 27; progress = Prefs.int(TimelineShizuku.K_HOLD, 8) - 3
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { holdTv.text = "停止後在 640 dp 停留：${p + 3} 秒" }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { Prefs.put(TimelineShizuku.K_HOLD, s.progress + 3) }
            })
        }
        addView(sb)
        hint("讓 Google Play 服務在平板寬度下重新啟動；時間軸沒出現時可加長。")
    }

    private fun a11yCard() = card {
        title("無障礙執行")
        status("無障礙服務", Perms.a11y(this@MainActivity)) { Perms.openA11y(this@MainActivity) }
        status("寫入安全設定（保活、免每次授權）", Perms.writeSecure(this@MainActivity)) { grantViaShizuku() }
        if (Shell.ready() && !Perms.writeSecure(this@MainActivity)) btn("一鍵用 Shizuku 授權") { grantViaShizuku() }
        txt("首次設定：先在開發人員選項查看並記住原本的「最小寬度」，第一次先別改，回來按下面記下。", 13.5f)
        btn("開啟開發人員選項／查看原值") { Perms.openDev(this@MainActivity) }
        val w = Prefs.int(TimelineFlow.K_WIDTH, -1)
        txt(if (w > 0) "你的原始寬度：$w dp" else "尚未保存原始寬度", bold = true)
        btn("記下我的原始寬度") { rememberWidth() }
        if (TimelineFlow.hasPending()) txt("⚠ 上次未完成，待還原寬度 ${TimelineFlow.pendingWidth()} dp", 13f, color = bad)
        hint("若設定頁沒有自動定位，請手動滑到最小寬度。")
        btn("開始自動測試", true) { TimelineFlow.runManual(this@MainActivity, false) }
        btn("僅還原畫面大小") { TimelineFlow.runManual(this@MainActivity, true) }
    }

    private fun autoCard(shz: Boolean) = card {
        title("自動執行")
        toggle("每次開機後自動執行", TimelineFlow.K_AUTO) { on ->
            if (on && !Prefs.bool(TimelineFlow.K_TESTED)) { toast("請先成功執行一次"); render() }
            else Prefs.put(TimelineFlow.K_AUTO, on)
        }
        if (shz) hint("Shizuku 模式：開機後 Shizuku 已啟動才會執行，不需解鎖。建議用 Shizuku-Next 並開「開機自動啟動」，重開機後會自動啟動保活。")
        else hint("無障礙模式：開機解鎖後才會執行；搭配下方「開機保活」較穩。")
        val daily = toggle("每日自動執行", TimelineFlow.K_DAILY) { }
        val m0 = Prefs.int(TimelineFlow.K_DAILY_MIN, TimelineFlow.DAILY_DEFAULT)
        val timeBtn = btn(TimelineFlow.dailyLabel(m0)) { }
        daily.setOnCheckedChangeListener { b, on ->
            if (on && !shz && Prefs.int(TimelineFlow.K_WIDTH, -1) <= 0) { b.isChecked = false; toast("請先記下並保存原始寬度") }
            else { TimelineFlow.setDaily(on, Prefs.int(TimelineFlow.K_DAILY_MIN, TimelineFlow.DAILY_DEFAULT)); TimelineShizuku.scheduleDaily(this@MainActivity) }
        }
        timeBtn.setOnClickListener {
            val m = Prefs.int(TimelineFlow.K_DAILY_MIN, TimelineFlow.DAILY_DEFAULT)
            TimePickerDialog(this@MainActivity, { _, hh, mm ->
                val v = hh * 60 + mm; TimelineFlow.setDaily(daily.isChecked, v); TimelineShizuku.scheduleDaily(this@MainActivity); timeBtn.text = TimelineFlow.dailyLabel(v)
            }, m / 60, m % 60, true).show()
        }
        hint(if (shz) "依手機當地時間執行，熄屏也會跑。請避開導航時段。" else "依當地時間執行；鎖屏或熄屏時會等亮屏解鎖後再跑。請避開導航時段。")
        txt("狀態", bold = true)
        statusTv = txt(Prefs.str(TimelineFlow.K_STATUS, "尚未執行"), 13f, color = tSub)
    }

    private fun keepCard() = card {
        title("開機保活（OPPO／vivo 重開機後無障礙被關）")
        hint("用「寫入安全設定」每 15 分鐘檢查一次，無障礙被系統關掉就自動重新開回來。需先授權寫入安全設定。")
        if (!Keep.ok(this@MainActivity)) {
            status("寫入安全設定", false) { grantViaShizuku() }
            if (Shell.ready()) btn("一鍵用 Shizuku 授權") { grantViaShizuku() }
        } else {
            toggle("啟用開機保活", Keep.K_KEEP) { on -> Prefs.put(Keep.K_KEEP, on); Keep.schedule(this@MainActivity); toast(if (on) "已啟用" else "已關閉"); render() }
            val log = Keep.log(this@MainActivity)
            if (log.isNotEmpty()) { txt("保活紀錄", bold = true); txt(log, 12f, color = tSub) }
        }
    }

    private fun manualCard() = card {
        title("手動操作（自動失敗時備援）")
        btn("1　開啟設定頁，自行調成 640 dp") { Perms.openDev(this@MainActivity) }
        btn("2　開啟 Google 地圖資訊") { appDetails("com.google.android.apps.maps") }
        btn("3　開啟 Google Play 服務資訊") { appDetails("com.google.android.gms") }
        hint("在上述兩個資訊頁分別點「強制停止」並確認。")
        btn("4　開啟設定，還原我的寬度") { Perms.openDev(this@MainActivity) }
        btn("開啟 Google 地圖時間軸") { openTimeline() }
    }

    private fun aboutCard() = card {
        title("關於")
        val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        txt("時間軸助手 v$v", bold = true)
        hint("免 root。Shizuku 模式用 adb shell 權限直接下指令；無障礙模式自動操作設定頁。只操作系統設定頁，不讀取也不上傳個人內容。")
        hint("建議搭配 Shizuku-Next（開機自動啟動、保活）：github.com/rushiranpise/Shizuku-Next")
        hint("© 2026 steve19860004｜授權 GPLv3（本程式不提供任何擔保）")
    }

    // ---------- 動作 ----------
    private fun requestShizuku() {
        if (!Shell.running()) { showText("Shizuku 未啟動", "請先安裝並啟動 Shizuku 服務後再回來授權。\n\n建議裝「Shizuku-Next」（Shizuku 的強化分支，相容本 App）：用無線偵錯啟動一次、開「開機自動啟動」後，重開機會自動啟動，被系統關掉也會自動重啟。\n注意：它與官方 Shizuku 簽章不同，已裝官方版要先解除安裝。"); return }
        Shell.request { granted -> App.ui { toast(if (granted) "Shizuku 已授權" else "未授權"); render() } }
    }
    private fun grantViaShizuku() {
        if (!Shell.ready()) { requestShizuku(); return }
        App.bg { val log = Perms.grantViaShizuku(this@MainActivity); App.ui { showText("授權結果", log); render() } }
    }
    private fun appDetails(pkg: String) = runCatching {
        startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg")))
    }.onFailure { toast("請從手機設定手動開啟這個頁面") }.let {}
    private fun openTimeline() = runCatching {
        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/maps/timeline")).setPackage("com.google.android.apps.maps"))
    }.onFailure { toast("找不到 Google 地圖") }.let {}
    private fun rememberWidth() {
        val et = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "填入修改前看到的 dp" }
        AlertDialog.Builder(this).setTitle("保存原本的最小寬度").setView(et)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val v = et.text.toString().trim().toIntOrNull()
                if (v == null || v < 1 || v > 4096) toast("請輸入設定中顯示的數值") else { Prefs.put(TimelineFlow.K_WIDTH, v); render() }
            }.show()
    }
}
