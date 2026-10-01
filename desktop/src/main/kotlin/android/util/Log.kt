package android.util

/** JVM adapter for the protocol's Android logging seam; payload traces stay disabled. */
object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    @Volatile var report: (String) -> Unit = { System.err.println(it) }
    fun isLoggable(tag: String, level: Int) = false
    fun v(tag: String, message: String) = 0
    fun d(tag: String, message: String) = 0
    fun i(tag: String, message: String): Int { report(message); return 0 }
    fun w(tag: String, message: String, error: Throwable? = null): Int {
        report(message + (error?.let { ": ${it.message}" } ?: "")); return 0
    }
    fun e(tag: String, message: String, error: Throwable? = null) = w(tag, message, error)
}
