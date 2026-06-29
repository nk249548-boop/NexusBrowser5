package com.nexus.browser.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * ScopedStorageHelper
 *
 * Centralises all Android storage interactions for Phase 2B:
 *  • Android 10+ (API 29+): MediaStore insertions for public Downloads/Movies/Music.
 *  • Android 9 (API 28) and below: direct File I/O to public external dirs.
 *  • Rename: MediaStore update (API 29+) / File.renameTo (API 28-).
 *  • Delete: MediaStore delete (API 29+) / File.delete (API 28-).
 *  • Share:  FileProvider URI for private files; MediaStore content:// URI for public ones.
 *  • Open:   standard ACTION_VIEW Intent with correct MIME type.
 *
 * Play Store compliance:
 *  - No READ_ALL_FILES_ACCESS (MANAGE_EXTERNAL_STORAGE).
 *  - Reads own app's MediaStore insertions with no extra permission on API 29+.
 *  - On API 28- uses READ_EXTERNAL_STORAGE (maxSdkVersion=28 in manifest).
 *  - On API 29-32 uses READ_EXTERNAL_STORAGE (maxSdkVersion=32 in manifest).
 *  - On API 33+ uses READ_MEDIA_IMAGES / READ_MEDIA_VIDEO / READ_MEDIA_AUDIO.
 */
object ScopedStorageHelper {

    private const val TAG = "ScopedStorageHelper"
    private const val AUTHORITY = "com.nexus.browser.fileprovider"
    private const val NEXUS_SUBDIR = "NexusBrowser"

