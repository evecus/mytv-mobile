package com.github.mytv

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext()

        view.findViewById<TextView>(R.id.tv_version_name).text =
            "当前版本: v${ctx.appVersionName}"

        view.findViewById<View>(R.id.btn_close_settings).setOnClickListener {
            dismissAllowingStateLoss()
        }

        view.findViewById<SwitchCompat>(R.id.switch_auto_speedtest).apply {
            isChecked = SP.autoSpeedtest
            setOnCheckedChangeListener { _, checked -> SP.autoSpeedtest = checked }
        }

        view.findViewById<SwitchCompat>(R.id.switch_channel_reversal).apply {
            isChecked = SP.channelReversal
            setOnCheckedChangeListener { _, checked -> SP.channelReversal = checked }
        }

        view.findViewById<SwitchCompat>(R.id.switch_boot_startup).apply {
            isChecked = SP.bootStartup
            setOnCheckedChangeListener { _, checked -> SP.bootStartup = checked }
        }

        view.findViewById<View>(R.id.btn_speedtest).setOnClickListener {
            dismissAllowingStateLoss()
            SpeedtestDialogFragment.show(requireActivity())
        }

        view.findViewById<View>(R.id.btn_check_version).setOnClickListener {
            val versionCode = ctx.appVersionCode
            UpdateManager(requireActivity(), versionCode).checkAndUpdate()
        }

        view.findViewById<View>(R.id.btn_exit).setOnClickListener {
            requireActivity().finishAffinity()
        }
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
        fun show(activity: androidx.fragment.app.FragmentActivity) {
            if (activity.supportFragmentManager.findFragmentByTag(TAG) != null) return
            SettingsBottomSheet().show(activity.supportFragmentManager, TAG)
        }
    }
}
