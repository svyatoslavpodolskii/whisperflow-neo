package com.pimax.whisperflow

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteNotificationsTest {
    @Test fun unreadNoteNotificationIsSilentAndOpeningTheNoteRemovesIt() {
        val context: Context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val repo = MarkdownNotes.reopenForTests(context)
        AudioInboxRepository.reopenForTests(context)
        val id = UUID.randomUUID().toString()
        repo.materialize(id, System.currentTimeMillis(), "Новая мысль", "WATCH", null)
        NoteNotifications.publish(context, repo.find(id)!!)
        assertEquals(1, Shadows.shadowOf(manager).allNotifications.size)
        assertNull(manager.getNotificationChannel("neo-unread-notes").sound)
        assertEquals(repo.find(id)!!.updatedAt, repo.find(id)!!.notifiedAt)
        NoteNotifications.read(context, id)
        assertTrue(repo.find(id)!!.seenAt > 0)
        assertTrue(Shadows.shadowOf(manager).allNotifications.isEmpty())
        // Even an old candidate snapshot must not resurrect a notification for a viewed note.
        NoteNotifications.publish(context, repo.find(id)!!)
        assertTrue(Shadows.shadowOf(manager).allNotifications.isEmpty())
    }
    @Test fun manuallyWrittenAndDeletedNotesAreNotAnnouncedAsNewVoiceNotes() {
        val context: Context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val repo = MarkdownNotes.reopenForTests(context)
        AudioInboxRepository.reopenForTests(context)
        val manual = repo.create("# Ручная заметка")
        NoteNotifications.publish(context, manual)
        assertTrue(Shadows.shadowOf(manager).allNotifications.isEmpty())
        val id = UUID.randomUUID().toString()
        repo.materialize(id, System.currentTimeMillis(), "Удалённая мысль", "WATCH", null)
        val stale = repo.find(id)!!
        repo.delete(id)
        NoteNotifications.publish(context, stale)
        assertTrue(Shadows.shadowOf(manager).allNotifications.isEmpty())
    }
}
