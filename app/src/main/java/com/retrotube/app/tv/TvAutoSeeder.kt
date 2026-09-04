package com.retrotube.app.tv

import com.retrotube.app.collections.CollectionRepository
import com.retrotube.app.library.LibraryRepository
import com.retrotube.app.network.NetworkShareRepository
import java.util.UUID

/**
 * Builds a starting set of channel definitions for the "Auto-Generate" path in
 * TV Mode's first-launch setup -- one channel per top-level library folder,
 * one per collection, and one pooling every connected SMB share, each channel
 * holding a single whole-folder/collection source rather than a hand-picked
 * list. This is a simpler split than the old fixed Shows/Movies/Wildcard logic
 * (dropped along with it, since "shuffle across everything" doesn't map onto
 * an ordered list of sources) -- it's just a reasonable starting point the
 * user is expected to edit from here, not a final answer.
 */
object TvAutoSeeder {

    fun buildSeedChannels(
        libraryRepository: LibraryRepository,
        collectionRepository: CollectionRepository,
        networkShareRepository: NetworkShareRepository,
    ): List<TvChannelDefinition> {
        val folderChannels = libraryRepository.getRootDocuments().map { root ->
            TvChannelDefinition(
                id = UUID.randomUUID().toString(),
                name = root.name,
                sources = listOf(TvChannelSource.LocalFolder(root.document.uri.toString(), root.name)),
            )
        }

        val collectionChannels = collectionRepository.getAll().map { collection ->
            TvChannelDefinition(
                id = UUID.randomUUID().toString(),
                name = collection.name,
                sources = listOf(TvChannelSource.Collection(collection.id, collection.name)),
            )
        }

        val shares = networkShareRepository.getAll()
        val networkChannel = if (shares.isEmpty()) {
            emptyList()
        } else {
            listOf(
                TvChannelDefinition(
                    id = UUID.randomUUID().toString(),
                    name = "Network",
                    sources = shares.map { share -> TvChannelSource.SmbFolder(share.id, "", share.displayName) },
                ),
            )
        }

        return folderChannels + collectionChannels + networkChannel
    }
}
