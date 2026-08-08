package com.neondrive.launcher.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Сканер аудиофайлов на самом ГУ: внутренняя память, SD-карта, USB-накопитель. */
object MediaLibrary {

    private const val MIN_DURATION_MS = 20_000L

    suspend fun scan(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>(512)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val order = "${MediaStore.Audio.Media.ARTIST} COLLATE NOCASE ASC, " +
            "${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE ASC, " +
            "${MediaStore.Audio.Media.TRACK} ASC"

        // Внешние тома: на ГУ это обычно external + sdcard + usb
        val volumes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            runCatching { MediaStore.getExternalVolumeNames(context).toList() }
                .getOrDefault(listOf("external"))
        } else listOf("external")

        for (vol in volumes.ifEmpty { listOf("external") }) {
            val collection = runCatching { MediaStore.Audio.Media.getContentUri(vol) }
                .getOrDefault(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

            runCatching {
                context.contentResolver.query(collection, projection, selection, null, order)
            }.getOrNull()?.use { c ->
                val idIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (c.moveToNext()) {
                    val dur = c.getLong(durIx)
                    if (dur < MIN_DURATION_MS) continue          // отсекаем рингтоны и «пшики»
                    val id = c.getLong(idIx)
                    val albumId = c.getLong(albumIdIx)
                    out += Track(
                        id = id,
                        title = c.getString(titleIx) ?: "Без названия",
                        artist = c.getString(artistIx)?.takeIf { it != "<unknown>" } ?: "Неизвестный исполнитель",
                        album = c.getString(albumIx).orEmpty(),
                        durationMs = dur,
                        uri = ContentUris.withAppendedId(collection, id),
                        albumArtUri = ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"), albumId
                        )
                    )
                }
            }
        }

        // Один и тот же файл может попасться на нескольких томах
        out.distinctBy { it.uri.toString() }
    }
}
