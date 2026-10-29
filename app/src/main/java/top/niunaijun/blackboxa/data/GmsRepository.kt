package top.niunaijun.blackboxa.data

import androidx.lifecycle.MutableLiveData
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.gms.BlackBoxMicroGInstaller
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.bean.GmsBean
import top.niunaijun.blackboxa.bean.GmsInstallBean
import top.niunaijun.blackboxa.util.getString


class GmsRepository {


    fun getGmsInstalledList(mInstalledLiveData: MutableLiveData<List<GmsBean>>) {
        val userList = arrayListOf<GmsBean>()

        BlackBoxCore.get().users.forEach {
            val userId = it.id
            val userName =
                AppManager.mRemarkSharedPreferences.getString("Remark$userId", "User $userId") ?: ""
            val isInstalled = BlackBoxCore.get().isInstallGms(userId)
            val bean = GmsBean(userId, userName, isInstalled)
            userList.add(bean)
        }

        mInstalledLiveData.postValue(userList)
    }

    fun installGms(
        userID: Int,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        val outcome = try {
            BlackBoxMicroGInstaller.create(BlackBoxCore.getContext()).install(userID)
        } catch (e: Throwable) {
            mUpdateInstalledLiveData.postValue(
                GmsInstallBean(
                    userID, false,
                    getString(R.string.install_fail, e.message ?: e.javaClass.simpleName)
                )
            )
            return
        }

        val detail = outcome.steps.joinToString("\n") { step ->
            val suffix = if (step.message.isNullOrEmpty()) "" else " (${step.message})"
            "${step.packageName}: ${step.status.name.lowercase()}$suffix"
        }
        val result = if (outcome.success()) {
            getString(R.string.install_success)
        } else {
            getString(R.string.install_fail, detail)
        }

        mUpdateInstalledLiveData.postValue(GmsInstallBean(userID, outcome.success(), result))
    }

    fun uninstallGms(
        userID: Int,
        mUpdateInstalledLiveData: MutableLiveData<GmsInstallBean>
    ) {
        var isSuccess = false
        if (BlackBoxCore.get().isInstallGms(userID)) {
            isSuccess = BlackBoxCore.get().uninstallGms(userID)
        }

        val result = if (isSuccess) {
            getString(R.string.uninstall_success)
        } else {
            getString(R.string.uninstall_fail)
        }

        val bean = GmsInstallBean(userID, isSuccess, result)

        mUpdateInstalledLiveData.postValue(bean)
    }
}
