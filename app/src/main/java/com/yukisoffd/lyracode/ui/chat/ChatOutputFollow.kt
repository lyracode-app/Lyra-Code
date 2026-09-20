package com.yukisoffd.lyracode

/** Value geometry, never the identity of a LazyList measurement result. */
internal data class ChatOutputGeometry(
    val itemCount: Int,
    val viewportStart: Int,
    val viewportEnd: Int,
    val viewportHeight: Int,
    val lastIndex: Int?,
    val lastOffset: Int?,
    val lastSize: Int?,
) {
    fun canFollow(anchorIndex: Int): Boolean =
        viewportHeight > 0 && viewportEnd > viewportStart && anchorIndex in 0 until itemCount
}

/** A no-progress scroll must not retrigger itself on each layout/scroll-state notification. */
internal class ChatOutputFollowGuard {
    private var attempted: ChatOutputGeometry? = null

    fun shouldScroll(geometry: ChatOutputGeometry, anchorIndex: Int, following: Boolean,
                     scrolling: Boolean, canScrollForward: Boolean): Boolean {
        if (!following) {
            attempted = null
            return false
        }
        if (scrolling || !canScrollForward || !geometry.canFollow(anchorIndex) || geometry == attempted) return false
        attempted = geometry
        return true
    }
}
