/**
 *  Bambu Lab Printer Manager - Hubitat App
 *
 *  Companion app for the "Bambu Lab Printer" driver.
 *  Provides:
 *    • Notification alerts (print finished, errors, filament changes)
 *    • Automation triggers (turn on a switch when printing starts / stops)
 *    • Dashboard tile builder (all key status in one virtual device attribute)
 *    • Periodic refresh scheduling (belt-and-suspenders on top of the driver)
 *
 *  Installation:
 *    1. In Hubitat UI → Apps Code → New App → paste this file → Save
 *    2. Apps → Add User App → "Bambu Lab Printer Manager"
 *    3. Configure preferences and click Done
 */

definition(
    name:        "Bambu Lab Printer Manager",
    namespace:   "jonnyborbs",
    author:      "Jon Schulman",
    description: "Automation and notifications for your Bambu Lab 3D printer",
    category:    "Convenience",
    iconUrl:     "",
    iconX2Url:   ""
)

// ──────────────────────────────────────────────────────────────
//  Preferences / UI
// ──────────────────────────────────────────────────────────────

preferences {
    page(name: "mainPage")
    page(name: "notificationsPage")
    page(name: "automationsPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Bambu Lab Printer Manager", install: true, uninstall: true) {

        section("Printer Device") {
            input "printerDevice",
                  "device.BambuLabPrinter",
                  title: "Select your Bambu Lab printer device",
                  required: true,
                  multiple: false
            paragraph "The device above must be created using the 'Bambu Lab Printer' driver " +
                       "and have its IP, serial, and LAN access code configured."
        }

        section("Dashboard Summary Device (optional)") {
            paragraph "Choose a Virtual Omni Sensor or similar device where the app will write " +
                       "a formatted printer summary attribute. Useful for dashboard tiles."
            input "summaryDevice",
                  "capability.sensor",
                  title: "Summary Virtual Device (optional)",
                  required: false,
                  multiple: false
        }

        section("Configuration") {
            href "notificationsPage",
                 title: "🔔 Notifications",
                 description: "Configure push / SMS alerts"

            href "automationsPage",
                 title: "⚡ Automations",
                 description: "Lights, switches, and other actions"
        }

        section("App Info") {
            paragraph "Printer Status:  ${currentStatus()}"
            paragraph "<b>What works without cloud credentials:</b> All status monitoring, " +
                      "notifications, and switch automations.\n" +
                      "<b>What requires cloud credentials (configured in driver):</b> Chamber light " +
                      "control and print commands (pause, resume, stop). Cloud credentials must be " +
                      "a direct Bambu username and password — Sign In with Apple, Google, or " +
                      "Facebook is not supported."
        }
    }
}

def notificationsPage() {
    dynamicPage(name: "notificationsPage", title: "Notifications", nextPage: "mainPage") {

        section("Notification Device") {
            input "notifyDevice",
                  "capability.notification",
                  title: "Send notifications to",
                  multiple: true,
                  required: false
        }

        section("Alert Triggers") {
            input "notifyOnFinish",
                  "bool",
                  title: "Notify when print finishes",
                  defaultValue: true

            input "notifyOnStart",
                  "bool",
                  title: "Notify when print starts",
                  defaultValue: false

            input "notifyOnPause",
                  "bool",
                  title: "Notify when print pauses",
                  defaultValue: false

            input "notifyOnError",
                  "bool",
                  title: "Notify on printer error",
                  defaultValue: true

            input "notifyOnFilamentChange",
                  "bool",
                  title: "Notify on filament type change",
                  defaultValue: false
        }

        section("Progress Milestones") {
            paragraph "Receive a notification at each of the following completion percentages."
            input "progressMilestones",
                  "enum",
                  title: "Progress alerts at (%)",
                  multiple: true,
                  options: ["25", "50", "75", "90"],
                  required: false
        }
    }
}

