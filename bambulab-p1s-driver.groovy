/**
 *  Bambu Lab P1S 3D Printer - Hubitat Driver
 *
 *  Communicates with the Bambu Lab P1S over its local MQTT broker (port 8883, TLS).
 *  No cloud account required — uses LAN access code only.
 *
 *  Attributes exposed:
 *    - printerStatus     : idle / printing / paused / finished / error
 *    - printProgress     : 0–100 (%)
 *    - printElapsed      : HH:MM:SS since print started
 *    - printRemaining    : HH:MM:SS remaining (from printer estimate)
 *    - filamentType      : e.g. PLA, PETG, ABS, ASA …
 *    - filamentColor     : hex colour reported by AMS tray (#RRGGBB)
 *    - chamberLight      : on / off
 *    - currentFile       : name of the gcode file being printed
 *    - nozzleTemp        : current nozzle temperature (°C)
 *    - bedTemp           : current bed temperature (°C)
 *    - connectionStatus  : connected / disconnected
 *
 *  Commands:
 *    - lightOn()  / lightOff()   : toggle chamber light
 *    - pausePrint() / resumePrint() / stopPrint()
 *    - refresh()                 : request full-status push from printer
 *    - connect() / disconnect()
 *
 *  Installation:
 *    1. In Hubitat UI → Drivers Code → New Driver → paste this file → Save
 *    2. Devices → Add Device → Virtual → choose "Bambu Lab P1S"
 *    3. Fill in Preferences: Printer IP, Serial Number, LAN Access Code
 *    4. Save Preferences — the driver will connect automatically
 *
 *  Prerequisites:
 *    - Enable "LAN Mode" on the printer (Settings → Network → LAN Mode)
 *      OR keep cloud mode but ensure the printer is reachable on the LAN.
 *      The LAN Access Code is always visible in Settings → Network on the touchscreen.
 *    - Assign a static IP to the printer in your router.
 *
 *  IMPORTANT – TLS note:
 *    Bambu printers use a self-signed certificate. Hubitat's MQTT interface requires
 *    ssl:// prefix and will attempt certificate validation. This driver uses ssl:// and
 *    passes `ignoreSSLIssues: true` in the connect options map so the self-signed cert
 *    is accepted without needing to import it.
 */

metadata {
    definition(
        name: "Bambu Lab P1S",
        namespace: "community",
        author: "Custom Driver",
        description: "Monitor and control a Bambu Lab P1S 3D printer via local MQTT"
    ) {
        capability "Initialize"
        capability "Refresh"
        capability "Switch"          // maps on()/off() to chamber light for RM convenience
        capability "Sensor"
        capability "Actuator"

        // --- Status attributes ---
        attribute "printerStatus",    "string"    // idle|printing|paused|finished|error
        attribute "printProgress",    "number"    // 0-100
        attribute "printElapsed",     "string"    // HH:MM:SS
        attribute "printRemaining",   "string"    // HH:MM:SS
        attribute "filamentType",     "string"
        attribute "filamentColor",    "string"    // #RRGGBB
        attribute "chamberLight",     "string"    // on|off
        attribute "currentFile",      "string"
        attribute "nozzleTemp",       "number"
        attribute "bedTemp",          "number"
        attribute "connectionStatus", "string"    // connected|disconnected

        // --- Commands ---
        command "lightOn"
        command "lightOff"
        command "pausePrint"
        command "resumePrint"
        command "stopPrint"
        command "connect"
        command "disconnect"
    }

    preferences {
        input name: "printerIP",
              type: "text",
              title: "Printer IP Address",
              description: "Static LAN IP of your P1S (e.g. 192.168.1.50)",
              required: true

        input name: "printerSerial",
              type: "text",
              title: "Printer Serial Number",
              description: "Found in: touchscreen → Settings → Device Info, or Bambu Studio",
              required: true

        input name: "lanAccessCode",
              type: "password",
              title: "LAN Access Code",
              description: "Found in: touchscreen → Settings → Network",
              required: true

        input name: "refreshInterval",
              type: "number",
              title: "Status Refresh Interval (seconds)",
              description: "How often to request a full-status push (minimum 60 recommended)",
              defaultValue: 120,
              range: "30..3600",
              required: false

        input name: "enableDebug",
              type: "bool",
              title: "Enable Debug Logging",
              defaultValue: false
    }
}

