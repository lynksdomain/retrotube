package com.retrotube.app

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.retrotube.app.util.applyBottomBarInset

/**
 * Wires the shared bottom_nav_bar.xml include into whichever of the three tab
 * roots is currently on screen (Library / TV / Sources -- see the Phase 3
 * migration plan). Each tab root is its own Activity rather than a Fragment
 * (the simpler of the two options the migration plan allowed for), so
 * switching tabs is a same-level `startActivity` + `finish()` -- no back-stack
 * builds up between tabs, matching how a real tab bar feels, and every screen
 * already pushed on top of a tab (folder browsing, a video's settings, TV
 * channel detail, ...) is untouched by this.
 */
object MainTabBar {

    enum class Tab { LIBRARY, TV, SOURCES }

    fun setup(activity: Activity, root: View, current: Tab) {
        val libraryTab = root.findViewById<View>(R.id.navLibraryTab)
        val tvTab = root.findViewById<View>(R.id.navTvTab)
        val sourcesTab = root.findViewById<View>(R.id.navSourcesTab)
        root.findViewById<View>(R.id.bottomNavBar).applyBottomBarInset()

        highlight(root, R.id.navLibraryIcon, R.id.navLibraryLabel, current == Tab.LIBRARY)
        highlight(root, R.id.navTvIcon, R.id.navTvLabel, current == Tab.TV)
        highlight(root, R.id.navSourcesIcon, R.id.navSourcesLabel, current == Tab.SOURCES)

        libraryTab.setOnClickListener { switchTo(activity, current, Tab.LIBRARY, LibraryActivity::class.java) }
        tvTab.setOnClickListener { switchTo(activity, current, Tab.TV, TvChannelEditorActivity::class.java) }
        sourcesTab.setOnClickListener { switchTo(activity, current, Tab.SOURCES, SourcesActivity::class.java) }
    }

    private fun switchTo(activity: Activity, current: Tab, target: Tab, destination: Class<*>) {
        if (current == target) return
        activity.startActivity(
            Intent(activity, destination).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
        )
        activity.finish()
    }

    private fun highlight(root: View, iconId: Int, labelId: Int, selected: Boolean) {
        val color = ContextCompat.getColor(root.context, if (selected) R.color.retro_teal else R.color.retro_text_muted)
        root.findViewById<ImageView>(iconId).setColorFilter(color)
        root.findViewById<TextView>(labelId).setTextColor(color)
    }
}
