package com.neondrive.launcher.media

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Сканер аудиофайлов на самом ГУ: внутренняя память, SD-карта, USB-накопитель.
 *
 * Два независимых механизма, один поверх другого — на разных прошивках головных
 * устройств съёмные накопители видны по-разному:
 *
 *  1. MediaStore по всем томам ([MediaStore.getExternalVolumeNames]) — основной путь.
 *     На большинстве автомобильных прошивок (это их стандартная фича — проигрывать
 *     музыку с флешки) SD-карта и USB-накопитель монтируются как обычные тома и
 *     автоматически индексируются системой, появляясь здесь без какого-либо участия
 *     пользователя.
 *  2. SAF-папки, добавленные вручную в настройках ([extraFolders]) — резерв на случай,
 *     если конкретная прошивка НЕ индексирует накопитель в MediaStore (так бывает —
 *     часть USB-флешек Android видит только как «отдельное хранилище», доступное
 *     через системный выбор папки, а не как классический том). Тогда пользователь
 *     один раз указывает папку через системный диалог, и она пересканируется здесь
 *     напрямую через Storage Access Framework — легальный, не требующий особых
 *     разрешений способ читать содержимое чужого тома при scoped storage.
 */
object MediaLibrary {

    private const val MIN_DURATION_MS = 20_000L
    private const val MAX_SAF_FILES = 4000
    private const val MAX_SAF_DEPTH = 10

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "ogg", "oga", "m4a", "aac", "wma", "opus"
    )

    suspend fun scan(context: Context, extraFolders: List<String> = emptyList()): List<Track> =
        withContext(Dispatchers.IO) {
            val fromStore = scanMediaStore(context)
            val fromSaf = if (extraFolders.isEmpty()) emptyList() else scanSaf(context, extraFolders)
            // Один и тот же файл может попасться на нескольких томах или совпасть
            // между MediaStore и вручную добавленной папкой.
            (fromStore + fromSaf).distinctBy { it.uri.toString() }
        }

    private fun scanMediaStore(context: Context): List<Track> {
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

        return out
    }

    /** Обход папок, добавленных вручную через SAF (см. [SettingsScreen]/MusicTab). */
    private fun scanSaf(context: Context, folders: List<String>): List<Track> {
        val out = ArrayList<Track>(256)
        for (folderUri in folders) {
            val uri = runCatching { Uri.parse(folderUri) }.getOrNull() ?: continue
            val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: continue
            if (!root.exists() || !root.isDirectory) continue
            walkSaf(context, root, out, depth = 0)
            if (out.size >= MAX_SAF_FILES) break
        }
        return out
    }

    private fun walkSaf(context: Context, dir: DocumentFile, out: MutableList<Track>, depth: Int) {
        if (depth > MAX_SAF_DEPTH || out.size >= MAX_SAF_FILES) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (out.size >= MAX_SAF_FILES) return
            if (child.isDirectory) {
                walkSaf(context, child, out, depth + 1)
                continue
            }
            val name = child.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in AUDIO_EXTENSIONS) continue

            val meta = readSafMetadata(context, child.uri)
            val duration = meta?.first ?: 0L
            // Если метаданные вообще не читаются — не выкидываем файл: пользователь
            // сам добавил эту папку целенаправленно, лучше показать трек без тегов,
            // чем молча его потерять. Если метаданные прочитались — фильтруем
            // короткие «пшики» так же, как и в основном сканере.
            if (meta != null && duration < MIN_DURATION_MS) continue

            out += Track(
                id = -(child.uri.toString().hashCode().toLong() and 0x7FFFFFFFL),
                title = meta?.second?.takeIf { it.isNotBlank() }
                    ?: name.substringBeforeLast('.').ifBlank { name },
                artist = meta?.third?.takeIf { it.isNotBlank() } ?: "Неизвестный исполнитель",
                album = meta?.fourth.orEmpty(),
                durationMs = duration,
                uri = child.uri,
                albumArtUri = null
            )
        }
    }

    /** title/artist/album/duration через MediaMetadataRetriever, либо null, если файл не читается. */
    private fun readSafMetadata(context: Context, uri: Uri): Quad<Long, String, String, String>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            Quad(duration, title, artist, album)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
