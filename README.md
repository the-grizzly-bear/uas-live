# UAS Live

Live drone position from DJI Pilot 2 to CalTopo, so the drone shows up on the incident map like a searcher's phone.

## How it works
- Runs on the DJI controller next to Pilot 2.
- Pilot 2's Cloud Service connects to it (it acts as the "third-party cloud", all on the controller).
- It sends the drone's position to CalTopo as a live device named after your **UAS name**, on the day map `UAS yyyy-MM-dd`.
- Needs internet on the controller (phone hotspot / Starlink / wifi). Positions queue while offline and send when back.

## Setup (once per controller)
- **DJI Cloud API app**: developer.dji.com → My Apps → Create App → type **Cloud API** → activate → copy **App ID, App Key, App License** → UAS Live **Settings**.
- **CalTopo**: Settings → **Sign in to CalTopo** (or type account ID / credential ID / key).
- **UAS name**: type it on the main screen (e.g. `UAS-1`) → Save.

## Each flight
- UAS Live → **Start**.
- Pilot 2 → Cloud Service → **Open Platform** → address `http://127.0.0.1:8722` → connect. The page should say **Connected**.
- Fly as normal. The drone appears live in CalTopo. Tap the map line in UAS Live to share the link.

## Testing without a drone
- `python3 tools/sim_pilot.py HOST PORT MQTT_PASSWORD --seconds 60` pretends to be Pilot 2 (handshake + moving drone).

## License
Free for search and rescue, public safety and other noncommercial use.
Please don't redistribute or modify it outside of contributing back here.
Issues, feature requests and pull requests welcome. Making changes to submit
a pull request is fine; by submitting one, you agree it can be included and
licensed under the same terms.
PolyForm Strict 1.0.0, see LICENSE.
