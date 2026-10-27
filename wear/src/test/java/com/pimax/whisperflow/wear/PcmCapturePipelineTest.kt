package com.pimax.whisperflow.wear

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PcmCapturePipelineTest {
    @Test fun blockedWriterAndUiDoNotBlockMicrophoneReadsOrChangeSamples() {
        val writerEntered = CountDownLatch(1)
        val allowWriter = CountDownLatch(1)
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                writerEntered.countDown()
                check(allowWriter.await(3, TimeUnit.SECONDS))
                super.write(bytes, offset, count)
            }
        }
        var nextSample = 0
        var released = false
        var readerThread = ""
        val source = object : PcmCapturePipeline.Source {
            override fun start() { readerThread = Thread.currentThread().name }
            override fun read(samples: ShortArray): Int {
                for (i in samples.indices) samples[i] = (nextSample++).toShort()
                return samples.size
            }
            override fun stop() = Unit
            override fun release() { released = true }
        }
        val capture = PcmCapturePipeline(source, output, maxSamples = 32000)
        capture.start()
        assertTrue(writerEntered.await(1, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (!capture.reachedLimit && System.nanoTime() < deadline) Thread.sleep(2)
        try {
            assertTrue("Reader must finish while writer/UI are blocked", capture.reachedLimit)
            assertEquals("Neo-AudioReader", readerThread)
        } finally { allowWriter.countDown(); capture.await() }
        assertTrue(released)
        val bytes = output.toByteArray()
        assertEquals(64000, bytes.size)
        for (i in 0 until 32000) {
            val sample = ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
            assertEquals("PCM sample $i", i.toShort(), sample)
        }
    }
}
