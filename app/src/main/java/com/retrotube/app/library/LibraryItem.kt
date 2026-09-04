package com.retrotube.app.library

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.retrotube.app.network.SmbUri

sealed class LibraryItem {
    data class FolderItem(val document: DocumentFile, val name: String) : LibraryItem()

    data class VideoItem(
        val document: DocumentFile,
        val name: String,
        /** Immediate containing folder's name, if known -- shown on the card so a
         *  generic filename like "Episode 1" still says where it came from. */
        val locationHint: String = "",
    ) : LibraryItem() {
        /** [name] is the raw filename; this cleans it up for display until Tier 2
         *  adds a real editable title -- strip the extension and swap separator
         *  characters for spaces rather than showing `neon_drift_ep03.mkv` verbatim. */
        val displayName: String
            get() = name
                .substringBeforeLast('.')
                .replace('_', ' ')
                .replace('.', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
    }

    /** A single full-width row embedding the horizontal Continue Watching rail --
     *  travels as a normal grid item so it scrolls away with everything else,
     *  rather than sitting pinned above the grid. */
    data class ContinueWatchingRail(val videos: List<VideoItem>) : LibraryItem()

    /** A user-curated shelf that can span folders -- a card like [FolderItem], but
     *  opening it shows a hand-picked, manually-orderable set of videos instead of
     *  whatever's really on disk in one place. */
    data class CollectionItem(val id: String, val name: String, val videoCount: Int) : LibraryItem()

    /** A full-width label separating the root grid into Collections vs. Library --
     *  only ever included alongside at least one item of that kind, never on its own. */
    data class SectionHeader(val title: String) : LibraryItem()

    /** A folder inside (or the root of) a connected SMB share. An empty [relativePath]
     *  means this card represents the share itself, shown at the library root. */
    data class SmbFolderItem(val shareId: String, val relativePath: String, val name: String) : LibraryItem()

    /** A video file inside a connected SMB share -- carries no DocumentFile, since SAF
     *  has no part in reaching it; [uri] is what every existing per-video repository
     *  (progress, settings, custom metadata, collections) keys off of instead. */
    data class SmbVideoItem(
        val shareId: String,
        val relativePath: String,
        val name: String,
        val locationHint: String = "",
    ) : LibraryItem() {
        val uri: Uri get() = SmbUri.build(shareId, relativePath)

        val displayName: String
            get() = name
                .substringBeforeLast('.')
                .replace('_', ' ')
                .replace('.', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
