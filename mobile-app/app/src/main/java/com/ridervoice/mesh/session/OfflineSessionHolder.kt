package com.ridervoice.mesh.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ridervoice.mesh.MeshLog
import com.ridervoice.mesh.OfflineMeshGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

data class StoredBackupMeta(
    val pairingCode: String,
    val createdAtMs: Long,
    val expiresAtMs: Long
)

interface KeyStoreSeam {
    fun deleteEntry(alias: String)
}

object AndroidKeyStoreSeam : KeyStoreSeam {
    override fun deleteEntry(alias: String) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        keyStore.deleteEntry(alias)
    }
}

interface BackupSessionStore {
    val isPersisted: Boolean get() = false
    fun saveBackup(pairingCode: String, createdAtMs: Long, expiresAtMs: Long)
    fun loadBackup(): StoredBackupMeta?
    fun clearBackup()
}

object NoOpBackupSessionStore : BackupSessionStore {
    override val isPersisted: Boolean get() = false
    override fun saveBackup(pairingCode: String, createdAtMs: Long, expiresAtMs: Long) {}
    override fun loadBackup(): StoredBackupMeta? = null
    override fun clearBackup() {}
}

class EncryptedPrefsBackupSessionStore(
    private val context: Context,
    private val prefsFactory: ((Context) -> SharedPreferences)? = null,
    private val keyStoreSeam: KeyStoreSeam = AndroidKeyStoreSeam,
    private val deleteStoreFile: (Context) -> Boolean = { it.applicationContext.deleteSharedPreferences(PREFS_FILE) }
) : BackupSessionStore {

    @Volatile private var ramStore: StoredBackupMeta? = null
    @Volatile private var lastWritePersisted: Boolean? = null

    private val prefsLock = Any()
    @Volatile private var prefsInitialized = false
    private var prefsInstance: SharedPreferences? = null

    private fun getPrefs(): SharedPreferences? {
        if (!prefsInitialized) {
            synchronized(prefsLock) {
                if (!prefsInitialized) {
                    prefsInstance = try {
                        prefsFactory?.invoke(context.applicationContext)
                            ?: createSafePreferences(context.applicationContext, keyStoreSeam)
                    } catch (e: Exception) {
                        MeshLog.w(TAG, "EncryptedSharedPreferences init failed; failing closed to RAM-only store: ${e.message}")
                        null
                    }
                    prefsInitialized = true
                }
            }
        }
        return prefsInstance
    }

    override val isPersisted: Boolean
        get() = lastWritePersisted ?: true

    override fun saveBackup(pairingCode: String, createdAtMs: Long, expiresAtMs: Long) {
        val p = getPrefs()
        if (p == null) {
            ramStore = StoredBackupMeta(pairingCode, createdAtMs, expiresAtMs)
            lastWritePersisted = false
            return
        }
        try {
            p.edit()
                .putString(KEY_CODE, pairingCode)
                .putLong(KEY_CREATED_AT, createdAtMs)
                .putLong(KEY_EXPIRES_AT, expiresAtMs)
                .apply()
            lastWritePersisted = true
        } catch (e: Exception) {
            MeshLog.w(TAG, "Failed to save backup session metadata to encrypted prefs, failing closed to RAM: ${e.message}")
            ramStore = StoredBackupMeta(pairingCode, createdAtMs, expiresAtMs)
            lastWritePersisted = false
        }
    }

    override fun loadBackup(): StoredBackupMeta? {
        val p = getPrefs() ?: return ramStore
        return try {
            val code = p.getString(KEY_CODE, null)?.takeIf { it.isNotBlank() } ?: return ramStore
            val created = p.getLong(KEY_CREATED_AT, 0L)
            val expires = p.getLong(KEY_EXPIRES_AT, 0L)
            if (created <= 0L || expires <= created) {
                clearBackup()
                null
            } else {
                StoredBackupMeta(code, created, expires)
            }
        } catch (e: Exception) {
            MeshLog.w(TAG, "Failed to load backup session metadata: ${e.message}")
            ramStore
        }
    }

    override fun clearBackup() {
        ramStore = null
        lastWritePersisted = null
        // 1) best effort through the opened prefs (also invalidates any in-memory copy)
        if (prefsInitialized) runCatching {
            prefsInstance?.edit()?.remove(KEY_CODE)?.remove(KEY_CREATED_AT)?.remove(KEY_EXPIRES_AT)?.commit()
        }
        // 2) always remove the file itself — no Keystore access needed, works on a fresh process
        runCatching { deleteStoreFile(context) }.onFailure { MeshLog.w(TAG, "backup file delete failed: ${it.javaClass.simpleName}") }
    }

    companion object {
        private const val TAG = "EncryptedPrefsBackupSessionStore"
        const val PREFS_FILE = "mesh_backup_session_secure_prefs"
        const val BACKUP_MASTER_KEY_ALIAS = "rvmesh_backup_master_key"
        private const val KEY_CODE = "backup_pairing_code"
        private const val KEY_CREATED_AT = "backup_created_at_ms"
        private const val KEY_EXPIRES_AT = "backup_expires_at_ms"

        internal fun createSafePreferences(
            context: Context,
            keyStoreSeam: KeyStoreSeam = AndroidKeyStoreSeam
        ): SharedPreferences? {
            return try {
                createEncryptedPrefs(context)
            } catch (e: Exception) {
                MeshLog.w(TAG, "EncryptedSharedPreferences init failed, self-healing: ${e.message}")
                try {
                    val parent = context.filesDir?.parentFile
                    if (parent != null) {
                        val prefsFile = File(parent, "shared_prefs/${PREFS_FILE}.xml")
                        if (prefsFile.exists()) {
                            prefsFile.delete()
                        }
                    }
                    try {
                        keyStoreSeam.deleteEntry(BACKUP_MASTER_KEY_ALIAS)
                    } catch (ksEx: Exception) {
                        MeshLog.w(TAG, "Keystore entry cleanup skipped: ${ksEx.message}")
                    }
                    createEncryptedPrefs(context)
                } catch (recoveryEx: Exception) {
                    MeshLog.w(TAG, "EncryptedSharedPreferences recovery failed, failing closed to RAM-only store (never plaintext): ${recoveryEx.message}")
                    null
                }
            }
        }

        internal fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context, BACKUP_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}

/**
 * In-memory holder for the active offline mesh session and online ride backup session.
 * Retains session across Activity configuration changes (rotations)
 * and optionally restores unexpired backup metadata via [BackupSessionStore] off the main thread.
 *
 * Dispatcher defaults to [Dispatchers.Default] for tests and standalone callers who don't supply one,
 * while production Hilt binds [Dispatchers.IO] via [com.ridervoice.di.MeshModule].
 */
@Singleton
class OfflineSessionHolder(
    private val backupStore: BackupSessionStore = NoOpBackupSessionStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val kdfIterations: Int = SessionKdf.DEFAULT_ITERATIONS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    @Inject
    constructor() : this(NoOpBackupSessionStore)

    private val restoreScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _current = MutableStateFlow<OfflineSession?>(null)
    private val _backup = MutableStateFlow<OfflineSession?>(null)
    val current: StateFlow<OfflineSession?> = _current.asStateFlow()
    val backup: StateFlow<OfflineSession?> = _backup.asStateFlow()

    @Volatile private var restoreJob: Job? = null
    @Volatile private var restoreCompleted: Boolean = false
    private val restoreLock = Any()

    internal val isRestoreCompletedForTest: Boolean get() = restoreCompleted

    fun restoreBackupIfAllowed(): Job? {
        if (!OfflineMeshGate.isOfflineAllowed()) return null
        synchronized(restoreLock) {
            if (restoreCompleted) return null
            val existing = restoreJob
            if (existing != null && existing.isActive) return existing
            val job = restoreScope.launch {
                if (OfflineMeshGate.isOfflineAllowed()) {
                    restoreFromStoreIfValid()
                    restoreCompleted = true
                }
            }
            restoreJob = job
            return job
        }
    }

    fun restoreFromStoreIfValid() {
        val meta = backupStore.loadBackup() ?: return
        val now = clock()
        if (now >= meta.expiresAtMs) {
            backupStore.clearBackup()
            return
        }
        val restored = OfflineSession.fromCode(
            rawCode = meta.pairingCode,
            now = meta.createdAtMs,
            iterations = kdfIterations,
            expiresAtOverride = meta.expiresAtMs
        )
        if (restored == null || restored.isExpired(now)) {
            restored?.close()
            backupStore.clearBackup()
            return
        }
        // Only populate if user hasn't already set a newer backup in RAM
        val updated = _backup.compareAndSet(null, restored)
        if (!updated) {
            restored.close()
        }
    }

    fun setCurrent(s: OfflineSession?) {
        val old = _current.getAndUpdate { s }
        if (old !== s) old?.close()
    }

    /**
     * Backup MUST be a separately derived OfflineSession instance (fromCode again, off-main),
     * so closing one never zeroes the other.
     */
    fun setBackup(s: OfflineSession?) {
        val old = _backup.getAndUpdate { s }
        if (old !== s) old?.close()
        if (s != null && !s.isClosed && !s.isExpired(clock())) {
            backupStore.saveBackup(s.pairingCode, s.createdAt, s.expiresAt)
        } else {
            backupStore.clearBackup()
        }
    }

    fun get(): OfflineSession? = _current.value
    fun set(s: OfflineSession?) = setCurrent(s)
    val isBackupPersisted: Boolean get() = backupStore.isPersisted
    fun getBackup(): OfflineSession? {
        val b = _backup.value ?: return null
        if (b.isExpired(clock())) {
            clearBackup()
            return null
        }
        return b
    }

    fun clearCurrent() = setCurrent(null)
    fun clearBackup() {
        setBackup(null)
        synchronized(restoreLock) {
            restoreJob = null
            restoreCompleted = false
        }
    }
    fun clearAll() {
        setCurrent(null)
        setBackup(null)
        synchronized(restoreLock) {
            restoreJob = null
            restoreCompleted = false
        }
    }
}
