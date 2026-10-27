package com.pimax.whisperflow.wear

import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** The reader never calls UI code or performs file I/O. Buffers are recycled, not allocated per read. */
internal class PcmCapturePipeline(
    private val source: Source,
    private val output: OutputStream,
    private val maxSamples: Int = 16000 * 600,
    private val prioritizeReader: () -> Unit = {}
) {
    interface Source {
        fun start()
        fun read(samples: ShortArray): Int
        fun stop()
        fun release()
    }
    private class Frame(val samples: ShortArray = ShortArray(320), var count: Int = 0)
    private val pool = ArrayBlockingQueue<Frame>(128).apply { repeat(128) { add(Frame()) } }
    private val ready = ArrayBlockingQueue<Frame>(128)
    private val stopping = AtomicBoolean(false)
    private val reading = AtomicBoolean(true)
    @Volatile var amplitude = 0f
        private set
    @Volatile var sampleCount = 0L
        private set
    @Volatile var failure: Throwable? = null
        private set
    @Volatile var reachedLimit = false
        private set
    private lateinit var reader: Thread
    private lateinit var writer: Thread

    fun start() {
        writer = Thread({
            val bytes = ByteArray(640)
            try {
                while (reading.get() || ready.isNotEmpty()) {
                    val frame = ready.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    try {
                        for (i in 0 until frame.count) {
                            bytes[i * 2] = frame.samples[i].toByte()
                            bytes[i * 2 + 1] = (frame.samples[i].toInt() shr 8).toByte()
                        }
                        output.write(bytes, 0, frame.count * 2)
                    } finally { pool.put(frame) }
                }
                output.flush()
            } catch (error: Throwable) {
                failure = error
                stopping.set(true)
                runCatching { source.stop() }
            }
        }, "Neo-PcmWriter").apply { start() }
        reader = Thread({
            try {
                prioritizeReader()
                source.start()
                while (!stopping.get()) {
                    val frame = pool.poll(250, TimeUnit.MILLISECONDS)
                        ?: throw IllegalStateException("PCM writer backlog exceeded 2.5 seconds")
                    val count = source.read(frame.samples)
                    if (count < 0) {
                        pool.put(frame)
                        if (!stopping.get()) error("AudioRecord.read: $count")
                        break
                    }
                    if (count == 0) { pool.put(frame); Thread.yield(); continue }
                    frame.count = minOf(count, maxSamples - sampleCount.toInt())
                    var peak = 0
                    for (i in 0 until frame.count) peak = maxOf(peak, abs(frame.samples[i].toInt()))
                    amplitude = (peak / 32768f).coerceIn(0f, 1f)
                    sampleCount += frame.count
                    ready.put(frame)
                    if (sampleCount >= maxSamples) { reachedLimit = true; break }
                }
            } catch (error: Throwable) {
                if (!stopping.get()) failure = error
            } finally {
                runCatching { source.stop() }
                reading.set(false)
                amplitude = 0f
            }
        }, "Neo-AudioReader").apply { start() }
    }

    fun stop() {
        stopping.set(true)
        runCatching { source.stop() } // Unblocks a pending native read, without releasing it underneath the reader.
    }

    fun await() {
        reader.join()
        writer.join()
        source.release()
        failure?.let { throw IllegalStateException("Audio capture failed; PCM journal retained", it) }
    }
}
