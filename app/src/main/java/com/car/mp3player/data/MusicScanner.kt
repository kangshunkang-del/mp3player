package com.car.mp3player.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.car.mp3player.model.Song
import java.io.File

class MusicScanner(
    private val context: Context,
    private val customPaths: List<String> = emptyList(),
    private val treeUris: List<String> = emptyList()
) {
    private val audioExtensions = setOf("mp3", "flac", "m4a", "wav", "ogg", "aac")

    fun scan(): List<Song> {
        val merged = linkedMapOf<String, Song>()
        scanMediaStore().forEach { merged[it.path] = it }
        scanDirectories(resolvePaths()).forEach { song ->
            if (!merged.containsKey(song.path)) merged[song.path] = song
        }
        scanDocumentTrees().forEach { merged[it.path] = it }
        return merged.values.sortedBy { it.title.lowercase() }
    }

    private fun resolvePaths(): List<File> {
        val paths = mutableListOf<File>()
        customPaths.forEach { paths.add(File(it)) }
        if (paths.isEmpty()) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)?.let { paths.add(it) }
            paths.add(File("/storage/emulated/0/Music"))
            paths.add(File("/sdcard/Music"))
        }
        return paths.distinctBy { it.absolutePath }
    }

    private fun scanMediaStore(): List<Song> {
        val songs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC}=1",
            null,
            MediaStore.Audio.Media.TITLE + " ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataCol) ?: continue
                if (!isAudioFile(path)) continue
                songs.add(
                    Song(
                        id = cursor.getLong(idCol),
                        title = cursor.getString(titleCol) ?: File(path).nameWithoutExtension,
                        artist = cursor.getString(artistCol) ?: "未知歌手",
                        path = path,
                        lrcPath = findLrc(path),
                        durationMs = cursor.getLong(durationCol).coerceAtLeast(0L)
                    )
                )
            }
        }
        return songs
    }

    private fun scanDirectories(paths: List<File>): List<Song> {
        val songs = mutableListOf<Song>()
        var id = 1L
        for (root in paths) {
            if (!root.exists() || !root.isDirectory) continue
            root.walkTopDown().maxDepth(8)
                .filter { it.isFile && isAudioFile(it.absolutePath) }
                .forEach { file ->
                    songs.add(
                        Song(
                            id = id++,
                            title = file.nameWithoutExtension,
                            artist = file.parentFile?.name ?: "本地音乐",
                            path = file.absolutePath,
                            lrcPath = findLrc(file.absolutePath),
                            durationMs = readDurationMs(file.absolutePath)
                        )
                    )
                }
        }
        return songs
    }

    private fun scanDocumentTrees(): List<Song> {
        val songs = mutableListOf<Song>()
        var id = 10_000_000L
        for (uriStr in treeUris) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) ?: continue
            walkDocumentFile(root, songs) { id++ }
        }
        return songs
    }

    private fun walkDocumentFile(
        file: DocumentFile,
        out: MutableList<Song>,
        nextId: () -> Long
    ) {
        if (file.isDirectory) {
            file.listFiles().forEach { walkDocumentFile(it, out, nextId) }
            return
        }
        val name = file.name ?: return
        if (!isAudioFile(name)) return
        val uri = file.uri.toString()
        val id = nextId()
        val title = name.substringBeforeLast('.')
        out.add(
            Song(
                id = id,
                title = title,
                artist = "本地音乐",
                path = uri,
                lrcPath = LyricFileStore.resolveSidecarPath(
                    context,
                    Song(id, title, "本地音乐", uri, null)
                ),
                durationMs = readDurationMs(uri)
            )
        )
    }

    private fun readDurationMs(pathOrUri: String): Long {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            if (pathOrUri.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(pathOrUri))
            } else {
                retriever.setDataSource(pathOrUri)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.coerceAtLeast(0L) ?: 0L
        }.getOrDefault(0L).also {
            runCatching { retriever.release() }
        }
    }

    private fun findLrc(audioPath: String): String? {
        if (audioPath.startsWith("content://")) return null
        val lrc = File("${audioPath.substringBeforeLast('.')}.lrc")
        return lrc.takeIf { it.exists() }?.absolutePath
    }

    private fun isAudioFile(path: String): Boolean {
        return path.substringAfterLast('.', "").lowercase() in audioExtensions
    }
}
