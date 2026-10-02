package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class Iap2UsbMuxHandshakeTest {
    @Test fun trailingVersionReplyBytesAreDiscardedBeforeSetup() {
        assertEquals(0, discardUsbMuxHandshakeRemainder(byteArrayOf(0x49, 0x28, 0x73, 0x00)).size)
    }

    @Test fun emptyVersionReplyRemainderStaysEmpty() {
        assertEquals(0, discardUsbMuxHandshakeRemainder(ByteArray(0)).size)
    }
}
