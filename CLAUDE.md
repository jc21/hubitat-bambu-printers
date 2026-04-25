# CLAUDE.md — Hubitat Bambu Lab Printer Driver

## What this project is

A Hubitat Elevation device driver and companion app for Bambu Lab 3D printers. It communicates with the printer over its local MQTT broker (port 8883, TLS) — no Bambu cloud account required.

Two files:
- `bambulab-printer-driver.groovy` — device driver (MQTT, state, commands)
- `bambulab-printer-app.groovy` — companion app (notifications, automations, dashboard tile)

Compatible models: P1S, P1P, X1C, A1, A1 Mini, P2S — all use the same local MQTT protocol.

---

## Hubitat driver specifics

### Language and runtime
Groovy, running inside Hubitat's sandboxed script engine. No imports needed — Hubitat builtins are available globally. Access preferences via `settings.prefName` (explicit) or bare `prefName` (implicit, also works).

### MQTT interface
Use `interfaces.mqtt.*` — not a standard library, it's Hubitat's built-in MQTT client.

```groovy
interfaces.mqtt.connect(brokerUrl, clientId, username, password, options)
interfaces.mqtt.subscribe(topic, qos)
interfaces.mqtt.publish(topic, payload, qos, retained)
interfaces.mqtt.disconnect()
```

Incoming messages arrive via the `parse(String event)` method. Call `interfaces.mqtt.parseMessage(event)` inside it to get `.topic` and `.payload`.

### Critical: never subscribe inside `mqttClientStatus()`
Calling `interfaces.mqtt.subscribe()` directly inside the `mqttClientStatus()` callback silently fails on Hubitat — the MQTT client hasn't fully transitioned to connected state yet, so the SUBSCRIBE packet is never sent. The broker confirms the connection but the subscription never registers.

**The fix:** use `runIn(1, "subscribeAndRefresh")` inside the callback to defer the subscription by 1 second.

```groovy
// WRONG — subscribe is silently dropped
def mqttClientStatus(String status) {
    if (status.startsWith("Status: Connection succeeded")) {
        interfaces.mqtt.subscribe(topic, 1)  // never actually sent
    }
}

// CORRECT — defer outside the callback
def mqttClientStatus(String status) {
    if (status.startsWith("Status: Connection succeeded")) {
        runIn(1, "subscribeAndRefresh")
    }
}

def subscribeAndRefresh() {
    interfaces.mqtt.subscribe(topic, 1)  // works reliably
    refresh()
}
```

### `updated()` vs `initialize()`
- `initialize()` (triggered by the Initialize button or `installed()`) should reset all state and attributes via `initializeState()`
- `updated()` (triggered by saving preferences) should only reconnect — do **not** call `initializeState()` from `updated()` or every preference save will flash all attributes to "unknown"/0 on dashboards

