package com.retrotube.app.util

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import com.retrotube.app.databinding.OverlayScrapingProgressBinding

/**
 * A persistent "actively scraping" indicator -- a spinner + status text
 * floating over whatever screen kicked off a TMDB match, replacing a toast
 * that could disappear well before the actual matching finished (a show with
 * a lot of episodes, or a bucket-wide/library-wide refresh, easily outlasts
 * a few-second toast). Callers report real progress as they loop over
 * folders/videos, so the text always reflects where the scrape actually is,
 * not just "started" and "done" with a black box in between.
 *
 * Inflated straight into the Activity's content root ([android.R.id.content])
 * rather than a spot in each screen's own layout -- every activity has one,
 * so this works everywhere without touching per-screen XML.
 */
class ScrapingProgressOverlay(private val activity: Activity) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val binding: OverlayScrapingProgressBinding by lazy {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val inflated = OverlayScrapingProgressBinding.inflate(LayoutInflater.from(activity), root, true)
        inflated
    }

    /** Shows the indicator with an initial message -- call once per scrape
     *  operation, before the first [update]. */
    fun show(text: String) {
        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            binding.scrapingProgressText.text = text
            binding.scrapingProgressRoot.alpha = 1f
            binding.scrapingProgressRoot.visibility = View.VISIBLE
        }
    }

    /** Updates the message in place -- e.g. "Matching 3 of 12…" -- without any
     *  show/hide flicker. Safe to call from a background thread. */
    fun update(text: String) {
        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            binding.scrapingProgressText.text = text
        }
    }

    /** Briefly shows [finalText] (default "Done"), then fades the indicator
     *  out -- the visible confirmation a plain toast gave, but only after
     *  scraping has actually finished rather than racing it. */
    fun finish(finalText: String) {
        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            binding.scrapingProgressText.text = finalText
            mainHandler.postDelayed({
                if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                val fadeOut = AlphaAnimation(1f, 0f).apply { duration = 250 }
                binding.scrapingProgressRoot.startAnimation(fadeOut)
                binding.scrapingProgressRoot.visibility = View.GONE
            }, FINAL_MESSAGE_DWELL_MS)
        }
    }

    private companion object {
        const val FINAL_MESSAGE_DWELL_MS = 900L
    }
}
