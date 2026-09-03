package com.retrotube.app.library

import androidx.documentfile.provider.DocumentFile

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
}
