package com.grizzly.uaslive

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * CalTopo live tracking with the user's own credential (personal account).
 *  - day map "UAS yyyy-MM-dd" (shared with Drone Route), found by title or created
 *  - a LiveTrack object on it, deviceId "FLEET:<group>-<id>"
 *  - positions via GET /api/v1/position/report/<group>?id=<id>&lat=&lng=&ele=
 */
class CalTopo(private val acct: String, private val credId: String, private val key: String) {

    fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val expires = System.currentTimeMillis() + 120_000
        val payload = body?.toString() ?: ""
        val msg = "$method $path\n$expires\n" + (if (method == "POST") payload else "")
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(android.util.Base64.decode(key.trim(), android.util.Base64.DEFAULT), "HmacSHA256"))
        }
        val sig = android.util.Base64.encodeToString(mac.doFinal(msg.toByteArray()), android.util.Base64.NO_WRAP)
        val form = listOf("id" to credId, "expires" to expires.toString(), "signature" to sig, "json" to payload)
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val c = URL("https://caltopo.com$path" + if (method == "POST") "" else "?$form").openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "UASLive/0.1")
        if (method == "POST") {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) throw IllegalStateException("CalTopo $method $path -> HTTP $code ${text.take(160)}")
        return JSONObject(text.ifBlank { "{}" })
    }

    fun dayMap(title: String, lat: Double, lon: Double): String {
        val feats = call("GET", "/api/v1/acct/$acct/since/0").getJSONObject("result").optJSONArray("features") ?: JSONArray()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i); val p = f.optJSONObject("properties") ?: continue
            if (p.optString("class") == "CollaborativeMap" && p.optString("accountId") == acct && p.optString("title") == title)
                return f.getString("id")
        }
        val body = JSONObject()
            .put("properties", JSONObject().put("title", title).put("mode", "sar").put("sharing", "URL")
                .put("mapConfig", JSONObject().put("activeLayers", JSONArray().put(JSONArray().put("mbt").put(1)))))
            .put("state", JSONObject().put("type", "FeatureCollection").put("features", JSONArray().put(
                JSONObject().put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat)))
                    .put("properties", JSONObject().put("class", "Marker").put("title", "UAS launch")))))
        return call("POST", "/api/v1/acct/$acct/CollaborativeMap", body).getJSONObject("result").getString("id")
    }

    /** Returns the existing LiveTrack for this device on the map, or creates it. */
    fun ensureLiveTrack(mapId: String, deviceId: String, title: String, color: String = "#FF8A00"): String {
        val feats = call("GET", "/api/v1/map/$mapId/since/0").getJSONObject("result")
            .optJSONObject("state")?.optJSONArray("features") ?: JSONArray()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i); val p = f.optJSONObject("properties") ?: continue
            if (p.optString("class") == "LiveTrack" && p.optString("deviceId") == deviceId) return f.getString("id")
        }
        return call("POST", "/api/v1/map/$mapId/LiveTrack", JSONObject().put("properties", JSONObject()
            .put("title", title).put("deviceId", deviceId)
            .put("stroke", color).put("stroke-width", 3).put("stroke-opacity", 1).put("pattern", "solid")))
            .getJSONObject("result").getString("id")
    }

    companion object {
        const val ACTIVATE_PAGE = "https://caltopo.com/app/activate/offline?redirect=localhost"

        /** Unauthenticated position report for a LiveTrack device ("FLEET:<group>-<id>"). */
        fun report(group: String, id: String, lat: Double, lon: Double, ele: Double?) {
            val q = "id=" + URLEncoder.encode(id, "UTF-8") + "&lat=%.7f&lng=%.7f".format(java.util.Locale.US, lat, lon) +
                (ele?.let { "&ele=%.1f".format(java.util.Locale.US, it) } ?: "")
            val c = URL("https://caltopo.com/api/v1/position/report/" + URLEncoder.encode(group, "UTF-8") + "?" + q)
                .openConnection() as HttpURLConnection
            c.connectTimeout = 8_000; c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "UASLive/0.1")
            val code = c.responseCode
            runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.close() }
            if (code !in 200..299) throw IllegalStateException("position report HTTP $code")
        }

        fun extractCode(s: String): String? =
            Regex("""code=([A-Za-z0-9]{6,12})""").find(s)?.groupValues?.get(1)
                ?: s.trim().takeIf { Regex("^[A-Za-z0-9]{6,12}$").matches(it) }

        fun activate(code: String): Triple<String, String, String> {
            val c = URL("https://caltopo.com/api/v1/activate?code=" + URLEncoder.encode(code, "UTF-8")).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000; c.setRequestProperty("User-Agent", "UASLive/0.1")
            val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (c.responseCode !in 200..299) throw IllegalStateException("Activation failed (HTTP ${c.responseCode}). Codes are single-use.")
            val o = JSONObject(text)
            return Triple(o.getJSONObject("account").getString("id"), o.getString("code"), o.getString("key"))
        }
    }
}