// ──────────────────────────────────────────────────────────────
//  Life-cycle callbacks
// ──────────────────────────────────────────────────────────────

def installed() {
    log.info "[BambuP1S] Driver installed"
    initializeState()
}

def updated() {
    log.info "[BambuP1S] Preferences saved — reconnecting"
    disconnect()
    pauseExecution(1000)
    initialize()
}

def initialize() {
    log.info "[BambuP1S] Initializing"
    initializeState()
    connect()
    // Schedule periodic full-status refresh
    int interval = (settings.refreshInterval ?: 120) as int
    schedule("0/${interval} * * * * ?", "refresh")
}

def uninstalled() {
    disconnect()
    unschedule()
}

// ──────────────────────────────────────────────────────────────
//  MQTT connection
// ──────────────────────────────────────────────────────────────

def connect() {
    if (!printerIP || !printerSerial || !lanAccessCode) {
        log.warn "[BambuP1S] Cannot connect — preferences incomplete"
        return
    }

    String broker   = "ssl://${printerIP}:8883"
    String clientId = "hubitat-bambu-${printerSerial}"

    debugLog("Connecting to MQTT broker: ${broker}")

    try {
        // Username is always "bblp"; password is the LAN access code
        interfaces.mqtt.connect(
            broker,
            clientId,
            "bblp",
            lanAccessCode as String,
            ignoreSSLIssues: true   // accept self-signed cert
        )
        // mqttClientStatus() callback will fire on connect/disconnect
    } catch (e) {
        log.error "[BambuP1S] MQTT connect failed: ${e.message}"
        sendEvent(name: "connectionStatus", value: "disconnected")
    }
}

def disconnect() {
    try {
        interfaces.mqtt.disconnect()
    } catch (e) {
        // ignore
    }
    sendEvent(name: "connectionStatus", value: "disconnected")
}

// Called by the platform when MQTT connection state changes
def mqttClientStatus(String status) {
    debugLog("mqttClientStatus: ${status}")

    if (status.startsWith("Status: Connection succeeded")) {
        log.info "[BambuP1S] MQTT connected"
        sendEvent(name: "connectionStatus", value: "connected")

        // Subscribe to the printer's report topic
        String reportTopic = "device/${printerSerial}/report"
        interfaces.mqtt.subscribe(reportTopic, 0)
        debugLog("Subscribed to: ${reportTopic}")

        // Request a full push immediately
        pauseExecution(500)
        refresh()
    } else {
        log.warn "[BambuP1S] MQTT status: ${status}"
        sendEvent(name: "connectionStatus", value: "disconnected")
        // Attempt reconnect after 30 s
        runIn(30, "connect")
    }
}

// ──────────────────────────────────────────────────────────────
//  Incoming message handling
// ──────────────────────────────────────────────────────────────

def parse(String event) {
    def msg = interfaces.mqtt.parseMessage(event)
    debugLog("Received on ${msg.topic}: ${msg.payload}")

    try {
        def json = new groovy.json.JsonSlurper().parseText(msg.payload)
        processPrintReport(json)
    } catch (e) {
        log.error "[BambuP1S] JSON parse error: ${e.message}"
    }
}

