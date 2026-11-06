package app.coredump.platform

/**
 * Crash reports that survive a restart.
 * record(): a fatal crash (uncaught exception) — shown on the next launch so it can be shared.
 * note(): a handled failure (logged, the app keeps running) — kept as a short rolling log.
 */
object CrashLog {
    private const val LAST = "crash_last.txt"
    private const val NOTES = "crash_notes.txt"

    fun record(where: String, t: Throwable) {
        runCatching {
            val text = buildString {
                append("CORE DUMP crash · ").append(Platform.name).append(" · ").append(Platform.epochMillis()).append('\n')
                append("where: ").append(where).append('\n')
                append(t.stackTraceToString().take(14_000))
                notes()?.let { append("\n\n--- recent handled errors ---\n").append(it.takeLast(3_000)) }
            }
            Platform.writeText(LAST, text)
            println("CRASHLOG fatal $where: ${t::class.simpleName}: ${t.message}")
        }
    }

    fun note(where: String, t: Throwable) {
        runCatching {
            println("CRASHLOG note $where: ${t::class.simpleName}: ${t.message}")
            val line = "${Platform.epochMillis()} $where: ${t::class.simpleName}: ${t.message?.take(160)}\n" +
                t.stackTraceToString().lineSequence().drop(1).take(4).joinToString("\n") + "\n"
            val old = Platform.readText(NOTES).orEmpty()
            Platform.writeText(NOTES, (old + line).takeLast(8_000))
        }
    }

    fun last(): String? = runCatching { Platform.readText(LAST) }.getOrNull()?.takeIf { it.isNotBlank() }
    fun notes(): String? = runCatching { Platform.readText(NOTES) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear() {
        runCatching { Platform.delete(LAST) }
        runCatching { Platform.delete(NOTES) }
    }
}
