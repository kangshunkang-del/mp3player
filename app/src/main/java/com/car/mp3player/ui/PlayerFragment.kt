package com.car.mp3player.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.car.mp3player.MusicPlaybackService
import com.car.mp3player.R
import com.car.mp3player.data.SettingsRepository
import com.car.mp3player.databinding.DialogLyricSearchBinding
import com.car.mp3player.databinding.FragmentPlayerBinding
import com.car.mp3player.model.PlaybackMode
import com.car.mp3player.model.Song
import com.car.mp3player.playback.PlaybackStateHolder
import com.car.mp3player.util.AlbumColorExtractor
import com.google.android.material.slider.Slider
import kotlin.math.max

class PlayerFragment : Fragment(), PlaybackStateHolder.Listener {

    private enum class PlayerLayoutMode {
        CENTER,
        VINYL_LEFT,
        VINYL_RIGHT,
        LYRICS_FULL
    }

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private var userSeeking = false
    private lateinit var settings: SettingsRepository
    private var layoutMode = PlayerLayoutMode.CENTER
    private var defaultThemeColor = Color.parseColor("#FF1A1410")
    private var currentPlayerBgColor = Color.parseColor("#FF1A1410")
    private var stageTapDetector: GestureDetectorCompat? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())
        binding.btnPlayPause.setOnClickListener { sendAction(MusicPlaybackService.ACTION_TOGGLE) }
        binding.btnNext.setOnClickListener { sendAction(MusicPlaybackService.ACTION_NEXT) }
        binding.btnPrev.setOnClickListener { sendAction(MusicPlaybackService.ACTION_PREV) }
        binding.btnMode.setOnClickListener { toggleMode() }
        binding.btnLyrics.setOnClickListener { toggleLyricsView() }

        binding.progressSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                userSeeking = false
                seekTo(slider.value.toLong())
            }
        })

        setupStageTapHandling()
        binding.scrollLyricView.setOnClickListener {
            applyLayoutMode(PlayerLayoutMode.CENTER, animate = true)
        }
        binding.scrollLyricView.setOnLongClickListener {
            showLyricSearchDialog()
            true
        }

        applyLayoutMode(
            if (isLandscape()) PlayerLayoutMode.VINYL_LEFT else PlayerLayoutMode.CENTER,
            animate = false,
        )
        renderState()
        renderCover(PlaybackStateHolder.coverArtPath)
        updateProgressUi(PlaybackStateHolder.positionMs, PlaybackStateHolder.durationMs)
        syncBottomNavTheme()
    }

    private fun setupStageTapHandling() {
        stageTapDetector = GestureDetectorCompat(
            requireContext(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    handleStageTap(e)
                    return true
                }
            }
        )
        binding.playerStage.setOnTouchListener { _, event ->
            stageTapDetector?.onTouchEvent(event) ?: false
        }
    }

    private fun toggleLyricsView() {
        val next = if (isLandscape()) {
            if (layoutMode == PlayerLayoutMode.CENTER) PlayerLayoutMode.VINYL_LEFT
            else PlayerLayoutMode.CENTER
        } else {
            if (layoutMode == PlayerLayoutMode.LYRICS_FULL) PlayerLayoutMode.CENTER
            else PlayerLayoutMode.LYRICS_FULL
        }
        applyLayoutMode(next, animate = true)
    }

    override fun onResume() {
        super.onResume()
        if (!isLandscape() && layoutMode in setOf(PlayerLayoutMode.VINYL_LEFT, PlayerLayoutMode.VINYL_RIGHT)) {
            applyLayoutMode(PlayerLayoutMode.CENTER, animate = false)
        }
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun handleStageTap(event: MotionEvent) {
        if (!isLandscape()) {
            when {
                layoutMode == PlayerLayoutMode.LYRICS_FULL &&
                    isPointInside(event, binding.scrollLyricView) ->
                    applyLayoutMode(PlayerLayoutMode.CENTER, animate = true)
                layoutMode == PlayerLayoutMode.CENTER && isDiscTap(event) ->
                    applyLayoutMode(PlayerLayoutMode.LYRICS_FULL, animate = true)
                layoutMode == PlayerLayoutMode.LYRICS_FULL ->
                    applyLayoutMode(PlayerLayoutMode.CENTER, animate = true)
            }
            return
        }

        val half = binding.playerStage.width / 2f
        when (layoutMode) {
            PlayerLayoutMode.CENTER -> when {
                isDiscTap(event) -> applyLayoutMode(PlayerLayoutMode.VINYL_LEFT, animate = true)
                event.x < half -> applyLayoutMode(PlayerLayoutMode.VINYL_LEFT, animate = true)
                else -> applyLayoutMode(PlayerLayoutMode.VINYL_RIGHT, animate = true)
            }
            PlayerLayoutMode.VINYL_LEFT -> when {
                isPointInside(event, binding.vinylRecord) ||
                    isPointInside(event, binding.scrollLyricView) ->
                    applyLayoutMode(PlayerLayoutMode.CENTER, animate = true)
                event.x >= half ->
                    applyLayoutMode(PlayerLayoutMode.VINYL_RIGHT, animate = true)
            }
            PlayerLayoutMode.VINYL_RIGHT -> when {
                isPointInside(event, binding.vinylRecord) ||
                    isPointInside(event, binding.scrollLyricView) ->
                    applyLayoutMode(PlayerLayoutMode.CENTER, animate = true)
                event.x < half ->
                    applyLayoutMode(PlayerLayoutMode.VINYL_LEFT, animate = true)
            }
            else -> Unit
        }
    }

    private fun isDiscTap(event: MotionEvent): Boolean {
        val vinyl = binding.vinylRecord
        if (vinyl.visibility != View.VISIBLE) return false
        val stageLoc = IntArray(2)
        binding.playerStage.getLocationOnScreen(stageLoc)
        val vinylLoc = IntArray(2)
        vinyl.getLocationOnScreen(vinylLoc)
        val localX = event.x + (stageLoc[0] - vinylLoc[0])
        val localY = event.y + (stageLoc[1] - vinylLoc[1])
        return vinyl.isDiscTap(localX, localY)
    }

    private fun isPointInside(event: MotionEvent, view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val stageLoc = IntArray(2)
        binding.playerStage.getLocationOnScreen(stageLoc)
        val x = loc[0] - stageLoc[0]
        val y = loc[1] - stageLoc[1]
        return event.x >= x && event.x <= x + view.width &&
            event.y >= y && event.y <= y + view.height
    }

    private fun applyLayoutMode(mode: PlayerLayoutMode, animate: Boolean) {
        layoutMode = mode
        val stage = binding.playerStage
        val cs = ConstraintSet()
        cs.clone(stage)

        cs.clear(binding.vinylRecord.id, ConstraintSet.END)
        cs.clear(binding.vinylRecord.id, ConstraintSet.START)
        cs.clear(binding.vinylRecord.id, ConstraintSet.TOP)
        cs.clear(binding.vinylRecord.id, ConstraintSet.BOTTOM)
        cs.clear(binding.scrollLyricView.id, ConstraintSet.END)
        cs.clear(binding.scrollLyricView.id, ConstraintSet.START)

        when (mode) {
            PlayerLayoutMode.CENTER -> {
                cs.setVisibility(binding.vinylRecord.id, View.VISIBLE)
                cs.connect(binding.vinylRecord.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                cs.connect(binding.vinylRecord.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                cs.connect(binding.vinylRecord.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                cs.connect(binding.vinylRecord.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                cs.setVisibility(binding.scrollLyricView.id, View.GONE)
            }
            PlayerLayoutMode.LYRICS_FULL -> {
                cs.setVisibility(binding.vinylRecord.id, View.GONE)
                cs.connect(binding.scrollLyricView.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                cs.connect(binding.scrollLyricView.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                cs.connect(binding.scrollLyricView.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                cs.connect(binding.scrollLyricView.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                cs.setVisibility(binding.scrollLyricView.id, View.VISIBLE)
            }
            PlayerLayoutMode.VINYL_LEFT -> {
                cs.setVisibility(binding.vinylRecord.id, View.VISIBLE)
                cs.connect(binding.vinylRecord.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                cs.connect(binding.vinylRecord.id, ConstraintSet.END, R.id.stageCenterGuide, ConstraintSet.START)
                cs.connect(binding.vinylRecord.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                cs.connect(binding.vinylRecord.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                cs.connect(binding.scrollLyricView.id, ConstraintSet.START, R.id.stageCenterGuide, ConstraintSet.END)
                cs.connect(binding.scrollLyricView.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                cs.connect(binding.scrollLyricView.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                cs.connect(binding.scrollLyricView.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                cs.setVisibility(binding.scrollLyricView.id, View.VISIBLE)
            }
            PlayerLayoutMode.VINYL_RIGHT -> {
                cs.setVisibility(binding.vinylRecord.id, View.VISIBLE)
                cs.connect(binding.scrollLyricView.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                cs.connect(binding.scrollLyricView.id, ConstraintSet.END, R.id.stageCenterGuide, ConstraintSet.START)
                cs.connect(binding.scrollLyricView.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                cs.connect(binding.scrollLyricView.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                cs.connect(binding.vinylRecord.id, ConstraintSet.START, R.id.stageCenterGuide, ConstraintSet.END)
                cs.connect(binding.vinylRecord.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                cs.connect(binding.vinylRecord.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                cs.connect(binding.vinylRecord.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                cs.setVisibility(binding.scrollLyricView.id, View.VISIBLE)
            }
        }

        if (animate) {
            TransitionManager.beginDelayedTransition(stage, AutoTransition().apply { duration = 280L })
        }
        cs.applyTo(stage)
        if (mode != PlayerLayoutMode.CENTER) {
            binding.scrollLyricView.update(PlaybackStateHolder.lrcLines, PlaybackStateHolder.positionMs)
        }
        binding.vinylRecord.requestLayout()
    }

    fun syncBottomNavTheme() {
        (activity as? MainHost)?.syncPlayerBottomNav(currentPlayerBgColor)
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
        renderState()
        binding.vinylRecord.setPlaying(playing)
        binding.scrollLyricView.update(lines, positionMs)
        if (!userSeeking) updateProgressUi(positionMs, PlaybackStateHolder.durationMs)
    }

    override fun onPlayModeChanged(mode: PlaybackMode) {
        updateModeUi(mode)
    }

    override fun onCoverChanged(coverPath: String?) {
        renderCover(coverPath)
    }

    override fun onDurationChanged(durationMs: Long) {
        if (!userSeeking) updateProgressUi(PlaybackStateHolder.positionMs, durationMs)
    }

    private fun renderState() {
        val song = PlaybackStateHolder.currentSong
        binding.songTitle.text = song?.title ?: getString(R.string.app_name)
        binding.songArtist.text = song?.artist ?: getString(R.string.no_songs)
        binding.btnPlayPause.setImageResource(
            if (PlaybackStateHolder.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.vinylRecord.setPlaying(PlaybackStateHolder.isPlaying)
        binding.scrollLyricView.update(PlaybackStateHolder.lrcLines, PlaybackStateHolder.positionMs)
        updateModeUi(PlaybackStateHolder.playMode)
    }

    private fun updateProgressUi(positionMs: Long, durationMs: Long) {
        binding.timeCurrent.text = formatTime(positionMs)
        binding.timeTotal.text = formatTime(durationMs)
        val max = max(durationMs, 1L).toFloat()
        if (binding.progressSlider.valueTo != max) {
            binding.progressSlider.valueTo = max
        }
        binding.progressSlider.value = positionMs.coerceIn(0L, durationMs).toFloat()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        return "$min:${sec.toString().padStart(2, '0')}"
    }

    private fun applyPlayerBackground(color: Int) {
        currentPlayerBgColor = color
        binding.themeBackground.setBackgroundColor(color)
        syncBottomNavTheme()
    }

    private fun renderCover(coverPath: String?) {
        if (coverPath.isNullOrBlank()) {
            applyPlayerBackground(defaultThemeColor)
            binding.vinylRecord.setCoverBitmap(null)
            return
        }
        val bitmap = runCatching { BitmapFactory.decodeFile(coverPath) }.getOrNull()
        if (bitmap != null) {
            applyPlayerBackground(AlbumColorExtractor.backgroundColor(bitmap))
            binding.vinylRecord.setCoverBitmap(bitmap)
        } else {
            applyPlayerBackground(defaultThemeColor)
            binding.vinylRecord.setCoverBitmap(null)
        }
    }

    private fun updateModeUi(mode: PlaybackMode) {
        if (mode == PlaybackMode.SHUFFLE) {
            binding.btnMode.setImageResource(R.drawable.ic_mode_shuffle)
            binding.modeLabel.text = getString(R.string.mode_shuffle)
        } else {
            binding.btnMode.setImageResource(R.drawable.ic_mode_order)
            binding.modeLabel.text = getString(R.string.mode_order)
        }
    }

    private fun toggleMode() {
        val next = if (PlaybackStateHolder.playMode == PlaybackMode.ORDER) PlaybackMode.SHUFFLE else PlaybackMode.ORDER
        val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_SET_MODE
            putExtra(MusicPlaybackService.EXTRA_MODE, next.ordinal)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun seekTo(positionMs: Long) {
        val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
            action = MusicPlaybackService.ACTION_SEEK
            putExtra(MusicPlaybackService.EXTRA_SEEK, positionMs)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun sendAction(action: String) {
        if (PlaybackStateHolder.songs.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_songs, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                requireContext(),
                Intent(requireContext(), MusicPlaybackService::class.java).apply { this.action = action }
            )
        }.onFailure {
            Toast.makeText(requireContext(), R.string.playback_start_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshLyricStyle() {
        binding.scrollLyricView.refreshStyle()
        binding.scrollLyricView.update(PlaybackStateHolder.lrcLines, PlaybackStateHolder.positionMs)
    }

    private fun showLyricSearchDialog() {
        val song = PlaybackStateHolder.currentSong ?: return
        val dialogBinding = DialogLyricSearchBinding.inflate(layoutInflater)
        dialogBinding.inputTitle.setText(settings.lyricSearchTitle(song.path) ?: song.title)
        dialogBinding.inputArtist.setText(settings.lyricSearchArtist(song.path) ?: song.artist)

        AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.lyric_search_action) { _, _ ->
                val title = dialogBinding.inputTitle.text?.toString()?.trim().orEmpty()
                val artist = dialogBinding.inputArtist.text?.toString()?.trim().orEmpty()
                if (title.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.lyric_search_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val intent = Intent(requireContext(), MusicPlaybackService::class.java).apply {
                    action = MusicPlaybackService.ACTION_RELOAD_LYRICS
                    putExtra(MusicPlaybackService.EXTRA_SEARCH_TITLE, title)
                    putExtra(MusicPlaybackService.EXTRA_SEARCH_ARTIST, artist)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
                Toast.makeText(requireContext(), R.string.lyric_searching, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        stageTapDetector = null
        _binding = null
        super.onDestroyView()
    }
}
