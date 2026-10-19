package top.niunaijun.blackboxa.view.setting

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.gms.GmsManagerActivity

class SettingFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.setting, rootKey)

        initGms()

        invalidHideState {
            val rootHidePreference: Preference = (findPreference("root_hide")!!)
            val hideRoot = AppManager.mBlackBoxLoader.hideRoot()
            rootHidePreference.setDefaultValue(hideRoot)
            rootHidePreference
        }

        invalidHideState {
            val daemonPreference: Preference = (findPreference("daemon_enable")!!)
            val mDaemonEnable = AppManager.mBlackBoxLoader.daemonEnable()
            daemonPreference.setDefaultValue(mDaemonEnable)
            daemonPreference
        }

        invalidHideState {
            val vpnPreference: Preference = (findPreference("use_vpn_network")!!)
            val mUseVpnNetwork = AppManager.mBlackBoxLoader.useVpnNetwork()
            vpnPreference.setDefaultValue(mUseVpnNetwork)
            vpnPreference
        }

        invalidHideState {
            val disableFlagSecurePreference: Preference = (findPreference("disable_flag_secure")!!)
            val mDisableFlagSecure = AppManager.mBlackBoxLoader.disableFlagSecure()
            disableFlagSecurePreference.setDefaultValue(mDisableFlagSecure)
            disableFlagSecurePreference
        }

        initSendLogs()
        initLogSenderConfig()
    }

    private fun initLogSenderConfig() {
        val botTokenPreference: EditTextPreference? = findPreference("log_bot_token")
        botTokenPreference?.let {
            it.text = AppManager.mBlackBoxLoader.logBotToken()
            it.summary = it.text.ifEmpty { getString(R.string.log_bot_token_summary) }
            it.setOnPreferenceChangeListener { preference, newValue ->
                val value = newValue?.toString().orEmpty().trim()
                AppManager.mBlackBoxLoader.invalidLogBotToken(value)
                (preference as EditTextPreference).text = value
                preference.summary = value.ifEmpty { getString(R.string.log_bot_token_summary) }
                true
            }
        }

        val chatIdPreference: EditTextPreference? = findPreference("log_chat_id")
        chatIdPreference?.let {
            it.text = AppManager.mBlackBoxLoader.logChatId()
            it.summary = it.text.ifEmpty { getString(R.string.log_chat_id_summary) }
            it.setOnPreferenceChangeListener { preference, newValue ->
                val value = newValue?.toString().orEmpty().trim()
                AppManager.mBlackBoxLoader.invalidLogChatId(value)
                (preference as EditTextPreference).text = value
                preference.summary = value.ifEmpty { getString(R.string.log_chat_id_summary) }
                true
            }
        }
    }

    private fun initGms() {
        val gmsManagerPreference: Preference = (findPreference("gms_manager")!!)

        if (BlackBoxCore.get().isSupportGms) {

            gmsManagerPreference.setOnPreferenceClickListener {
                GmsManagerActivity.start(requireContext())
                true
            }
        } else {
            gmsManagerPreference.summary = getString(R.string.no_gms)
            gmsManagerPreference.isEnabled = false
        }
    }

    private fun invalidHideState(block: () -> Preference) {
        val pref = block()
        pref.setOnPreferenceChangeListener { preference, newValue ->
            val tmpHide = (newValue == true)
            when (preference.key) {
                "root_hide" -> {

                    AppManager.mBlackBoxLoader.invalidHideRoot(tmpHide)
                }
                "daemon_enable" -> {
                    AppManager.mBlackBoxLoader.invalidDaemonEnable(tmpHide)
                }
                "use_vpn_network" -> {
                    AppManager.mBlackBoxLoader.invalidUseVpnNetwork(tmpHide)
                }
                "disable_flag_secure" -> {
                    AppManager.mBlackBoxLoader.invalidDisableFlagSecure(tmpHide)
                }
            }

            toast(R.string.restart_module)
            return@setOnPreferenceChangeListener true
        }
    }
    private fun initSendLogs() {
        val sendLogsPreference: Preference? = findPreference("send_logs")
        sendLogsPreference?.setOnPreferenceClickListener {
            val botToken = AppManager.mBlackBoxLoader.logBotToken()
            val chatId = AppManager.mBlackBoxLoader.logChatId()
            if (botToken.isEmpty() || chatId.isEmpty()) {
                toast("Set your Telegram Bot Token and Chat ID first")
                return@setOnPreferenceClickListener true
            }
            it.isEnabled = false
            BlackBoxCore.get()
                    .sendLogs(
                            "Manual Log Upload from Settings",
                            true,
                            object : BlackBoxCore.LogSendListener {
                                override fun onSuccess() {
                                    activity?.runOnUiThread { sendLogsPreference.isEnabled = true }
                                }

                                override fun onFailure(error: String?) {
                                    activity?.runOnUiThread { sendLogsPreference.isEnabled = true }
                                }
                            }
                    )
            toast("Sending logs to your Telegram... (check notifications)")
            true
        }
    }
}
