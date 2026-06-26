package com.github.mytv

import android.content.Intent
import android.content.res.Configuration
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

    private var player: ExoPlayer? = null
    private var isPlayerPlaying = true

    private var tvListViewModel = TVListViewModel()
    private var currentPosition = 0
    private var currentGroupIndex = 0
    private val groupNames = mutableListOf<String>()
    private val groupAdapter by lazy { GroupAdapter() }
    private var channelAdapter: ChannelAdapter? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { portraitControls.visibility = View.GONE }

    /** 是否平板（sw >= 600dp） */
    private val isTablet: Boolean
        get() = resources.configuration.smallestScreenWidthDp >= 600

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

        if (SP.lastSpeedtest == 0L || SP.autoSpeedtest) {
            SpeedtestDialogFragment.show(this)
        }
    }

    /**
     * 平板横屏时系统会选择 layout-sw600dp/activity_main.xml，
     * 竖屏/手机用默认 layout/activity_main.xml。
     * configChanges 保留 orientation|screenSize，
     * 系统不会重建 Activity，需手动 setContentView 再重绑。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 重新加载布局（系统会根据当前配置自动选择正确的 layout 文件夹）
        setContentView(R.layout.activity_main)
        bindViews()
        setupPlayer()           // 重新绑定已有 player
        setupListeners()
        setupGroupList()
        buildChannelData()      // 恢复列表状态
    }

    private fun bindViews() {
        playerView       = findViewById(R.id.player_view)
        loadingOverlay   = findViewById(R.id.loading_overlay)
        errorOverlay     = findViewById(R.id.error_overlay)
        errorMsg         = findViewById(R.id.error_msg)
        portraitControls = findViewById(R.id.portrait_controls)
        btnPlayPause     = findViewById(R.id.btn_play_pause)
        btnRetry         = findViewById(R.id.btn_retry)
        btnFullscreen    = findViewById(R.id.btn_fullscreen)
        tvChannelName    = findViewById(R.id.tv_channel_name)
        sourceRow        = findViewById(R.id.source_row)
        tvSourceLabel    = findViewById(R.id.tv_source_label)
        btnSourcePrev    = findViewById(R.id.btn_source_prev)
        btnSourceNext    = findViewById(R.id.btn_source_next)
        btnSettings      = findViewById(R.id.btn_settings)
        groupList        = findViewById(R.id.group_list)
        channelList      = findViewById(R.id.channel_list)
    }

    @OptIn(UnstableApi::class)
    private fun setupPlayer() {
        // 复用已有 player，避免旋转时重建
        if (player == null) {
            player = ExoPlayer.Builder(this).build()
        }
        playerView.player = player
        // 清掉旧监听，重新注册
        player?.removeListener(playerListener)
        player?.addListener(playerListener)
    }

    private val playerListener = object : Player.Listener {
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
    }

    private fun setupListeners() {
        playerView.setOnClickListener {
            if (portraitControls.visibility == View.VISIBLE) {
                portraitControls.visibility = View.GONE
            } else {
                portraitControls.visibility = View.VISIBLE
                scheduleHideControls()
            }
        }
        loadingOverlay.setOnClickListener { }

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
            tvListViewModel.getTVViewModel(currentPosition)?.let { playChannel(it) }
            scheduleHideControls()
        }

        btnFullscreen.setOnClickListener {
            val vm = tvListViewModel.getTVViewModel(currentPosition) ?: return@setOnClickListener
            val url = vm.getVideoUrlCurrent()
            if (url.isEmpty()) return@setOnClickListener
            startActivity(Intent(this, FullscreenActivity::class.java).apply {
                putExtra(FullscreenActivity.EXTRA_URL, url)
                putExtra(FullscreenActivity.EXTRA_CHANNEL_NAME, vm.getTV().title)
            })
        }

        btnSourcePrev.setOnClickListener {
            tvListViewModel.getTVViewModel(currentPosition)?.switchSource(-1)
        }
        btnSourceNext.setOnClickListener {
            tvListViewModel.getTVViewModel(currentPosition)?.switchSource(+1)
        }
        btnSettings.setOnClickListener {
            if (isTablet) {
                // 平板：使用居中 Dialog，避免 BottomSheet 显示不全
                SettingsDialogFragment.show(this)
            } else {
                SettingsBottomSheet.show(this)
            }
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

    fun buildChannelData() {
        tvListViewModel = TVListViewModel()
        groupNames.clear()
        groupAdapter.clear()

        if (TVList.isEmpty()) return

        for ((group, channels) in TVList.list) {
            groupNames.add(group)
            for ((idx, tv) in channels.withIndex()) {
                val vm = TVViewModel(tv)
                vm.setRowPosition(groupNames.size - 1)
                vm.setItemPosition(idx)
                tvListViewModel.addTVViewModel(vm)
            }
            tvListViewModel.maxNum.add(channels.size)
        }

        groupAdapter.setItems(groupNames)

        currentPosition = SP.itemPosition.coerceIn(0, (tvListViewModel.size() - 1).coerceAtLeast(0))
        currentGroupIndex = tvListViewModel.getTVViewModel(currentPosition)?.getRowPosition() ?: 0
        groupAdapter.setSelected(currentGroupIndex)
        showGroupChannels(currentGroupIndex)

        attachObservers()
        tvListViewModel.getTVViewModel(currentPosition)?.changed("init")
    }

    private fun attachObservers() {
        tvListViewModel.tvListViewModel.value?.forEach { vm ->
            vm.change.observe(this) { from ->
                if (from != null && vm.getTV().id == currentPosition) {
                    val url = vm.getVideoUrlCurrent().ifEmpty {
                        vm.getTV().videoUrl.firstOrNull() ?: return@observe
                    }
                    if (from != "source") vm.addVideoUrl(url)
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

        var startId = 0
        for (i in 0 until groupIndex) {
            startId += tvListViewModel.maxNum.getOrElse(i) { 0 }
        }

        val vms = channels.indices.mapNotNull { idx ->
            tvListViewModel.getTVViewModel(startId + idx)
        }
        channelAdapter = ChannelAdapter(vms)
        channelList.adapter = channelAdapter

        val currentVm = tvListViewModel.getTVViewModel(currentPosition)
        if (currentVm?.getRowPosition() == groupIndex) {
            val localIdx = currentVm.getItemPosition()
            channelAdapter?.setSelected(localIdx)
            channelList.post { channelList.setSelection(localIdx) }
        } else {
            channelAdapter?.setSelected(-1)
        }

        channelList.setOnItemClickListener { _, _, position, _ ->
            val clickedVm = channelAdapter?.getViewModel(position) ?: return@setOnItemClickListener
            channelAdapter?.setSelected(position)
            val newId = clickedVm.getTV().id
            if (newId != currentPosition) {
                currentPosition = newId
                tvListViewModel.setItemPosition(currentPosition)
                clickedVm.changed("menu")
                attachObservers()
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
        val total = vm.getTV().videoUrl.size
        if (total > 1) {
            sourceRow.visibility = View.VISIBLE
            val idx = vm.videoIndex.value ?: 0
            tvSourceLabel.text = "线路 ${idx + 1}/$total"
        } else {
            sourceRow.visibility = View.GONE
        }
    }

    private fun updateSourceLabel(current: Int, total: Int) {
        if (total <= 1) { sourceRow.visibility = View.GONE; return }
        sourceRow.visibility = View.VISIBLE
        tvSourceLabel.text = when (current) {
            0  -> "已是第一条线路"
            -1 -> "已是最后一条线路"
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
        player = null
    }

    // ── GroupAdapter ───────────────────────────────────────────────

    inner class GroupAdapter : BaseAdapter() {
        private val items = mutableListOf<String>()
        private var selectedPos = 0

        fun setItems(list: List<String>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        fun clear() { items.clear(); notifyDataSetChanged() }
        fun setSelected(pos: Int) { selectedPos = pos; notifyDataSetChanged() }

        override fun getCount() = items.size
        override fun getItem(pos: Int): Any = items[pos]
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

    // ── ChannelAdapter ─────────────────────────────────────────────

    inner class ChannelAdapter(private val items: List<TVViewModel>) : BaseAdapter() {
        private var selectedPos = -1

        fun setSelected(pos: Int) { selectedPos = pos; notifyDataSetChanged() }
        fun getViewModel(pos: Int): TVViewModel? = items.getOrNull(pos)

        override fun getCount() = items.size
        override fun getItem(pos: Int): Any = items[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val row = convertView
                ?: layoutInflater.inflate(R.layout.item_channel, parent, false)
            val vm = items[pos]
            val tv = vm.getTV()
            val numView  = row.findViewById<TextView>(R.id.tv_channel_num)
            val nameView = row.findViewById<TextView>(R.id.tv_channel_name)
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
