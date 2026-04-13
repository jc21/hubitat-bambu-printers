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
    log.info "[BambuP1S] Driver installed"
    state.printStartTime = null
    initializeState()
    createChamberLightChild()
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
    createChamberLightChild()
    connect()
    scheduleRefresh()
}

private void scheduleRefresh() {
    int interval = (settings.refreshInterval ?: 120) as int
    runIn(interval, "scheduledRefresh")
}

def scheduledRefresh() {
    refresh()
    scheduleRefresh()
}

def uninstalled() {
    disconnect()
    unschedule()
    deleteChildDevices()
}

// ──────────────────────────────────────────────────────────────
//  Child device management
// ──────────────────────────────────────────────────────────────

private void createChamberLightChild() {
    String childDni = "${device.deviceNetworkId}-chamberlight"
    if (!getChildDevice(childDni)) {
        try {
            addChildDevice("hubitat", "Generic Component Switch", childDni,
                [name: "${device.displayName} Chamber Light", isComponent: false])
            log.info "[BambuP1S] Chamber light child device created"
        } catch (e) {
            log.error "[BambuP1S] Failed to create chamber light child device: ${e.message}"
        }
    }
}

private void deleteChildDevices() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

// Called by Generic Component Switch child when turned on
void componentOn(cd) {
    debugLog("componentOn called by child: ${cd.displayName}")
    lightOn()
}

// Called by Generic Component Switch child when turned off
void componentOff(cd) {
    debugLog("componentOff called by child: ${cd.displayName}")
    lightOff()
}

// Called by Generic Component Switch child on refresh
void componentRefresh(cd) {
    debugLog("componentRefresh called by child: ${cd.displayName}")
    refresh()
}

private void syncChamberLightChild(String lightState) {
    def child = getChildDevice("${device.deviceNetworkId}-chamberlight")
    if (child) {
        child.parse([[name: "switch", value: lightState, descriptionText: "${child.displayName} is ${lightState}"]])
    }
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
            [ignoreSSLIssues: true]   // accept self-signed cert
        )
        // mqttClientStatus() callback will fire on connect/disconnect
    } catch (e) {
        log.error "[BambuP1S] MQTT connect failed: ${e.class.simpleName} — ${e.message}"
        sendEvent(name: "connectionStatus", value: "disconnected")
        scheduleReconnect()
    }
}

def disconnect() {
    unschedule("connect")          // cancel any pending reconnect
    state.reconnectDelay = 0      // reset backoff for next manual connect
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
    log.info "[BambuP1S] Reconnect scheduled in ${delay} s"
    runIn(delay, "connect")
}

// Called by the platform when MQTT connection state changes
def mqttClientStatus(String status) {
    debugLog("mqttClientStatus: ${status}")

    if (status.startsWith("Status: Connection succeeded")) {
        log.info "[BambuP1S] MQTT connected"
        state.reconnectDelay = 0   // reset backoff on successful connect
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
        scheduleReconnect()
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
                syncChamberLightChild(lightState)
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
    syncChamberLightChild("on")
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
    syncChamberLightChild("off")
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
    state.sequenceId = 0
    // Note: printStartTime is intentionally NOT reset here so that a preferences
    // save mid-print does not lose the elapsed-time reference.
    sendEvent(name: "connectionStatus", value: "disconnected")
    sendEvent(name: "printerStatus",    value: "unknown")
    sendEvent(name: "printProgress",    value: 0)
    sendEvent(name: "printElapsed",     value: "—")
    sendEvent(name: "printRemaining",   value: "—")
    sendEvent(name: "chamberLight",     value: "off")
    sendEvent(name: "switch",           value: "off")
    syncChamberLightChild("off")
    sendEvent(name: "filamentType",     value: "—")
    sendEvent(name: "filamentColor",    value: "#000000")
    sendEvent(name: "currentFile",      value: "—")
    sendEvent(name: "nozzleTemp",       value: 0)
    sendEvent(name: "bedTemp",          value: 0)
}

private int nextSeq() {
    state.sequenceId = ((state.sequenceId ?: 0) + 1) % 10000
    return state.sequenceId as int
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