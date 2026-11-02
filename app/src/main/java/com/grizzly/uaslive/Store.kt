package com.grizzly.uaslive

import android.content.Context
import java.security.SecureRandom

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("uaslive", Context.MODE_PRIVATE)
    private fun s(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun set(k: String, v: String) = sp.edit().putString(k, v.trim()).apply()

    var uasName: String get() = s("uasName", "UAS-1"); set(v) = set("uasName", v.ifBlank { "UAS-1" })
    var sendPosition: Boolean get() = sp.getBoolean("sendPosition", true); set(v) = sp.edit().putBoolean("sendPosition", v).apply()
    var intervalSec: Int get() = sp.getInt("interval", 3); set(v) = sp.edit().putInt("interval", v.coerceIn(1, 60)).apply()

    var djiAppId: String get() = s("djiAppId"); set(v) = set("djiAppId", v)
    var djiAppKey: String get() = s("djiAppKey"); set(v) = set("djiAppKey", v)
    var djiLicense: String get() = s("djiLicense"); set(v) = set("djiLicense", v)

    var ctAcct: String get() = s("ctAcct"); set(v) = set("ctAcct", v)
    var ctCred: String get() = s("ctCred"); set(v) = set("ctCred", v)
    var ctKey: String get() = s("ctKey"); set(v) = set("ctKey", v)
    val calTopo: CalTopo? get() = if (ctAcct.isNotBlank() && ctCred.isNotBlank() && ctKey.isNotBlank()) CalTopo(ctAcct, ctCred, ctKey) else null

    /** Random, per-install CalTopo device address (the report URL has no login, so it must not be guessable). */
    val group: String get() = s("group").ifBlank { rand(12).also { set("group", it) } }
    val deviceId: String get() = s("deviceId").ifBlank { rand(8).also { set("deviceId", it) } }
    /** Password Pilot 2 uses for our local MQTT broker. */
    val mqttPass: String get() = s("mqttPass").ifBlank { rand(16).also { set("mqttPass", it) } }

    companion object {
        const val MQTT_USER = "pilot"
        const val MQTT_PORT = 1883
        const val WEB_PORT = 8722
        private fun rand(n: Int): String {
            val a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; val r = SecureRandom()
            return (1..n).map { a[r.nextInt(a.length)] }.joinToString("")
        }
    }
}
