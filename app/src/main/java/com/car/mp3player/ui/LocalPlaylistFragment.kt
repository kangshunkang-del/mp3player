package com.car.mp3player.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.car.mp3player.ArtistAdapter
import com.car.mp3player.FolderAdapter
import com.car.mp3player.R
import com.car.mp3player.SongAdapter
import com.car.mp3player.data.SettingsRepository
import com.car.mp3player.databinding.FragmentLocalPlaylistBinding
import com.car.mp3player.model.ArtistGroup
import com.car.mp3player.model.FolderGroup
import com.car.mp3player.model.LibraryKind
import com.car.mp3player.model.PlaylistSortOrder
import com.car.mp3player.model.PlaylistViewMode
import com.car.mp3player.model.Song
import com.car.mp3player.playback.PlaybackStateHolder

class LocalPlaylistFragment : Fragment(), PlaybackStateHolder.Listener {
    private var _binding: FragmentLocalPlaylistBinding? = null
    private val binding get() = _binding!!
    private lateinit var songAdapter: SongAdapter
    private lateinit var artistAdapter: ArtistAdapter
    private lateinit var folderAdapter: FolderAdapter
    private var query = ""
    private var viewMode = PlaylistViewMode.ALL_SONGS
    private var sortOrder = PlaylistSortOrder.TITLE
    private var selectedArtist: String? = null
    private var selectedFolderPath: String? = null
    private var lastClickMs = 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLocalPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settings = SettingsRepository(requireContext())
        AppThemeManager.applyFragmentRoot(binding.root, AppThemeManager.palette(requireContext(), settings))
        songAdapter = SongAdapter(
            onClick = { song, indexInList ->
                val now = System.currentTimeMillis()
                if (now - lastClickMs < 280) return@SongAdapter
                lastClickMs = now
                (activity as? MainHost)?.switchToTab(1)
                val visibleSongs = currentVisibleSongs()
                val playIndex = visibleSongs.indexOfFirst { it.path == song.path }.takeIf { it >= 0 } ?: indexInList
                (activity as? MainHost)?.playSongSubset(visibleSongs, playIndex, LibraryKind.MUSIC)
            }
        )
        artistAdapter = ArtistAdapter { group ->
            selectedArtist = group.name
            updateToolbar()
            applyFilter()
        }
        folderAdapter = FolderAdapter { group ->
            selectedFolderPath = group.path
            updateToolbar()
            applyFilter()
        }

