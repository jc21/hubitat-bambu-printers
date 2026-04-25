# Bambu Lab 3D Printer — Hubitat Integration

A custom Hubitat Elevation driver and companion app for monitoring and controlling your **Bambu Lab** 3D printer directly from your home automation platform — no cloud required.

Communication happens over the printer's built-in local MQTT broker (TLS, port 8883) using your LAN access code. Once installed, the printer appears as a standard Hubitat device with attributes you can read in dashboards, trigger on in Rule Machine, and act on through automations.

The chamber light also appears as an independently controllable child device.


**Note** - Only one MQTT connection can be active to a printer at a time. If you have Handy or Studio open, the connection may fail. The driver has error handling for this scenario and should recover gracefully, but I have not tested this extensively yet.

---

## Features

- **Live print status** — idle, printing, paused, finished, error
- **Print progress** — completion percentage (0–100%)
- **Elapsed time** — how long the current job has been running
- **Remaining time** — printer's own estimate of time left
- **Filament info** — material type (PLA, PETG, ABS, ASA, …) and color from the AMS or external spool
- **Chamber light control** — read state and toggle on/off
- **Temperature monitoring** — live nozzle and bed temperatures
- **Print controls** — pause, resume, and stop from Hubitat
- **Push notifications** — alerts on print start, finish, pause, error, and selectable progress milestones
- **Switch automations** — turn switches/dimmers on or off when prints start, finish, or encounter errors
- **Dashboard summary tile** — a formatted single-attribute summary for dashboard widgets

---

## Requirements

- **Hubitat Elevation** hub — tested on firmware 2.4.4.156 (C-8 Pro), but should work on any recent version
- **Bambu Lab** 3D printer with firmware that exposes a local MQTT broker
- A **static IP address** assigned to your printer in your router
- The printer's **LAN Access Code** (found on the touchscreen)
- The printer's **Serial Number** (found on the touchscreen or in Bambu Studio)

> **Firmware note:** Bambu Lab firmware released after approximately May 2024 restricts local MQTT to LAN mode only. If you are on recent firmware and experiencing connection issues, ensure your printer is set to LAN mode under **Settings → Network** on the touchscreen. The LAN Access Code remains valid regardless of whether LAN-only mode is enabled.

---

## Files

| File | Purpose |
|---|---|
| `bambulab-printer-driver.groovy` | Hubitat device driver — handles MQTT, parses printer telemetry, exposes attributes and commands |
| `bambulab-printer-app.groovy` | Hubitat user app — notifications, automations, and dashboard summary |

---

## Installation

### 1. Find your printer credentials

Before installing anything in Hubitat, collect the following from your printer's touchscreen:

| Value | Where to find it |
|---|---|
| **IP Address** | Settings → Network (assign a static IP in your router) |
| **Serial Number** | Settings → Device Info, or visible in Bambu Studio / Bambu Handy |
| **LAN Access Code** | Settings → Network |

### 2. Install the Driver

1. In the Hubitat web UI, go to **Drivers Code** (under Developer Tools in the sidebar).
2. Click **New Driver**.
3. Paste the full contents of `bambulab-printer-driver.groovy`.
4. Click **Save**.

### 3. Create the Device

1. Go to **Devices** → **Add Device** → **Virtual**.
2. Give the device a name (e.g. *Bambu P1S Printer*).
3. Set the **Type** to **Bambu Lab Printer**.
4. Click **Create Device**.
5. On the device detail page, scroll to **Preferences** and fill in:
   - **Printer IP Address** — e.g. `192.168.1.50`
   - **Printer Serial Number** — e.g. `01S00C123456789`
   - **LAN Access Code** — e.g. `12345678`
   - **Status Refresh Interval** — how often (in seconds) to request a full status push from the printer; 120 seconds is a good default
   - **Enable Debug Logging** — turn on temporarily if troubleshooting
6. Click **Save Preferences**. The driver will connect to the printer's MQTT broker immediately.

If the connection succeeds, you will see **connectionStatus: connected** in the device's Current States within a few seconds.

### 4. Install the App

1. Go to **Apps Code** → **New App**.
2. Paste the full contents of `bambulab-printer-app.groovy`.
3. Click **Save**.
4. Go to **Apps** → **Add User App** → **Bambu Lab P1S Manager**.
5. Select your printer device and configure notifications and automations as desired.
6. Click **Done**.

---

## Driver Reference

### Attributes

| Attribute | Type | Example | Description |
|---|---|---|---|
| `printerStatus` | string | `printing` | Current printer state: `idle`, `printing`, `paused`, `finished`, `error`, `preparing`, `unknown` |
| `printProgress` | number | `67` | Completion percentage (0–100) |
| `printElapsed` | string | `1:23:45` | Time elapsed since print started (H:MM:SS) |
| `printRemaining` | string | `0:45:00` | Estimated time remaining per printer (H:MM:SS) |
| `filamentType` | string | `PLA` | Filament material type reported by AMS or external spool |
| `filamentColor` | string | `#DFE2E3` | Filament color as a hex RGB value |
| `chamberLight` | string | `on` | Chamber light state: `on` or `off` |
| `currentFile` | string | `benchy.gcode` | Name of the gcode file currently printing |
| `nozzleTemp` | number | `220` | Current nozzle temperature in °C |
| `bedTemp` | number | `65` | Current bed temperature in °C |
| `connectionStatus` | string | `connected` | MQTT connection state: `connected` or `disconnected` |

