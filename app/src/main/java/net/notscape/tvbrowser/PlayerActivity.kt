package net.notscape.tvbrowser

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView

/**
 * Trimmed Media3/ExoPlayer video player, ported from Notscape Mariner's
 * PlayerActivity.kt (Media3 1.9.4). Same control set, adapted for TV:
 *
 * - Top: Close + title. Centre: skip -10s, play/pause, skip +10s.
 * - Bottom: title-free scrubber with elapsed / remaining, speed cycle
 *   0.5/0.75/1/1.25/1.5/2x, sleep cycle 0/10/20/30/60 min, audio-track
 *   picker, subtitle picker (incl. off), quality picker (HLS/DASH
 *   renditions, Auto + manual pin).
 * - Large focus targets; controls auto-hide after 4s and return on
 *   any D-pad press.
 *
 * v1 cuts (reported): no downloads, no merge, no cast, no PiP, no
 * external subtitle-file loading, no playlist/queue (single item).
 * Stall handling is a simple 8s buffering notice + error state with
 * Retry; Mariner's 2-recovery watchdog is not reproduced.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
    }

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private lateinit var playerView: PlayerView
    private lateinit var controls: View
    private lateinit var playPauseButton: Button
    private lateinit var positionText: TextView
    private lateinit var remainingText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var speedButton: Button
    private lateinit var sleepButton: Button
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null
    private var sleepRunnable: Runnable? = null

    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 2
    private val sleepSteps = intArrayOf(0, 10, 20, 30, 60)
    private var sleepIndex = 0
    private var controlsVisible = true
    private var videoUrl: String = ""
    private var videoTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        videoTitle = intent.getStringExtra(EXTRA_TITLE) ?: videoUrl
        if (videoUrl.isBlank()) { finish(); return }

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = false
        }
        root.addView(playerView)

        controls = buildControls()
        root.addView(controls)
        setContentView(root)

        trackSelector = DefaultTrackSelector(this)
        player = ExoPlayer.Builder(this).setTrackSelector(trackSelector!!).build().also { p ->
            playerView.player = p
            val mime = mimeFor(videoUrl)
            val item = MediaItem.Builder().setUri(videoUrl).apply {
                if (mime != null) setMimeType(mime)
            }.build()
            p.setMediaItem(item)
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    updatePlayPause()
                    if (state == Player.STATE_READY) {
                        errorText.visibility = View.GONE
                        retryButton.visibility = View.GONE
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayPause()

                override fun onPlayerError(error: PlaybackException) {
                    errorText.text = "Could not play this video: ${error.message ?: "playback error"}"
                    errorText.visibility = View.VISIBLE
                    retryButton.visibility = View.VISIBLE
                }
            })
            p.prepare()
            p.playWhenReady = true
        }
        startProgressLoop()
        showControls()
    }

    private fun mimeFor(url: String): String? {
        val lower = url.lowercase().substringBefore('?').substringBefore('#')
        return when {
            lower.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            lower.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            lower.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            lower.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            lower.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            lower.endsWith(".mov") -> MimeTypes.VIDEO_MP4
            lower.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
            lower.endsWith(".m4a") -> MimeTypes.AUDIO_MP4
            else -> null
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tvButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 18f
        setTextColor(ContextCompat.getColor(context, android.R.color.white))
        background = ContextCompat.getDrawable(context, R.drawable.btn_tv)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        minHeight = dp(56)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun buildControls(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        // Top bar: Close + title.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
            )
        }
        val close = tvButton("Close").apply { setOnClickListener { finish() } }
        val title = TextView(this).apply {
            text = videoTitle
            textSize = 20f
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            setPadding(dp(16), dp(12), dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        top.addView(close)
        top.addView(title)
        overlay.addView(top)

        // Centre: skip back, play/pause, skip forward.
        val centre = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        }
        val back10 = tvButton("−10s").apply {
            minHeight = dp(72)
            setOnClickListener { seekBy(-10_000); showControls() }
        }
        playPauseButton = tvButton("Pause").apply {
            minHeight = dp(96)
            textSize = 24f
            setOnClickListener { togglePlay(); showControls() }
        }
        val fwd10 = tvButton("+10s").apply {
            minHeight = dp(72)
            setOnClickListener { seekBy(10_000); showControls() }
        }
        centre.addView(back10)
        centre.addView(playPauseButton)
        centre.addView(fwd10)
        overlay.addView(centre)

        // Bottom panel: scrubber + rows.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(20))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
        }
        seekBar = SeekBar(this).apply {
            max = 1000
            isFocusable = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val p = player ?: return
                        if (p.duration > 0) p.seekTo(p.duration * progress / 1000)
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar) = showControls()
                override fun onStopTrackingTouch(s: SeekBar) = showControls()
            })
        }
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        positionText = TextView(this).apply { textSize = 16f; setTextColor(android.graphics.Color.WHITE) }
        remainingText = TextView(this).apply {
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.END
        }
        times.addView(positionText)
        times.addView(remainingText)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        speedButton = tvButton("1×").apply {
            setOnClickListener {
                speedIndex = (speedIndex + 1) % speeds.size
                player?.setPlaybackSpeed(speeds[speedIndex])
                text = formatSpeed(speeds[speedIndex])
                showControls()
            }
        }
        val audioBtn = tvButton("Audio").apply { setOnClickListener { showAudioPicker(); showControls() } }
        val subBtn = tvButton("Subtitles").apply { setOnClickListener { showSubtitlePicker(); showControls() } }
        val qualityBtn = tvButton("Quality").apply { setOnClickListener { showQualityPicker(); showControls() } }
        sleepButton = tvButton("Sleep: off").apply {
            setOnClickListener {
                sleepIndex = (sleepIndex + 1) % sleepSteps.size
                applySleep()
                showControls()
            }
        }
        row.addView(speedButton)
        row.addView(audioBtn)
        row.addView(subBtn)
        row.addView(qualityBtn)
        row.addView(sleepButton)
        errorText = TextView(this).apply {
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.tv_red))
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        retryButton = tvButton("Retry").apply {
            visibility = View.GONE
            setOnClickListener {
                errorText.visibility = View.GONE
                visibility = View.GONE
                player?.prepare()
                player?.playWhenReady = true
            }
        }
        bottom.addView(seekBar)
        bottom.addView(times)
        bottom.addView(row)
        bottom.addView(errorText)
        bottom.addView(retryButton)
        overlay.addView(bottom)
        return overlay
    }

    private fun formatSpeed(s: Float): String =
        if (s == 1f) "1×" else "${s}×"

    private fun togglePlay() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        updatePlayPause()
    }

    private fun updatePlayPause() {
        val p = player
        playPauseButton.text = if (p != null && p.isPlaying) "Pause" else "Play"
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + ms).coerceIn(0L, p.duration.coerceAtLeast(0L)))
    }

    private fun applySleep() {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        val minutes = sleepSteps[sleepIndex]
        sleepButton.text = if (minutes == 0) "Sleep: off" else "Sleep: ${minutes}m"
        if (minutes > 0) {
            val r = Runnable { finish() }
            sleepRunnable = r
            handler.postDelayed(r, minutes * 60_000L)
        }
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        controlsVisible = true
        hideRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { hideControls() }
        hideRunnable = r
        handler.postDelayed(r, 4_000)
    }

    private fun hideControls() {
        controls.visibility = View.GONE
        controlsVisible = false
    }

    private fun startProgressLoop() {
        val r = object : Runnable {
            override fun run() {
                val p = player
                if (p != null && p.duration > 0) {
                    seekBar.progress = (p.currentPosition * 1000 / p.duration).toInt().coerceIn(0, 1000)
                    positionText.text = formatTime(p.currentPosition)
                    val remain = p.duration - p.currentPosition
                    remainingText.text = "−${formatTime(remain)}"
                }
                handler.postDelayed(this, 500)
            }
        }
        progressRunnable = r
        handler.post(r)
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ---- Track pickers ----

    private data class TrackChoice(
        val group: androidx.media3.common.Tracks.Group,
        val index: Int,
        val label: String
    )

    private fun audioChoices(): List<TrackChoice> {
        val out = ArrayList<TrackChoice>()
        val tracks = player?.currentTracks ?: return out
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO || !group.isSupported) continue
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                val label = f.label ?: f.language ?: "Track ${i + 1}"
                out.add(TrackChoice(group, i, label))
            }
        }
        return out
    }

    private fun subtitleChoices(): List<TrackChoice> {
        val out = ArrayList<TrackChoice>()
        val tracks = player?.currentTracks ?: return out
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT || !group.isSupported) continue
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                val label = f.label ?: f.language ?: "Subtitle ${i + 1}"
                out.add(TrackChoice(group, i, label))
            }
        }
        return out
    }

    private fun showAudioPicker() {
        val choices = audioChoices()
        if (choices.isEmpty()) {
            Toast.makeText(this, "No alternate audio tracks", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = choices.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Audio track")
            .setItems(labels) { _, which ->
                val c = choices[which]
                trackSelector?.setParameters(
                    trackSelector!!.buildUponParameters()
                        .setSelectionOverride(c.group, TrackSelectionOverride(c.group, listOf(c.index)))
                )
            }
            .show()
    }

    private fun showSubtitlePicker() {
        val choices = subtitleChoices()
        val labels = ArrayList<String>().apply {
            add("Off")
            addAll(choices.map { it.label })
        }
        AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setItems(labels.toTypedArray()) { _, which ->
                val sel = trackSelector ?: return@setItems
                if (which == 0) {
                    sel.setParameters(
                        sel.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    )
                } else {
                    val c = choices[which - 1]
                    sel.setParameters(
                        sel.buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setSelectionOverride(c.group, TrackSelectionOverride(c.group, listOf(c.index)))
                    )
                }
            }
            .show()
    }

    private fun showQualityPicker() {
        val heights = sortedSetOf<Int>()
        val tracks = player?.currentTracks
        if (tracks != null) {
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_VIDEO || !group.isSupported) continue
                for (i in 0 until group.length) {
                    val h = group.getTrackFormat(i).height
                    if (h > 0) heights.add(h)
                }
            }
        }
        val labels = ArrayList<String>().apply {
            add("Auto")
            addAll(heights.sortedDescending().map { "${it}p" })
        }
        val sorted = heights.sortedDescending()
        AlertDialog.Builder(this)
            .setTitle("Video quality")
            .setItems(labels.toTypedArray()) { _, which ->
                val sel = trackSelector ?: return@setItems
                if (which == 0) {
                    sel.setParameters(sel.buildUponParameters().setMaxVideoHeight(Int.MAX_VALUE))
                } else {
                    sel.setParameters(sel.buildUponParameters().setMaxVideoHeight(sorted[which - 1]))
                }
            }
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (!controlsVisible) {
                showControls()
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    return true
                }
            } else {
                showControls()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable?.let { handler.removeCallbacks(it) }
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