def automationsPage() {
    dynamicPage(name: "automationsPage", title: "Automations", nextPage: "mainPage") {

        section("Printing-Start Actions") {
            input "switchOnPrintStart",
                  "capability.switch",
                  title: "Turn ON these switches when printing starts",
                  multiple: true,
                  required: false
            input "switchOffPrintStart",
                  "capability.switch",
                  title: "Turn OFF these switches when printing starts",
                  multiple: true,
                  required: false
        }

        section("Print-Finished Actions") {
            input "switchOnPrintEnd",
                  "capability.switch",
                  title: "Turn ON these switches when printing finishes",
                  multiple: true,
                  required: false
            input "switchOffPrintEnd",
                  "capability.switch",
                  title: "Turn OFF these switches when printing finishes",
                  multiple: true,
                  required: false
            input "dimmerLevelOnEnd",
                  "capability.switchLevel",
                  title: "Set these dimmers to a level when printing finishes",
                  multiple: true,
                  required: false
            input "dimmerLevelValue",
                  "number",
                  title: "Dimmer level on finish (0-100)",
                  range: "0..100",
                  defaultValue: 100,
                  required: false
        }

        section("Printer Error Actions") {
            input "switchOnError",
                  "capability.switch",
                  title: "Turn ON these switches on printer error",
                  multiple: true,
                  required: false
        }

        section("Chamber Light Automation") {
            paragraph "<b>⚠️ Requires cloud authentication.</b> Chamber light control only works on " +
                      "cloud-connected printers when Bambu account credentials are configured in the " +
                      "driver preferences. A direct Bambu username and password is required — " +
                      "Sign In with Apple, Google, or Facebook is not supported."
            input "autoLightWithPrint",
                  "bool",
                  title: "Automatically turn chamber light ON when printing starts and OFF when finished",
                  defaultValue: false
        }

        section("Mode Restrictions") {
            input "restrictedModes",
                  "mode",
                  title: "Only run automations when hub mode is",
                  multiple: true,
                  required: false
        }
    }
}

// ──────────────────────────────────────────────────────────────
//  Life-cycle
// ──────────────────────────────────────────────────────────────

def installed() {
    log.info "[BambuApp] Installed"
    initialize()
}

def updated() {
    log.info "[BambuApp] Updated"
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    if (!printerDevice) {
        log.warn "[BambuApp] No printer device selected"
        return
    }

    // Subscribe to attribute changes on the printer driver
    subscribe(printerDevice, "printerStatus",  "onPrinterStatusChange")
    subscribe(printerDevice, "printProgress",  "onProgressChange")
    subscribe(printerDevice, "filamentType",   "onFilamentTypeChange")
    subscribe(printerDevice, "chamberLight",   "onChamberLightChange")

    // Update summary tile every minute
    schedule("0 * * * * ?", "updateSummary")

    // Keep state — only reset milestones if not mid-print to avoid re-firing notifications
    state.lastStatus       = printerDevice.currentValue("printerStatus") ?: "unknown"
    state.lastFilamentType = printerDevice.currentValue("filamentType")  ?: "—"
    if (printerDevice.currentValue("printerStatus") != "printing") {
        state.milestonesReported = []
    }

    log.info "[BambuApp] Subscriptions registered"
    updateSummary()
}

// ──────────────────────────────────────────────────────────────
//  Event handlers
// ──────────────────────────────────────────────────────────────

def onPrinterStatusChange(evt) {
    String newStatus  = evt.value
    String prevStatus = state.lastStatus ?: "unknown"

    log.info "[BambuApp] Printer status changed: ${prevStatus} → ${newStatus}"

    // ── Print started ──────────────────────────────────────────
    if (newStatus == "printing" && prevStatus != "printing") {
        state.milestonesReported = []
        if (notifyOnStart) {
            String file = printerDevice.currentValue("currentFile") ?: "unknown file"
            sendNotification("🖨️ Bambu printer started printing: ${file}")
        }
        runAutomation("start")
        if (autoLightWithPrint) printerDevice.lightOn()
    }

    // ── Print finished ─────────────────────────────────────────
    if (newStatus == "finished" && prevStatus in ["printing", "paused"]) {
        if (notifyOnFinish) {
            String file    = printerDevice.currentValue("currentFile") ?: "unknown file"
            String elapsed = printerDevice.currentValue("printElapsed") ?: "—"
            sendNotification("✅ Bambu printer finished: ${file} (elapsed: ${elapsed})")
        }
        runAutomation("end")
        if (autoLightWithPrint) printerDevice.lightOff()
    }

    // ── Paused ─────────────────────────────────────────────────
    if (newStatus == "paused" && notifyOnPause) {
        sendNotification("⏸️ Bambu printer print paused")
    }

    // ── Error ──────────────────────────────────────────────────
    if (newStatus == "error") {
        if (notifyOnError) {
            sendNotification("🚨 Bambu printer printer error! Check the printer.")
        }
        runAutomation("error")
    }

    state.lastStatus = newStatus
    updateSummary()
}

