package com.abel.focusboardsync

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * FocusBoard Sync
 *
 * 1. Reads your screen time (per-app foreground sessions) for the last 7 days.
 * 2. You scan the QR code FocusBoard shows on your laptop.
 * 3. The data is encrypted with the one-time key inside that QR code and sent to your
 *    laptop over your local Wi-Fi / hotspot. It is never sent anywhere else.
 */
class MainActivity : Activity() {

    private val days = 7
    private lateinit var status: TextView
    private lateinit var today: TextView
    private lateinit var accessBtn: Button
    private lateinit var scanBtn: Button

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(24)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(56), pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        col.addView(text("FocusBoard Sync", 28f, true, "#37352F"))
        col.addView(text("Send your phone's screen time to FocusBoard on your laptop.", 15f, false, "#9B9A97").apply {
            setPadding(0, dp(6), 0, dp(28))
        })

        today = text("", 15f, false, "#37352F").apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded("#F7F6F3", 12)
        }
        col.addView(today)

        accessBtn = button("Allow usage access", "#2383E2") { openUsageSettings() }
        scanBtn = button("Scan QR code to sync", "#37352F") { scan() }
        col.addView(accessBtn, lp(dp(20)))
        col.addView(scanBtn, lp(dp(12)))

        status = text("", 14f, false, "#787774").apply { setPadding(0, dp(18), 0, 0) }
        col.addView(status)

        col.addView(text(
            "How to sync: on your laptop double-click \"Sync phone\" (or press Ctrl+Alt+P). " +
            "A QR code appears. Tap the button above and scan it. Phone and laptop must be on " +
            "the same Wi-Fi, or the laptop connected to this phone's hotspot.", 13f, false, "#9B9A97"
        ).apply { setPadding(0, dp(28), 0, 0) })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val ok = hasUsageAccess()
        accessBtn.visibility = if (ok) View.GONE else View.VISIBLE
        scanBtn.isEnabled = ok
        scanBtn.alpha = if (ok) 1f else 0.4f
        if (!ok) {
            today.text = "Step 1: tap \"Allow usage access\", find FocusBoard Sync in the list and switch it on."
            return
        }
        Thread {
            val summary = try { todaySummary() } catch (e: Exception) { "Couldn't read screen time: ${e.message}" }
            runOnUiThread { today.text = summary }
        }.start()
    }

    // ---------------------------------------------------------------- usage access

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        else
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun openUsageSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    // ---------------------------------------------------------------- reading screen time

    private class Session(val pkg: String, val start: Long, var end: Long)

    private fun collect(sinceMidnightDaysAgo: Int): List<Session> {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(sinceMidnightDaysAgo - 1))
        }
        val from = cal.timeInMillis
        val to = System.currentTimeMillis()

        // Don't count the home screen, the system UI or this app itself
        val ignore = mutableSetOf(packageName, "com.android.systemui")
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0).forEach { ignore.add(it.activityInfo.packageName) }

        val out = ArrayList<Session>()
        fun add(pkg: String, s: Long, e: Long) {
            if (pkg in ignore || e - s < 1000) return
            val last = out.lastOrNull()
            if (last != null && last.pkg == pkg && s - last.end < 3000) last.end = e   // same app, switched screens
            else out.add(Session(pkg, s, e))
        }

        val events = usm.queryEvents(from, to)
        val ev = UsageEvents.Event()
        var cur: String? = null
        var curStart = 0L
        fun close(t: Long) { cur?.let { add(it, curStart, t) }; cur = null }

        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val t = ev.timeStamp
            when (ev.eventType) {
                1 -> if (cur != ev.packageName) { close(t); cur = ev.packageName; curStart = t }  // ACTIVITY_RESUMED
                2 -> if (cur == ev.packageName) close(t)                                          // ACTIVITY_PAUSED
                16, 26 -> close(t)                                                                // SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN
            }
        }
        close(to)
        return out
    }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }

    private fun fmt(ms: Long): String {
        val m = ms / 60000
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    private fun todaySummary(): String {
        val s = collect(1)
        val byApp = s.groupBy { it.pkg }.mapValues { (_, v) -> v.sumOf { it.end - it.start } }
        val total = byApp.values.sum()
        val top = byApp.entries.sortedByDescending { it.value }.take(4)
            .joinToString("\n") { "• ${label(it.key)}  ${fmt(it.value)}" }
        return "Today so far: ${fmt(total)}\n" + (if (top.isEmpty()) "No app use yet." else top)
    }

    private fun payload(): JSONObject {
        val sessions = collect(days)
        val pkgs = sessions.map { it.pkg }.distinct()
        val idx = pkgs.withIndex().associate { it.value to it.index }
        val s = JSONArray()
        sessions.forEach { s.put(JSONArray().put(idx.getValue(it.pkg)).put(it.start).put(it.end)) }
        return JSONObject()
            .put("v", 1)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("generated", System.currentTimeMillis())
            .put("days", days)
            .put("pk", JSONArray(pkgs))
            .put("names", JSONArray(pkgs.map { label(it) }))
            .put("s", s)
    }

    // ---------------------------------------------------------------- scan + send

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.let { send(it) } ?: say("Couldn't read that QR code.") }
            .addOnCanceledListener { say("") }
            .addOnFailureListener { say("Scanner error: ${it.message}") }
    }

    /** QR format: FBSYNC1;<port>;<key, base64url>;<ip1>,<ip2>,... */
    private fun send(qr: String) {
        val parts = qr.split(";")
        if (parts.size != 4 || parts[0] != "FBSYNC1") { say("That's not a FocusBoard QR code."); return }
        val port = parts[1].toIntOrNull()
        val key = try { Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) } catch (e: Exception) { null }
        val ips = parts[3].split(",").filter { isPrivate(it) }
        if (port == null || port !in 1024..65535 || key == null || key.size != 32 || ips.isEmpty()) {
            say("That QR code isn't valid (only local network addresses are allowed)."); return
        }
        say("Reading screen time…")
        scanBtn.isEnabled = false
        Thread {
            val result = try {
                val p = payload()
                val body = encrypt(p.toString().toByteArray(Charsets.UTF_8), key)
                var last = "Couldn't reach your laptop. Are both on the same Wi-Fi?"
                var done = false
                for (ip in ips) {
                    runOnUiThread { say("Sending to $ip…") }
                    try {
                        val c = URL("http://$ip:$port/sync").openConnection() as HttpURLConnection
                        c.requestMethod = "POST"; c.connectTimeout = 4000; c.readTimeout = 15000; c.doOutput = true
                        c.setRequestProperty("Content-Type", "application/json")
                        c.outputStream.use { it.write(body) }
                        val code = c.responseCode
                        c.disconnect()
                        if (code == 200) { done = true; break }
                        last = if (code == 403) "Your laptop rejected the data. Show a new QR code and try again." else "Laptop replied with error $code."
                    } catch (e: Exception) { /* try the next address */ }
                }
                if (done) {
                    val n = p.getJSONArray("pk").length()
                    "Synced. $days days of screen time, $n apps sent to FocusBoard."
                } else last
            } catch (e: Exception) { "Sync failed: ${e.message}" }
            runOnUiThread { say(result); scanBtn.isEnabled = true; refresh() }
        }.start()
    }

    private fun isPrivate(ip: String): Boolean {
        val p = ip.trim().split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    /** AES-256-CBC + HMAC-SHA256 (encrypt-then-MAC). Keys are derived from the one-time QR key. */
    private fun encrypt(plain: ByteArray, master: ByteArray): ByteArray {
        val encKey = hmac(master, "enc".toByteArray())
        val macKey = hmac(master, "mac".toByteArray())
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plain)
        val mac = hmac(macKey, iv + ct)
        val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
        return JSONObject().put("iv", b64(iv)).put("ct", b64(ct)).put("mac", b64(mac)).toString().toByteArray(Charsets.UTF_8)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data)
    }

    // ---------------------------------------------------------------- helpers

    private fun say(msg: String) { status.text = msg }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun lp(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = top }

    private fun rounded(color: String, radiusDp: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun text(s: String, size: Float, bold: Boolean, color: String) = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun button(label: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
        background = rounded(color, 12); gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }
}
