package com.pimax.whisperflow

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NoteNotifications {
    const val NOTE_ID_EXTRA = "neo_note_id"
    private fun tag(id: String) = "neo-note-$id"
    fun cancel(context: Context, id: String) { NotificationManagerCompat.from(context).cancel(tag(id), 1) }
    @Synchronized fun read(context: Context, id: String) {
        MarkdownNotes.get(context).markRead(id)
        NotificationManagerCompat.from(context).cancel(tag(id), 1)
    }
    @android.annotation.SuppressLint("MissingPermission")
    @Synchronized fun publish(context: Context, candidate: MarkdownNote) {
        val note = MarkdownNotes.get(context).find(candidate.id) ?: return
        if (note.deleted || note.seenAt != 0L || note.transcript.isBlank() || note.notifiedAt >= note.updatedAt) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("neo-unread-notes", context.getString(R.string.neo_unread_notification), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
        val intent = Intent(context, MainActivity::class.java).putExtra(NOTE_ID_EXTRA, note.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, note.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "neo-unread-notes")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(note.title)
            .setContentText(note.transcript.take(160)).setContentIntent(pending)
            .setSilent(true).setOnlyAlertOnce(true).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        NotificationManagerCompat.from(context).notify(tag(note.id), 1, notification)
        MarkdownNotes.get(context).markNotified(note.id, note.updatedAt)
    }
}
