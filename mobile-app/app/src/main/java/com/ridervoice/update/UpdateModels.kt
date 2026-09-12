package com.ridervoice.update

import com.google.gson.annotations.SerializedName
import java.io.File

data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("name") val name: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("published_at") val publishedAt: String?,
    @SerializedName("assets") val assets: List<GitHubAsset> = emptyList()
)

data class GitHubAsset(
    @SerializedName("name") val name: String,
    @SerializedName("size") val size: Long,
    @SerializedName("browser_download_url") val downloadUrl: String,
    @SerializedName("content_type") val contentType: String? = null
)

data class AppReleaseInfo(
    val versionName: String,
    val tagName: String,
    val apkFileName: String,
    val downloadUrl: String,
    val apkSizeBytes: Long,
    val releaseNotes: String,
    val publishedAt: String,
    val sha256Checksum: String? = null
) {
    val formattedSize: String
        get() {
            if (apkSizeBytes <= 0) return "Unknown size"
            val mb = apkSizeBytes.toDouble() / (1024.0 * 1024.0)
            return "%.1f MB".format(mb)
        }
}

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    object Checking : UpdateUiState()
    data class UpToDate(val currentVersion: String) : UpdateUiState()
    data class UpdateAvailable(
        val releaseInfo: AppReleaseInfo,
        val currentVersion: String
    ) : UpdateUiState()
    data class ConfirmDownload(
        val releaseInfo: AppReleaseInfo,
        val currentVersion: String
    ) : UpdateUiState()
    data class Downloading(
        val releaseInfo: AppReleaseInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progressPercent: Int,
        val speedText: String
    ) : UpdateUiState()
    data class Verifying(val releaseInfo: AppReleaseInfo) : UpdateUiState()
    data class ReadyToInstall(
        val releaseInfo: AppReleaseInfo,
        val apkFile: File
    ) : UpdateUiState()
    data class RequestUnknownSourcesPermission(
        val apkFile: File,
        val releaseInfo: AppReleaseInfo
    ) : UpdateUiState()
    object Installing : UpdateUiState()
    data class Error(val message: String, val canRetry: Boolean = true) : UpdateUiState()
}
