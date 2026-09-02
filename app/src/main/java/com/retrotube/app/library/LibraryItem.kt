package com.retrotube.app.library

import androidx.documentfile.provider.DocumentFile

sealed class LibraryItem {
    data class FolderItem(val document: DocumentFile, val name: String) : LibraryItem()
    data class VideoItem(val document: DocumentFile, val name: String) : LibraryItem()
}
