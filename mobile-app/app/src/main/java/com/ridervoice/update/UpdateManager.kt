package com.ridervoice.update

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
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
        private const val GITHUB_WEB_LATEST_URL = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"
        private const val PREFS_NAME = "app_update_prefs"
        private const val KEY_LAST_ETAG = "last_etag"
        private const val KEY_LAST_RELEASE_JSON = "last_release_json"
        private const val CACHE_TTL_MS = 60_000L // 60s in-memory TTL to avoid rapid spamming
        private const val TAG = "UpdateManager"
    }

    @Volatile private var lastCheckTimestamp = 0L
    @Volatile private var cachedReleaseInfo: AppReleaseInfo? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    /** APK waiting for the user to grant "install unknown apps"; installed automatically on return. */
    @Volatile private var pendingInstall: Pair<File, AppReleaseInfo>? = null

    /** Weak reference to currently foregrounded activity for clean package installer launching */
    @Volatile private var currentActivityRef: java.lang.ref.WeakReference<Activity>? = null

    /** Number of started activities; > 0 means the app is visible, so we may launch other screens. */
    @Volatile private var startedActivities = 0
    private val isAppVisible: Boolean get() = startedActivities > 0

    init {
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) { startedActivities++ }
                override fun onActivityStopped(activity: Activity) {
                    startedActivities = (startedActivities - 1).coerceAtLeast(0)
                }
                override fun onActivityResumed(activity: Activity) {
                    currentActivityRef = java.lang.ref.WeakReference(activity)
                    resumePendingInstallIfAllowed()
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityPaused(activity: Activity) {
                    if (currentActivityRef?.get() === activity) {
                        currentActivityRef = null
                    }
                }
                override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
        )
    }

    /** Called when the user comes back from Settings: if they allowed it, go straight to the installer. */
    private fun resumePendingInstallIfAllowed() {
        val pending = pendingInstall ?: return
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
        if (allowed) {
            pendingInstall = null
            _uiState.value = UpdateUiState.ReadyToInstall(
                releaseInfo = pending.second,
                apkFile = pending.first
            )
            installUpdate(pending.first, pending.second)
        }
    }

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

    fun checkForUpdates(scope: CoroutineScope, forceRefresh: Boolean = false) {
        if (_uiState.value is UpdateUiState.Checking || _uiState.value is UpdateUiState.Downloading) return

        val now = System.currentTimeMillis()
        val cached = cachedReleaseInfo
        if (!forceRefresh && cached != null && (now - lastCheckTimestamp < CACHE_TTL_MS)) {
            val installedVer = AppVersion.parse(currentVersionName)
            val latestVer = AppVersion.parse(cached.versionName)
            if (latestVer.isNewerThan(installedVer)) {
                _uiState.value = UpdateUiState.UpdateAvailable(
                    releaseInfo = cached,
                    currentVersion = currentVersionName
                )
            } else {
                _uiState.value = UpdateUiState.UpToDate(currentVersion = currentVersionName)
            }
            return
        }

        _uiState.value = UpdateUiState.Checking

        scope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedEtag = prefs.getString(KEY_LAST_ETAG, null)

            var releaseInfo: AppReleaseInfo? = null
            var rateLimited = false

            try {
                val reqBuilder = Request.Builder()
                    .url(GITHUB_API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "RiderVoice-AppUpdater")

                if (!cachedEtag.isNullOrBlank()) {
                    reqBuilder.header("If-None-Match", cachedEtag)
                }

                val response = httpClient.newCall(reqBuilder.build()).execute()
                val code = response.code

                if (code == 304) {
                    val cachedJson = prefs.getString(KEY_LAST_RELEASE_JSON, null)
                    if (!cachedJson.isNullOrBlank()) {
                        try {
                            val cachedRel = gson.fromJson(cachedJson, GitHubRelease::class.java)
                            releaseInfo = parseReleaseToInfo(cachedRel)
                        } catch (_: Exception) {}
                    }
                    if (releaseInfo == null) {
                        _uiState.value = UpdateUiState.UpToDate(currentVersion = currentVersionName)
                        return@launch
                    }
                } else if (code == 403 || code == 429) {
                    rateLimited = true
                    Log.w(TAG, "GitHub REST API rate limit encountered ($code). Falling back to direct web release redirect.")
                } else if (code == 200) {
                    val newEtag = response.header("ETag")
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        if (!newEtag.isNullOrBlank()) {
                            prefs.edit().putString(KEY_LAST_ETAG, newEtag).putString(KEY_LAST_RELEASE_JSON, body).apply()
                        }
                        val release = gson.fromJson(body, GitHubRelease::class.java)
                        releaseInfo = parseReleaseToInfo(release)
                    }
                } else if (code == 404) {
                    _uiState.value = UpdateUiState.Error("No releases published yet on repository.")
                    return@launch
                }
            } catch (e: Exception) {
                Log.w(TAG, "GitHub REST API call failed (${e.message}). Falling back to web release redirect.")
            }

            // Resilient Fallback: If GitHub API was rate limited or failed, fetch from the official web redirect
            // https://github.com/BuzyU/Rider_APP/releases/latest which has NO REST API rate limit!
            if (releaseInfo == null) {
                try {
                    releaseInfo = fetchReleaseViaWebRedirect()
                    if (releaseInfo != null) {
                        Log.i(TAG, "Successfully resolved release ${releaseInfo.tagName} via web redirect fallback.")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Web redirect fallback failed: ${e.message}")
                }
            }

            if (releaseInfo == null) {
                if (rateLimited) {
                    _uiState.value = UpdateUiState.Error("GitHub API rate limit exceeded and fallback failed. Please try again later.")
                } else {
                    _uiState.value = UpdateUiState.Error("Unable to check for updates. Please check your internet connection.")
                }
                return@launch
            }

            lastCheckTimestamp = System.currentTimeMillis()
            cachedReleaseInfo = releaseInfo

            val releaseVersion = releaseInfo.versionName
            val latestVer = AppVersion.parse(releaseVersion)
            val installedVer = AppVersion.parse(currentVersionName)

            if (latestVer.isNewerThan(installedVer)) {
                val updatesDir = File(context.cacheDir, "updates")
                val existingApk = File(updatesDir, "Rider_APP-v${releaseVersion}.apk")
                if (existingApk.exists() && existingApk.length() > 0) {
                    val expectedHash = releaseInfo.sha256Checksum
                    val matchesChecksum = if (!expectedHash.isNullOrBlank()) {
                        calculateSha256(existingApk).equals(expectedHash.trim(), ignoreCase = true)
                    } else true
                    if (matchesChecksum) {
                        _uiState.value = UpdateUiState.ReadyToInstall(
                            releaseInfo = releaseInfo,
                            apkFile = existingApk
                        )
                        return@launch
                    }
                }

                _uiState.value = UpdateUiState.UpdateAvailable(
                    releaseInfo = releaseInfo,
                    currentVersion = currentVersionName
                )
            } else {
                _uiState.value = UpdateUiState.UpToDate(currentVersion = currentVersionName)
            }
        }
    }

    private fun parseReleaseToInfo(release: GitHubRelease): AppReleaseInfo? {
        val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null

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
                Log.w(TAG, "Failed to download sha256 asset: ${e.message}")
            }
        }

        if (sha256Checksum == null && !release.body.isNullOrBlank()) {
            val match = Regex("[a-fA-F0-9]{64}").find(release.body)
            sha256Checksum = match?.value
        }

        val releaseVersion = release.tagName.removePrefix("v").removePrefix("V")
        return AppReleaseInfo(
            versionName = releaseVersion,
            tagName = release.tagName,
            apkFileName = apkAsset.name,
            downloadUrl = apkAsset.downloadUrl,
            apkSizeBytes = apkAsset.size,
            releaseNotes = release.body?.ifBlank { "Performance improvements and bug fixes." } ?: "Performance improvements and bug fixes.",
            publishedAt = release.publishedAt ?: "Recently",
            sha256Checksum = sha256Checksum
        )
    }

    private fun fetchReleaseViaWebRedirect(): AppReleaseInfo? {
        val nonRedirectingClient = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val req = Request.Builder()
            .url(GITHUB_WEB_LATEST_URL)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
            .build()

        val resp = nonRedirectingClient.newCall(req).execute()
        val location = resp.header("Location") ?: return null

        // Location header format: https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.5
        val tagName = location.substringAfterLast("/").trim()
        if (tagName.isBlank() || !tagName.startsWith("v", ignoreCase = true)) {
            return null
        }

        val releaseVersion = tagName.removePrefix("v").removePrefix("V")
        val apkFileName = "Rider_APP-v${releaseVersion}.apk"
        val apkDownloadUrl = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/download/$tagName/$apkFileName"
        val shaDownloadUrl = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/download/$tagName/$apkFileName.sha256"

        var sha256Checksum: String? = null
        try {
            val shaReq = Request.Builder().url(shaDownloadUrl).build()
            val shaResp = httpClient.newCall(shaReq).execute()
            if (shaResp.isSuccessful) {
                val content = shaResp.body?.string()?.trim()
                if (!content.isNullOrBlank()) {
                    val match = Regex("[a-fA-F0-9]{64}").find(content)
                    sha256Checksum = match?.value
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct sha256 fetch failed: ${e.message}")
        }

        var apkSizeBytes = 0L
        try {
            val headReq = Request.Builder().url(apkDownloadUrl).head().build()
            val headResp = httpClient.newCall(headReq).execute()
            if (headResp.isSuccessful) {
                apkSizeBytes = headResp.header("Content-Length")?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct HEAD content-length fetch failed: ${e.message}")
        }

        return AppReleaseInfo(
            versionName = releaseVersion,
            tagName = tagName,
            apkFileName = apkFileName,
            downloadUrl = apkDownloadUrl,
            apkSizeBytes = apkSizeBytes,
            releaseNotes = "Performance improvements and bug fixes for RiderVoice $tagName.",
            publishedAt = "Latest Release",
            sha256Checksum = sha256Checksum
        )
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

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            ensureActive()

                            output.write(buffer, 0, bytesRead)
                            totalDownloaded += bytesRead
                            bytesSinceLastTimestamp += bytesRead

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastTimestamp
                            if (elapsed >= 800) {
                                val speedBps = (bytesSinceLastTimestamp.toDouble() / (elapsed / 1000.0)).toLong()
                                val currentSpeedText = formatSpeed(speedBps)
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

            // Go straight to install (or to the permission screen) without another tap.
            // If the app is in the background Android blocks activity launches, so the
            // ReadyToInstall dialog stays as the fallback.
            if (isAppVisible) installUpdate(targetFile, releaseInfo)

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
                pendingInstall = apkFile to releaseInfo
                _uiState.value = UpdateUiState.RequestUnknownSourcesPermission(apkFile, releaseInfo)
                // Open the "Allow from this source" screen right away; the install resumes
                // automatically when the user returns with the permission granted.
                if (isAppVisible) openUnknownSourcesSettings()
                return
            }
        }
        pendingInstall = null

        try {
            _uiState.value = UpdateUiState.Installing(apkFile, releaseInfo)

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }

            // Explicitly grant read uri permission to all resolving installer packages
            val resInfoList = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                context.grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            val currentAct = currentActivityRef?.get()
            if (currentAct != null && !currentAct.isFinishing && !currentAct.isDestroyed) {
                currentAct.startActivity(intent)
            } else {
                context.startActivity(intent)
            }

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
        pendingInstall = null
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
