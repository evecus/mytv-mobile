package com.github.mytv

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class FullscreenActivity : FragmentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var controls: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var channelNameView: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnRetry: ImageButton
    private lateinit var btnExit: ImageButton

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private var isPlaying = true

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        setContentView(R.layout.activity_fullscreen)

        playerView = findViewById(R.id.fullscreen_player_view)
        controls = findViewById(R.id.fullscreen_controls)
        loading = findViewById(R.id.fullscreen_loading)
        channelNameView = findViewById(R.id.tv_fs_channel_name)
        btnPlayPause = findViewById(R.id.btn_fs_play_pause)
        btnRetry = findViewById(R.id.btn_fs_retry)
        btnExit = findViewById(R.id.btn_fs_exit)

        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""

        channelNameView.text = channelName
        channelNameView.visibility = View.VISIBLE
        handler.postDelayed({ channelNameView.visibility = View.GONE }, 3000)

        setupPlayer(url)

        // 点击显示/隐藏控制栏
        playerView.setOnClickListener {
            if (controls.visibility == View.VISIBLE) {
                hideControls()
            } else {
                showControls()
            }
        }

        btnPlayPause.setOnClickListener {
            if (player?.isPlaying == true) {
                player?.pause()
                isPlaying = false
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                player?.play()
                isPlaying = true
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            }
            scheduleHideControls()
        }

        btnRetry.setOnClickListener {
            player?.prepare()
            player?.play()
            scheduleHideControls()
        }

        btnExit.setOnClickListener {
            finish()
        }
    }

    private fun setupPlayer(url: String) {
        loading.visibility = View.VISIBLE
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) loading.visibility = View.GONE
            }
            override fun onPlayerError(error: PlaybackException) {
                loading.visibility = View.GONE
            }
        })
        player?.setMediaItem(MediaItem.fromUri(url))
        player?.playWhenReady = true
        player?.prepare()
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        scheduleHideControls()
    }

    private fun hideControls() {
        controls.visibility = View.GONE
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3000)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (isPlaying) player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
    }
}
