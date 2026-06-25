package com.github.mytv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.github.mytv.models.TVList
import com.github.mytv.models.TVListViewModel
import com.github.mytv.models.TVViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    // ── Views ──────────────────────────────────────────────────────
    private lateinit var playerView: PlayerView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var errorOverlay: LinearLayout
    private lateinit var errorMsg: TextView
    private lateinit var portraitControls: LinearLayout
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnRetry: ImageButton
    private lateinit var btnFullscreen: ImageButton
    private lateinit var tvChannelName: TextView
    private lateinit var sourceRow: LinearLayout
    private lateinit var tvSourceLabel: TextView
    private lateinit var btnSourcePrev: ImageButton
    private lateinit var btnSourceNext: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var groupList: ListView
    private lateinit var channelList: ListView

    // ── Player ─────────────────────────────────────────────────────
    private var player: ExoPlayer? = null
    private var isPlayerPlaying = true

    // ── Data ───────────────────────────────────────────────────────
    private var tvListViewModel = TVListViewModel()
    private var currentPosition = 0
    private var currentGroupIndex = 0
    private val groupNames = mutableListOf<String>()
    private val groupAdapter by lazy { GroupAdapter() }
    private var channelAdapter: ChannelAdapter? = null

    // ── Handlers ───────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { portraitControls.visibility = View.GONE }

    // ── Lifecycle ──────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        bindViews()
        setupPlayer()
        setupListeners()
        setupGroupList()

        syncTime()
        TVList.load(this)
        buildChannelData()

        val neverTested = SP.lastSpeedtest == 0L
        if (neverTested || SP.autoSpeedtest) {
            SpeedtestDialogFragment.show(this)
        }
    }

    private fun bindViews() {
        playerView        = findViewById(R.id.player_view)
        loadingOverlay    = findViewById(R.id.loading_overlay)
        errorOverlay      = findViewById(R.id.error_overlay)
        errorMsg          = findViewById(R.id.error_msg)
        portraitControls  = findViewById(R.id.portrait_controls)
        btnPlayPause      = findViewById(R.id.btn_play_pause)
        btnRetry          = findViewById(R.id.btn_retry)
        btnFullscreen     = findViewById(R.id.btn_fullscreen)
        tvChannelName     = findViewById(R.id.tv_channel_name)
        sourceRow         = findViewById(R.id.source_row)
        tvSourceLabel     = findViewById(R.id.tv_source_label)
        btnSourcePrev     = findViewById(R.id.btn_source_prev)
        btnSourceNext     = findViewById(R.id.btn_source_next)
        btnSettings       = findViewById(R.id.btn_settings)
        groupList         = findViewById(R.id.group_list)
        channelList       = findViewById(R.id.channel_list)
    }

    @OptIn(UnstableApi::class)
    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    loadingOverlay.visibility = View.GONE
                    errorOverlay.visibility   = View.GONE
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                loadingOverlay.visibility = View.GONE
                errorOverlay.visibility   = View.VISIBLE
                errorMsg.text             = "播放错误，请尝试换线路"
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) {
                    loadingOverlay.visibility = View.VISIBLE
                    errorOverlay.visibility   = View.GONE
                }
            }
        })
    }

    private fun setupListeners() {
        // 点击播放区域显示/隐藏控制条
        playerView.setOnClickListener {
            if (portraitControls.visibility == View.VISIBLE) {
                portraitControls.visibility = View.GONE
            } else {
                portraitControls.visibility = View.VISIBLE
                scheduleHideControls()
            }
        }
        loadingOverlay.setOnClickListener { /* 不透传 */ }

        btnPlayPause.setOnClickListener {
            if (player?.isPlaying == true) {
                player?.pause()
                isPlayerPlaying = false
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            } else {
                player?.play()
                isPlayerPlaying = true
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            }
            scheduleHideControls()
        }

        btnRetry.setOnClickListener {
            val vm = tvListViewModel.getTVViewModel(currentPosition)
            if (vm != null) playChannel(vm)
            scheduleHideControls()
        }

        btnFullscreen.setOnClickListener {
            val vm = tvListViewModel.getTVViewModel(currentPosition) ?: return@setOnClickListener
            val url = vm.getVideoUrlCurrent()
            if (url.isEmpty()) return@setOnClickListener
            val intent = Intent(this, FullscreenActivity::class.java).apply {
                putExtra(FullscreenActivity.EXTRA_URL, url)
                putExtra(FullscreenActivity.EXTRA_CHANNEL_NAME, vm.getTV().title)
            }
            startActivity(intent)
        }

        btnSourcePrev.setOnClickListener {
            tvListViewModel.getTVViewModel(currentPosition)?.switchSource(-1)
        }

        btnSourceNext.setOnClickListener {
            tvListViewModel.getTVViewModel(currentPosition)?.switchSource(+1)
        }

        btnSettings.setOnClickListener {
            SettingsBottomSheet.show(this)
        }
    }

    private fun setupGroupList() {
        groupList.adapter = groupAdapter
        groupList.setOnItemClickListener { _, _, position, _ ->
            currentGroupIndex = position
            groupAdapter.setSelected(position)
            showGroupChannels(position)
        }
    }

    // ── Data / Channel Logic ───────────────────────────────────────

    fun buildChannelData() {
        tvListViewModel = TVListViewModel()
        groupNames.clear()
        groupAdapter.clear()

        if (TVList.isEmpty()) return

        var globalId = 0
        for ((group, channels) in TVList.list) {
            groupNames.add(group)
            for ((idx, tv) in channels.withIndex()) {
                val vm = TVViewModel(tv)
                vm.setRowPosition(groupNames.size - 1)
                vm.setItemPosition(idx)
                tvListViewModel.addTVViewModel(vm)
                globalId++
            }
            tvListViewModel.maxNum.add(channels.size)
        }

        groupAdapter.setItems(groupNames)

        // 恢复上次频道
        currentPosition = SP.itemPosition.coerceIn(0, (tvListViewModel.size() - 1).coerceAtLeast(0))

        // 确定当前频道所在分组
        currentGroupIndex = tvListViewModel.getTVViewModel(currentPosition)?.getRowPosition() ?: 0
        groupAdapter.setSelected(currentGroupIndex)
        showGroupChannels(currentGroupIndex)

        // 自动播放
        observeCurrentChannel()
        tvListViewModel.getTVViewModel(currentPosition)?.changed("init")
    }

    private fun observeCurrentChannel() {
        tvListViewModel.tvListViewModel.value?.forEach { vm ->
            vm.change.observe(this) { from ->
                if (from != null && vm.getTV().id == currentPosition) {
                    if (from != "source") vm.addVideoUrl(vm.getVideoUrlCurrent().ifEmpty {
                        vm.getTV().videoUrl.firstOrNull() ?: return@observe
                    })
                    vm.allReady()
                }
            }
            vm.ready.observe(this) {
                if (vm.getTV().id == currentPosition && vm.ready.value == true) {
                    playChannel(vm)
                }
            }
            vm.sourceChanged.observe(this) { pair ->
                if (pair != null && vm.getTV().id == currentPosition) {
                    updateSourceLabel(pair.first, pair.second)
                    if (pair.first > 0) playChannel(vm)
                }
            }
        }
    }

    private fun showGroupChannels(groupIndex: Int) {
        if (groupIndex >= groupNames.size) return
        val group = groupNames[groupIndex]
        val channels = TVList.list[group] ?: return

        // 计算该分组在全局列表中的起始 id
        var startId = 0
        for (i in 0 until groupIndex) {
            startId += tvListViewModel.maxNum.getOrElse(i) { 0 }
        }

        channelAdapter = ChannelAdapter(channels.mapIndexed { idx, _ ->
            tvListViewModel.getTVViewModel(startId + idx)
        }.filterNotNull())

        channelList.adapter = channelAdapter

        // 高亮当前选中频道
        val vm = tvListViewModel.getTVViewModel(currentPosition)
        if (vm?.getRowPosition() == groupIndex) {
            val localIdx = vm.getItemPosition()
            channelAdapter?.setSelected(localIdx)
            channelList.post { channelList.setSelection(localIdx) }
        } else {
            channelAdapter?.setSelected(-1)
        }

        channelList.setOnItemClickListener { _, _, position, _ ->
            channelAdapter?.setSelected(position)
            val clickedVm = channelAdapter?.getItem(position) ?: return@setOnItemClickListener
            val newId = clickedVm.getTV().id
            if (newId != currentPosition) {
                currentPosition = newId
                tvListViewModel.setItemPosition(currentPosition)
                clickedVm.changed("menu")
                observeCurrentChannel()
            }
        }
    }

    private fun playChannel(vm: TVViewModel) {
        val url = vm.getVideoUrlCurrent()
        if (url.isEmpty()) return

        tvChannelName.text = vm.getTV().title
        updateSourceUI(vm)

        loadingOverlay.visibility = View.VISIBLE
        errorOverlay.visibility   = View.GONE

        player?.setMediaItem(MediaItem.fromUri(url))
        player?.playWhenReady = true
        player?.prepare()
    }

    private fun updateSourceUI(vm: TVViewModel) {
        val total = vm.sourceCount
        if (total > 1) {
            sourceRow.visibility = View.VISIBLE
            tvSourceLabel.text   = "线路 ${vm.currentSourceIndex + 1}/$total"
        } else {
            sourceRow.visibility = View.GONE
        }
    }

    private fun updateSourceLabel(current: Int, total: Int) {
        if (total <= 1) { sourceRow.visibility = View.GONE; return }
        sourceRow.visibility = View.VISIBLE
        tvSourceLabel.text = when (current) {
            0    -> "已是第一条线路"
            -1   -> "已是最后一条线路"
            else -> "线路 $current/$total"
        }
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3000)
    }

    fun reloadChannels() {
        TVList.load(this)
        buildChannelData()
        Toast.makeText(this, "频道列表已更新", Toast.LENGTH_SHORT).show()
    }

    private fun syncTime() {
        lifecycleScope.launch(Dispatchers.IO) {
            val job = async(start = CoroutineStart.LAZY) { Utils.init() }
            job.start()
        }
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (isPlayerPlaying) player?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
    }

    // ── Inner Adapters ─────────────────────────────────────────────

    inner class GroupAdapter : BaseAdapter() {
        private val items = mutableListOf<String>()
        private var selectedPos = 0

        fun setItems(list: List<String>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }
        fun clear() { items.clear(); notifyDataSetChanged() }
        fun setSelected(pos: Int) { selectedPos = pos; notifyDataSetChanged() }

        override fun getCount() = items.size
        override fun getItem(pos: Int) = items[pos]
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getView(pos: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val tv = (convertView as? TextView)
                ?: layoutInflater.inflate(R.layout.item_group, parent, false) as TextView
            tv.text = items[pos]
            if (pos == selectedPos) {
                tv.setBackgroundResource(R.drawable.bg_group_selected)
                tv.setTextColor(getColor(R.color.selected_text))
                tv.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                tv.setBackgroundResource(android.R.color.transparent)
                tv.setTextColor(getColor(R.color.on_surface_secondary))
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            return tv
        }
    }

    inner class ChannelAdapter(private val items: List<TVViewModel>) : BaseAdapter() {
        private var selectedPos = -1

        fun setSelected(pos: Int) { selectedPos = pos; notifyDataSetChanged() }
        fun getItem(pos: Int) = items.getOrNull(pos)

        override fun getCount() = items.size
        override fun getItem(pos: Int): TVViewModel? = items.getOrNull(pos)
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val row = (convertView
                ?: layoutInflater.inflate(R.layout.item_channel, parent, false))
            val vm = items[pos]
            val tv = vm.getTV()
            val numView  = row.findViewById<TextView>(R.id.tv_channel_num)
            val nameView = row.findViewById<TextView>(R.id.tv_channel_name)
            // 全局 id+1 作为频道号
            numView.text  = "${tv.id + 1}"
            nameView.text = tv.title

            if (pos == selectedPos) {
                row.setBackgroundResource(R.drawable.bg_channel_selected)
                numView.setTextColor(getColor(R.color.selected_text))
                nameView.setTextColor(getColor(R.color.selected_text))
                nameView.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                row.setBackgroundResource(android.R.color.transparent)
                numView.setTextColor(getColor(R.color.on_surface_secondary))
                nameView.setTextColor(getColor(R.color.on_surface))
                nameView.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            return row
        }
    }
}
