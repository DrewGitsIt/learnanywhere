package com.learnanywhere.car

import android.os.Bundle
import androidx.media3.session.legacy.MediaBrowserCompat
import androidx.media3.session.legacy.MediaBrowserServiceCompat
import com.learnanywhere.LearnAnywhereApp
import com.learnanywhere.ui.UiController

/**
 * Android Auto "media app" bridge for LearnAnywhere.
 *
 * MediaBrowserServiceCompat (in media3:media3-session) is what
 * Android Auto's media stack discovers. We expose three special items
 * (play-all / ask / figures) plus one per added document.
 *
 * Car transport buttons (play/pause/next/prev) are routed to the
 * phone-side UiController.player. "Ask" and "figures" items are
 * dispatched via onLoadItem (the car "play this item" callback).
 */
class LearnStudyMediaService : MediaBrowserServiceCompat() {

    private val ctl: () -> UiController = { LearnAnywhereApp.get().ui }

    /** Called when the browser connects (e.g. Android Auto headunit). */
    override fun onGetRoot(
        clientPackageName: String?,
        clientId: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        return BrowserRoot(ROOT_ID, null)
    }

    @JvmSuppressWildcards
    override fun onLoadChildren(
        parentId: String?,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val items: MutableList<MediaBrowserCompat.MediaItem> = ArrayList()
        items.add(makeItem("learnanywhere://play-all",  "Play audiobook (all docs)"))
        items.add(makeItem("learnanywhere://ask",       "Ask the agent (voice reply)"))
        items.add(makeItem("learnanywhere://figures",   "Figures (spoken)"))
        ctl().docs.value.forEach { d -> items.add(makeItem("learnanywhere://doc/" + d.id, d.title)) }
        result.sendResult(items)
    }

    /**
     * Called when the user selects an item in the car (or a media controller
     * triggers a specific item). We route by mediaId.
     */
    override fun onLoadItem(
        mediaId: String,
        result: Result<androidx.media3.session.legacy.MediaBrowserCompat.MediaItem>
    ) {
        when {
            mediaId == "learnanywhere://play-all" -> {
                post { ctl().listenOrPlayAll() }
            }
            mediaId == "learnanywhere://ask" -> {
                post { ctl().speakLatestReplyOrHint() }
            }
            mediaId == "learnanywhere://figures" -> {
                post { ctl().speakFiguresSummary() }
            }
            mediaId.startsWith("learnanywhere://doc/") -> {
                val id = mediaId.substringAfterLast('/')
                post { ctl().playSingle(id) }
            }
        }
        result.sendResult(makeItem(mediaId, mediaId))
    }

    private fun post(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private fun makeItem(mediaId: String, title: String): MediaBrowserCompat.MediaItem {
        val desc = MediaBrowserCompat.MediaItem(
            androidx.media3.session.legacy.MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title)
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE or MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
        return desc
    }

    companion object {
        private const val ROOT_ID = "1"
    }
}
