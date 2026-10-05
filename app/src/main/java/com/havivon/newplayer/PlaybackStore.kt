package com.havivon.newplayer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Remembers the last playlist and position so playback can continue after the app is closed. */
class PlaybackStore(context: Context) {

    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    data class State(val uris: List<Uri>, val index: Int, val positionMs: Long)

    fun save(uris: List<Uri>, index: Int, positionMs: Long) {
        prefs.edit()
            .putString(KEY_URIS, uris.joinToString("\n"))
            .putInt(KEY_INDEX, index)
            .putLong(KEY_POSITION, positionMs)
            .apply()
    }

    fun load(): State? {
        val uris = prefs.getString(KEY_URIS, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?.map(Uri::parse)
            .orEmpty()
        if (uris.isEmpty()) return null
        val index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, uris.size - 1)
        return State(uris, index, prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L))
    }

    private companion object {
        const val KEY_URIS = "uris"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position"
    }
}

/** Builds a playable item whose title is the file name. */
fun audioItem(context: Context, uri: Uri): MediaItem {
    val title = displayName(context, uri)
    return MediaItem.Builder()
        .setUri(uri)
        .setMediaId(uri.toString())
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setDisplayTitle(title).build())
        .build()
}

private fun displayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
    val name = fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    return name.substringBeforeLast('.').ifBlank { name }
}