private void processPrintReport(Map json) {
    // Top-level key for status updates is "print"
    def p = json?.print
    if (!p) return

    // ── Printer state ──────────────────────────────────────────
    if (p.containsKey("gcode_state")) {
        String rawState = (p.gcode_state as String).toLowerCase()
        String status = mapGcodeState(rawState)
        sendEvent(name: "printerStatus", value: status, descriptionText: "Printer status: ${status}")
    }

    // ── Progress ───────────────────────────────────────────────
    if (p.containsKey("mc_percent")) {
        int pct = (p.mc_percent as int)
        sendEvent(name: "printProgress", value: pct, unit: "%")
    }

    // ── Time remaining ─────────────────────────────────────────
    if (p.containsKey("mc_remaining_time")) {
        int remMins = (p.mc_remaining_time as int)
        sendEvent(name: "printRemaining", value: formatMinutes(remMins))
    }

    // ── Elapsed time (computed from print_real_action / subtask_id presence) ──
    // The printer does not always report elapsed time directly on P1 series;
    // we track it ourselves from when printing starts.
    if (p.containsKey("gcode_state")) {
        String rawState = (p.gcode_state as String).toLowerCase()
        if (rawState == "running") {
            if (!state.printStartTime) {
                state.printStartTime = now()
            }
        } else if (rawState in ["finish", "failed", "idle"]) {
            state.printStartTime = null
        }
    }
    updateElapsed()

    // ── Current file ───────────────────────────────────────────
    if (p.containsKey("gcode_file")) {
        String fileName = (p.gcode_file as String).tokenize("/").last()
        sendEvent(name: "currentFile", value: fileName ?: "—")
    }

    // ── Temperatures ───────────────────────────────────────────
    if (p.containsKey("nozzle_temper")) {
        sendEvent(name: "nozzleTemp", value: Math.round(p.nozzle_temper as double), unit: "°C")
    }
    if (p.containsKey("bed_temper")) {
        sendEvent(name: "bedTemp", value: Math.round(p.bed_temper as double), unit: "°C")
    }

    // ── Chamber light ──────────────────────────────────────────
    // Reported as a list: lights_report: [{node: "chamber_light", mode: "on"|"off"}]
    if (p.containsKey("lights_report")) {
        p.lights_report.each { light ->
            if (light.node == "chamber_light") {
                String lightState = (light.mode == "on") ? "on" : "off"
                sendEvent(name: "chamberLight", value: lightState)
                sendEvent(name: "switch",       value: lightState)  // capability alias
            }
        }
    }

    // ── AMS filament ───────────────────────────────────────────
    // ams_status == 0 means no AMS / AMS idle; the active tray is referenced by ams_rfid_status
    // We also check vt_tray for external spool
    if (p.containsKey("ams")) {
        def amsData = p.ams
        // ams_rfid_status & ams_status help identify which tray is active
        // The simplest approach: look at the currently active tray_type across all AMS
        // The printer sets an active tray indicator via the ams block's "tray_now" or
        // the top-level "ams_rfid_status" / subtask info.
        // We extract from the first loaded tray we find.
        def amsList = amsData?.ams
        if (amsList) {
            boolean found = false
            amsList.each { amsUnit ->
                if (!found && amsUnit?.tray) {
                    amsUnit.tray.each { tray ->
                        if (!found && tray?.tray_type && tray.tray_type != "") {
                            sendEvent(name: "filamentType", value: tray.tray_type as String)
                            String hexColor = trayColorToHex(tray.tray_color as String)
                            sendEvent(name: "filamentColor", value: hexColor)
                            found = true
                        }
                    }
                }
            }
        }
    }

    // External spool (no AMS) — vt_tray
    if (p.containsKey("vt_tray")) {
        def vt = p.vt_tray
        if (vt?.tray_type) {
            sendEvent(name: "filamentType", value: vt.tray_type as String)
            String hexColor = trayColorToHex(vt.tray_color as String)
            sendEvent(name: "filamentColor", value: hexColor)
        }
    }
}

// ──────────────────────────────────────────────────────────────
//  Commands
// ──────────────────────────────────────────────────────────────

def refresh() {
    // Request full status push from printer
    publishCommand([
        pushing: [
            sequence_id: nextSeq(),
            command:     "pushall",
            version:     1,
            push_target: 1
        ]
    ])
}

def lightOn() {
    publishCommand([
        system: [
            sequence_id:   nextSeq(),
            command:       "ledctrl",
            led_node:      "chamber_light",
            led_mode:      "on",
            led_on_time:   500,
            led_off_time:  500,
            loop_times:    0,
            interval_time: 0
        ]
    ])
    sendEvent(name: "chamberLight", value: "on")
    sendEvent(name: "switch",       value: "on")
}

