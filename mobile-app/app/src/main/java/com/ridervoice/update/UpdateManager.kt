package com.ridervoice.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.ridervoice.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val GITHUB_REPO_OWNER = "BuzyU"
        const val GITHUB_REPO_NAME = "Rider_APP"
        private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    val currentVersionName: String
        get() = BuildConfig.VERSION_NAME

    val currentVersionCode: Long
        get() {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
                }
            } catch (e: Exception) {
                BuildConfig.VERSION_CODE.toLong()
            }
        }

    fun checkForUpdates(scope: CoroutineScope) {
        if (_uiState.value is UpdateUiState.Checking || _uiState.value is UpdateUiState.Downloading) return

        _uiState.value = UpdateUiState.Checking

        scope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(GITHUB_API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "RiderVoice-AppUpdater")
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string()

                if (response.code == 404) {
                    _uiState.value = UpdateUiState.Error("No releases published yet on repository.")
                    return@launch
                }

                if (response.code == 403 || response.code == 429) {
                    _uiState.value = UpdateUiState.Error("GitHub API rate limit exceeded. Please try again in a few moments.")
                    return@launch
                }

                if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                    _uiState.value = UpdateUiState.Error("Update server returned error (${response.code}).")
                    return@launch
                }

                val release = gson.fromJson(responseBody, GitHubRelease::class.java)

                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                if (apkAsset == null) {
                    _uiState.value = UpdateUiState.Error("The latest release (${release.tagName}) does not contain an APK asset.")
                    return@launch
                }

                var sha256Checksum: String? = null
                val shaAsset = release.assets.firstOrNull {
                    it.name.endsWith(".sha256", ignoreCase = true) || it.name.contains("checksum", ignoreCase = true)
                }

                if (shaAsset != null) {
                    try {
                        val shaReq = Request.Builder().url(shaAsset.downloadUrl).build()
                        val shaResp = httpClient.newCall(shaReq).execute()
                        val shaContent = shaResp.body?.string()?.trim()
                        if (!shaContent.isNullOrBlank()) {

                            val match = Regex("[a-fA-F0-9]{64}").find(shaContent)
                            sha256Checksum = match?.value
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (sha256Checksum == null && !release.body.isNullOrBlank()) {
                    val match = Regex("[a-fA-F0-9]{64}").find(release.body)
                    sha256Checksum = match?.value
                }

                val releaseVersion = release.tagName.removePrefix("v").removePrefix("V")
                val latestVer = AppVersion.parse(releaseVersion)
                val installedVer = AppVersion.parse(currentVersionName)

                val releaseInfo = AppReleaseInfo(
                    versionName = releaseVersion,
                    tagName = release.tagName,
                    apkFileName = apkAsset.name,
                    downloadUrl = apkAsset.downloadUrl,
                    apkSizeBytes = apkAsset.size,
                    releaseNotes = release.body?.ifBlank { "Performance improvements and bug fixes." } ?: "Performance improvements and bug fixes.",
                    publishedAt = release.publishedAt ?: "Recently",
                    sha256Checksum = sha256Checksum
                )

                if (latestVer.isNewerThan(installedVer)) {
                    _uiState.value = UpdateUiState.UpdateAvailable(
                        releaseInfo = releaseInfo,
                        currentVersion = currentVersionName
                    )
                } else {
                    _uiState.value = UpdateUiState.UpToDate(currentVersion = currentVersionName)
                }

            } catch (e: java.net.UnknownHostException) {
                _uiState.value = UpdateUiState.Error("Unable to check for updates. Please check your internet connection.")
            } catch (e: Exception) {
                _uiState.value = UpdateUiState.Error("Update check failed: ${e.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    fun promptDownloadConfirmation(releaseInfo: AppReleaseInfo) {
        _uiState.value = UpdateUiState.ConfirmDownload(
            releaseInfo = releaseInfo,
            currentVersion = currentVersionName
        )
    }

    fun startDownload(releaseInfo: AppReleaseInfo, scope: CoroutineScope) {
        downloadJob?.cancel()

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }

        updatesDir.listFiles()?.forEach { try { it.delete() } catch (ignored: Exception) {} }

        val requiredBytes = if (releaseInfo.apkSizeBytes > 0) releaseInfo.apkSizeBytes + 20_000_000 else 80_000_000
        if (updatesDir.usableSpace < requiredBytes) {
            _uiState.value = UpdateUiState.Error("Insufficient storage space on device to download update.")
            return
        }

        val tempFile = File(updatesDir, "update_temp_${System.currentTimeMillis()}.apk")

        _uiState.value = UpdateUiState.Downloading(
            releaseInfo = releaseInfo,
            downloadedBytes = 0L,
            totalBytes = releaseInfo.apkSizeBytes,
            progressPercent = 0,
            speedText = "Connecting..."
        )

        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(releaseInfo.downloadUrl)
                    .header("Accept", "application/octet-stream")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    _uiState.value = UpdateUiState.Error("Download server returned HTTP ${response.code}.")
                    return@launch
                }

                val body = response.body ?: throw IllegalStateException("Empty response body from download server")
                val totalLength = if (body.contentLength() > 0) body.contentLength() else releaseInfo.apkSizeBytes

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var totalDownloaded = 0L
                        var lastTimestamp = System.currentTimeMillis()
                        var bytesSinceLastTimestamp = 0L
                        var currentSpeedText = "Calculating..."

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            ensureActive()

                            output.write(buffer, 0, bytesRead)
                            totalDownloaded += bytesRead
                            bytesSinceLastTimestamp += bytesRead

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastTimestamp
                            if (elapsed >= 800) {
                                val speedBps = (bytesSinceLastTimestamp.toDouble() / (elapsed / 1000.0)).toLong()
                                currentSpeedText = formatSpeed(speedBps)
                                lastTimestamp = now
                                bytesSinceLastTimestamp = 0L

                                val progressPercent = if (totalLength > 0) {
                                    ((totalDownloaded * 100) / totalLength).toInt().coerceIn(0, 99)
                                } else 0

                                _uiState.value = UpdateUiState.Downloading(
                                    releaseInfo = releaseInfo,
                                    downloadedBytes = totalDownloaded,
                                    totalBytes = totalLength,
                                    progressPercent = progressPercent,
                                    speedText = currentSpeedText
                                )
                            }
                        }
                    }
                }

                verifyAndPrepareInstall(tempFile, releaseInfo)

            } catch (e: CancellationException) {
                tempFile.delete()
                _uiState.value = UpdateUiState.Idle
            } catch (e: Exception) {
                tempFile.delete()
                _uiState.value = UpdateUiState.Error("Download failed: ${e.localizedMessage ?: "Network interrupted"}")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _uiState.value = UpdateUiState.Idle
    }

    private fun verifyAndPrepareInstall(tempFile: File, releaseInfo: AppReleaseInfo) {
        _uiState.value = UpdateUiState.Verifying(releaseInfo)

        try {

            if (!tempFile.exists() || tempFile.length() <= 0) {
                tempFile.delete()
                _uiState.value = UpdateUiState.Error("Downloaded update file is missing or empty.")
                return
            }

            if (!releaseInfo.sha256Checksum.isNullOrBlank()) {
                val computedHash = calculateSha256(tempFile)
                if (!computedHash.equals(releaseInfo.sha256Checksum.trim(), ignoreCase = true)) {
                    tempFile.delete()
                    _uiState.value = UpdateUiState.Error("Update integrity verification failed (checksum mismatch). Download rejected.")
                    return
                }
            }

            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    tempFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(tempFile.absolutePath, 0)
            }

            if (packageInfo == null) {
                tempFile.delete()
                _uiState.value = UpdateUiState.Error("Downloaded file is not a valid Android application package (APK).")
                return
            }

            if (packageInfo.packageName != context.packageName) {
                tempFile.delete()
                _uiState.value = UpdateUiState.Error(
                    "Security violation: Package ID (${packageInfo.packageName}) does not match RiderVoice (${context.packageName})."
                )
                return
            }

            val archiveVersion = AppVersion.parse(packageInfo.versionName)
            val installedVersion = AppVersion.parse(currentVersionName)

            val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            if (archiveVersion < installedVersion || archiveVersionCode < currentVersionCode) {
                tempFile.delete()
                _uiState.value = UpdateUiState.Error(
                    "Downgrade attack rejected: Downloaded version ($archiveVersion / code $archiveVersionCode) is older than currently installed ($installedVersion / code $currentVersionCode)."
                )
                return
            }

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val finalApkFile = File(updatesDir, "Rider_APP-v${releaseInfo.versionName}.apk")
            if (finalApkFile.exists()) finalApkFile.delete()

            val success = tempFile.renameTo(finalApkFile)
            val targetFile = if (success) finalApkFile else tempFile

            _uiState.value = UpdateUiState.ReadyToInstall(
                releaseInfo = releaseInfo,
                apkFile = targetFile
            )

        } catch (e: Exception) {
            tempFile.delete()
            _uiState.value = UpdateUiState.Error("Verification error: ${e.localizedMessage ?: "Unknown verification fault"}")
        }
    }

    fun installUpdate(apkFile: File, releaseInfo: AppReleaseInfo) {
        if (!apkFile.exists()) {
            _uiState.value = UpdateUiState.Error("Installation file could not be found.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                _uiState.value = UpdateUiState.RequestUnknownSourcesPermission(apkFile, releaseInfo)
                return
            }
        }

        try {
            _uiState.value = UpdateUiState.Installing

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)

        } catch (e: Exception) {
            _uiState.value = UpdateUiState.Error("Failed to launch package installer: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    fun openUnknownSourcesSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    fun dismissUpdate() {
        _uiState.value = UpdateUiState.Idle
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSecond.toDouble() / (1024 * 1024))
            bytesPerSecond >= 1024 -> "%d KB/s".format(bytesPerSecond / 1024)
            else -> "$bytesPerSecond B/s"
        }
    }
}
