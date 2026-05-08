package com.audiorouter.ui.stack

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.audiorouter.model.AudioChannel
import com.audiorouter.model.AudioStream

/**
 * Tracks an in-flight drag of an [AudioStream] across the window.
 *
 * Drop targets register their bounds via [registerTarget] from
 * onGloballyPositioned. While [draggingStream] is non-null, the overlay
 * renders the floating chip at [pointer], and the row whose Rect contains
 * the pointer becomes [hoveredChannel].
 */
@Stable
class DragController {
    var draggingStream by mutableStateOf<AudioStream?>(null)
        private set

    /** Pointer position in the root container's coordinate space. */
    var pointer by mutableStateOf(Offset.Zero)

    /** Pixel-space size of the source row (so the floater matches). */
    var sourceSize by mutableStateOf(Offset.Zero)
        private set

    /** Pointer offset within the source card at drag start, so the card
     * stays under the cursor where it was first pressed. */
    private var grabOffset = Offset.Zero

    private val targets = mutableMapOf<AudioChannel, Rect>()

    val hoveredChannel: AudioChannel?
        get() = targets.entries.firstOrNull { (_, r) -> r.contains(pointer) }?.key

    fun begin(stream: AudioStream, pointerInRoot: Offset, cardSize: Offset, grab: Offset) {
        draggingStream = stream
        pointer = pointerInRoot
        sourceSize = cardSize
        grabOffset = grab
    }

    fun update(pointerInRoot: Offset) {
        if (draggingStream != null) pointer = pointerInRoot
    }

    /**
     * Returns the channel under the pointer (if any), then clears state.
     * Caller is responsible for invoking the assignment callback.
     */
    fun end(): AudioChannel? {
        val target = hoveredChannel
        draggingStream = null
        pointer = Offset.Zero
        return target
    }

    fun cancel() {
        draggingStream = null
        pointer = Offset.Zero
    }

    fun registerTarget(channel: AudioChannel, rect: Rect) {
        targets[channel] = rect
    }

    fun unregisterTarget(channel: AudioChannel) {
        targets.remove(channel)
    }

    /** Top-left of the floating chip in root coords. */
    fun floaterTopLeft(): Offset = pointer - grabOffset
}
