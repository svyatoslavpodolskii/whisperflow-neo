package com.pimax.whisperflow

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class NoteTrashCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        MarkdownNotes.get(applicationContext).pruneTrash()
        Result.success()
    }
    companion object {
        fun schedule(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "notes-trash-cleanup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NoteTrashCleanupWorker>(1, TimeUnit.DAYS).build())
    }
}
