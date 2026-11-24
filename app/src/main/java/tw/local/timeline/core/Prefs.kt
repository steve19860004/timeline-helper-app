package tw.local.timeline.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import tw.local.timeline.App

/** 全部設定集中在一個 SharedPreferences，方便備份還原。 */
object Prefs {
    private val sp: SharedPreferences by lazy { App.app.getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    fun bool(k: String, def: Boolean = false) = sp.getBoolean(k, def)
    fun int(k: String, def: Int) = sp.getInt(k, def)
    fun long(k: String, def: Long = 0L) = sp.getLong(k, def)
    fun str(k: String, def: String = "") = sp.getString(k, def) ?: def
    fun set(k: String, def: Set<String> = emptySet()): Set<String> = sp.getStringSet(k, def)?.toSet() ?: def

    fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    fun put(k: String, v: Long) = sp.edit().putLong(k, v).apply()
    fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    fun put(k: String, v: Set<String>) = sp.edit().putStringSet(k, v).apply()
    fun remove(k: String) = sp.edit().remove(k).apply()
    /** 同步寫入（流程中途斷電／被殺也不會遺失），回傳是否成功 */
    fun putNow(k: String, v: Int): Boolean = sp.edit().putInt(k, v).commit()
    fun putNow(k: String, v: Long): Boolean = sp.edit().putLong(k, v).commit()
    fun putNowStr(k: String, v: String): Boolean = sp.edit().putString(k, v).commit()
    fun removeNow(k: String): Boolean = sp.edit().remove(k).commit()
    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)
    fun has(k: String) = sp.contains(k)

    /** 這些是執行期狀態，不應被備份或還原 */
    private fun isRuntimeKey(k: String) = k.startsWith("rt_")

    fun exportJson(prefixes: Set<String>?): JSONObject {
        val root = JSONObject()
        root.put("app", "tw.local.timeline")
        root.put("version", 1)
        val data = JSONObject()
        for ((k, v) in sp.all) {
            if (isRuntimeKey(k)) continue
            if (prefixes != null && prefixes.none { k.startsWith(it) }) continue
            val o = JSONObject()
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            data.put(k, o)
        }
        root.put("data", data)
        return root
    }

    /** 回傳還原的項目數 */
    fun importJson(root: JSONObject): Int {
        val data = root.optJSONObject("data") ?: return 0
        val e = sp.edit()
        var n = 0
        for (k in data.keys()) {
            if (isRuntimeKey(k)) continue
            val o = data.optJSONObject(k) ?: continue
            when (o.optString("t")) {
                "b" -> e.putBoolean(k, o.optBoolean("v"))
                "i" -> e.putInt(k, o.optInt("v"))
                "l" -> e.putLong(k, o.optLong("v"))
                "f" -> e.putFloat(k, o.optDouble("v").toFloat())
                "s" -> e.putString(k, o.optString("v"))
                "ss" -> {
                    val arr = o.optJSONArray("v") ?: JSONArray()
                    e.putStringSet(k, (0 until arr.length()).map { arr.optString(it) }.toSet())
                }
                else -> continue
            }
            n++
        }
        e.apply()
        return n
    }
}
