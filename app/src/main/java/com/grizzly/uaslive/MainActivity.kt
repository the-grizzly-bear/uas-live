package com.grizzly.uaslive

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.*
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var startBtn: Button
    private lateinit var nameEdit: EditText
    private val ui = Handler(Looper.getMainLooper())
    private val pilotUrl = "http://127.0.0.1:${Store.WEB_PORT}"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        store = Store(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)

        val outer = android.widget.FrameLayout(this).apply { setBackgroundColor(BG); fitsSystemWindows = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
        outer.addView(root)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "UAS Live  " + runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")
            textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(btn("Pilot 2 setup") { pilotSetup() })
        top.addView(btn("Settings") { settings() })
        root.addView(top)

        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(6)) }
        nameRow.addView(TextView(this).apply { text = "UAS name  "; setTextColor(DIM); textSize = 16f })
        nameEdit = EditText(this).apply {
            setText(store.uasName); setSingleLine(); textSize = 22f; setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        nameRow.addView(nameEdit, LinearLayout.LayoutParams(0, -2, 1f))
        nameRow.addView(btn("Save") { saveName() })
        root.addView(nameRow)

        root.addView(CheckBox(this).apply {
            text = "Send position to CalTopo"; textSize = 16f; setTextColor(Color.WHITE); isChecked = store.sendPosition
            setOnCheckedChangeListener { _, on -> store.sendPosition = on }
        })
        startBtn = Button(this).apply { textSize = 20f; isAllCaps = false; setOnClickListener { toggle() } }
        root.addView(startBtn, LinearLayout.LayoutParams(-1, dp(64)))

        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(0, dp(10), 0, dp(6)); typeface = Typeface.MONOSPACE }
        status.setOnClickListener { Tracker.mapUrl?.let(::shareLink) }
        root.addView(status)
        logView = TextView(this).apply { setTextColor(DIM); textSize = 12f; typeface = Typeface.MONOSPACE }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(outer)
        handleShare(intent)
        refresh()
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleShare(i) }
    override fun onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun refresh() {
        startBtn.text = if (Tracker.running) "■  Stop" else "▶  Start"
        startBtn.setBackgroundColor(if (Tracker.running) 0xFF7A2E2E.toInt() else 0xFF2E6B3A.toInt())
        startBtn.setTextColor(Color.WHITE)
        val f = Tracker.lastFix
        val age = f?.let { maxOf(0L, (System.currentTimeMillis() - it.receivedAt) / 1000) }
        status.text = buildString {
            if (!Tracker.running) { append("Not running. Tap Start, then connect Pilot 2 (Pilot 2 setup)."); return@buildString }
            append("Pilot 2:  ").append(if (Tracker.pilotConnected) "connected" else "not connected yet").append('\n')
            append("Drone:    ").append(Tracker.aircraft ?: "–").append('\n')
            append("Position: ").append(f?.let { "%.6f, %.6f  (%ds ago)".format(it.lat, it.lon, age) } ?: "–").append('\n')
            append("CalTopo:  ${Tracker.sent} sent").append(if (Tracker.queued > 0) ", ${Tracker.queued} waiting" else "").append('\n')
            Tracker.lastError?.let { append("⚠ ").append(it).append('\n') }
            Tracker.mapUrl?.let { append("Map:      ").append(it).append("  (tap to share)") }
        }
        logView.text = Tracker.logLines.joinToString("\n")
        ui.postDelayed(::refresh, 1000)
    }

    private fun saveName() {
        store.uasName = nameEdit.text.toString()
        nameEdit.setText(store.uasName)
        toast("Saved: ${store.uasName}" + if (Tracker.running) ". Restart tracking to rename on today's map." else "")
    }

    private fun toggle() {
        if (Tracker.running) { TrackerService.stop(this); return }
        saveName()
        if (store.calTopo == null) { toast("Sign in to CalTopo first (Settings)"); settings(); return }
        if (store.djiAppId.isBlank() || store.djiAppKey.isBlank() || store.djiLicense.isBlank()) { toast("Enter the DJI Cloud API keys (Settings)"); settings(); return }
        TrackerService.start(this)
    }

    private fun pilotSetup() {
        AlertDialog.Builder(this).setTitle("Connect Pilot 2 (once per controller)")
            .setMessage("1. Tap Start here.\n" +
                "2. Open DJI Pilot 2 → Cloud Service → Open Platform (third-party cloud).\n" +
                "3. Enter this address and connect:\n\n      $pilotUrl\n\n" +
                "4. The page shows \"Connected\". Go back to flying.\n\n" +
                "The drone appears in CalTopo as \"${store.uasName}\" on today's map (UAS yyyy-MM-dd).")
            .setPositiveButton("Copy address") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", pilotUrl))
                toast("Copied $pilotUrl")
            }.setNegativeButton("Close", null).show()
    }

    private fun settings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        fun label(s: String) = box.addView(TextView(this).apply { text = s; setPadding(0, dp(12), 0, 0); typeface = Typeface.DEFAULT_BOLD })
        fun field(v: String, hint: String) = EditText(this).apply { setText(v); setSingleLine(); this.hint = hint }.also { box.addView(it) }
        label("Send position every (seconds)")
        val iv = field(store.intervalSec.toString(), "3").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        label("DJI Cloud API (developer.dji.com → My Apps → app type Cloud API)")
        val aid = field(store.djiAppId, "App ID"); val akey = field(store.djiAppKey, "App Key"); val lic = field(store.djiLicense, "App License")
        label("CalTopo (this device's own credential; personal account)")
        val ca = field(store.ctAcct, "Account ID"); val cc = field(store.ctCred, "Credential ID"); val ck = field(store.ctKey, "Credential key")
        box.addView(btn("Sign in to CalTopo (fills these in)") { calTopoSignIn() })
        box.addView(TextView(this).apply { text = "Keys stay on this device. Clear a field and Save to remove it."; setTextColor(DIM); textSize = 12f })
        AlertDialog.Builder(this).setTitle("Settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                store.intervalSec = iv.text.toString().toIntOrNull() ?: 3
                store.djiAppId = aid.text.toString(); store.djiAppKey = akey.text.toString(); store.djiLicense = lic.text.toString()
                store.ctAcct = ca.text.toString(); store.ctCred = cc.text.toString(); store.ctKey = ck.text.toString()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun calTopoSignIn() {
        AlertDialog.Builder(this).setTitle("Sign in to CalTopo")
            .setMessage("1. Your browser opens CalTopo (sign in if asked).\n2. Name the device, tick the box, tap Sync Account.\n" +
                "3. On the 'lost' page: Share it to UAS Live, or type the code=XXXXXXXX from its address.")
            .setPositiveButton("Open CalTopo") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CalTopo.ACTIVATE_PAGE))) }
            .setNeutralButton("Enter code") { _, _ ->
                val e = EditText(this).apply { hint = "8-character code or the page address" }
                AlertDialog.Builder(this).setTitle("CalTopo code").setView(e)
                    .setPositiveButton("Sign in") { _, _ -> CalTopo.extractCode(e.text.toString())?.let(::activate) ?: toast("No code found") }
                    .setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        CalTopo.extractCode(i.getStringExtra(Intent.EXTRA_TEXT).orEmpty())?.let(::activate) ?: toast("That link has no CalTopo code")
    }

    private fun activate(code: String) = thread {
        val r = runCatching { CalTopo.activate(code) }
        runOnUiThread {
            r.onSuccess { (a, c, k) -> store.ctAcct = a; store.ctCred = c; store.ctKey = k; toast("CalTopo connected (account $a)") }
                .onFailure { toast("CalTopo sign-in failed: ${it.message}") }
        }
    }

    private fun shareLink(url: String) = startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${store.uasName} live: $url")
    }, "Share map link"))

    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        val BG = Color.parseColor("#15181c")
        val DIM = Color.parseColor("#9aa3ad")
    }
}
