#!/usr/bin/env python3
"""Pretend to be DJI Pilot 2 talking to UAS Live's MQTT broker (Cloud API thing model).

usage: sim_pilot.py HOST PORT PASSWORD [--seconds 60] [--lat 38.84 --lon -106.13]
Checks the handshake replies and streams drone OSD in a lawnmower pattern (plus controller OSD, which must be ignored).
"""
import argparse, json, math, socket, struct, threading, time, uuid

GW, DRONE = "SIMRC0001", "SIMUAV0001"


def enc_str(s):
    b = s.encode(); return struct.pack("!H", len(b)) + b


def var_int(n):
    out = bytearray()
    while True:
        d = n % 128; n //= 128
        out.append(d | (0x80 if n else 0))
        if not n: return bytes(out)


def packet(h, body):
    return bytes([h]) + var_int(len(body)) + body


class Client:
    def __init__(self, host, port, user, pw):
        self.s = socket.create_connection((host, port), timeout=10)
        self.replies = []
        body = enc_str("MQTT") + bytes([4, 0xC2]) + struct.pack("!H", 60) + enc_str("pilot2-sim") + enc_str(user) + enc_str(pw)
        self.s.sendall(packet(0x10, body))
        ack = self.read()
        assert ack[0] == 0x20 and ack[1][1] == 0, f"CONNACK refused: {ack}"
        threading.Thread(target=self.reader, daemon=True).start()
        self.pid = 1

    def read(self):
        h = self.s.recv(1)
        if not h: raise EOFError
        mul, n = 1, 0
        while True:
            d = self.s.recv(1)[0]; n += (d & 127) * mul
            if not d & 128: break
            mul *= 128
        body = b""
        while len(body) < n: body += self.s.recv(n - len(body))
        return h[0], body

    def reader(self):
        try:
            while True:
                h, body = self.read()
                if h >> 4 == 3:
                    tl = struct.unpack("!H", body[:2])[0]
                    topic = body[2:2 + tl].decode(); payload = body[2 + tl:]
                    self.replies.append((topic, json.loads(payload)))
        except Exception:
            pass

    def subscribe(self, topics):
        body = struct.pack("!H", self.pid) + b"".join(enc_str(t) + b"\x01" for t in topics); self.pid += 1
        self.s.sendall(packet(0x82, body))

    def publish(self, topic, obj, qos=0):
        body = enc_str(topic)
        if qos: body += struct.pack("!H", self.pid); self.pid += 1
        self.s.sendall(packet(0x30 | (qos << 1), body + json.dumps(obj).encode()))


def msg(method=None, data=None):
    m = {"tid": str(uuid.uuid4()), "bid": str(uuid.uuid4()), "timestamp": int(time.time() * 1000), "data": data or {}}
    if method: m["method"] = method
    return m


def wait_reply(c, topic, timeout=5):
    t0 = time.time()
    while time.time() - t0 < timeout:
        for t, p in c.replies:
            if t == topic: return p
        time.sleep(0.1)
    return None


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("host"); ap.add_argument("port", type=int); ap.add_argument("password")
    ap.add_argument("--seconds", type=int, default=60); ap.add_argument("--lat", type=float, default=38.8400)
    ap.add_argument("--lon", type=float, default=-106.1300)
    a = ap.parse_args()
    c = Client(a.host, a.port, "pilot", a.password); print("connected to broker")
    c.subscribe([f"sys/product/{GW}/status_reply", f"thing/product/{GW}/status_reply", f"thing/product/{GW}/requests_reply"])
    time.sleep(0.3)
    topo = msg("update_topo", {"domain": "2", "type": 144, "sub_type": 0, "sub_devices": [{"sn": DRONE, "domain": "0", "type": 77, "sub_type": 1, "index": "A"}]})
    c.publish(f"sys/product/{GW}/status", topo, qos=1)
    r = wait_reply(c, f"sys/product/{GW}/status_reply")
    print("status_reply:", "OK" if r and r.get("data", {}).get("result") == 0 and r.get("tid") == topo["tid"] else f"MISSING/BAD {r}")
    req = msg("config", {"config_type": "json", "config_scope": "product"})
    c.publish(f"thing/product/{GW}/requests", req, qos=1)
    r = wait_reply(c, f"thing/product/{GW}/requests_reply")
    print("requests_reply(config):", "OK" if r and r.get("data", {}).get("result") == 0 else f"MISSING/BAD {r}")

    t0 = time.time(); i = 0
    while time.time() - t0 < a.seconds:
        k = i * 0.5
        leg, pos = int(k // 20), (k % 20) / 20
        x = pos if leg % 2 == 0 else 1 - pos
        lat = a.lat + leg * 0.0006 + (0.00005 * math.sin(k))
        lon = a.lon + x * 0.002
        c.publish(f"thing/product/{DRONE}/osd", msg(data={"latitude": lat, "longitude": lon, "height": 2550.0 + leg, "elevation": 60.0,
                                                         "attitude_head": 90 if leg % 2 == 0 else -90, "horizontal_speed": 8.0,
                                                         "battery": {"capacity_percent": 90 - i // 20}}))
        if i % 4 == 0:   # controller's own GPS at launch: must NOT become the drone track
            c.publish(f"thing/product/{GW}/osd", msg(data={"latitude": a.lat - 0.003, "longitude": a.lon - 0.003, "capacity_percent": 80}))
        i += 1; time.sleep(0.5)
    print(f"sent {i} drone OSD frames")


if __name__ == "__main__":
    main()
