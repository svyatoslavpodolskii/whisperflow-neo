package com.pimax.whisperflow

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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioInboxRepositoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun reset() {
        context.deleteDatabase("voice_memory.db")
        File(context.filesDir, "voice_memory").deleteRecursively()
    }

    @Test fun redeliveryAndDatabaseReopenPreserveOneDurableRecordingAndTranscript() {
        val id = UUID.randomUUID().toString()
        val bytes = wav()
        val sha = CaptureProtocol.sha256(bytes)
        val repo = AudioInboxRepository(context)
        val row = repo.accept(id, 12345, bytes, sha)
        assertArrayEquals(bytes, File(row.audioPath).readBytes())
        repo.update(id, "DONE", "Сохранённый текст")
        repo.accept(id, 12345, bytes, sha)
        assertEquals(1, repo.list().size)
        repo.close()
        val reopened = AudioInboxRepository(context)
        assertEquals("DONE", reopened.find(id)!!.state)
        assertEquals("Сохранённый текст", reopened.find(id)!!.transcription)
        assertArrayEquals(bytes, File(reopened.find(id)!!.audioPath).readBytes())
        reopened.close()
    }

    @Test fun corruptDeliveryNeverCreatesAnAckableRow() {
        val repo = AudioInboxRepository(context)
        val id = UUID.randomUUID().toString()
        assertTrue(runCatching { repo.accept(id, 1, wav(), "wrong-hash") }.isFailure)
        assertNull(repo.find(id))
        val truncated = wav().dropLast(2).toByteArray()
        assertTrue(runCatching { repo.accept(id, 1, truncated, CaptureProtocol.sha256(truncated)) }.isFailure)
        assertTrue(repo.list().isEmpty())
        repo.close()
    }

    @Test fun userDeletionCannotBeUndoneByLostAckRedelivery() {
        val repo = AudioInboxRepository(context)
        val id = UUID.randomUUID().toString()
        val bytes = wav(); val sha = CaptureProtocol.sha256(bytes)
        val row = repo.accept(id, 1, bytes, sha)
        repo.delete(id)
        assertTrue(repo.accept(id, 1, bytes, sha).deleted)
        assertTrue(repo.list().isEmpty())
        assertFalse(File(row.audioPath).exists())
        repo.close()
    }

    private fun wav(): ByteArray = ByteBuffer.allocate(3244).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(3236); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(3200); put(ByteArray(3200) { it.toByte() })
    }.array()
}
