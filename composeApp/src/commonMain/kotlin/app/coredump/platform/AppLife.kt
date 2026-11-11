package app.coredump.platform

/** The app coming back to the screen (iOS: did become active; Android: onStart). Network links reconnect at once. */
object AppLife {
    private val listeners = ArrayList<() -> Unit>()

    fun onForeground(f: () -> Unit) { listeners += f }

    fun foreground() { for (l in listeners.toList()) runCatching { l() } }
}