### Commands

| Command | Description |
|---|---|
| `lightOn()` | Turn the chamber light on |
| `lightOff()` | Turn the chamber light off |
| `on()` | Alias for `lightOn()` — satisfies the Switch capability for Rule Machine |
| `off()` | Alias for `lightOff()` |
| `pausePrint()` | Pause the current print job |
| `resumePrint()` | Resume a paused print job |
| `stopPrint()` | Stop (cancel) the current print job |
| `refresh()` | Request a full status push from the printer immediately |
| `connect()` | Manually initiate the MQTT connection |
| `disconnect()` | Disconnect from the MQTT broker |

### Capabilities

The driver implements the following Hubitat capabilities, which controls how it appears as a selectable device type in Rule Machine and other apps:

- `Initialize`
- `Refresh`
- `Switch` (mapped to chamber light on/off)
- `Sensor`
- `Actuator`

---

## App Reference

### Notifications

The app can send push or SMS notifications to any Hubitat-compatible notification device (e.g. the Hubitat mobile app). Configurable triggers:

| Trigger | Description |
|---|---|
| Print started | Fires when `printerStatus` changes to `printing` |
| Print finished | Fires when `printerStatus` changes to `finished`; includes file name and elapsed time |
| Print paused | Fires when `printerStatus` changes to `paused` |
| Printer error | Fires when `printerStatus` changes to `error` |
| Filament type changed | Fires when `filamentType` changes to a new value |
| Progress milestones | Fires once each when print progress passes 25%, 50%, 75%, or 90% — select which milestones you want |

### Automations

Switches and dimmers can be controlled automatically based on printer events:

| Trigger | Available actions |
|---|---|
| Print starts | Turn switches ON / Turn switches OFF |
| Print finishes | Turn switches ON / Turn switches OFF / Set dimmers to a level |
| Printer error | Turn switches ON |

An optional **hub mode restriction** lets you limit automations to specific modes (e.g. Home, Away).

The **Auto Chamber Light** option automatically turns the printer's internal light on when printing begins and off when the print finishes.

### Dashboard Summary Tile

If you select an optional **Summary Virtual Device** in the app, the app writes a formatted multi-line status string to a `bambuSummary` attribute on that device every minute. This is useful for placing a single tile on a Hubitat dashboard that shows all key printer info at a glance. Any device that accepts custom events (such as a Virtual Omni Sensor) will work.

Example output:
```
🟢 PRINTING  |  67%
File: benchy_0.20mm_PLA.gcode
Elapsed: 1:23:45  |  Remaining: 0:45:00
Filament: PLA (#DFE2E3)
Nozzle: 220°C  |  Bed: 65°C
Light: on  |  MQTT: connected
```

---

## How It Works

The Bambu Lab P1S runs its own MQTT broker on port 8883 with TLS. The printer continuously publishes JSON status messages to the topic `device/{SERIAL}/report` and accepts commands on `device/{SERIAL}/request`.

The driver connects using username `bblp` and your LAN access code as the password. Because the printer uses a self-signed TLS certificate, the driver accepts it without requiring you to import the certificate into Hubitat.

The P1 series sends only *changed* fields in each status update (unlike the X1 series, which sends full state every time). The driver accumulates state across updates. A periodic `pushall` command is sent at the configured refresh interval to force the printer to emit a complete state snapshot, which keeps all attributes current even if a change was missed.

Elapsed print time is tracked locally by the driver from the moment it observes a transition into the `running` gcode state, since the P1S does not directly report a start timestamp over MQTT.

---

## Troubleshooting

**Device shows `connectionStatus: disconnected` immediately after saving preferences**
- Confirm the printer IP is correct and reachable from the Hubitat hub (try pinging it).
- Confirm the LAN access code is correct — copy it carefully from the touchscreen.
- Check that your network does not block port 8883 between the hub and printer.
- Enable **Debug Logging** in the device preferences and check the Hubitat logs for specific error messages.

**Attributes update only when `refresh()` is called, not continuously**
- This is expected behavior on recent Bambu firmware. The printer sends delta updates that may come infrequently when little is changing. Reduce the **Status Refresh Interval** in preferences (e.g. to 60 seconds) if you want more frequent full-state updates.

**`filamentType` shows `—` even with filament loaded**
- If you are using an external spool (no AMS), the filament information comes from the `vt_tray` field. This is supported. If no filament type is being reported, the spool may not have an RFID tag — in that case the printer reports an empty type.

**`printElapsed` resets to `—` unexpectedly**
- Elapsed time is computed on the hub from when the driver first observes the `running` state. If the hub or driver restarts mid-print, the elapsed counter resets. The `printRemaining` value always comes directly from the printer and is not affected.

**Hub logs show repeated reconnect attempts**
- If the printer is powered off or unreachable, the driver will attempt to reconnect every 30 seconds. This is intentional. The log entries will stop as soon as the printer comes back online.

---

## Contributing

Pull requests are welcome. If you encounter a JSON field in your printer's MQTT output that isn't being parsed, please open an issue and include a sanitized sample of the payload (remove your serial number and access code).

---

## Disclaimer

This integration uses an unofficial, reverse-engineered local API. It is not affiliated with or endorsed by Bambu Lab. Use at your own risk. Sending `stopPrint()` or `pausePrint()` commands will affect an active print job — confirm before using these in automations.

---

## License

Apache License 2.0 — see `LICENSE` for details.
