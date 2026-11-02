package com.grizzly.uaslive

import org.json.JSONObject
import java.util.UUID

/** One live sample of the drone. */
data class Fix(
    val sn: String, val lat: Double, val lon: Double,
    val heightEllipsoid: Double?, val elevationRel: Double?,
    val heading: Double?, val speed: Double?, val battery: Int?, val t: Long,
    val receivedAt: Long = System.currentTimeMillis(),
)

/**
 * DJI Cloud API "thing model" over MQTT, gateway = the remote controller running Pilot 2.
 *  up:   sys|thing/product/{gw}/status (update_topo), thing/product/{sn}/osd, thing/product/{gw}/requests
 *  down: sys|thing/product/{gw}/status_reply, thing/product/{gw}/requests_reply
 * Pilot 2 keeps retrying and never streams OSD until update_topo is acknowledged.
 */
class DjiLink(
    private val publish: (topic: String, payload: ByteArray) -> Unit,
    private val onFix: (Fix) -> Unit,
    private val log: (String) -> Unit,
) {
    @Volatile var gateway: String? = null; private set
    @Volatile var aircraft: String? = null; private set
    @Volatile var lastOsdAt = 0L; private set

    private val autoAck = setOf("config", "airport_bind_status", "airport_organization_get",
        "airport_organization_bind", "flighttask_progress")

    fun onMessage(topic: String, payload: ByteArray) {
        val parts = topic.split('/')
        if (parts.size < 4 || parts[1] != "product") return
        val ns = parts[0]; val sn = parts[2]; val kind = parts[3]
        val body = runCatching { JSONObject(String(payload)) }.getOrNull() ?: return
        when {
            kind == "status" && (ns == "sys" || ns == "thing") -> {
                gateway = sn
                val data = body.optJSONObject("data")
                val subs = data?.optJSONArray("sub_devices") ?: data?.optJSONArray("sub_device")
                val drone = subs?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("domain") == "0" } }
                aircraft = drone?.optString("sn")?.takeIf { it.isNotBlank() } ?: aircraft
                reply("$ns/product/$sn/status_reply", body, "update_topo", JSONObject().put("result", 0))
                log("Controller $sn online" + (aircraft?.let { ", drone $it" } ?: ", no drone yet"))
            }
            ns == "thing" && kind == "requests" -> {
                val method = body.optString("method")
                if (method in autoAck) reply("thing/product/$sn/requests_reply", body, method, requestData(method, body))
            }
            ns == "thing" && kind == "osd" -> {
                val d = body.optJSONObject("data") ?: return
                if (!d.has("latitude") || !d.has("longitude")) return          // controller's own OSD has no aircraft fix
                val lat = d.optDouble("latitude"); val lon = d.optDouble("longitude")
                if (lat.isNaN() || lon.isNaN() || (kotlin.math.abs(lat) < 1e-6 && kotlin.math.abs(lon) < 1e-6)) return
                if (gateway != null && sn == gateway) return                       // the controller's own GPS, not the drone
                lastOsdAt = System.currentTimeMillis()
                onFix(Fix(sn, lat, lon, d.optNum("height"), d.optNum("elevation"), d.optNum("attitude_head"),
                    d.optNum("horizontal_speed"), d.optJSONObject("battery")?.optInt("capacity_percent", -1)?.takeIf { it >= 0 },
                    body.optLong("timestamp", System.currentTimeMillis())))
            }
        }
    }

    private fun JSONObject.optNum(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

    private fun reply(topic: String, inbound: JSONObject, method: String, data: JSONObject) {
        val out = JSONObject()
            .put("tid", inbound.optString("tid").ifBlank { UUID.randomUUID().toString() })
            .put("bid", inbound.optString("bid").ifBlank { UUID.randomUUID().toString() })
            .put("timestamp", System.currentTimeMillis())
            .put("method", method)
            .put("data", data)
        publish(topic, out.toString().toByteArray())
    }

    private fun requestData(method: String, body: JSONObject): JSONObject {
        val inbound = body.optJSONObject("data") ?: JSONObject()
        val r = JSONObject().put("result", 0)
        when (method) {
            "airport_organization_get" -> r.put("output", JSONObject().put("organization_name", "UAS Live"))
            "airport_bind_status" -> {
                val list = org.json.JSONArray()
                inbound.optJSONArray("devices")?.let { a ->
                    for (i in 0 until a.length()) list.put(JSONObject().put("sn", a.getJSONObject(i).optString("sn"))
                        .put("is_bind", true).put("bind_organization_id", "uas-live").put("bind_organization_name", "UAS Live"))
                }
                r.put("output", JSONObject().put("bind_status_list", list))
            }
            "airport_organization_bind" -> r.put("output", JSONObject().put("err_infos", org.json.JSONArray()))
        }
        return r
    }
}
