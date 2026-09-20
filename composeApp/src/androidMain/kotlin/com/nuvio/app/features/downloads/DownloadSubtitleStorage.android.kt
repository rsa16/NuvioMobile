package com.nuvio.app.features.downloads

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import java.io.File
import java.net.URI

internal actual class DownloadSubtitleStorage actual constructor(localVideoUri: String) {
    private val directory: SubtitleDirectory = if (localVideoUri.startsWith("content:")) {
        SafSubtitleDirectory(Uri.parse(localVideoUri))
    } else {
        LegacySubtitleDirectory(File(File(URI(localVideoUri)).path + SUBTITLES_SUFFIX))
    }

    actual fun read(fileName: String): String? = directory.read(fileName)

    actual fun write(fileName: String, text: String) = directory.write(fileName, text)

    actual fun localFileUri(fileName: String): String? = directory.fileUri(fileName)

    actual fun remove() = directory.remove()
}

private interface SubtitleDirectory {
    fun read(fileName: String): String?
    fun write(fileName: String, text: String)
    fun fileUri(fileName: String): String?
    fun remove()
}

private class LegacySubtitleDirectory(private val directory: File) : SubtitleDirectory {
    override fun read(fileName: String): String? =
        runCatching { AtomicFile(file(fileName)).readFully().decodeToString() }.getOrNull()

    override fun write(fileName: String, text: String) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create subtitle directory" }
        val file = AtomicFile(file(fileName))
        val output = file.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    override fun fileUri(fileName: String): String? =
        file(fileName).takeIf { it.isFile }?.toURI()?.toString()

    override fun remove() {
        directory.deleteRecursively()
    }

    private fun file(fileName: String): File {
        requireValidFileName(fileName)
        return File(directory, fileName)
    }
}

/**
 * Writes the `<video>.<ext>.subtitles/` directory beside a downloaded `content://` video, so a
 * folder-scoped download stays self-contained and playable from its stored-file URI.
 */
private class SafSubtitleDirectory(private val videoUri: Uri) : SubtitleDirectory {
    override fun read(fileName: String): String? {
        val resolver = contentResolver() ?: return null
        val document = findFile(fileName) ?: return null
        return runCatching {
            resolver.openInputStream(document)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
    }

    override fun write(fileName: String, text: String) {
        requireValidFileName(fileName)
        val resolver = contentResolver() ?: error("Downloads are not initialized")
        val directory = ensureDirectory() ?: error("Cannot create the subtitle directory")
        findChild(directory, fileName)?.let { existing ->
            runCatching { DocumentsContract.deleteDocument(resolver, existing) }
        }
        val document = DocumentsContract.createDocument(resolver, directory, SUBTITLE_MIME_TYPE, fileName)
            ?: error("Cannot create the subtitle file")
        val output = resolver.openOutputStream(document, "w")
            ?: error("Cannot write the subtitle file")
        output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    override fun fileUri(fileName: String): String? {
        val document = findFile(fileName) ?: return null
        return document.takeIf { !isDirectory(it) }?.toString()
    }

    override fun remove() {
        val resolver = contentResolver() ?: return
        val directory = findDirectory() ?: return
        runCatching { DocumentsContract.deleteDocument(resolver, directory) }
    }

    private fun findFile(fileName: String): Uri? {
        requireValidFileName(fileName)
        val directory = findDirectory() ?: return null
        return findChild(directory, fileName)
    }

    private fun findDirectory(): Uri? {
        val parent = parentDocument() ?: return null
        val directoryName = directoryName() ?: return null
        return findChild(parent.treeUri, parent.documentId, directoryName)
    }

    private fun ensureDirectory(): Uri? {
        findDirectory()?.let { return it }
        val parent = parentDocument() ?: return null
        val directoryName = directoryName() ?: return null
        val resolver = contentResolver() ?: return null
        val created = runCatching {
            DocumentsContract.createDocument(
                resolver,
                parent.documentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                directoryName,
            )
        }.getOrNull() ?: return null
        return runCatching {
            DocumentsContract.buildDocumentUriUsingTree(parent.treeUri, DocumentsContract.getDocumentId(created))
        }.getOrNull()
    }

    private fun directoryName(): String? =
        videoDisplayName()?.let { "$it$SUBTITLES_SUFFIX" }

    private fun findChild(directoryUri: Uri, displayName: String): Uri? =
        runCatching {
            val treeUri = DocumentsContract.buildTreeDocumentUri(
                directoryUri.authority,
                DocumentsContract.getTreeDocumentId(directoryUri),
            )
            findChild(treeUri, DocumentsContract.getDocumentId(directoryUri), displayName)
        }.getOrNull()

    private fun findChild(treeUri: Uri, parentDocumentId: String, displayName: String): Uri? {
        val resolver = contentResolver() ?: return null
        return runCatching {
            resolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId),
                CHILD_PROJECTION,
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) == displayName) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun isDirectory(documentUri: Uri): Boolean {
        val resolver = contentResolver() ?: return false
        return runCatching {
            resolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null,
            )?.use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
            }
        }.getOrDefault(false) ?: false
    }

    private fun parentDocument(): ParentDocument? {
        runCatching { DocumentsContract.getTreeDocumentId(videoUri) }.getOrNull()?.let { treeDocumentId ->
            val treeUri = runCatching {
                DocumentsContract.buildTreeDocumentUri(videoUri.authority, treeDocumentId)
            }.getOrNull() ?: return null
            return ParentDocument(treeUri, treeDocumentId)
        }
        val documentId = runCatching { DocumentsContract.getDocumentId(videoUri) }.getOrNull() ?: return null
        val parentDocumentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentDocumentId.isBlank() || parentDocumentId == documentId) return null
        val treeUri = runCatching {
            DocumentsContract.buildTreeDocumentUri(videoUri.authority, parentDocumentId)
        }.getOrNull() ?: return null
        return ParentDocument(treeUri, parentDocumentId)
    }

    private fun videoDisplayName(): String? = queryVideoDisplayName() ?: documentIdFileName()

    private fun queryVideoDisplayName(): String? {
        val resolver = contentResolver() ?: return null
        return runCatching {
            resolver.query(
                videoUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun documentIdFileName(): String? =
        runCatching { DocumentsContract.getDocumentId(videoUri) }
            .getOrNull()
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }

    private fun contentResolver(): ContentResolver? =
        DownloadLocationManager.applicationContext()?.contentResolver

    private data class ParentDocument(val treeUri: Uri, val documentId: String) {
        val documentUri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }
}

private fun requireValidFileName(fileName: String) {
    require(fileName.isNotBlank() && '/' !in fileName && fileName != "." && fileName != "..") {
        "Invalid subtitle file name"
    }
}

private const val SUBTITLES_SUFFIX = ".subtitles"
private const val SUBTITLE_MIME_TYPE = "application/octet-stream"
private val CHILD_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
)
