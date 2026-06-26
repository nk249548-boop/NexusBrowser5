package com.nexus.browser

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * ══════════════════════════════════════════════════════════════════
 * MediaMuxerHelper — Native Android Video+Audio Muxer (Zero FFmpeg)
 * ══════════════════════════════════════════════════════════════════
 *
 * WHY NO FFMPEG?
 * FFmpeg binary ~8MB adds unnecessary bloat. Android ke native
 * MediaExtractor + MediaMuxer APIs same kaam karte hain bina
 * kisi extra library ke — ye APIs Android 4.3+ (API 18) se available hain.
 *
 * USE CASE:
 * YouTube-style streams alag video-only aur audio-only files serve karte hain.
 * Download ke baad inhe ek playable .mp4 file mein merge karna padta hai.
 *
 * HOW IT WORKS:
 *
 *   [video.mp4]          [audio.m4a]
 *       │                    │
 *       ▼                    ▼
 *   MediaExtractor       MediaExtractor
 *   (reads encoded       (reads encoded
 *    video frames)        audio frames)
 *       │                    │
 *       └────────┬───────────┘
 *                ▼
 *           MediaMuxer
 *       (writes both tracks
 *        into one .mp4 file)
 *                │
 *                ▼
 *         output.mp4 ✅
 *
 * IMPORTANT: Ye tool REMUXING karta hai, re-encoding NAHI.
 * No quality loss — frames copy hote hain as-is, sirf container change hota hai.
 *
 * LIMITATIONS:
 * - Input files already-encoded hone chahiye (mp4/m4a/aac/h264)
 * - MediaMuxer MPEG-4 container support karta hai → .mp4 output
 * - Live streams (HLS/DASH) ke liye pehle .ts chunks download karo,
 *   phir merge karo, tab ye function call karo
 */
object MediaMuxerHelper {

    private const val TAG = "MediaMuxerHelper"

    // Buffer size: 1MB — large enough for most video frames
    private const val BUFFER_SIZE = 1 * 1024 * 1024  // 1 MB

