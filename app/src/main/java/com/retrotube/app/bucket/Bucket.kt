package com.retrotube.app.bucket

/** What kind of content a tagged folder holds -- decides which grid (Shows vs.
 *  Movies) a video surfaces in and which metadata-matching path applies to it. */
enum class ContentKind { SHOW, MOVIE }

data class Bucket(
    val id: String,
    val name: String,
    /** False only for the two seeded buckets (TV, Movies) -- every custom
     *  bucket the user creates can always be renamed and deleted. */
    val isDeletable: Boolean,
)
