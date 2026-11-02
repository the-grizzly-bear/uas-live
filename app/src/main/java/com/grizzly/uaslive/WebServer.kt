package com.grizzly.uaslive

import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Serves the page Pilot 2 opens as its "third-party cloud" (http://127.0.0.1:8722). */
class WebServer(private val port: Int, private val page: () -> String, private val status: () -> JSONObject) {
    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start(bindAll: Boolean = false) {
        if (running) return
        server = ServerSocket(port, 8, if (bindAll) null else InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
        running = true
        thread(name = "web", isDaemon = true) {
            while (running) {
                val s = try { server!!.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    runCatching {
                        s.use { sock ->
                            val req = sock.getInputStream().bufferedReader().readLine() ?: return@use
                            val path = req.split(" ").getOrNull(1) ?: "/"
                            val (type, body) = if (path.startsWith("/status")) "application/json" to status().toString()
                                else "text/html; charset=utf-8" to page()
                            val b = body.toByteArray()
                            sock.getOutputStream().apply {
                                write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\n" +
                                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
                                write(b); flush()
                            }
                        }
                    }
                }
            }
        }
    }

    fun stop() { running = false; runCatching { server?.close() } }

    companion object {
        private fun js(s: String) = JSONObject.quote(s)

        fun pilotPage(store: Store): String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>UAS Live</title>
<style>
body{background:#15181c;color:#eee;font:15px sans-serif;margin:16px}
h2{margin:0 0 4px}.ok{color:#7CFF4F}.bad{color:#ff6b6b}
#log{font:12px monospace;white-space:pre-wrap;background:#22262c;padding:8px;border-radius:6px;margin-top:12px}
button{font-size:16px;padding:10px 16px;margin-top:10px}
</style></head><body>
<h2>UAS Live</h2>
<div>Live position of <b>${store.uasName.replace("<", "")}</b> to CalTopo</div>
<div id="state">Starting…</div>
<button onclick="go()">Connect again</button>
<div id="log"></div>
<script>
var CFG = {appId: ${js(store.djiAppId)}, appKey: ${js(store.djiAppKey)}, license: ${js(store.djiLicense)},
  host: "tcp://127.0.0.1:${Store.MQTT_PORT}", user: ${js(Store.MQTT_USER)}, pass: ${js(store.mqttPass)}, name: ${js(store.uasName)}};
function log(m){ document.getElementById('log').textContent += m + "\n"; }
function state(m, ok){ var e=document.getElementById('state'); e.textContent=m; e.className = ok ? 'ok' : 'bad'; }
function res(name, raw){ log(name + " -> " + raw); try { return JSON.parse(raw); } catch(e) { return {code:-1, data:raw}; } }
window.thingConnected = function(arg){ log("thing callback: " + JSON.stringify(arg)); poll(); };
function poll(){
  var r = res("thingGetConnectState", window.djiBridge.thingGetConnectState());
  if (r.data === true) state("Connected. Leave Pilot 2 flying; positions go to CalTopo.", true);
  else state("Waiting for Pilot 2 to connect to UAS Live…", false);
}
function go(){
  document.getElementById('log').textContent = "";
  if (!window.djiBridge) { state("Open this page inside DJI Pilot 2: Cloud Service > Open Platform.", false); return; }
  if (!CFG.appId || !CFG.appKey || !CFG.license) { state("Enter the DJI Cloud API App ID / Key / License in UAS Live settings.", false); return; }
  var id = /^\d+$/.test(CFG.appId) ? Number(CFG.appId) : CFG.appId;
  res("platformVerifyLicense", window.djiBridge.platformVerifyLicense(id, CFG.appKey, CFG.license));
  var v = res("platformIsVerified", window.djiBridge.platformIsVerified());
  if (v.data !== true) { state("DJI license not accepted. Check App ID / Key / License.", false); return; }
  res("platformSetInformation", window.djiBridge.platformSetInformation("UAS Live", CFG.name, "Live drone position to CalTopo"));
  res("platformLoadComponent(thing)", window.djiBridge.platformLoadComponent("thing", JSON.stringify(
    {host: CFG.host, username: CFG.user, password: CFG.pass, connectCallback: "thingConnected"})));
  state("Connecting to UAS Live…", false);
  setTimeout(poll, 3000); setInterval(poll, 10000);
}
go();
</script></body></html>"""
    }
}