    /**
     * Video-only file + Audio-only file ko ek .mp4 mein mux karta hai.
     *
     * @param videoPath   Input: video-only file path (e.g. video.mp4 without audio)
     * @param audioPath   Input: audio-only file path (e.g. audio.m4a or audio.aac)
     * @param outputPath  Output: merged .mp4 file path
     * @return            true = success, false = failure
     *
     * Coroutine scope mein call karo:
     *   lifecycleScope.launch {
     *       val ok = MediaMuxerHelper.muxVideoAndAudio(videoPath, audioPath, outputPath)
     *       if (ok) { // show success } else { // show error }
     *   }
     */
    suspend fun muxVideoAndAudio(
        videoPath: String,
        audioPath: String,
        outputPath: String
    ): Boolean = withContext(Dispatchers.IO) {

        Log.d(TAG, "🎬 Starting mux: video=$videoPath, audio=$audioPath → $outputPath")

        // ── Resources — null check ke liye nullable, finally mein release ──
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            // ── Step 1: MediaExtractor setup ─────────────────────────────
            //
            // MediaExtractor ek MP4/AAC/M4A file ko parse karta hai
            // aur individual tracks (video/audio) expose karta hai.
            // Hum sirf READ karte hain — koi decoding nahi.

            videoExtractor = MediaExtractor().apply { setDataSource(videoPath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioPath) }

            // ── Step 2: Track index dhundo ────────────────────────────────
            //
            // Ek MP4 file mein multiple tracks ho sakte hain.
            // Hume video track ka index chahiye (video file se)
            // aur audio track ka index (audio file se).

            val videoTrackIndex = findTrackIndex(videoExtractor, "video/")
            val audioTrackIndex = findTrackIndex(audioExtractor, "audio/")

            // Validation
            if (videoTrackIndex < 0) {
                Log.e(TAG, "❌ No video track found in: $videoPath")
                return@withContext false
            }
            if (audioTrackIndex < 0) {
                Log.e(TAG, "❌ No audio track found in: $audioPath")
                return@withContext false
            }

            Log.d(TAG, "📹 Video track index: $videoTrackIndex")
            Log.d(TAG, "🔊 Audio track index: $audioTrackIndex")

            // ── Step 3: MediaMuxer initialize karo ───────────────────────
            //
            // MediaMuxer ek naya .mp4 container likhta hai.
            // OUTPUT_FORMAT_MPEG_4 → standard .mp4 container
            // (WEBM, 3GP bhi supported hain, lekin .mp4 sabse compatible hai)

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // ── Step 4: Tracks add karo muxer mein ──────────────────────
            //
            // MediaFormat track ki metadata define karta hai —
            // codec type, resolution, sample rate, etc.
            // Hum same format reuse karte hain (no re-encoding).

            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)

            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)

            Log.d(TAG, "✅ Tracks added to muxer: video=$muxerVideoTrack, audio=$muxerAudioTrack")

            // ── Step 5: Muxer start karo ──────────────────────────────────
            // addTrack() ke baad hi start() call kar sakte hain
            muxer.start()

            // ── Step 6: ByteBuffer setup ──────────────────────────────────
            //
            // Buffer mein encoded frames/samples read honge.
            // 1MB kaafi hai most HD video ke liye.
            // 4K ke liye 2MB consider karo agar zaroorat ho.
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            // ── Step 7: Video frames write karo ─────────────────────────
            //
            // seekTo(0) → pehle frame se shuru karo
            // SEEK_TO_CLOSEST_SYNC → nearest keyframe pe jump karo
            videoExtractor.selectTrack(videoTrackIndex)
            videoExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            writeTrack(
                extractor  = videoExtractor,
                muxer      = muxer,
                muxerTrack = muxerVideoTrack,
                buffer     = buffer,
                bufferInfo = bufferInfo,
                label      = "Video"
            )

            // ── Step 8: Audio frames write karo ─────────────────────────
            audioExtractor.selectTrack(audioTrackIndex)
            audioExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            writeTrack(
                extractor  = audioExtractor,
                muxer      = muxer,
                muxerTrack = muxerAudioTrack,
                buffer     = buffer,
                bufferInfo = bufferInfo,
                label      = "Audio"
            )

            Log.d(TAG, "✅ Muxing complete! Output: $outputPath")

            // ── Step 9: Output file verify karo ─────────────────────────
            val outFile = File(outputPath)
            if (outFile.exists() && outFile.length() > 0) {
                Log.d(TAG, "📁 Output file size: ${outFile.length()} bytes")
                return@withContext true
            } else {
                Log.e(TAG, "❌ Output file missing or empty!")
                return@withContext false
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Muxing failed: ${e.message}", e)
            // Failed output file delete karo (corrupt ho sakta hai)
            try { File(outputPath).delete() } catch (_: Exception) {}
            return@withContext false

        } finally {
            // ── Cleanup — resources hamesha release karo ─────────────────
            //
            // try-finally ensure karta hai ke even exception pe resources release hon.
            // Release order matter karta hai: muxer stop → release, extractors release.
            try { muxer?.stop() }   catch (e: Exception) { Log.w(TAG, "muxer stop: ${e.message}") }
            try { muxer?.release() } catch (e: Exception) { Log.w(TAG, "muxer release: ${e.message}") }
            try { videoExtractor?.release() } catch (e: Exception) { Log.w(TAG, "vExtractor release: ${e.message}") }
            try { audioExtractor?.release() } catch (e: Exception) { Log.w(TAG, "aExtractor release: ${e.message}") }
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    /**
     * MIME type prefix se track index dhundta hai.
     *
     * @param extractor MediaExtractor instance
     * @param mimePrefix e.g. "video/" ya "audio/"
     * @return track index, ya -1 agar nahi mila
     *
     * WHY PREFIX?: "video/" se "video/avc", "video/hevc", "video/vp9" sab match ho jaate hain.
     * Specific codec pe depend nahi karna padta.
     */
    private fun findTrackIndex(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime   = format.getString("mime") ?: continue
            if (mime.startsWith(mimePrefix)) {
                return i
            }
        }
        return -1
    }

    /**
     * Ek track ke saare encoded samples muxer mein likhta hai.
     *
     * HOW THE LOOP WORKS:
     *
     *   readSampleData(buffer, 0) → buffer mein encoded frame/sample load karo
     *   sampleTime                → presentation timestamp (PTS) in microseconds
     *   sampleFlags               → sync frame hai ya nahi (keyframe flag)
     *   writeSampleData(...)      → muxer ko dedo
     *   advance()                 → next sample pe move karo
     *
     * TERMINATION: readSampleData() -1 return karta hai jab track khatam ho jaata hai.
     *
     * PERFORMANCE: Buffer reuse kiya hai — koi naya allocation nahi loop ke andar.
     * Ye important hai large video files ke liye (GC pressure avoid karta hai).
     *
     * @param label Logging ke liye "Video" ya "Audio"
     */
    private fun writeTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerTrack: Int,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        label: String
    ) {
        var frameCount = 0

        while (true) {
            buffer.clear()  // Buffer reuse — koi naya ByteBuffer allocate nahi

            // readSampleData: current sample ko buffer mein load karo
            // Returns: bytes read, ya -1 agar track khatam
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                // Track khatam ho gayi — loop se bahar niklo
                Log.d(TAG, "$label: wrote $frameCount samples")
                break
            }

            // BufferInfo fill karo — muxer ko metadata chahiye
            bufferInfo.apply {
                offset          = 0
                size            = sampleSize
                presentationTimeUs = extractor.sampleTime   // PTS in microseconds
                flags           = extractor.sampleFlags     // BUFFER_FLAG_SYNC_FRAME etc.
            }

            // Muxer mein write karo
            muxer.writeSampleData(muxerTrack, buffer, bufferInfo)

            // Next sample pe move karo
            extractor.advance()
            frameCount++
        }
    }
}
