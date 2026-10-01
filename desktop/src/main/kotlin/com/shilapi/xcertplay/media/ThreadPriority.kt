package com.shilapi.xcertplay.media

import android.os.Process

/** Raises the calling thread's scheduling priority; head units run many background services. */
internal object ThreadPriority {
    fun raise(priority: Int) {
        runCatching { Process.setThreadPriority(priority) }
    }
}
