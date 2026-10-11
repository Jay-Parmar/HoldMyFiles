package dev.jay.holdmyfiles.sharing

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.core.net.toUri
import dev.jay.holdmyfiles.core.storage.RandomShareIdGenerator
import dev.jay.holdmyfiles.core.storage.ShareId
import dev.jay.holdmyfiles.core.storage.ShareIdGenerator
import dev.jay.holdmyfiles.core.storage.ShareMutationRejection
import dev.jay.holdmyfiles.core.storage.ShareMutationResult
import dev.jay.holdmyfiles.core.storage.ShareRepository
import dev.jay.holdmyfiles.core.storage.ShareSetVersion
import dev.jay.holdmyfiles.core.storage.ShareSnapshot
import dev.jay.holdmyfiles.core.storage.SharedFolder
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface ObservableShareRepository<R : Any> : ShareRepository<R> {
    val snapshots: Flow<ShareSnapshot<R>>
}

private val Context.holdMyFilesShareDataStore by preferencesDataStore(
    name = "hold_my_files_shares",
)

class DataStoreShareRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val idGenerator: ShareIdGenerator = RandomShareIdGenerator(),
) : ObservableShareRepository<Uri> {
    constructor(
        context: Context,
        idGenerator: ShareIdGenerator = RandomShareIdGenerator(),
    ) : this(context.applicationContext.holdMyFilesShareDataStore, idGenerator)

    override val snapshots: Flow<ShareSnapshot<Uri>> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map(::decodeSnapshot)

    override suspend fun snapshot(): ShareSnapshot<Uri> = snapshots.first()

    override suspend fun add(
        label: String,
        storageRoot: Uri,
    ): ShareMutationResult<Uri> = mutate { current ->
        if (current.shares.any { share -> share.storageRoot == storageRoot }) {
            return@mutate rejected(current, ShareMutationRejection.DuplicateRoot)
        }
        if (current.shares.size >= MAX_CONFIGURED_SHARES) {
            return@mutate rejected(current, ShareMutationRejection.CapacityReached)
        }

        val id = generateUniqueId(current)
            ?: return@mutate rejected(current, ShareMutationRejection.IdUnavailable)
        changed(
            current = current,
            shares = current.shares + SharedFolder(
                id = id,
                label = label,
                enabled = true,
                storageRoot = storageRoot,
            ),
        )
    }

    override suspend fun rename(
        id: ShareId,
        label: String,
    ): ShareMutationResult<Uri> = mutate { current ->
        val index = current.shares.indexOfFirst { share -> share.id == id }
        if (index == -1) {
            return@mutate rejected(current, ShareMutationRejection.MissingShare)
        }
        val existing = current.shares[index]
        if (existing.label == label) {
            return@mutate ShareMutationResult.Unchanged(current)
        }

        val shares = current.shares.toMutableList()
        shares[index] = existing.copy(label = label)
        changed(current, shares)
    }

    override suspend fun setEnabled(
        id: ShareId,
        enabled: Boolean,
    ): ShareMutationResult<Uri> = mutate { current ->
        val index = current.shares.indexOfFirst { share -> share.id == id }
        if (index == -1) {
            return@mutate rejected(current, ShareMutationRejection.MissingShare)
        }
        val existing = current.shares[index]
        if (existing.enabled == enabled) {
            return@mutate ShareMutationResult.Unchanged(current)
        }

        val shares = current.shares.toMutableList()
        shares[index] = existing.copy(enabled = enabled)
        changed(current, shares)
    }

    override suspend fun remove(id: ShareId): ShareMutationResult<Uri> = mutate { current ->
        val shares = current.shares.filterNot { share -> share.id == id }
        if (shares.size == current.shares.size) {
            return@mutate rejected(current, ShareMutationRejection.MissingShare)
        }
        changed(current, shares)
    }

    override fun toString(): String = "DataStoreShareRepository(redacted)"

    private suspend fun mutate(
        transform: (ShareSnapshot<Uri>) -> ShareMutationResult<Uri>,
    ): ShareMutationResult<Uri> {
        var result: ShareMutationResult<Uri>? = null
        dataStore.edit { preferences ->
            val mutation = transform(decodeSnapshot(preferences))
            if (mutation is ShareMutationResult.Changed) {
                preferences[STATE_KEY] = encodeSnapshot(mutation.snapshot)
            }
            result = mutation
        }
        return checkNotNull(result)
    }

    private fun generateUniqueId(snapshot: ShareSnapshot<Uri>): ShareId? {
        val existingIds = snapshot.shares.mapTo(mutableSetOf()) { share -> share.id }
        repeat(MAX_ID_GENERATION_ATTEMPTS) {
            val candidate = idGenerator.generate()
            if (candidate !in existingIds) {
                return candidate
            }
        }
        return null
    }

    private fun changed(
        current: ShareSnapshot<Uri>,
        shares: List<SharedFolder<Uri>>,
    ): ShareMutationResult.Changed<Uri> = ShareMutationResult.Changed(
        ShareSnapshot(
            version = ShareSetVersion(Math.addExact(current.version.value, 1)),
            shares = shares,
        ),
    )

    private fun rejected(
        snapshot: ShareSnapshot<Uri>,
        reason: ShareMutationRejection,
    ): ShareMutationResult.Rejected<Uri> = ShareMutationResult.Rejected(snapshot, reason)

    private fun decodeSnapshot(preferences: Preferences): ShareSnapshot<Uri> {
        val state = ShareStateCodec.decode(preferences[STATE_KEY]) ?: return EMPTY_SNAPSHOT
        return runCatching {
            ShareSnapshot(
                version = ShareSetVersion(state.version),
                shares = state.shares.map { stored ->
                    val uri = stored.uri.toUri()
                    require(uri.scheme == ContentResolver.SCHEME_CONTENT)
                    require(!uri.authority.isNullOrBlank())
                    require(uri.query == null && uri.fragment == null)
                    require(stored.uri.length <= MAX_URI_LENGTH)
                    require(DocumentsContract.isTreeUri(uri))
                    SharedFolder(
                        id = ShareId(stored.id),
                        label = stored.label,
                        enabled = stored.enabled,
                        storageRoot = uri,
                    )
                },
            )
        }.getOrDefault(EMPTY_SNAPSHOT)
    }

    private fun encodeSnapshot(snapshot: ShareSnapshot<Uri>): String = ShareStateCodec.encode(
        StoredShareState(
            version = snapshot.version.value,
            shares = snapshot.shares.map { share ->
                StoredShare(
                    id = share.id.value,
                    label = share.label,
                    enabled = share.enabled,
                    uri = share.storageRoot.toString(),
                )
            },
        ),
    )

    private companion object {
        val STATE_KEY = stringPreferencesKey("share_state")
        val EMPTY_SNAPSHOT = ShareSnapshot<Uri>(
            version = ShareSetVersion(0),
            shares = emptyList(),
        )
        const val MAX_CONFIGURED_SHARES = 64
        const val MAX_ID_GENERATION_ATTEMPTS = 8
        const val MAX_URI_LENGTH = 16_384
    }
}