### Logging
- `log.info`, `log.warn`, `log.error` always appear in Hubitat logs
- `log.debug` only shows when debug logging is enabled in preferences
- Hubitat log lines have a character limit (~800 chars). Large payloads (Bambu's pushall is ~14KB) get truncated. Parse the JSON first and log `groovy.json.JsonOutput.toJson(json)` to get compact single-line output.

### Child devices
The driver creates a Generic Component Switch child device for the chamber light, allowing it to be used independently in Rule Machine. The child forwards `componentOn()`/`componentOff()` up to `lightOn()`/`lightOff()` on the parent.

---

## Bambu MQTT protocol

Full spec: https://github.com/Doridian/OpenBambuAPI/blob/main/mqtt.md

### Connection
- **Direct:** `ssl://PRINTER_IP:8883`, username `bblp`, password = LAN access code, `ignoreSSLIssues: true` (self-signed cert)
- **Via relay:** `tcp://MOSQUITTO_IP:1883`, no credentials (relay handles auth)

### Topics
- Subscribe: `device/{SERIAL}/report` — all printer state pushed here
- Publish: `device/{SERIAL}/request` — commands go here

### Key commands

**Push full status** (needed on P1-series which only sends deltas):
```json
{"pushing": {"sequence_id": "1", "command": "pushall", "version": 1, "push_target": 1}}
```

**Print control** — pause, resume, stop all require `param: ""`:
```json
{"print": {"sequence_id": "1", "command": "resume", "param": ""}}
{"print": {"sequence_id": "1", "command": "pause",  "param": ""}}
{"print": {"sequence_id": "1", "command": "stop",   "param": ""}}
```
Send these at QoS 1. Missing `param` causes the printer to return `"mqtt message verify failed"`.

**LED control** — `loop_times: 0` for steady on, `loop_times: 0` for off:
```json
{
    "system": {
        "sequence_id": "1", "command": "ledctrl", "led_node": "chamber_light",
        "led_mode": "on", "led_on_time": 500, "led_off_time": 500,
        "loop_times": 0, "interval_time": 0
    }
}
```
`loop_times: 1` means "one flash cycle then off" — not what you want for steady on.

### Print state mapping (`gcode_state` → driver attribute)
| Printer value | Driver value |
|---|---|
| `running` | `printing` |
| `pause` | `paused` |
| `finish` | `finished` |
| `failed` | `error` |
| `idle` | `idle` |
| `prepare` | `preparing` |

### P1-series delta updates
The P1 series only sends fields that changed since the last report, not the full state every time. Always call `pushall` on connect to get baseline state. The driver tracks print start time locally (not from printer) to compute elapsed time, since the printer only reports remaining time in whole minutes.

### AMS filament detection
- `tray_now` on each AMS unit = active tray index ("0"–"3"), or "254"/"255" = no tray
- Fall back to first loaded tray if `tray_now` is unresolvable
- Fall back to `vt_tray` (external spool) if no AMS filament found
- `tray_color` is `RRGGBBAA` hex — strip the last two chars to get `#RRGGBB`

---

## TLS / Mosquitto relay

Bambu printers use a self-signed certificate. Hubitat's `ignoreSSLIssues: true` handles this for **direct** SSL connections.

If direct SSL doesn't work (known on some Hubitat hardware revisions — symptom: subscribes fine, commands work, but `parse()` is never called for incoming messages), use a Mosquitto bridge as a relay:

### Mosquitto bridge config
```conf
connection bambu-SERIAL
address PRINTER_IP:8883

bridge_cafile /mosquitto/config/bambu-ca.crt   # intermediate CA extracted from printer
bridge_insecure true                            # disables hostname verification
                                                # (NOT chain verification — needs cafile)

remote_username bblp
remote_password LAN_ACCESS_CODE

clientid mosquitto-bambu-bridge

topic device/SERIAL/report in 0
topic device/SERIAL/request out 0
```

**Extracting the cert chain:** The printer presents a two-level chain (leaf → BBL Device CA N7-V2 → BBL CA2 RSA root). Extract the intermediate CA (the second cert):
```bash
echo | openssl s_client -connect PRINTER_IP:8883 -showcerts 2>/dev/null \
  | awk '/-----BEGIN CERTIFICATE-----/{c++} c==2{print}' > bambu-ca.crt
```
The root CA (BBL CA2 RSA) is not sent by the printer and is not publicly distributed. `bridge_insecure true` handles hostname mismatch; the cafile handles chain trust up to the intermediate.

**If your Mosquitto host is ARM (e.g. armv8):** `dweomer/stunnel` doesn't support ARM. Use a custom Alpine-based image instead:
```dockerfile
FROM alpine:latest
RUN apk add --no-cache stunnel
ENTRYPOINT ["stunnel"]
CMD ["/etc/stunnel/stunnel.conf"]
```

### Hubitat driver relay preferences
- **MQTT Relay Host:** IP of Mosquitto instance
- **MQTT Relay Port:** 1883 (default)

When relay host is set, driver connects `tcp://` with no credentials; Mosquitto bridge handles the authenticated TLS connection to the printer.

---

## Known limitations

### Print control commands (pause/resume/stop) fail with `"mqtt message verify failed"`
Newer Bambu firmware (approximately v01.07+) requires print control commands to be authenticated when the printer is in cloud-connected mode. LED control and status reading work fine — only print control is restricted.

Error response looks like:
```json
{"print": {"command": "resume", "err_code": 84033543, "reason": "mqtt message verify failed", "result": "failed"}}
```

This is **not a driver bug** — the command format matches the official spec exactly. It is a printer configuration issue. Options:
1. Block the printer from internet access at the router (forces pure local operation)
2. Enable developer/LAN-only mode on the printer touchscreen (Settings → Network)
3. Add Bambu cloud credentials to the driver (cloud MQTT uses `u_{USER_ID}` as username and access token as password — would need new preferences)

---

## Reconnect / watchdog logic

- Exponential backoff on failed connections: 30s → 60s → 120s → 300s (cap)
- `state.suppressReconnect = true` in `disconnect()` prevents the `mqttClientStatus()` disconnect callback from scheduling an unwanted reconnect
- `scheduledRefresh()` detects silent connection death (no messages for 3× refresh interval) and forces a full reconnect
- `state.lastMessageTime` is updated on every received message and used by the watchdog
