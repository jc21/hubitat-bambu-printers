/**
 *  Bambu Lab 3D Printer - Hubitat Driver
 *
 *  Compatible with Bambu Lab printers that support local MQTT (P1S, P1P, X1C, A1, A1 Mini, P2S, etc.).
 *  Connects over local MQTT (port 8883, TLS) using your LAN access code.
 *
 *  STATUS MONITORING only — no control commands. All attributes are read-only.
 *
 *  Attributes exposed:
 *    - printerStatus     : idle / printing / paused / finished / error
 *    - printProgress     : 0–100 (%)
 *    - printElapsed      : HH:MM:SS since print started
 *    - printRemaining    : HH:MM:SS remaining (from printer estimate)
 *    - filamentType      : e.g. PLA, PETG, ABS, ASA …
 *    - filamentColor     : hex colour reported by AMS tray (#RRGGBB)
 *    - chamberLight      : on / off (read-only)
 *    - currentFile       : name of the gcode file being printed
 *    - nozzleTemp        : current nozzle temperature (°C)
 *    - bedTemp           : current bed temperature (°C)
 *    - connectionStatus  : connected / disconnected
 *
 *  Commands:
 *    - refresh()                 : request full-status push from printer
 *    - connect() / disconnect()
 *
 *  Installation:
 *    1. In Hubitat UI → Drivers Code → New Driver → paste this file → Save
 *    2. Devices → Add Device → Virtual → choose "Bambu Lab Printer"
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
        name: "Bambu Lab Printer",
        namespace: "jonnyborbs",
        author: "Jon Schulman",
        description: "Monitor a Bambu Lab 3D printer via local MQTT (P1S, P1P, X1C, A1, A1 Mini, P2S)"
    ) {
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"
        capability "Actuator"

        // --- Status attributes ---
        attribute "printerStatus",    "string"    // idle|printing|paused|finished|error
        attribute "printProgress",    "number"    // 0-100
        attribute "printElapsed",     "string"    // HH:MM:SS
        attribute "printRemaining",   "string"    // HH:MM:SS
        attribute "filamentType",     "string"
        attribute "filamentColor",    "string"    // #RRGGBB
        attribute "chamberLight",     "string"    // on|off (read-only)
        attribute "currentFile",      "string"
        attribute "nozzleTemp",       "number"
        attribute "bedTemp",          "number"
        attribute "connectionStatus", "string"    // connected|disconnected

        // --- Commands ---
        command "connect"
        command "disconnect"
    }

    preferences {
        input name: "printerIP",
              type: "text",
              title: "Printer IP Address",
              description: "Static LAN IP of your printer (e.g. 192.168.1.50)",
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

        input name: "mqttRelayHost",
              type: "text",
              title: "MQTT Relay Host (optional)",
              description: "IP of a local Mosquitto relay (e.g. 192.168.1.x). Leave blank to connect directly to the printer via SSL. Use this if status never updates — see README for relay setup.",
              required: false

        input name: "mqttRelayPort",
              type: "integer",
              title: "MQTT Relay Port",
              description: "Port of the local relay (default 1883)",
              defaultValue: 1883,
              required: false

        input name: "refreshInterval",
              type: "integer",
              title: "Status Refresh Interval (seconds)",
              description: "How often to request a full-status push (minimum 60 recommended)",
              defaultValue: 60,
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
    log.info "[BambuPrinter] Driver installed"
    state.printStartTime = null
    initialize()
}

def updated() {
    log.info "[BambuPrinter] Preferences saved — reconnecting"
    unschedule()
    disconnect()
    pauseExecution(1000)
    connect()
    scheduleRefresh()
}

def initialize() {
    log.info "[BambuPrinter] Initializing"
    initializeState()
    connect()
    scheduleRefresh()
}

private void scheduleRefresh() {
    int interval = (settings.refreshInterval ?: 120) as int
    runIn(interval, "scheduledRefresh")
}

def scheduledRefresh() {
    // If no MQTT message has arrived in 3× the refresh interval, the connection
    // is silently dead — force a full reconnect rather than just sending pushall
    // into the void. mqttClientStatus() will call refresh() on success, so skip it here.
    int interval = (settings.refreshInterval ?: 120) as int
    long silenceMs = now() - (state.lastMessageTime ?: 0)
    if (state.lastMessageTime && silenceMs > (interval * 3 * 1000)) {
        log.warn "[BambuPrinter] No MQTT messages for ${silenceMs / 1000}s — forcing reconnect"
        disconnect()
        pauseExecution(1000)
        connect()
    } else {
        try {
            refresh()
        } catch (e) {
            log.error "[BambuPrinter] Error during scheduled refresh: ${e.message}"
        }
    }

    scheduleRefresh()
}

def uninstalled() {
    disconnect()
    unschedule()
}

// ──────────────────────────────────────────────────────────────
//  MQTT connection
// ──────────────────────────────────────────────────────────────

def connect() {
    if (!settings.printerSerial) {
        log.warn "[BambuPrinter] Cannot connect — serial number not configured"
        return
    }

    boolean usingRelay = settings.mqttRelayHost as boolean

    if (usingRelay) {
        connectBroker("tcp://${settings.mqttRelayHost}:${settings.mqttRelayPort ?: 1883}", null, null, false)
    } else {
        if (!settings.printerIP || !settings.lanAccessCode) {
            log.warn "[BambuPrinter] Cannot connect — printer IP and LAN access code required"
            return
        }
        connectBroker("ssl://${settings.printerIP}:8883", "bblp", settings.lanAccessCode as String, true)
    }
}

private void connectBroker(String broker, String username, String password, boolean ignoreSSL) {
    String clientId = "hubitat-bambu-${settings.printerSerial}"
    log.info "[BambuPrinter] Connecting to ${broker}"
    try {
        if (username && ignoreSSL) {
            interfaces.mqtt.connect(broker, clientId, username, password, ignoreSSLIssues: true)
        } else if (username) {
            interfaces.mqtt.connect(broker, clientId, username, password)
        } else {
            interfaces.mqtt.connect(broker, clientId, null, null)
        }
        // mqttClientStatus() callback will fire on connect/disconnect
    } catch (e) {
        String reason = e.message ?: e.class.simpleName
        if (e.hasProperty("reasonCode")) reason += " (code ${e.reasonCode})"
        if (e.cause)                     reason += " caused by: ${e.cause.message}"
        log.error "[BambuPrinter] MQTT connect failed: ${reason}"
        sendEvent(name: "connectionStatus", value: "disconnected")
        scheduleReconnect()
    }
}

def disconnect() {
    state.suppressReconnect = true  // prevent mqttClientStatus callback from re-scheduling
    unschedule("connect")           // cancel any pending reconnect
    state.reconnectDelay = 0        // reset backoff for next manual connect
    try {
        interfaces.mqtt.disconnect()
    } catch (e) {
        // ignore
    }
    sendEvent(name: "connectionStatus", value: "disconnected")
}

// Exponential back-off reconnect: 30 s → 60 s → 120 s → 300 s (cap), then holds.
// The counter is reset to 0 whenever a connection succeeds.
private void scheduleReconnect() {
    int delay
    int attempt = (state.reconnectDelay ?: 0) as int
    if (attempt == 0)        { delay = 30  }
    else if (attempt <= 30)  { delay = 60  }
    else if (attempt <= 60)  { delay = 120 }
    else                     { delay = 300 }
    state.reconnectDelay = delay
    log.info "[BambuPrinter] Reconnect scheduled in ${delay} s"
    runIn(delay, "connect")
}

// Called by the platform when MQTT connection state changes
def mqttClientStatus(String status) {
    debugLog("mqttClientStatus: ${status}")

    if (status.startsWith("Status: Connection succeeded")) {
        log.info "[BambuPrinter] MQTT connected"
        state.reconnectDelay = 0   // reset backoff on successful connect
        sendEvent(name: "connectionStatus", value: "connected")

        // Defer subscribe outside this callback — calling interfaces.mqtt.subscribe()
        // directly inside mqttClientStatus() silently fails on some Hubitat versions
        // because the MQTT client hasn't fully transitioned to connected state yet.
        runIn(1, "subscribeAndRefresh")
    } else {
        log.warn "[BambuPrinter] MQTT status: ${status}"
        sendEvent(name: "connectionStatus", value: "disconnected")
        if (!state.suppressReconnect) {
            scheduleReconnect()
        }
        state.suppressReconnect = false
    }
}

def subscribeAndRefresh() {
    String reportTopic = "device/${settings.printerSerial}/report"
    interfaces.mqtt.subscribe(reportTopic, 1)
    log.info "[BambuPrinter] Subscribed to: ${reportTopic}"
    pauseExecution(500)
    refresh()
}

// ──────────────────────────────────────────────────────────────
//  Incoming message handling
// ──────────────────────────────────────────────────────────────

def parse(String event) {
    def msg = interfaces.mqtt.parseMessage(event)
    debugLog("Message received on ${msg.topic}")
    state.lastMessageTime = now()

    Map json
    try {
        json = new groovy.json.JsonSlurper().parseText(msg.payload)
    } catch (e) {
        log.error "[BambuPrinter] JSON parse error: ${e.message}"
        return
    }
    debugLog("Payload: ${groovy.json.JsonOutput.toJson(json)}")

    if (!json?.print) {
        log.info "[BambuPrinter] Message has no 'print' key (top-level keys: ${json?.keySet()})"
        return
    }

    try {
        processPrintReport(json)
    } catch (e) {
        log.error "[BambuPrinter] Error processing message: ${e.message}"
    }
}

private void processPrintReport(Map json) {
    def p = json.print

    // Parse gcode_state once; used for both status event and elapsed-time tracking below
    String rawState = p.containsKey("gcode_state") ? (p.gcode_state as String).toLowerCase() : null

    // ── Printer state ──────────────────────────────────────────
    if (rawState != null) {
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
    if (rawState != null) {
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
        List parts = (p.gcode_file as String).tokenize("/")
        String fileName = parts ? parts.last() : ""
        sendEvent(name: "currentFile", value: fileName ?: "—")
    }

    // ── Temperatures ───────────────────────────────────────────
    if (p.containsKey("nozzle_temper")) {
        sendEvent(name: "nozzleTemp", value: Math.round(p.nozzle_temper as double), unit: "°C")
    }
    if (p.containsKey("bed_temper")) {
        sendEvent(name: "bedTemp", value: Math.round(p.bed_temper as double), unit: "°C")
    }

    // ── Chamber light (read-only) ──────────────────────────────
    // Reported as a list: lights_report: [{node: "chamber_light", mode: "on"|"off"}]
    if (p.containsKey("lights_report")) {
        p.lights_report.each { light ->
            if (light.node == "chamber_light") {
                sendEvent(name: "chamberLight", value: (light.mode == "on") ? "on" : "off")
            }
        }
    }

    // ── AMS filament ───────────────────────────────────────────
    // tray_now on each AMS unit indicates the active tray index ("0"–"3").
    // Values "254"/"255" mean no tray loaded in that unit.
    // vt_tray is only used when AMS is absent or yields no result.
    boolean filamentFound = false
    if (p.containsKey("ams")) {
        def amsList = p.ams?.ams
        if (amsList) {
            amsList.each { amsUnit ->
                if (filamentFound || !amsUnit?.tray) return
                String trayNow = amsUnit.tray_now?.toString()
                def activeTray = null
                if (trayNow && trayNow != "255" && trayNow != "254") {
                    activeTray = amsUnit.tray.find { it?.id?.toString() == trayNow }
                }
                // Fall back to first loaded tray if tray_now is unset or unresolvable
                if (!activeTray) {
                    activeTray = amsUnit.tray.find { it?.tray_type && it.tray_type != "" }
                }
                if (activeTray?.tray_type && activeTray.tray_type != "") {
                    sendEvent(name: "filamentType", value: activeTray.tray_type as String)
                    sendEvent(name: "filamentColor", value: trayColorToHex(activeTray.tray_color as String))
                    filamentFound = true
                }
            }
        }
    }

    // External spool (no AMS) — only use vt_tray when AMS didn't supply filament info
    if (!filamentFound && p.containsKey("vt_tray")) {
        def vt = p.vt_tray
        if (vt?.tray_type) {
            sendEvent(name: "filamentType", value: vt.tray_type as String)
            sendEvent(name: "filamentColor", value: trayColorToHex(vt.tray_color as String))
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

// ──────────────────────────────────────────────────────────────
//  Helpers
// ──────────────────────────────────────────────────────────────

private void publishCommand(Map payload) {
    if (device.currentValue("connectionStatus") != "connected") {
        log.warn "[BambuPrinter] Cannot publish — not connected"
        return
    }
    String topic   = "device/${printerSerial}/request"
    String jsonStr = groovy.json.JsonOutput.toJson(payload)
    log.info "[BambuPrinter] Publishing to ${topic}: ${jsonStr}"
    try {
        interfaces.mqtt.publish(topic, jsonStr, 1, false)
    } catch (e) {
        log.error "[BambuPrinter] MQTT publish failed: ${e.message}"
        sendEvent(name: "connectionStatus", value: "disconnected")
        scheduleReconnect()
    }
}

private void initializeState() {
    state.sequenceId = 0
    // Note: printStartTime is intentionally NOT reset here so that a preferences
    // save mid-print does not lose the elapsed-time reference.
    sendEvent(name: "connectionStatus", value: "disconnected")
    sendEvent(name: "printerStatus",    value: "unknown")
    sendEvent(name: "printProgress",    value: 0)
    sendEvent(name: "printElapsed",     value: "—")
    sendEvent(name: "printRemaining",   value: "—")
    sendEvent(name: "chamberLight",     value: "off")
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

// Printer reports remaining time in whole minutes; seconds are always :00 by design
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
        log.debug "[BambuPrinter] ${msg}"
    }
}
