package com.ridervoice.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ridervoice.update.AppReleaseInfo
import com.ridervoice.update.UpdateManager
import com.ridervoice.update.UpdateUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    val updateManager: UpdateManager
) : ViewModel() {

    val uiState: StateFlow<UpdateUiState> = updateManager.uiState

    val currentVersionName: String
        get() = updateManager.currentVersionName

    val currentVersionCode: Long
        get() = updateManager.currentVersionCode

    fun checkForUpdates() {
        updateManager.checkForUpdates(viewModelScope)
    }

    fun promptDownloadConfirmation(releaseInfo: AppReleaseInfo) {
        updateManager.promptDownloadConfirmation(releaseInfo)
    }

    fun startDownload(releaseInfo: AppReleaseInfo) {
        updateManager.startDownload(releaseInfo, viewModelScope)
    }

    fun cancelDownload() {
        updateManager.cancelDownload()
    }

    fun installUpdate(apkFile: File, releaseInfo: AppReleaseInfo) {
        updateManager.installUpdate(apkFile, releaseInfo)
    }

    fun openSettings() {
        updateManager.openUnknownSourcesSettings()
    }

    fun dismiss() {
        updateManager.dismissUpdate()
    }
}
