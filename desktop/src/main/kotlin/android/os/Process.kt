package android.os

/** Linux scheduling values are advisory only on the desktop JVM. */
object Process {
    const val THREAD_PRIORITY_AUDIO = -16
    const val THREAD_PRIORITY_DISPLAY = -4
    const val THREAD_PRIORITY_URGENT_AUDIO = -19
    fun setThreadPriority(priority: Int) {}
}
