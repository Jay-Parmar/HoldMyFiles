package dev.jay.holdmyfiles.storage

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import dev.jay.holdmyfiles.core.storage.DirectoryListing
import dev.jay.holdmyfiles.core.storage.OpenedFile
import dev.jay.holdmyfiles.core.storage.ReadLease
import dev.jay.holdmyfiles.core.storage.ReadOnlyStorageGateway
import dev.jay.holdmyfiles.core.storage.StorageNode
import dev.jay.holdmyfiles.core.storage.StorageNodeKind
import dev.jay.holdmyfiles.core.storage.StorageOutcome
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SafReadOnlyStorageGateway(
    private val contentResolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ReadOnlyStorageGateway<Uri, Uri> {
    override suspend fun root(storageRoot: Uri): StorageOutcome<StorageNode<Uri>> = onIo {
        val root = resolveRoot(storageRoot) ?: return@onIo StorageOutcome.OutsideRoot
        val node = queryDocument(root.rootDocumentUri)
            ?: return@onIo StorageOutcome.Missing
        if (node.kind != StorageNodeKind.Directory) {
            return@onIo StorageOutcome.WrongKind
        }
        StorageOutcome.Ok(node)
    }

    override suspend fun listChildren(
        storageRoot: Uri,
        directory: StorageNode<Uri>,
    ): StorageOutcome<DirectoryListing<Uri>> = onIo {
        if (directory.kind != StorageNodeKind.Directory) {
            return@onIo StorageOutcome.WrongKind
        }
        val root = resolveRoot(storageRoot) ?: return@onIo StorageOutcome.OutsideRoot
        if (!isContained(root, directory.reference)) {
            return@onIo StorageOutcome.OutsideRoot
        }
        val actualDirectory = queryDocument(directory.reference)
            ?: return@onIo StorageOutcome.Missing
        if (actualDirectory.kind != StorageNodeKind.Directory) {
            return@onIo StorageOutcome.WrongKind
        }

        val directoryId = DocumentsContract.getDocumentId(directory.reference)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            root.treeUri,
            directoryId,
        )
        val cursor = contentResolver.query(
            childrenUri,
            DOCUMENT_PROJECTION,
            null,
            null,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC",
        ) ?: return@onIo StorageOutcome.Unavailable

        cursor.use {
            val nodes = ArrayList<StorageNode<Uri>>(MAX_LISTING_ENTRIES)
            var truncated = false
            var inspectedRows = 0
            while (it.moveToNext()) {
                if (inspectedRows == MAX_LISTING_ENTRIES) {
                    truncated = true
                    break
                }
                inspectedRows += 1
                val child = readDocument(it, root.treeUri) ?: continue
                if (!isContained(root, child.reference)) {
                    throw MalformedDocumentException()
                }
                nodes += child
            }
            StorageOutcome.Ok(DirectoryListing(nodes, truncated))
        }
    }

    override suspend fun openFile(
        storageRoot: Uri,
        file: StorageNode<Uri>,
    ): StorageOutcome<OpenedFile> = onIo {
        if (file.kind != StorageNodeKind.File) {
            return@onIo StorageOutcome.WrongKind
        }
        val root = resolveRoot(storageRoot) ?: return@onIo StorageOutcome.OutsideRoot
        if (!isContained(root, file.reference)) {
            return@onIo StorageOutcome.OutsideRoot
        }
        val actualFile = queryDocument(file.reference)
            ?: return@onIo StorageOutcome.Missing
        if (actualFile.kind != StorageNodeKind.File) {
            return@onIo StorageOutcome.WrongKind
        }

        val descriptor = contentResolver.openFileDescriptor(file.reference, READ_MODE)
            ?: return@onIo StorageOutcome.Unavailable
        val input = try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        } catch (error: Throwable) {
            descriptor.close()
            throw error
        }
        val lease = InputStreamReadLease(input, ioDispatcher)
        try {
            StorageOutcome.Ok(
                OpenedFile(
                    displayName = actualFile.displayName,
                    sizeBytes = actualFile.sizeBytes,
                    content = lease,
                ),
            )
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    override fun toString(): String = "SafReadOnlyStorageGateway(redacted)"

    private suspend fun <T> onIo(
        operation: () -> StorageOutcome<T>,
    ): StorageOutcome<T> = withContext(ioDispatcher) {
        try {
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (_: FileNotFoundException) {
            StorageOutcome.Missing
        } catch (_: SecurityException) {
            StorageOutcome.AccessRevoked
        } catch (_: IOException) {
            StorageOutcome.Unavailable
        } catch (_: MalformedDocumentException) {
            StorageOutcome.Unavailable
        } catch (_: IllegalArgumentException) {
            StorageOutcome.Unavailable
        } catch (_: IllegalStateException) {
            StorageOutcome.Unavailable
        } catch (_: UnsupportedOperationException) {
            StorageOutcome.Unavailable
        }
    }

    private fun resolveRoot(treeUri: Uri): RootContext? = runCatching {
        if (
            treeUri.scheme != ContentResolver.SCHEME_CONTENT ||
            treeUri.authority.isNullOrBlank() ||
            treeUri.query != null ||
            treeUri.fragment != null ||
            !DocumentsContract.isTreeUri(treeUri)
        ) {
            return null
        }
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        if (documentId.isBlank()) {
            return null
        }
        RootContext(
            treeUri = treeUri,
            rootDocumentId = documentId,
            rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
        )
    }.getOrNull()

    private fun isContained(root: RootContext, candidate: Uri): Boolean {
        if (
            candidate.scheme != ContentResolver.SCHEME_CONTENT ||
            candidate.authority != root.treeUri.authority ||
            candidate.query != null ||
            candidate.fragment != null ||
            !DocumentsContract.isTreeUri(candidate)
        ) {
            return false
        }

        val treeId: String
        val documentId: String
        try {
            treeId = DocumentsContract.getTreeDocumentId(candidate)
            documentId = DocumentsContract.getDocumentId(candidate)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (treeId != root.rootDocumentId) {
            return false
        }
        if (documentId == root.rootDocumentId || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }
        return DocumentsContract.isChildDocument(
            contentResolver,
            root.rootDocumentUri,
            candidate,
        )
    }

    private fun queryDocument(documentUri: Uri): StorageNode<Uri>? {
        val cursor = contentResolver.query(
            documentUri,
            DOCUMENT_PROJECTION,
            null,
            null,
            null,
        ) ?: throw MalformedDocumentException()
        return cursor.use {
            if (it.moveToFirst()) readDocument(it, documentUri) else null
        }
    }

    private fun readDocument(
        cursor: Cursor,
        treeUri: Uri,
    ): StorageNode<Uri>? {
        val flags = cursor.longOrZero(DocumentsContract.Document.COLUMN_FLAGS)
        if (flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT.toLong() != 0L) {
            return null
        }
        val documentId = cursor.requiredString(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        if (documentId.isBlank() || documentId.length > MAX_DOCUMENT_ID_LENGTH) {
            throw MalformedDocumentException()
        }
        val displayName = cursor.requiredString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        if (displayName.isEmpty() || displayName.length > MAX_DISPLAY_NAME_LENGTH) {
            throw MalformedDocumentException()
        }
        val mimeType = cursor.requiredString(DocumentsContract.Document.COLUMN_MIME_TYPE)
        if (mimeType.isEmpty() || mimeType.length > MAX_MIME_TYPE_LENGTH) {
            throw MalformedDocumentException()
        }
        val kind = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            StorageNodeKind.Directory
        } else {
            StorageNodeKind.File
        }
        val sizeBytes = if (kind == StorageNodeKind.File) {
            cursor.nonNegativeLongOrNull(DocumentsContract.Document.COLUMN_SIZE)
        } else {
            null
        }
        val modifiedAt = cursor.nonNegativeLongOrNull(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return StorageNode(
            reference = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
            displayName = displayName,
            kind = kind,
            sizeBytes = sizeBytes,
            modifiedAtEpochMillis = modifiedAt,
            mimeType = if (kind == StorageNodeKind.File) mimeType else null,
        )
    }

    private fun Cursor.requiredString(columnName: String): String {
        val index = getColumnIndex(columnName)
        if (index == -1 || isNull(index)) {
            throw MalformedDocumentException()
        }
        return getString(index) ?: throw MalformedDocumentException()
    }

    private fun Cursor.nonNegativeLongOrNull(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        if (index == -1 || isNull(index)) {
            return null
        }
        return getLong(index).takeIf { value -> value >= 0 }
    }

    private fun Cursor.longOrZero(columnName: String): Long {
        val index = getColumnIndex(columnName)
        return if (index == -1 || isNull(index)) 0 else getLong(index)
    }

    private data class RootContext(
        val treeUri: Uri,
        val rootDocumentId: String,
        val rootDocumentUri: Uri,
    )

    private class MalformedDocumentException : RuntimeException()

    private companion object {
        const val MAX_LISTING_ENTRIES = 1_000
        const val MAX_DISPLAY_NAME_LENGTH = 512
        const val MAX_MIME_TYPE_LENGTH = 255
        const val MAX_DOCUMENT_ID_LENGTH = 8_192
        const val READ_MODE = "r"
        val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}

internal class InputStreamReadLease(
    private val input: InputStream,
    private val ioDispatcher: CoroutineDispatcher,
) : ReadLease {
    private val closed = AtomicBoolean()

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        check(!closed.get())
        require(offset >= 0 && length >= 0 && offset <= destination.size - length)
        if (length == 0) {
            return 0
        }
        return withContext(ioDispatcher) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { close() }
                try {
                    continuation.resume(input.read(destination, offset, length))
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            input.close()
        }
    }

    override fun toString(): String = "InputStreamReadLease(redacted)"
}
