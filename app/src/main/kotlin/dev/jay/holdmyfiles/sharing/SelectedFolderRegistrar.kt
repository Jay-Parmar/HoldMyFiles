package dev.jay.holdmyfiles.sharing

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.jay.holdmyfiles.core.storage.ShareId
import dev.jay.holdmyfiles.core.storage.ShareMutationRejection
import dev.jay.holdmyfiles.core.storage.ShareMutationResult
import dev.jay.holdmyfiles.core.storage.ShareSetVersion
import dev.jay.holdmyfiles.core.storage.ShareSnapshot
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SelectedFolderRejection {
    InvalidTree,
    PermissionDenied,
    DuplicateRoot,
    CapacityReached,
    MissingShare,
    IdUnavailable,
    PersistenceUnavailable,
}

enum class SelectedFolderWarning {
    PermissionReleaseFailed,
}

sealed interface SelectedFolderResult {
    val snapshot: ShareSnapshot<Uri>
    val warning: SelectedFolderWarning?

    data class Changed(
        override val snapshot: ShareSnapshot<Uri>,
        override val warning: SelectedFolderWarning? = null,
    ) : SelectedFolderResult

    data class Unchanged(
        override val snapshot: ShareSnapshot<Uri>,
        override val warning: SelectedFolderWarning? = null,
    ) : SelectedFolderResult

    data class Rejected(
        override val snapshot: ShareSnapshot<Uri>,
        val reason: SelectedFolderRejection,
        override val warning: SelectedFolderWarning? = null,
    ) : SelectedFolderResult
}

