package com.mantz_it.rfanalyzer.digiext

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.mantz_it.rfanalyzer.BuildConfig
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalExtensionApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * <h1>RF Analyzer - Digital Availability</h1>
 *
 * Module:      DigitalAvailability.kt
 * Description: Whether digital modes can be received, and why not - the one state every digital
 *              entry point reads (Digital tab, mode sheet, quick start, the Demodulation tab's
 *              Analog/Digital switch, Stereo & RDS) and the service obeys, so nothing can start a
 *              mode that silently receives nothing. Stored data stays viewable in every state.
 *
 * Two parts: [DigitalAvailability] (can it run at all: the extension's package and protocol) and
 * [DigitalLink] (while a mode that needs the extension runs: is the connection alive). A broken
 * connection is never silent - see AnalyzerService's watchdog, which turns a lost or unresponsive
 * extension into [DigitalLink.INTERRUPTED] and, if reconnecting fails, [DigitalLink.FAILED].
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * Whether digital features are shown - the one switch every digital entry point reads: the Digital
 * tab, the digital modes in every mode list, Stereo & RDS, the digital trial, the digital station
 * list, What's New. Hidden means hidden in the UI only: stored data and settings stay, a station of
 * a digital mode is tuned with the analog mode its signal is heard with ([shownOrAnalog]), and the
 * service never binds an extension that isn't there (DigitalAvailability).
 *
 * - F-Droid (FOSS) builds: never. The extension only exists on Google Play and only talks to apps
 *   signed with its key (F-Droid flags apps that advertise non-free add-ons); About says so in one
 *   sentence. FOSS debug builds are the same, so they show what the release shows.
 * - Google Play builds: always once the extension is public (`BuildConfig.DIGITAL_LAUNCHED`, from
 *   RF Analyzer 2.4 on). Before - RF Analyzer 2.3, while the extension is in its closed test -
 *   only where the extension is installed, so nobody sees features they can't get yet. Debug
 *   builds behave the same.
 *
 * [shown] is Compose state, so a composable that reads it follows an install or removal of the
 * extension; [shownFlow] is the same for flows. Updated at start (RFAnalyzerApplication) and on
 * every package check (DigitalExtensionStatus: the activity coming back, package broadcasts).
 */
object DigitalFeatures {
    /** Whether this build can have digital features at all (false: F-Droid). */
    val inBuild: Boolean = !BuildConfig.IS_FOSS

    private val shownState = mutableStateOf(false)
    private val _shownFlow = MutableStateFlow(false)
    val shown: Boolean get() = shownState.value
    val shownFlow: StateFlow<Boolean> = _shownFlow

    fun update(extensionInstalled: Boolean) {
        val shown = digitalFeaturesShown(inBuild, BuildConfig.DIGITAL_LAUNCHED, extensionInstalled)
        shownState.value = shown
        _shownFlow.value = shown
    }
}

internal fun digitalFeaturesShown(inBuild: Boolean, launched: Boolean, extensionInstalled: Boolean): Boolean =
    inBuild && (launched || extensionInstalled)

/** Whether [this] mode is offered (in mode lists, filters, the bookmark editor). */
fun DemodulationMode.shown(digitalShown: Boolean = DigitalFeatures.shown): Boolean = !isDigitalExtension || digitalShown

/** What a station of [this] mode is tuned with: itself, or while digital features are hidden, the
 *  analog mode its signal is heard with (null: keep the current mode). */
fun DemodulationMode.shownOrAnalog(digitalShown: Boolean = DigitalFeatures.shown): DemodulationMode? = when {
    shown(digitalShown) -> this
    this == DemodulationMode.FT8 || this == DemodulationMode.RTTY || this == DemodulationMode.PSK31 -> DemodulationMode.USB
    this == DemodulationMode.POCSAG || this == DemodulationMode.R09 || this == DemodulationMode.AX25 -> DemodulationMode.NFM
    else -> null
}

enum class DigitalAvailability {
    /** This build has no digital features ([DigitalFeatures]) - nothing about them is shown. */
    NOT_IN_BUILD,
    NOT_INSTALLED,
    /** RFA Digital is signed with another key than this RF Analyzer (a self-built one): Android
     *  doesn't let them connect. */
    FOREIGN_SIGNATURE,
    UPDATE_EXTENSION,
    UPDATE_APP,
    /** Installed and compatible, but neither the full version nor digital trial time left
     *  (AppStateRepository.digitalFeaturesUnlocked). Each start still decodes for a grace period
     *  (MainViewModel, 30 s), so the extension can be seen at work before buying. */
    LOCKED,
    READY,
}

/** The extension is bound and decodes - when [DigitalAvailability.LOCKED] only until the grace period ends. */
val DigitalAvailability.decodes: Boolean get() = this == DigitalAvailability.READY || this == DigitalAvailability.LOCKED

/** The connection while a mode that needs the extension runs ([IDLE] otherwise). */
enum class DigitalLink {
    IDLE,
    CONNECTING,
    CONNECTED,
    /** Lost or unresponsive - reconnecting. */
    INTERRUPTED,
    /** Reconnecting failed: nothing is decoded until the user retries or restarts. */
    FAILED,
}

data class DigitalStatus(
    val availability: DigitalAvailability = DigitalAvailability.READY,
    val link: DigitalLink = DigitalLink.IDLE,
    /** The digital trial's decoding time left, while it counts (null with the full version). */
    val trialSecondsLeft: Int? = null,
)

/** Whether [mode] (with Stereo & RDS for WFM) needs the extension. */
fun usesDigitalExtension(mode: DemodulationMode, wfmStereoAndRds: Boolean): Boolean =
    mode.isDigitalExtension || (mode == DemodulationMode.WFM && wfmStereoAndRds)

/** The extension first: the package check, unless connecting found out more (an extension too old
 *  to name its protocol in its manifest, a refused binding), then the license - installing comes
 *  before buying, so the extension can be tried (in the grace period, see [DigitalAvailability.LOCKED])
 *  before deciding. */
internal fun availabilityOf(unlocked: Boolean, packageCheck: DigitalAvailability, connection: ExtensionConnectionState): DigitalAvailability = when {
    packageCheck != DigitalAvailability.READY -> packageCheck
    connection == ExtensionConnectionState.VERSION_MISMATCH_UPDATE_APP -> DigitalAvailability.UPDATE_APP
    connection == ExtensionConnectionState.VERSION_MISMATCH_UPDATE_EXTENSION -> DigitalAvailability.UPDATE_EXTENSION
    connection == ExtensionConnectionState.NOT_PERMITTED -> DigitalAvailability.FOREIGN_SIGNATURE
    connection == ExtensionConnectionState.NOT_INSTALLED -> DigitalAvailability.NOT_INSTALLED
    !unlocked -> DigitalAvailability.LOCKED
    else -> DigitalAvailability.READY
}

internal fun linkOf(needed: Boolean, connection: ExtensionConnectionState): DigitalLink = when {
    !needed -> DigitalLink.IDLE
    else -> when (connection) {
        ExtensionConnectionState.CONNECTED -> DigitalLink.CONNECTED
        ExtensionConnectionState.DISCONNECTED -> DigitalLink.CONNECTING
        ExtensionConnectionState.RECONNECTING -> DigitalLink.INTERRUPTED
        ExtensionConnectionState.UNAVAILABLE -> DigitalLink.FAILED
        // the availability tells these
        ExtensionConnectionState.VERSION_MISMATCH_UPDATE_APP,
        ExtensionConnectionState.VERSION_MISMATCH_UPDATE_EXTENSION,
        ExtensionConnectionState.NOT_INSTALLED,
        ExtensionConnectionState.NOT_PERMITTED -> DigitalLink.IDLE
    }
}

/**
 * The one [DigitalStatus]. The package is checked at start, on [recheck] (the activity coming back -
 * after an install or update in the Play Store - and before anything is started) and whenever
 * connecting reports a problem.
 */
class DigitalExtensionStatus(
    private val context: Context,
    appStateRepository: AppStateRepository,
    connectionState: StateFlow<ExtensionConnectionState>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val packageCheck = MutableStateFlow(checkPackage())

    fun recheck(): DigitalAvailability {
        packageCheck.value = checkPackage()
        return availability.value
    }

    private fun checkPackage(): DigitalAvailability {
        DigitalFeatures.update(DigitalExtensionConnection.isDigitalExtensionPackageInstalled(context))
        return DigitalExtensionConnection.checkPackage(context)
    }

    val availability: StateFlow<DigitalAvailability> = combine(appStateRepository.digitalFeaturesUnlocked.stateFlow, packageCheck, connectionState, ::availabilityOf)
        .stateIn(scope, SharingStarted.Eagerly, availabilityOf(appStateRepository.digitalFeaturesUnlocked.value, packageCheck.value, connectionState.value))

    private val trialSecondsLeft = combine(appStateRepository.digitalTrialRunning.stateFlow, appStateRepository.digitalTrialSecondsLeft.stateFlow) { running, left ->
        left.takeIf { running }
    }

    private val needed = combine(
        appStateRepository.analyzerRunning.stateFlow,
        appStateRepository.demodulationMode.stateFlow,
        appStateRepository.demodulationWfmStereoAndRdsEnabled.stateFlow,
    ) { running, mode, stereo -> running && usesDigitalExtension(mode, stereo) }

    val status: StateFlow<DigitalStatus> = combine(availability, needed, connectionState, trialSecondsLeft) { availability, needed, connection, trialLeft ->
        DigitalStatus(availability, if (availability.decodes) linkOf(needed, connection) else DigitalLink.IDLE, trialLeft)
    }.stateIn(scope, SharingStarted.Eagerly, DigitalStatus(availability.value))
}

/** What the user can do about a [DigitalNotice]. */
enum class DigitalAction(val label: String) {
    /** Opens the license sheet: the Full Version, or for Base Version owners the upgrade. */
    UNLOCK_DIGITAL_MODES("Unlock digital modes"),
    INSTALL_EXTENSION("Install RFA Digital"),
    UPDATE_EXTENSION("Update RFA Digital"),
    UPDATE_APP("Update RF Analyzer"),
    GET_APP_FROM_PLAY("Open Google Play"),
    RETRY("Retry"),
}

/** One line for every digital entry point: what is wrong, whether it is an error (red) and the
 *  one thing to do about it. */
data class DigitalNotice(val title: String, val text: String, val isError: Boolean, val action: DigitalAction?)

/** Null when there is nothing to say: ready, and connected or not needed. */
fun DigitalStatus.notice(): DigitalNotice? = when (availability) {
    DigitalAvailability.NOT_IN_BUILD -> null
    DigitalAvailability.LOCKED -> DigitalNotice(
        "Digital modes trial used up",
        "Each start still decodes for 30 seconds. The full version - or the upgrade from the Base Version - unlocks digital modes for good. Everything received so far stays here.",
        isError = false, DigitalAction.UNLOCK_DIGITAL_MODES,
    )
    DigitalAvailability.NOT_INSTALLED -> DigitalNotice(
        "RFA Digital is not installed",
        "Receiving digital modes needs the RFA Digital app from Google Play (free to install).",
        isError = false, DigitalAction.INSTALL_EXTENSION,
    )
    DigitalAvailability.FOREIGN_SIGNATURE -> DigitalNotice(
        "RFA Digital can't connect",
        "This RF Analyzer and RFA Digital come from different sources, so Android doesn't let them connect. Digital modes work with RF Analyzer from Google Play.",
        isError = true, DigitalAction.GET_APP_FROM_PLAY,
    )
    DigitalAvailability.UPDATE_EXTENSION -> DigitalNotice(
        "Update RFA Digital",
        "The installed RFA Digital is too old for this RF Analyzer. Nothing is decoded until it is updated.",
        isError = true, DigitalAction.UPDATE_EXTENSION,
    )
    DigitalAvailability.UPDATE_APP -> DigitalNotice(
        "Update RF Analyzer",
        "This RF Analyzer is too old for the installed RFA Digital. Nothing is decoded until RF Analyzer is updated.",
        isError = true, DigitalAction.UPDATE_APP,
    )
    DigitalAvailability.READY -> when (link) {
        DigitalLink.INTERRUPTED -> DigitalNotice(
            "Connection to RFA Digital lost",
            "Reconnecting - nothing is decoded until it is back.",
            isError = true, action = null,
        )
        DigitalLink.FAILED -> DigitalNotice(
            "RFA Digital is not responding",
            "Nothing is being decoded.",
            isError = true, DigitalAction.RETRY,
        )
        DigitalLink.IDLE, DigitalLink.CONNECTING, DigitalLink.CONNECTED -> null
    }
}

/** The Play Store page an action opens. */
fun DigitalAction.playStorePackage(): String? = when (this) {
    DigitalAction.INSTALL_EXTENSION, DigitalAction.UPDATE_EXTENSION -> DigitalExtensionApps.EXTENSION_PACKAGE
    DigitalAction.UPDATE_APP, DigitalAction.GET_APP_FROM_PLAY -> DigitalExtensionApps.MAIN_APP_PACKAGE
    DigitalAction.RETRY, DigitalAction.UNLOCK_DIGITAL_MODES -> null
}

/** "1 h 52 min", "45 min", "under a minute". */
fun formatTrialTimeLeft(seconds: Int): String {
    val minutes = seconds / 60
    return when {
        minutes >= 60 -> "${minutes / 60} h ${minutes % 60} min"
        minutes >= 1 -> "$minutes min"
        else -> "under a minute"
    }
}
