package com.shilapi.xcertplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException

/** Exercise the Android 10 storage path, including OEM/provider failure responses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DiagnosticExportStoreTest {
    private lateinit var provider: DownloadsProvider
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        provider = DownloadsProvider(File(context.cacheDir, "export-test.txt"))
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    @Test fun android10SavesUtf8ReportToDownloadsAndPublishesAfterClosingIt() {
        val report = "LoopLink · diagnostic report\nVideo: H.264\n"
        val uri = DiagnosticExportStore.saveToDownloads(resolver, "LoopLink-test.txt", report)
        assertEquals(provider.uri, uri)
        assertEquals(report, provider.file.readText())
        assertEquals("Download/LoopLink", provider.insertValues!!.getAsString(MediaStore.Downloads.RELATIVE_PATH))
        assertEquals("text/plain", provider.insertValues!!.getAsString(MediaStore.Downloads.MIME_TYPE))
        assertEquals(1, provider.insertValues!!.getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertEquals(0, provider.publishValues!!.getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertEquals(report, provider.contentAtPublish)
        assertFalse(provider.deleted)
    }

    @Test fun unavailableDownloadsDoesNotReturnFalseSuccess() {
        provider.refuseInsert = true
        assertThrows(IOException::class.java) {
            DiagnosticExportStore.saveToDownloads(resolver, "test.txt", "report")
        }
        assertNull(provider.publishValues)
        assertFalse(provider.deleted)
    }

    @Test fun deniedWriteRemovesOnlyTheNewPendingEntry() {
        provider.denyWrite = true
        assertThrows(SecurityException::class.java) {
            DiagnosticExportStore.saveToDownloads(resolver, "test.txt", "report")
        }
        assertTrue(provider.deleted)
        assertNull(provider.publishValues)
    }

    @Test fun failedPublishDoesNotLeaveAnInvisibleReport() {
        provider.refusePublish = true
        assertThrows(IOException::class.java) {
            DiagnosticExportStore.saveToDownloads(resolver, "test.txt", "report")
        }
        assertTrue(provider.deleted)
    }

    @Test fun selectedDocumentIsWrittenButNeverDeletedOnFailure() {
        provider.denyWrite = true
        assertThrows(SecurityException::class.java) {
            DiagnosticExportStore.write(resolver, provider.uri, "report")
        }
        assertFalse(provider.deleted)
    }

    private class DownloadsProvider(val file: File) : ContentProvider() {
        val uri: Uri = Uri.parse("content://media/external/downloads/41")
        var insertValues: ContentValues? = null
        var publishValues: ContentValues? = null
        var contentAtPublish: String? = null
        var deleted = false
        var refuseInsert = false
        var denyWrite = false
        var refusePublish = false
        override fun onCreate() = true
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, uri)
            insertValues = ContentValues(values)
            return if (refuseInsert) null else this.uri
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            assertEquals(this.uri, uri)
            assertEquals("wt", mode)
            if (denyWrite) throw SecurityException("Test provider refused write")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            assertEquals(this.uri, uri)
            publishValues = ContentValues(values)
            contentAtPublish = file.readText()
            return if (refusePublish) 0 else 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            assertEquals(this.uri, uri)
            deleted = true
            file.delete()
            return 1
        }
        override fun getType(uri: Uri) = "text/plain"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }
}