    // ──────────────────────────────────────────────────────────────────────────
    // Write helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Copy [sourceFile] to the appropriate public directory via MediaStore (API 29+)
     * or direct File I/O (API 28-).
     *
     * Returns the content:// URI on API 29+, or a file:// URI wrapped via FileProvider
     * on older devices. Returns null on failure.
     *
     * @param mimeType   MIME type of the file (e.g. "video/mp4").
     * @param targetDir  One of Environment.DIRECTORY_DOWNLOADS / DIRECTORY_MOVIES /
     *                   DIRECTORY_MUSIC / DIRECTORY_PICTURES.
     */
    suspend fun publishToPublicStorage(
        context:    Context,
        sourceFile: File,
        displayName: String,
        mimeType:   String,
        targetDir:  String = Environment.DIRECTORY_DOWNLOADS
    ): Uri? = withContext(Dispatchers.IO) {
        return@withContext if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishViaMediaStore(context, sourceFile, displayName, mimeType, targetDir)
        } else {
            publishLegacy(context, sourceFile, displayName, targetDir)
        }
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(
        context:    Context,
        sourceFile: File,
        displayName: String,
        targetDir:  String
    ): Uri? {
        return try {
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(targetDir),
                NEXUS_SUBDIR
            )
            publicDir.mkdirs()
            val dest = File(publicDir, displayName)
            sourceFile.copyTo(dest, overwrite = true)
            FileProvider.getUriForFile(context, AUTHORITY, dest)
        } catch (e: Exception) {
            Log.e(TAG, "publishLegacy failed", e)
            null
        }
    }

    private fun publishViaMediaStore(
        context:    Context,
        sourceFile: File,
        displayName: String,
        mimeType:   String,
        targetDir:  String
    ): Uri? {
        val resolver = context.contentResolver
        val collection = resolveMediaStoreCollection(mimeType, targetDir)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$targetDir/$NEXUS_SUBDIR/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val itemUri = resolver.insert(collection, values) ?: run {
            Log.e(TAG, "MediaStore insert returned null for $displayName")
            return null
        }

        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { it.copyTo(out) }
            }
            val finalize = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(itemUri, finalize, null, null)
            itemUri
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore write failed", e)
            resolver.delete(itemUri, null, null)
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Rename
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Rename a file identified by its [savePath] absolute path.
     * On API 29+: updates MediaStore DISPLAY_NAME.
     * On API 28-: File.renameTo.
     *
     * Returns the new absolute path on success, null on failure.
     */
    suspend fun renameFile(
        context:     Context,
        savePath:    String,
        newName:     String
    ): String? = withContext(Dispatchers.IO) {
        if (savePath.isBlank() || newName.isBlank()) return@withContext null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            renameViaMediaStore(context, savePath, newName)
        } else {
            renameLegacy(savePath, newName)
        }
    }

    private fun renameViaMediaStore(context: Context, savePath: String, newName: String): String? {
        val uri = findMediaStoreUri(context, savePath) ?: return renameLegacy(savePath, newName)
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
            }
            val updated = context.contentResolver.update(uri, values, null, null)
            if (updated > 0) {
                // Re-query the path after rename
                queryPath(context.contentResolver, uri) ?: run {
                    // Derive new path from old path's parent
                    File(savePath).parent?.let { "$it/$newName" }
                }
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "renameViaMediaStore failed", e)
            renameLegacy(savePath, newName)
        }
    }

    @Suppress("DEPRECATION")
    private fun renameLegacy(savePath: String, newName: String): String? {
        val oldFile = File(savePath)
        if (!oldFile.exists()) return null
        val newFile = File(oldFile.parent, newName)
        return if (oldFile.renameTo(newFile)) newFile.absolutePath else null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Delete
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Delete the physical file from storage.
     * On API 29+: delete via MediaStore (handles ownership automatically for
     * files inserted by this app).
     * On API 28-: File.delete().
     *
     * Returns true if deletion was performed (or file was already absent).
     */
    suspend fun deleteFile(context: Context, savePath: String): Boolean =
        withContext(Dispatchers.IO) {
            if (savePath.isBlank()) return@withContext true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                deleteViaMediaStore(context, savePath)
            } else {
                deleteLegacy(savePath)
            }
        }

    private fun deleteViaMediaStore(context: Context, savePath: String): Boolean {
        val uri = findMediaStoreUri(context, savePath)
        return if (uri != null) {
            try {
                context.contentResolver.delete(uri, null, null) >= 0
            } catch (e: Exception) {
                Log.e(TAG, "MediaStore delete failed, falling back", e)
                deleteLegacy(savePath)
            }
        } else {
            // File may not be in MediaStore — fall back to direct delete
            deleteLegacy(savePath)
        }
    }

    @Suppress("DEPRECATION")
    private fun deleteLegacy(savePath: String): Boolean {
        val f = File(savePath)
        return !f.exists() || f.delete()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Share
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Build a share Intent for [savePath].
     * Prefers a content:// URI from MediaStore (API 29+) or FileProvider (API 28-).
     */
    fun buildShareIntent(context: Context, savePath: String, mimeType: String): Intent? {
        val uri = buildContentUri(context, savePath) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Open
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Build an ACTION_VIEW Intent to open [savePath] with an appropriate viewer.
     * Returns null if no URI can be resolved or the file does not exist.
     */
    fun buildOpenIntent(context: Context, savePath: String, mimeType: String): Intent? {
        val uri = buildContentUri(context, savePath) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Returns a content:// URI for a media file, for use with the internal player.
     * Equivalent to [buildContentUri] but with a clearer name for media-player callers.
     */
    fun getContentUri(context: Context, savePath: String, @Suppress("UNUSED_PARAMETER") mimeType: String): Uri? =
        buildContentUri(context, savePath)

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────

    /** Returns a content:// URI suitable for sharing/opening. */
    fun buildContentUri(context: Context, savePath: String): Uri? {
        if (savePath.isBlank()) return null
        // Try MediaStore first (works for publicly-inserted files on API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val msUri = findMediaStoreUri(context, savePath)
            if (msUri != null) return msUri
        }
        // Fallback: FileProvider (works for files in paths declared in file_paths.xml)
        return try {
            val file = File(savePath)
            if (!file.exists()) return null
            FileProvider.getUriForFile(context, AUTHORITY, file)
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider URI failed for $savePath", e)
            null
        }
    }

    /** Query MediaStore for the content URI matching the absolute [path]. */
    fun findMediaStoreUri(context: Context, path: String): Uri? {
        if (path.isBlank()) return null
        val resolver = context.contentResolver
        val collections = listOf(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        )
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection  = "${MediaStore.MediaColumns.DATA} = ?"
        val selArgs    = arrayOf(path)

        for (collection in collections) {
            try {
                resolver.query(collection, projection, selection, selArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        return ContentUris.withAppendedId(collection, id)
                    }
                }
            } catch (_: Exception) { /* collection not accessible on this API level */ }
        }
        return null
    }

    /** Resolve the right MediaStore top-level collection based on MIME and targetDir. */
    private fun resolveMediaStoreCollection(mimeType: String, targetDir: String): Uri {
        return when {
            targetDir == Environment.DIRECTORY_MOVIES ||
            mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            targetDir == Environment.DIRECTORY_MUSIC  ||
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            targetDir == Environment.DIRECTORY_PICTURES ||
            mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else
                MediaStore.Files.getContentUri("external")
        }
    }

    /** Re-query an updated MediaStore URI to get the new DATA path. */
    private fun queryPath(resolver: ContentResolver, uri: Uri): String? {
        val proj = arrayOf(MediaStore.MediaColumns.DATA)
        return try {
            resolver.query(uri, proj, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null }
    }

    /**
     * Determine the preferred public target directory from a MIME type.
     * Used by the File Manager when re-saving or sharing a downloaded file.
     */
    fun targetDirForMime(mimeType: String): String = when {
        mimeType.startsWith("video/") -> Environment.DIRECTORY_MOVIES
        mimeType.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
        mimeType.startsWith("image/") -> Environment.DIRECTORY_PICTURES
        else                          -> Environment.DIRECTORY_DOWNLOADS
    }

    /** Returns true if [savePath] exists on disk (best-effort). */
    fun fileExists(savePath: String): Boolean =
        savePath.isNotBlank() && File(savePath).exists()
}
