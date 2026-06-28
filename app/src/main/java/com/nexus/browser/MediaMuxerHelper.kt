package com.nexus.browser

/**
 * MediaMuxerHelper — stub only.
 *
 * Muxing separate video+audio tracks is not implemented; NexusBrowser only
 * downloads complete, already-muxed direct files (e.g. .mp4, .webm).
 * This object exists solely so any stale call-site import does not cause a
 * compile error during the transition.  All methods return failure/empty.
 */
object MediaMuxerHelper {

    /** Always returns false — muxing is not supported. */
    suspend fun muxVideoAndAudio(
        videoPath : String,
        audioPath : String,
        outputPath: String,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean = false
}