def onProgressChange(evt) {
    int pct = evt.integerValue ?: 0

    // Only fire milestones while actively printing
    if (printerDevice.currentValue("printerStatus") != "printing") return
    if (!progressMilestones) return

    List fired = state.milestonesReported ?: []
    progressMilestones.each { milestone ->
        int m = milestone as int
        if (pct >= m && !(m in fired)) {
            fired << m
            String file = printerDevice.currentValue("currentFile") ?: "unknown file"
            sendNotification("📊 Bambu printer is ${m}% complete — ${file}")
        }
    }
    state.milestonesReported = fired
    updateSummary()
}

def onFilamentTypeChange(evt) {
    String newType  = evt.value
    String prevType = state.lastFilamentType ?: "—"

    if (newType != prevType && prevType != "—" && notifyOnFilamentChange) {
        sendNotification("🧵 Bambu printer filament changed: ${prevType} → ${newType}")
    }
    state.lastFilamentType = newType
    updateSummary()
}

def onChamberLightChange(evt) {
    log.debug "[BambuApp] Chamber light: ${evt.value}"
    updateSummary()
}

// ──────────────────────────────────────────────────────────────
//  Automations
// ──────────────────────────────────────────────────────────────

private void runAutomation(String trigger) {
    if (restrictedModes && !(location.mode in restrictedModes)) {
        log.info "[BambuApp] Automation skipped — hub mode: ${location.mode}"
        return
    }

    switch (trigger) {
        case "start":
            switchOnPrintStart?.each  { it.on()  }
            switchOffPrintStart?.each { it.off() }
            break

        case "end":
            switchOnPrintEnd?.each    { it.on()  }
            switchOffPrintEnd?.each   { it.off() }
            dimmerLevelOnEnd?.each    { it.setLevel(dimmerLevelValue ?: 100) }
            break

        case "error":
            switchOnError?.each       { it.on()  }
            break
    }
}

// ──────────────────────────────────────────────────────────────
//  Summary tile
// ──────────────────────────────────────────────────────────────

def updateSummary() {
    if (!summaryDevice || !printerDevice) return

    String status    = printerDevice.currentValue("printerStatus")  ?: "unknown"
    int    progress  = (printerDevice.currentValue("printProgress") ?: 0) as int
    String elapsed   = printerDevice.currentValue("printElapsed")   ?: "—"
    String remaining = printerDevice.currentValue("printRemaining") ?: "—"
    String filament  = printerDevice.currentValue("filamentType")   ?: "—"
    String color     = printerDevice.currentValue("filamentColor")  ?: "#000000"
    String light     = printerDevice.currentValue("chamberLight")   ?: "off"
    String file      = printerDevice.currentValue("currentFile")    ?: "—"
    String nozzle    = printerDevice.currentValue("nozzleTemp")     ?: "—"
    String bed       = printerDevice.currentValue("bedTemp")        ?: "—"
    String conn      = printerDevice.currentValue("connectionStatus") ?: "disconnected"

    String emoji = statusEmoji(status)
    String summary =
        "${emoji} ${status.toUpperCase()}  |  ${progress}%\n" +
        "File: ${file}\n" +
        "Elapsed: ${elapsed}  |  Remaining: ${remaining}\n" +
        "Filament: ${filament} (${color})\n" +
        "Nozzle: ${nozzle}°C  |  Bed: ${bed}°C\n" +
        "Light: ${light}  |  MQTT: ${conn}"

    try {
        summaryDevice.sendEvent(name: "bambuSummary", value: summary)
    } catch (e) {
        log.debug "[BambuApp] Could not update summary device: ${e.message}"
    }
}

// ──────────────────────────────────────────────────────────────
//  Notification helper
// ──────────────────────────────────────────────────────────────

private void sendNotification(String msg) {
    log.info "[BambuApp] Notification: ${msg}"
    notifyDevice?.each { device ->
        try {
            device.deviceNotification(msg)
        } catch (e) {
            log.error "[BambuApp] Failed to notify ${device.displayName}: ${e.message}"
        }
    }
}

// ──────────────────────────────────────────────────────────────
//  Misc helpers
// ──────────────────────────────────────────────────────────────

private String currentStatus() {
    if (!printerDevice) return "No device selected"
    String s = printerDevice.currentValue("printerStatus") ?: "unknown"
    String c = printerDevice.currentValue("connectionStatus") ?: "disconnected"
    return "${s} (MQTT: ${c})"
}

private String statusEmoji(String status) {
    switch (status) {
        case "printing":   return "🟢"
        case "paused":     return "🟡"
        case "finished":   return "✅"
        case "error":      return "🔴"
        case "idle":       return "⚪"
        case "preparing":  return "🔵"
        default:           return "❓"
    }
}
