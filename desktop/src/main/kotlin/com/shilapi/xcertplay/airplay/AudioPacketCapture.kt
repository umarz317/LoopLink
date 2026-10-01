package com.shilapi.xcertplay.airplay

import android.util.Log
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Diagnostic capture for the encrypted UDP datagrams and decrypted RTP packets of one audio
 * stream. The caller must opt in by supplying a capture directory.
 *
 * File format:
 *   bytes 0..7: "XCAUDIO1"
 *   int32 version
 *   int32 stream type
 *   int64 wall-clock start time (milliseconds)
 *   repeated records:
 *     int32 wire length
 *     int32 decrypted RTP length, or -1 when authentication failed
 *     int32 RTP sample position, or -1 when unavailable
 *     int64 monotonic timestamp (nanoseconds)
 *     int32 UTF-8 error length
 *     wire bytes
 *     decrypted RTP bytes when length >= 0
 *     UTF-8 error bytes
 */
class AudioPacketCapture(
    directory: File,
    private val streamType: Int,
    private val maxPackets: Int = 512,
    private val maxBytes: Long = 16L * 1024L * 1024L,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private var packets = 0
    private var bytesWritten: Long = (MAGIC.size + HEADER_BYTES).toLong()
    private val output: DataOutputStream?

    val file: File

    init {
        val captureDirectory = if (directory.isDirectory || directory.mkdirs()) {
            directory
        } else {
            null
        }
        file = if (captureDirectory == null) {
            File(directory, "audio-capture-unavailable-type-$streamType.xcap")
        } else {
            File(
                captureDirectory,
                "audio-${System.currentTimeMillis()}-type-$streamType.xcap",
            )
        }
        output = if (captureDirectory == null) {
            null
        } else {
            try {
                DataOutputStream(BufferedOutputStream(FileOutputStream(file, false))).also {
                    it.write(MAGIC)
                    it.writeInt(VERSION)
                    it.writeInt(streamType)
                    it.writeLong(System.currentTimeMillis())
                    it.flush()
                    Log.i(TAG, "audio capture started type=$streamType file=${file.absolutePath}")
                }
            } catch (error: Exception) {
                Log.w(TAG, "audio capture could not open type=$streamType", error)
                null
            }
        }
    }

    fun record(
        wire: ByteArray,
        rtp: ByteArray?,
        sample: Int?,
        error: Throwable?,
    ) {
        if (output == null || closed.get()) return
        synchronized(this) {
            if (closed.get() || packets >= maxPackets) return
            val errorBytes = error?.message?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val recordBytes = 4L * 4 + 8 + wire.size + (rtp?.size ?: 0) + errorBytes.size
            if (bytesWritten + recordBytes > maxBytes) {
                close()
                return
            }
            try {
                output.writeInt(wire.size)
                output.writeInt(rtp?.size ?: -1)
                output.writeInt(sample ?: -1)
                output.writeLong(System.nanoTime())
                output.writeInt(errorBytes.size)
                output.write(wire)
                if (rtp != null) output.write(rtp)
                output.write(errorBytes)
                packets += 1
                bytesWritten += recordBytes
                if (packets % FLUSH_INTERVAL_PACKETS == 0) output.flush()
                if (packets >= maxPackets) close()
            } catch (failure: Exception) {
                Log.w(TAG, "audio capture write failed type=$streamType", failure)
                close()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            output?.flush()
            output?.close()
        } catch (error: Exception) {
            Log.w(TAG, "audio capture close failed type=$streamType", error)
        } finally {
            if (output != null) {
                Log.i(
                    TAG,
                    "audio capture stopped type=$streamType packets=$packets " +
                        "bytes=$bytesWritten file=${file.absolutePath}",
                )
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val VERSION = 1
        const val HEADER_BYTES = 16
        const val FLUSH_INTERVAL_PACKETS = 16
        val MAGIC = "XCAUDIO1".toByteArray(Charsets.US_ASCII)
    }
}