        binding.songList.layoutManager = LinearLayoutManager(requireContext())
        binding.songList.adapter = songAdapter
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.chipAllSongs.setOnClickListener { selectViewMode(PlaylistViewMode.ALL_SONGS) }
        binding.chipFolders.setOnClickListener { selectViewMode(PlaylistViewMode.BY_FOLDER) }
        binding.chipArtists.setOnClickListener { selectViewMode(PlaylistViewMode.BY_ARTIST) }
        binding.sortGroup.setOnCheckedChangeListener { _, checkedId ->
            sortOrder = when (checkedId) {
                R.id.sortDurationAsc -> PlaylistSortOrder.DURATION_ASC
                R.id.sortDurationDesc -> PlaylistSortOrder.DURATION_DESC
                else -> PlaylistSortOrder.TITLE
            }
            applyFilter()
        }
        binding.toolbar.setNavigationOnClickListener { exitArtistDetail() }
        refreshFromHost()
    }

    private fun selectViewMode(mode: PlaylistViewMode) {
        viewMode = mode
        selectedArtist = null
        selectedFolderPath = null
        binding.chipAllSongs.isChecked = mode == PlaylistViewMode.ALL_SONGS
        binding.chipFolders.isChecked = mode == PlaylistViewMode.BY_FOLDER
        binding.chipArtists.isChecked = mode == PlaylistViewMode.BY_ARTIST
        updateToolbar()
        applyFilter()
    }

    private fun exitArtistDetail() {
        if (selectedArtist == null && selectedFolderPath == null) return
        selectedArtist = null
        selectedFolderPath = null
        updateToolbar()
        applyFilter()
    }

    override fun onStart() {
        super.onStart()
        PlaybackStateHolder.addListener(this)
    }

    override fun onStop() {
        PlaybackStateHolder.removeListener(this)
        super.onStop()
    }

    override fun onPlaybackChanged(
        song: Song?,
        playing: Boolean,
        positionMs: Long,
        lines: List<com.car.mp3player.model.LrcLine>
    ) {
        songAdapter.playingPath = song?.path
    }

    override fun onPlaylistChanged(songs: List<Song>) {
        songAdapter.playingPath = PlaybackStateHolder.currentSong?.path
        applyFilter()
    }

    fun refreshFromHost() = applyFilter()

    private fun sourceSongs(): List<Song> = (activity as? MainHost)?.allSongs().orEmpty()

    private fun currentVisibleSongs(): List<Song> {
        var list = sourceSongs()
        if (selectedArtist != null) list = list.filter { it.artist == selectedArtist }
        if (selectedFolderPath != null) list = list.filter { folderPath(it) == selectedFolderPath }
        if (query.isNotBlank()) {
            val q = query.lowercase()
            list = list.filter { it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) }
        }
        return sortSongs(list)
    }

    private fun applyFilter() {
        if (viewMode == PlaylistViewMode.BY_ARTIST && selectedArtist == null) {
            showArtistList()
            return
        }
        if (viewMode == PlaylistViewMode.BY_FOLDER && selectedFolderPath == null) {
            showFolderList()
            return
        }
        binding.songList.adapter = songAdapter
        val list = currentVisibleSongs()
        songAdapter.playingPath = PlaybackStateHolder.currentSong?.path
        songAdapter.submitSongs(list)
        binding.songCountText.text = getString(R.string.song_count, list.size)
        binding.emptyText.text = getString(R.string.no_songs)
        binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.songList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showFolderList() {
        binding.songList.adapter = folderAdapter
        val songs = sourceSongs()
        val grouped = songs.mapNotNull { song ->
            val path = folderPath(song) ?: return@mapNotNull null
            val folder = java.io.File(path)
            FolderGroup(folder.name.ifBlank { path }, path, 0)
        }
        val nameCounts = grouped.groupingBy { it.name }.eachCount()
        val folders = grouped.groupBy { it.path }.map { (path, items) ->
            val rawName = items.first().name
            val label = if (nameCounts[rawName] == 1) rawName else path
            FolderGroup(label, path, songs.count { folderPath(it) == path })
        }.filter {
            query.isBlank() || it.name.lowercase().contains(query.lowercase())
        }.sortedBy { it.name.lowercase() }
        folderAdapter.submitList(folders)
        binding.songCountText.text = getString(R.string.folder_count, folders.size)
        binding.emptyText.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
        binding.songList.visibility = if (folders.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun folderPath(song: Song): String? {
        if (song.path.startsWith("content://")) return null
        return runCatching { java.io.File(song.path).parentFile?.absolutePath }.getOrNull()
    }

    private fun showArtistList() {
        binding.songList.adapter = artistAdapter
        var artists = sourceSongs().groupBy { it.artist }
            .map { (name, songs) -> ArtistGroup(name, songs.size) }
        if (query.isNotBlank()) {
            val q = query.lowercase()
            artists = artists.filter { it.name.lowercase().contains(q) }
        }
        artists = artists.sortedBy { it.name.lowercase() }
        artistAdapter.submitList(artists)
        binding.songCountText.text = getString(R.string.artist_count, artists.size)
        binding.emptyText.visibility = if (artists.isEmpty()) View.VISIBLE else View.GONE
        binding.songList.visibility = if (artists.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun sortSongs(list: List<Song>): List<Song> = when (sortOrder) {
        PlaylistSortOrder.TITLE -> list.sortedBy { it.title.lowercase() }
        PlaylistSortOrder.DURATION_ASC -> list.sortedWith(
            compareBy<Song> { if (it.durationMs <= 0L) Long.MAX_VALUE else it.durationMs }.thenBy { it.title.lowercase() }
        )
        PlaylistSortOrder.DURATION_DESC -> list.sortedWith(
            compareByDescending<Song> { if (it.durationMs <= 0L) Long.MIN_VALUE else it.durationMs }
                .thenBy { it.title.lowercase() }
        )
    }

    private fun updateToolbar() {
        val inArtistDetail =
            (viewMode == PlaylistViewMode.BY_ARTIST && selectedArtist != null) ||
                (viewMode == PlaylistViewMode.BY_FOLDER && selectedFolderPath != null)
        binding.toolbar.navigationIcon = if (inArtistDetail) {
            requireContext().getDrawable(android.R.drawable.ic_menu_revert)
        } else null
        binding.toolbar.subtitle = when {
            selectedArtist != null -> selectedArtist
            selectedFolderPath != null -> java.io.File(selectedFolderPath!!).name.ifBlank { selectedFolderPath }
            viewMode == PlaylistViewMode.BY_ARTIST -> getString(R.string.filter_artists)
            viewMode == PlaylistViewMode.BY_FOLDER -> getString(R.string.filter_folders)
            else -> null
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
