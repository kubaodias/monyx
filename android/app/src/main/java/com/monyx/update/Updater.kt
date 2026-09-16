package com.monyx.update

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.monyx.BuildConfig
import com.monyx.sync.Api
import com.monyx.sync.AvailableUpdate
import com.monyx.sync.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object CheckFailed : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class NeedsPermission(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val bytes: Long) : UpdateState
    /** Handed to the system installer; its own confirmation window is on screen. */
    data class Installing(val update: AvailableUpdate) : UpdateState
    data class Failed(val update: AvailableUpdate, val reason: Reason) : UpdateState

    enum class Reason { OFFLINE, UNAVAILABLE, CORRUPT, NOT_THIS_APP, CANCELLED, INSTALL_FAILED }
}

/**
 * Offers, downloads and installs a newer release. Never forces one.
 *
 * One per process, held by MonyxApp, because the install result arrives in a
 * BroadcastReceiver that has no screen to report to — it reports here, and
 * whichever screen is showing reads [state].
 *
 * Debug builds never check. They are `com.monyx.debug`, signed with the debug
 * key, and a release APK is neither the same package nor the same signer — every
 * offer would end in a refusal. See
 * docs/decisions/0020-the-app-updates-itself-and-asks-first.md.
 */
class Updater(private val app: Application, private val session: Session) {

    val enabled: Boolean = !BuildConfig.DEBUG

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var launchChecked = false

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Whether the dialog is up. Separate from [state] so "Later" hides it without forgetting the update. */
    private val _prompt = MutableStateFlow(false)
    val prompt: StateFlow<Boolean> = _prompt.asStateFlow()

    private val updatesDir get() = File(app.cacheDir, "updates")

    /**
     * Once per process, quietly: no spinner, and a failure is simply no prompt.
     * Somebody who opened the app to enter a coffee did not ask whether the
     * network is up.
     */
    fun checkOnLaunch() {
        if (!enabled || launchChecked) return
        launchChecked = true
        // Whatever is left from an earlier download. After a successful update
        // this process is the new version, and the old APK is dead weight.
        updatesDir.deleteRecursively()
        check(quiet = true)
    }

    fun checkNow() {
        if (enabled) check(quiet = false)
    }

    private fun check(quiet: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            val token = session.token() ?: return@launch
            if (!quiet) _state.value = UpdateState.Checking
            val result = runCatching { Api.latestRelease(token, BuildConfig.VERSION_CODE) }
            val update = result.getOrNull()?.update?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            _state.value = when {
                update != null -> UpdateState.Available(update)
                result.isFailure -> if (quiet) UpdateState.Idle else UpdateState.CheckFailed
                else -> if (quiet) UpdateState.Idle else UpdateState.UpToDate
            }
            if (update != null && quiet) _prompt.value = true
        }
    }

    fun showPrompt() {
        _prompt.value = true
    }

    /**
     * "Later". A download in flight keeps going — the dialog is only a view of
     * it — but a failure or a permission request goes back to a plain offer, so
     * the next time the dialog opens it asks again rather than repeating an error.
     */
    fun dismiss() {
        _prompt.value = false
        when (val current = _state.value) {
            is UpdateState.Failed -> _state.value = UpdateState.Available(current.update)
            is UpdateState.NeedsPermission -> _state.value = UpdateState.Available(current.update)
            else -> Unit
        }
    }

    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    /** Called on resume: if the permission was just granted, carry straight on. */
    fun resumeIfPermitted() {
        val current = _state.value
        if (current is UpdateState.NeedsPermission && canInstall()) install(current.update)
    }

    fun install(update: AvailableUpdate) {
        if (job?.isActive == true) return
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(update)
            return
        }
        job = scope.launch {
            _state.value = UpdateState.Downloading(update, 0)
            val token = session.token()
            if (token == null) {
                _state.value = UpdateState.Failed(update, UpdateState.Reason.UNAVAILABLE)
                return@launch
            }
            updatesDir.deleteRecursively()
            updatesDir.mkdirs()
            val file = File(updatesDir, "monyx-${update.versionCode}.apk")

            val link = runCatching { Api.releaseDownload(token, update.versionCode) }
            val url = link.getOrElse { error ->
                _state.value = UpdateState.Failed(update, reasonFor(error))
                return@launch
            }.url

            var lastReported = 0L
            val downloaded = runCatching {
                Api.download(url, file) { bytes ->
                    // Every 256 KiB, not every buffer: a recomposition per 64 KiB
                    // chunk is forty of them for one APK and none of them visible.
                    if (bytes - lastReported >= 256 * 1024 || bytes == update.sizeBytes) {
                        lastReported = bytes
                        _state.value = UpdateState.Downloading(update, bytes)
                    }
                }
            }
            if (downloaded.isFailure) {
                file.delete()
                _state.value = UpdateState.Failed(update, UpdateState.Reason.OFFLINE)
                return@launch
            }

            val problem = ApkChecks.verifyBytes(file, update.sizeBytes, update.sha256)
                ?: verifyArchive(file)
            if (problem != null) {
                file.delete()
                _state.value = UpdateState.Failed(
                    update,
                    when (problem) {
                        ApkChecks.Problem.SIZE, ApkChecks.Problem.CHECKSUM -> UpdateState.Reason.CORRUPT
                        else -> UpdateState.Reason.NOT_THIS_APP
                    },
                )
                return@launch
            }

            _state.value = UpdateState.Installing(update)
            runCatching { commit(file, update) }.onFailure {
                _state.value = UpdateState.Failed(update, UpdateState.Reason.INSTALL_FAILED)
            }
        }
    }

    /** From [InstallResultReceiver]. */
    internal fun onInstallResult(status: Int) {
        val current = _state.value
        val update = when (current) {
            is UpdateState.Installing -> current.update
            is UpdateState.Available -> current.update
            else -> return
        }
        _state.value = when (status) {
            // Rarely seen: on success the system replaces this process.
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Failed(update, UpdateState.Reason.CANCELLED)
            else -> UpdateState.Failed(update, UpdateState.Reason.INSTALL_FAILED)
        }
    }

    private fun reasonFor(error: Throwable): UpdateState.Reason =
        if (error is Api.ApiException) UpdateState.Reason.UNAVAILABLE else UpdateState.Reason.OFFLINE

    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File): ApkChecks.Problem? {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return ApkChecks.Problem.PACKAGE
        val installed = pm.getPackageInfo(app.packageName, flags)
        return ApkChecks.verifyIdentity(
            archivePackage = archive.packageName,
            archiveVersionCode = versionCodeOf(archive),
            archiveSigners = signersOf(archive),
            installedPackage = app.packageName,
            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
            installedSigners = signersOf(installed),
        )
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signersOf(info: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return certs.orEmpty().map { ApkChecks.certificateDigest(it.toByteArray()) }.toSet()
    }

    /**
     * A PackageInstaller session rather than an ACTION_VIEW intent: no
     * FileProvider to declare, and the result comes back as a status instead of
     * silence. The system still shows its own confirmation — an ordinary app
     * cannot install without one, and this is not asking to.
     */
    private fun commit(file: File, update: AvailableUpdate) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { installSession ->
            installSession.openWrite("monyx-${update.versionCode}.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                installSession.fsync(out)
            }
            // MUTABLE is required: the installer fills the status and the
            // confirmation intent into this PendingIntent's extras.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                app,
                sessionId,
                Intent(app, InstallResultReceiver::class.java).setPackage(app.packageName),
                flags,
            )
            installSession.commit(callback.intentSender)
        }
    }
}
