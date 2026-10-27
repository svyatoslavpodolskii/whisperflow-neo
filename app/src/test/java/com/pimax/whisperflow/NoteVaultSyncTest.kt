package com.pimax.whisperflow

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteVaultSyncTest {
    @Test fun safVaultRoundTripsMarkdownAndPreservesBothConcurrentEdits() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        // Helpers used by other tests must not retain a connection from an earlier Robolectric sandbox.
        AudioInboxRepository.get(context).close()
        MarkdownNotes.get(context).close()
        val provider = LocalVaultProvider()
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "neo.test.vault"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        // Robolectric's legacy resolver calls the pre-O query overload, which DocumentsProvider
        // deliberately rejects. Forward that overload to its real modern query implementation.
        val bridge = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
                provider.query(uri, projection, android.os.Bundle.EMPTY, null)
            override fun getType(uri: android.net.Uri): String? = provider.getType(uri)
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = throw UnsupportedOperationException()
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
            override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
            override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? = provider.call(method, arg, extras)
            override fun openFile(uri: android.net.Uri, mode: String): ParcelFileDescriptor? = provider.openFile(uri, mode)
        }
        bridge.attachInfo(context, ProviderInfo().apply { authority = "neo.test.vault" })
        ShadowContentResolver.registerProviderInternal("neo.test.vault", bridge)
        val notes = MarkdownNotes.get(context)
        val note = notes.create("# Мысль\n\n- первый пункт\n")
        notes.vaultUri = DocumentsContract.buildTreeDocumentUri("neo.test.vault", "root").toString()
        val tree = android.net.Uri.parse(notes.vaultUri)
        context.grantUriPermission(context.packageName, tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions("android.permission.MANAGE_DOCUMENTS")
        val rootDocument = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree)!!
        context.contentResolver.query(rootDocument.uri, null, null, null, null)!!.use { assertTrue(it.moveToFirst()) }
        assertTrue("Root ${rootDocument.uri}, type=${rootDocument.type}, exists=${rootDocument.exists()}, provider=${provider.root}", rootDocument.isDirectory)
        assertTrue("SAF write permission=${context.checkCallingOrSelfUriPermission(rootDocument.uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}", rootDocument.canWrite())
        suspend fun sync() {
            val result = TestListenableWorkerBuilder<NoteVaultSyncWorker>(context).build().doWork()
            val errors = org.robolectric.shadows.ShadowLog.getLogsForTag("NoteVault").joinToString { it.msg + ": " + it.throwable?.stackTraceToString() }
            assertEquals(errors, ListenableWorker.Result.success(), result)
        }
        sync()
        val external = provider.root.listFiles()!!.single { it.extension == "md" }
        assertTrue(external.readText().contains("- первый пункт"))
        external.appendText("\n**Правка Obsidian**\n")
        sync()
        assertTrue(notes.read(note.id).contains("Правка Obsidian"))
        notes.saveEdit(note.id, notes.read(note.id) + "\nПравка Neo\n", notes.hash(note.id))
        external.appendText("\nОдновременная внешняя правка\n")
        sync()
        assertTrue(external.readText().contains("Правка Neo"))
        assertEquals(2, provider.root.listFiles()!!.count { it.extension == "md" })
        assertTrue(notes.list().any { notes.read(it.id).contains("Одновременная внешняя правка") })
        // A cloud/provider scan can temporarily omit a document. Never move the local note to trash.
        external.delete()
        sync()
        assertFalse(notes.find(note.id)!!.deleted)
        assertTrue(provider.root.listFiles()!!.any { it.extension == "md" && it.readText().contains("Правка Neo") })
        // Reinstall/restore: importing the vault reconnects an exported audio attachment.
        val captureId = java.util.UUID.randomUUID().toString()
        val audio = java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(4); putShort(1200); putShort(-1200)
        }.array()
        java.io.File(provider.root, "attachments").mkdirs()
        java.io.File(provider.root, "attachments/$captureId.wav").writeBytes(audio)
        java.io.File(provider.root, "restored.md").writeText("---\nneo_id: $captureId\n---\n\nТекст из резервной копии\n\n[Аудиозапись](attachments/$captureId.wav)\n")
        sync()
        val restored = notes.find(captureId)!!
        assertEquals(captureId, restored.recordingId)
        assertArrayEquals(audio, java.io.File(restored.audioPath!!).readBytes())
        sync()
        assertFalse(notes.read(captureId).contains("## Расшифровка"))
    }
}

class LocalVaultProvider : DocumentsProvider() {
    lateinit var root: File
    override fun onCreate(): Boolean { root = File(checkNotNull(context).cacheDir, "test-vault").apply { mkdirs() }; return true }
    private fun file(id: String): File = if (id == "root") root else File(root, id).also { check(it.canonicalPath.startsWith(root.canonicalPath + File.separator)) }
    private fun id(file: File) = if (file == root) "root" else file.relativeTo(root).path
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = cursor(listOf(file(documentId)), projection)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = cursor(file(parentDocumentId).listFiles()?.toList() ?: emptyList(), projection)
    private fun cursor(files: List<File>, projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return MatrixCursor(columns).apply {
            for (file in files) addRow(columns.map { column -> when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id(file)
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "text/markdown"
                DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or if (file.isDirectory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0
                DocumentsContract.Document.COLUMN_SIZE -> file.length()
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                else -> null
            } }.toTypedArray())
        }
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val created = File(file(parentDocumentId), displayName)
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) check(created.mkdirs()) else check(created.createNewFile())
        return id(created)
    }
    override fun deleteDocument(documentId: String) { check(file(documentId).delete()) }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath + File.separator)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
}
