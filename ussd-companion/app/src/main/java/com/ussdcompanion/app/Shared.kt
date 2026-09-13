package com.ussdcompanion.app

object Prefs {
    const val NAME = "ussd_companion_prefs"
    const val API_KEY = "api_key"
    const val PORT = "port"
}

object ActivityLog {
    private val lines = ArrayDeque<String>()
    private const val MAX_LINES = 50

    @Synchronized
    fun add(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines.addLast("[$ts] $line")
        if (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        add("تم مسح السجل يدوياً")
    }
}
