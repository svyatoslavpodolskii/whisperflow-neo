package com.pimax.whisperflow.wear

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder

/** Conversion runs after Stop; transport is still validated PCM16 mono 16 kHz WAV. */
internal object NativeCaptureDecoder {
    fun wav(file: File): ByteArray {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME))).also { decoder = it }
            codec.configure(format, null, null, 0); codec.start()
            val info = MediaCodec.BufferInfo()
            val pcm = ByteArrayOutputStream()
            var resampler = Pcm16Resampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), pcm)
            var inputEnded = false
            var outputEnded = false
            var idle = 0
            while (!outputEnded) {
                check(idle++ < 3000) { "Audio decoder stalled; source retained" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val count = extractor.readSampleData(checkNotNull(codec.getInputBuffer(index)), 0)
                        if (count < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        idle = 0
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        check(!output.containsKey(MediaFormat.KEY_PCM_ENCODING) || output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT)
                        check(pcm.size() == 0) { "Unexpected mid-stream format change" }
                        resampler = Pcm16Resampler(output.getInteger(MediaFormat.KEY_SAMPLE_RATE), output.getInteger(MediaFormat.KEY_CHANNEL_COUNT), pcm)
                    }
                    else -> if (index >= 0) {
                        val buffer = checkNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        resampler.consume(buffer)
                        check(pcm.size() <= 19_200_000) { "Capture exceeds ten minutes" }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false); idle = 0
                    }
                }
            }
            check(pcm.size() >= 2) { "Empty capture" }
            return WearWav.encode(pcm.toByteArray())
        } finally {
            runCatching { decoder?.stop() }; decoder?.release(); extractor.release()
        }
    }
}

/** Area resampling preserves duration across arbitrary codec buffer boundaries. */
internal class Pcm16Resampler(private val rate: Int, private val channels: Int, private val output: java.io.OutputStream) {
    private var remaining = rate
    private var sum = 0.0
    private val bytes = ByteArray(4096)
    private var count = 0
    init { require(rate >= 16000 && channels in 1..8) }
    fun consume(buffer: java.nio.ByteBuffer) {
        while (buffer.remaining() >= channels * 2) {
            var mono = 0.0
            repeat(channels) { mono += buffer.short.toInt() }
            mono /= channels
            var weight = 16000
            while (weight > 0) {
                val portion = minOf(weight, remaining)
                sum += mono * portion
                remaining -= portion; weight -= portion
                if (remaining == 0) {
                    val sample = (sum / rate).toInt().coerceIn(-32768, 32767)
                    bytes[count++] = sample.toByte(); bytes[count++] = (sample shr 8).toByte()
                    if (count == bytes.size) { output.write(bytes, 0, count); count = 0 }
                    remaining = rate; sum = 0.0
                }
            }
        }
        check(!buffer.hasRemaining()) { "Partial decoded PCM frame" }
        if (count > 0) { output.write(bytes, 0, count); count = 0 }
    }
}
