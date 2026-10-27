package com.pimax.whisperflow.wear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WearWavTest {
    @Test
    fun headerDescribesMono16Bit16kHzPcmAndPreservesSamples() {
        val pcm = byteArrayOf(0, 0, -1, 127, 0, -128, -1, -1)
        val wav = WearWav.encode(pcm)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(wav.size - 8, header.getInt(4))
        assertEquals(16, header.getInt(16))
        assertEquals(1, header.getShort(20).toInt())
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16000, header.getInt(24))
        assertEquals(32000, header.getInt(28))
        assertEquals(2, header.getShort(32).toInt())
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(pcm.size, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun oneSecondCaptureHasConsistentRiffAndDataLengths() {
        val wav = WearWav.encode(ByteArray(32000))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32044, wav.size)
        assertEquals(32036, header.getInt(4))
        assertEquals(32000, header.getInt(40))
    }
}