def lightOff() {
    publishCommand([
        system: [
            sequence_id:   nextSeq(),
            command:       "ledctrl",
            led_node:      "chamber_light",
            led_mode:      "off",
            led_on_time:   500,
            led_off_time:  500,
            loop_times:    0,
            interval_time: 0
        ]
    ])
    sendEvent(name: "chamberLight", value: "off")
    sendEvent(name: "switch",       value: "off")
}

// Switch capability aliases for Rule Machine / dashboard convenience
def on()  { lightOn()  }
def off() { lightOff() }

def pausePrint() {
    publishCommand([
        print: [
            sequence_id: nextSeq(),
            command:     "pause"
        ]
    ])
}

def resumePrint() {
    publishCommand([
        print: [
            sequence_id: nextSeq(),
            command:     "resume"
        ]
    ])
}

def stopPrint() {
    publishCommand([
        print: [
            sequence_id: nextSeq(),
            command:     "stop"
        ]
    ])
}

// ──────────────────────────────────────────────────────────────
//  Helpers
// ──────────────────────────────────────────────────────────────

private void publishCommand(Map payload) {
    if (device.currentValue("connectionStatus") != "connected") {
        log.warn "[BambuP1S] Cannot publish — not connected"
        return
    }
    String topic   = "device/${printerSerial}/request"
    String jsonStr = groovy.json.JsonOutput.toJson(payload)
    debugLog("Publishing to ${topic}: ${jsonStr}")
    interfaces.mqtt.publish(topic, jsonStr, 1, false)
}

private void initializeState() {
    state.sequenceId   = 0
    state.printStartTime = null
    sendEvent(name: "connectionStatus", value: "disconnected")
    sendEvent(name: "printerStatus",    value: "unknown")
    sendEvent(name: "printProgress",    value: 0)
    sendEvent(name: "printElapsed",     value: "—")
    sendEvent(name: "printRemaining",   value: "—")
    sendEvent(name: "chamberLight",     value: "off")
    sendEvent(name: "switch",           value: "off")
    sendEvent(name: "filamentType",     value: "—")
    sendEvent(name: "filamentColor",    value: "#000000")
    sendEvent(name: "currentFile",      value: "—")
    sendEvent(name: "nozzleTemp",       value: 0)
    sendEvent(name: "bedTemp",          value: 0)
}

private String nextSeq() {
    state.sequenceId = ((state.sequenceId ?: 0) + 1) % 10000
    return state.sequenceId.toString()
}

private String mapGcodeState(String raw) {
    switch (raw) {
        case "running":  return "printing"
        case "pause":    return "paused"
        case "finish":   return "finished"
        case "failed":   return "error"
        case "idle":     return "idle"
        case "prepare":  return "preparing"
        default:         return raw ?: "unknown"
    }
}

private String formatMinutes(int totalMinutes) {
    if (totalMinutes <= 0) return "0:00:00"
    int h = totalMinutes / 60
    int m = totalMinutes % 60
    return String.format("%d:%02d:00", h, m)
}

private String formatSeconds(long totalSeconds) {
    if (totalSeconds <= 0) return "0:00:00"
    int h = (totalSeconds / 3600) as int
    int m = ((totalSeconds % 3600) / 60) as int
    int s = (totalSeconds % 60) as int
    return String.format("%d:%02d:%02d", h, m, s)
}

private void updateElapsed() {
    if (state.printStartTime) {
        long elapsedSecs = (now() - state.printStartTime) / 1000
        sendEvent(name: "printElapsed", value: formatSeconds(elapsedSecs))
    } else {
        sendEvent(name: "printElapsed", value: "—")
    }
}

// Bambu reports tray_color as "RRGGBBAA" hex; we want "#RRGGBB"
private String trayColorToHex(String raw) {
    if (!raw || raw.length() < 6) return "#000000"
    return "#${raw.substring(0, 6).toUpperCase()}"
}

private void debugLog(String msg) {
    if (settings.enableDebug) {
        log.debug "[BambuP1S] ${msg}"
    }
}
