package com.terista.environment.view.apps

import androidx.lifecycle.MutableLiveData
import com.terista.environment.bean.AppInfo
import com.terista.environment.data.AppsRepository
import com.terista.environment.view.base.BaseViewModel
import android.util.Log


class AppsViewModel(private val repo: AppsRepository) : BaseViewModel() {

    val appsLiveData = MutableLiveData<List<AppInfo>>()

    val resultLiveData = MutableLiveData<String>()

    val launchLiveData = MutableLiveData<Boolean>()

    
    val updateSortLiveData = MutableLiveData<Boolean>()

    fun getInstalledApps(userId: Int) {
        launchOnUI {
            repo.getVmInstallList(userId, appsLiveData)
        }
    }
    
    
    fun getInstalledAppsWithRetry(userId: Int) {
    launchOnUI {
        var retryCount = 0
        val maxRetries = 5

        while (retryCount < maxRetries) {

            repo.getVmInstallList(userId, appsLiveData)

            val currentApps = appsLiveData.value

            if (!currentApps.isNullOrEmpty()) {
                Log.d("AppsViewModel", "Apps loaded successfully")
                break
            }

            retryCount++
            Log.d("AppsViewModel", "Retrying load... ($retryCount/$maxRetries)")

            kotlinx.coroutines.delay(500)
        }
    }
}

    fun install(source: String, userID: Int) {
        launchOnUI {
            repo.installApk(source, userID, resultLiveData)
        }
    }

    fun unInstall(packageName: String, userID: Int) {
        launchOnUI {
            repo.unInstall(packageName, userID, resultLiveData)
        }
    }

    fun clearApkData(packageName: String,userID: Int){
        launchOnUI {
            repo.clearApkData(packageName,userID,resultLiveData)
        }
    }

    fun launchApk(packageName: String, userID: Int) {
        launchOnUI {
            repo.launchApk(packageName, userID, launchLiveData)
        }
    }

    fun updateApkOrder(userID: Int,dataList:List<AppInfo>){
        launchOnUI {
            repo.updateApkOrder(userID,dataList)
        }
    }

    fun previewInstalledList(userId: Int) {
    launchOnUI {
        try {
            repo.getVmInstallList(userId, appsLiveData)
        } catch (e: Exception) {
            Log.e("AppsViewModel", "Preview load failed: ${e.message}")
        }
    }
    }
}