class SelectedFolderRegistrar(
    private val contentResolver: ContentResolver,
    private val repository: ObservableShareRepository<Uri>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    suspend fun addPickedTree(
        treeUri: Uri,
        suggestedLabel: String? = null,
    ): SelectedFolderResult = mutex.withLock {
        if (!isValidTreeUri(treeUri)) {
            return@withLock rejected(SelectedFolderRejection.InvalidTree)
        }

        val alreadyGranted = hasPersistedReadPermission(treeUri)
        if (!alreadyGranted && !takeReadPermission(treeUri)) {
            return@withLock rejected(SelectedFolderRejection.PermissionDenied)
        }

        val label = ShareLabelSanitizer.sanitize(
            suggestedLabel?.takeUnless(String::isBlank) ?: readDisplayName(treeUri),
        )
        val mutation = try {
            repository.add(label, treeUri)
        } catch (error: Throwable) {
            val warning = withContext(NonCancellable) {
                releaseNewPermission(treeUri, alreadyGranted)
            }
            when (error) {
                is CancellationException -> throw error
                is IOException -> return@withLock rejected(
                    reason = SelectedFolderRejection.PersistenceUnavailable,
                    warning = warning,
                )

                else -> throw error
            }
        }

        val shouldKeepGrant = mutation is ShareMutationResult.Changed ||
            mutation is ShareMutationResult.Unchanged ||
            mutation is ShareMutationResult.Rejected &&
            mutation.reason == ShareMutationRejection.DuplicateRoot
        val warning = if (shouldKeepGrant) {
            null
        } else {
            releaseNewPermission(treeUri, alreadyGranted)
        }
        mapMutation(mutation, warning)
    }

    suspend fun rename(
        id: ShareId,
        label: String,
    ): SelectedFolderResult = mutex.withLock {
        val sanitized = ShareLabelSanitizer.sanitize(label)
        try {
            mapMutation(repository.rename(id, sanitized))
        } catch (_: IOException) {
            rejected(SelectedFolderRejection.PersistenceUnavailable)
        }
    }

    suspend fun setEnabled(
        id: ShareId,
        enabled: Boolean,
    ): SelectedFolderResult = mutex.withLock {
        try {
            mapMutation(repository.setEnabled(id, enabled))
        } catch (_: IOException) {
            rejected(SelectedFolderRejection.PersistenceUnavailable)
        }
    }

    suspend fun remove(id: ShareId): SelectedFolderResult = mutex.withLock {
        val root = try {
            repository.snapshot().shares.firstOrNull { share -> share.id == id }?.storageRoot
        } catch (_: IOException) {
            return@withLock rejected(SelectedFolderRejection.PersistenceUnavailable)
        }

        val mutation = try {
            repository.remove(id)
        } catch (_: IOException) {
            return@withLock rejected(SelectedFolderRejection.PersistenceUnavailable)
        }
        val warning = if (mutation is ShareMutationResult.Changed && root != null) {
            withContext(NonCancellable) { releaseReadPermission(root) }
        } else {
            null
        }
        mapMutation(mutation, warning)
    }

    override fun toString(): String = "SelectedFolderRegistrar(redacted)"

    private suspend fun rejected(
        reason: SelectedFolderRejection,
        warning: SelectedFolderWarning? = null,
    ): SelectedFolderResult.Rejected = SelectedFolderResult.Rejected(
        snapshot = safeSnapshot(),
        reason = reason,
        warning = warning,
    )

    private suspend fun safeSnapshot(): ShareSnapshot<Uri> = try {
        repository.snapshot()
    } catch (_: IOException) {
        EMPTY_SNAPSHOT
    }

    private fun mapMutation(
        mutation: ShareMutationResult<Uri>,
        warning: SelectedFolderWarning? = null,
    ): SelectedFolderResult = when (mutation) {
        is ShareMutationResult.Changed -> SelectedFolderResult.Changed(
            snapshot = mutation.snapshot,
            warning = warning,
        )

        is ShareMutationResult.Unchanged -> SelectedFolderResult.Unchanged(
            snapshot = mutation.snapshot,
            warning = warning,
        )

        is ShareMutationResult.Rejected -> SelectedFolderResult.Rejected(
            snapshot = mutation.snapshot,
            reason = when (mutation.reason) {
                ShareMutationRejection.DuplicateRoot -> SelectedFolderRejection.DuplicateRoot
                ShareMutationRejection.CapacityReached -> SelectedFolderRejection.CapacityReached
                ShareMutationRejection.MissingShare -> SelectedFolderRejection.MissingShare
                ShareMutationRejection.IdUnavailable -> SelectedFolderRejection.IdUnavailable
            },
            warning = warning,
        )
    }

    private fun isValidTreeUri(uri: Uri): Boolean = runCatching {
        uri.scheme == ContentResolver.SCHEME_CONTENT &&
            !uri.authority.isNullOrBlank() &&
            uri.query == null &&
            uri.fragment == null &&
            DocumentsContract.isTreeUri(uri) &&
            DocumentsContract.getTreeDocumentId(uri).isNotBlank()
    }.getOrDefault(false)

    private suspend fun hasPersistedReadPermission(uri: Uri): Boolean = withContext(ioDispatcher) {
        runCatching {
            contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isReadPermission
            }
        }.getOrDefault(false)
    }

    private suspend fun takeReadPermission(uri: Uri): Boolean = withContext(ioDispatcher) {
        try {
            contentResolver.takePersistableUriPermission(uri, READ_PERMISSION_FLAG)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private suspend fun releaseNewPermission(
        uri: Uri,
        alreadyGranted: Boolean,
    ): SelectedFolderWarning? = if (alreadyGranted) null else releaseReadPermission(uri)

    private suspend fun releaseReadPermission(uri: Uri): SelectedFolderWarning? =
        withContext(ioDispatcher) {
            if (!hasPersistedReadPermission(uri)) {
                return@withContext null
            }
            try {
                contentResolver.releasePersistableUriPermission(uri, READ_PERMISSION_FLAG)
                null
            } catch (_: SecurityException) {
                SelectedFolderWarning.PermissionReleaseFailed
            } catch (_: IllegalArgumentException) {
                SelectedFolderWarning.PermissionReleaseFailed
            }
        }

    private suspend fun readDisplayName(treeUri: Uri): String? = withContext(ioDispatcher) {
        try {
            val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            contentResolver.query(
                rootDocumentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val READ_PERMISSION_FLAG = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val EMPTY_SNAPSHOT = ShareSnapshot<Uri>(
            version = ShareSetVersion(0),
            shares = emptyList(),
        )
    }
}

internal object ShareLabelSanitizer {
    fun sanitize(label: String?): String {
        val withoutControls = buildString {
            label.orEmpty().forEach { character ->
                when {
                    character.isISOControl() -> append(' ')
                    else -> append(character)
                }
            }
        }
        val normalized = withoutControls
            .trim()
            .replace(WHITESPACE, " ")
        if (normalized.isEmpty()) {
            return DEFAULT_LABEL
        }

        val bounded = normalized.take(MAX_LABEL_LENGTH)
        return if (bounded.last().isHighSurrogate()) bounded.dropLast(1) else bounded
    }

    private const val MAX_LABEL_LENGTH = 80
    private const val DEFAULT_LABEL = "Shared folder"
    private val WHITESPACE = Regex("\\s+")
}
