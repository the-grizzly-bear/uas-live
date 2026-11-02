package com.grizzly.uaslive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.concurrent.thread

/** Shared state for the screen. */
object Tracker {
    val logLines = ConcurrentLinkedDeque<String>()
    @Volatile var running = false
    @Volatile var lastFix: Fix? = null
    @Volatile var sent = 0
    @Volatile var queued = 0
    @Volatile var lastSentAt = 0L
    @Volatile var lastError: String? = null
    @Volatile var mapUrl: String? = null
    @Volatile var pilotConnected = false
    @Volatile var gateway: String? = null
    @Volatile var aircraft: String? = null
    private val hm = SimpleDateFormat("HH:mm:ss", Locale.US)
    fun log(m: String) {
        logLines.addFirst("${hm.format(Date())}  $m")
        while (logLines.size > 60) logLines.removeLast()
    }
}

class TrackerService : Service() {
    private lateinit var store: Store
    private var broker: MqttBroker? = null
    private var web: WebServer? = null
    private var link: DjiLink? = null
    @Volatile private var stop = false
    private val queue = ConcurrentLinkedDeque<Fix>()
    private var mapDay = ""; private var mapId: String? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (Tracker.running) return START_STICKY
        store = Store(this)
        startForeground(1, notification("Waiting for Pilot 2…"))
        Tracker.running = true; stop = false
        val l = DjiLink(publish = { t, p -> broker?.publish(t, p) }, onFix = ::onFix, log = Tracker::log)
        link = l
        broker = MqttBroker(Store.MQTT_PORT, Store.MQTT_USER, store.mqttPass, l::onMessage, Tracker::log).also { it.start() }
        web = WebServer(Store.WEB_PORT, { WebServer.pilotPage(store) }, ::status).also { it.start() }
        Tracker.log("Started. In Pilot 2 open: http://127.0.0.1:${Store.WEB_PORT}")
        thread(name = "sender", isDaemon = true) { senderLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        stop = true; Tracker.running = false; Tracker.pilotConnected = false
        broker?.stop(); web?.stop()
        Tracker.log("Stopped")
        super.onDestroy()
    }

    private var lastQueuedAt = 0L
    private fun onFix(f: Fix) {
        Tracker.lastFix = f; Tracker.aircraft = f.sn
        val now = System.currentTimeMillis()
        if (now - lastQueuedAt >= store.intervalSec * 1000L) {
            lastQueuedAt = now
            queue.addLast(f)
            while (queue.size > 2000) queue.removeFirst()            // ~1.5 h at 3 s if offline the whole time
        }
    }

    private fun senderLoop() {
        var lastNote = ""
        while (!stop) {
            Tracker.pilotConnected = (broker?.connectedClients ?: 0) > 0
            Tracker.gateway = link?.gateway
            Tracker.queued = queue.size
            val ct = store.calTopo
            val f = queue.peekFirst()
            if (!store.sendPosition) queue.clear()
            if (f != null && ct == null && store.sendPosition) Tracker.lastError = "Not signed in to CalTopo (Settings)"
            if (f != null && ct != null && store.sendPosition) {
                try {
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(f.t))
                    if (day != mapDay || mapId == null) {
                        val id = ct.dayMap("UAS $day", f.lat, f.lon)
                        ct.ensureLiveTrack(id, "FLEET:${store.group}-${store.deviceId}", store.uasName)
                        mapId = id; mapDay = day; Tracker.mapUrl = "https://caltopo.com/m/$id"
                        Tracker.log("Live track '${store.uasName}' on map UAS $day")
                    }
                    // send the backlog oldest-first, then keep up with live
                    var n = 0
                    while (!stop && n < 50) {
                        val q = queue.peekFirst() ?: break
                        CalTopo.report(store.group, store.deviceId, q.lat, q.lon, q.heightEllipsoid?.let { it * 3.28084 })
                        queue.removeFirst(); Tracker.sent++; Tracker.lastSentAt = System.currentTimeMillis(); n++
                    }
                    Tracker.lastError = null
                } catch (e: Exception) {
                    Tracker.lastError = "Send failed (no internet?): ${e.message}"
                }
            }
            val note = when {
                !Tracker.pilotConnected -> "Waiting for Pilot 2…"
                Tracker.lastFix == null -> "Pilot 2 connected, waiting for drone position"
                !store.sendPosition -> "${store.uasName}: connected (position sending off)"
                Tracker.lastError != null -> "${store.uasName}: offline, ${queue.size} queued"
                else -> "${store.uasName}: live (${Tracker.sent} sent)"
            }
            if (note != lastNote) { lastNote = note; getSystemService(NotificationManager::class.java).notify(1, notification(note)) }
            Thread.sleep(1000)
        }
    }

    private fun status(): JSONObject = JSONObject()
        .put("uas", store.uasName).put("pilotConnected", Tracker.pilotConnected)
        .put("gateway", Tracker.gateway).put("aircraft", Tracker.aircraft)
        .put("sent", Tracker.sent).put("queued", queue.size).put("map", Tracker.mapUrl).put("error", Tracker.lastError)
        .put("lastFix", Tracker.lastFix?.let { JSONObject().put("lat", it.lat).put("lon", it.lon).put("t", it.t) })

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("t", "UAS Live", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "t") else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("UAS Live").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open).setOngoing(true).build()
    }

    companion object {
        const val ACTION_STOP = "stop"
        fun start(c: Context) {
            val i = Intent(c, TrackerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun stop(c: Context) { c.startService(Intent(c, TrackerService::class.java).setAction(ACTION_STOP)) }
    }
}
