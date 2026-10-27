package com.pimax.whisperflow.wear

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class Pcm16ResamplerTest {
    @Test fun native48kMonoMaintainsDurationAndSampleValuesAcrossCodecBuffers() {
        val output = ByteArrayOutputStream()
        val converter = Pcm16Resampler(48000, 1, output)
        repeat(100) {
            val buffer = ByteBuffer.allocate(960).order(ByteOrder.LITTLE_ENDIAN)
            repeat(480) { buffer.putShort(12000) }
            buffer.flip(); converter.consume(buffer)
        }
        val result = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000 * 2, result.remaining())
        while (result.hasRemaining()) assertEquals(12000.toShort(), result.short)
    }
    @Test fun stereo44100HasNoAccumulatingTimingDrift() {
        val output = ByteArrayOutputStream()
        val converter = Pcm16Resampler(44100, 2, output)
        repeat(441) {
            val buffer = ByteBuffer.allocate(400).order(ByteOrder.LITTLE_ENDIAN)
            repeat(100) { buffer.putShort(10000); buffer.putShort(-6000) }
            buffer.flip(); converter.consume(buffer)
        }
        val result = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32000, result.remaining())
        while (result.hasRemaining()) assertEquals(2000.toShort(), result.short)
    }
}
