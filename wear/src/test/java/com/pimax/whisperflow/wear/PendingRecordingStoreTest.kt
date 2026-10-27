package com.pimax.whisperflow.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pimax.whisperflow.sync.CaptureProtocol
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingRecordingStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun reset() {
        File(context.filesDir, "pending_recordings").deleteRecursively()
        File(context.filesDir, "capture_spool").deleteRecursively()
    }

    @Test fun offlineQueueSurvivesRecreationAndOnlyMatchingAckDeletesAudio() {
        val bytes = WearWav.encode(ByteArray(32000) { it.toByte() })
        val id = PendingRecordingStore(context).save(bytes)
        val recreated = PendingRecordingStore(context)
        assertEquals(id, recreated.pending().single().id)
        recreated.attempted(id, true) // System enqueue is not phone persistence.
        assertTrue(recreated.pending().single().file.exists())
        assertFalse(recreated.acknowledge(id, "wrong-checksum"))
        assertEquals(1, recreated.pending().size)
        assertTrue(recreated.acknowledge(id, CaptureProtocol.sha256(bytes)))
        assertTrue(PendingRecordingStore(context).pending().isEmpty())
        assertTrue(recreated.acknowledge(id, CaptureProtocol.sha256(bytes))) // Repeated ACK is harmless.
    }

    @Test fun interruptedMetadataWriteIsRecoveredAndCapturesHaveSeparateFiles() {
        val store = PendingRecordingStore(context)
        val first = store.save(WearWav.encode(ByteArray(1600)))
        val second = store.save(WearWav.encode(ByteArray(3200)))
        File(context.filesDir, "pending_recordings/$first.json").delete()
        val entries = PendingRecordingStore(context).pending()
        assertEquals(setOf(first, second), entries.map { it.id }.toSet())
        assertTrue(entries.all { it.file.exists() && it.sha256.length == 64 })
    }

    @Test fun processDeathJournalBecomesValidWavWithoutPhoneOrActivity() {
        val id = java.util.UUID.randomUUID().toString()
        val journal = File(context.filesDir, "capture_spool/$id.pcm")
        journal.parentFile!!.mkdirs()
        journal.writeBytes(ByteArray(3201) { it.toByte() })
        val entry = PendingRecordingStore(context).pending().single()
        assertEquals(id, entry.id)
        assertEquals(100L, CaptureProtocol.validateWav(entry.file.readBytes()))
        assertFalse(journal.exists())
    }
}
