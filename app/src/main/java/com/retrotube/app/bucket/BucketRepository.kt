package com.retrotube.app.bucket

import android.content.Context
import java.util.UUID

/**
 * The "shelf" model that shows/movies get tagged into and then get
 * automatically populated/sorted from scraped TMDB metadata -- this is what
 * the Library tab is: buckets referencing sources, showing rich metadata.
 * TV and Movies are seeded once, always present, and can never be deleted --
 * a brand-new install has both ready to tag folders into with zero setup.
 * Everything else is a user-created custom bucket via [create].
 */
class BucketRepository(context: Context) {

    private val prefs = context.getSharedPreferences("retrotube_buckets", Context.MODE_PRIVATE)

    fun getAll(): List<Bucket> {
        ensureSeeded()
        return idsInOrder().mapNotNull { get(it) }
    }

    fun get(id: String): Bucket? {
        ensureSeeded()
        val name = prefs.getString(nameKey(id), null) ?: return null
        return Bucket(id, name, isDeletable = id != BUCKET_TV && id != BUCKET_MOVIES)
    }

    fun create(name: String): Bucket {
        val id = UUID.randomUUID().toString()
        val ids = idsInOrder() + id
        prefs.edit()
            .putString(idsKey(), ids.joinToString(","))
            .putString(nameKey(id), name)
            .apply()
        return Bucket(id, name, isDeletable = true)
    }

    /** Renaming TV/Movies isn't blocked here -- the spec only forbids deleting
     *  them, not renaming; callers just never expose an Edit action for them. */
    fun rename(id: String, name: String) {
        if (prefs.getString(nameKey(id), null) == null) return
        prefs.edit().putString(nameKey(id), name).apply()
    }

    /** TV and Movies can never be deleted -- silently ignored rather than
     *  erroring, since no caller should be offering this action for them anyway. */
    fun delete(id: String) {
        if (id == BUCKET_TV || id == BUCKET_MOVIES) return
        val ids = idsInOrder().filterNot { it == id }
        prefs.edit()
            .putString(idsKey(), ids.joinToString(","))
            .remove(nameKey(id))
            .apply()
    }

    /** Runs on every read rather than once at install time -- cheap (two
     *  `contains` checks), and self-heals an install whose prefs got cleared
     *  or that predates this repository existing at all. */
    private fun ensureSeeded() {
        val hasTv = prefs.contains(nameKey(BUCKET_TV))
        val hasMovies = prefs.contains(nameKey(BUCKET_MOVIES))
        if (hasTv && hasMovies) return

        val ids = idsInOrder().toMutableList()
        val editor = prefs.edit()
        if (!hasTv) {
            ids.add(0, BUCKET_TV)
            editor.putString(nameKey(BUCKET_TV), "TV")
        }
        if (!hasMovies) {
            val insertAt = ids.indexOf(BUCKET_TV).let { if (it >= 0) it + 1 else 0 }
            ids.add(insertAt, BUCKET_MOVIES)
            editor.putString(nameKey(BUCKET_MOVIES), "Movies")
        }
        editor.putString(idsKey(), ids.distinct().joinToString(",")).apply()
    }

    private fun idsInOrder(): List<String> =
        prefs.getString(idsKey(), null)?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()

    private fun idsKey() = "bucket_ids"
    private fun nameKey(id: String) = "bucket_${id}_name"

    companion object {
        const val BUCKET_TV = "tv"
        const val BUCKET_MOVIES = "movies"
    }
}
