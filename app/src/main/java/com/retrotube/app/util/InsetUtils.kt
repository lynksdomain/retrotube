package com.retrotube.app.util

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * targetSdk 35+ makes edge-to-edge mandatory for every window regardless of
 * what an activity requests -- before that, the system reserved space for the
 * status bar/cutout and the 3-button/gesture nav bar automatically, so none
 * of this app's toolbars ever needed to think about it. Now every screen's
 * own top/bottom chrome has to pad itself away from those system bars, or its
 * icons render (and sit) underneath them, unreachable by touch.
 *
 * On a pre-35 device where edge-to-edge isn't forced, the system already
 * consumes these insets before they reach here, so the value read below is
 * zero and this is a no-op -- safe to apply everywhere, not just on newer
 * devices.
 */
fun View.applyTopBarInset() {
    val initialTop = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
        view.updatePadding(top = initialTop + top)
        insets
    }
}

fun View.applyBottomBarInset() {
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        view.updatePadding(bottom = initialBottom + bottom)
        insets
    }
}
