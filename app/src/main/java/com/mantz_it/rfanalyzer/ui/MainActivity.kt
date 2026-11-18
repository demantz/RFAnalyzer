package com.mantz_it.rfanalyzer.ui

import com.mantz_it.rfanalyzer.digiext.DigitalFeatures
import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.core.app.ShareCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.mantz_it.rfanalyzer.location.openAppSettings
import com.mantz_it.rfanalyzer.source.LocalNetworkAccess
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mantz_it.rfanalyzer.BuildConfig
import com.mantz_it.rfanalyzer.LogcatLogger
import com.mantz_it.rfanalyzer.analyzer.AnalyzerService
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.billing.BillingRepository
import com.mantz_it.rfanalyzer.database.collectAppState
import com.mantz_it.rfanalyzer.ui.MainViewModel.UiAction
import com.mantz_it.rfanalyzer.ui.composable.LocalShowHelp
import com.mantz_it.rfanalyzer.ui.composable.LocalSnackbarHostState
import com.mantz_it.rfanalyzer.ui.screens.LogFileScreen
import com.mantz_it.rfanalyzer.ui.screens.MainScreen
import com.mantz_it.rfanalyzer.ui.screens.RecordingsScreen
import com.mantz_it.rfanalyzer.ui.tabs.AnalyzerTabs
import com.mantz_it.rfanalyzer.ui.tabs.ScreenOrientation
import com.mantz_it.rfanalyzer.ui.tabs.SourceType
import com.mantz_it.rfanalyzer.ui.screens.AboutScreen
import com.mantz_it.rfanalyzer.ui.screens.ManualScreen
import com.mantz_it.rfanalyzer.ui.screens.TutorialScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.delay
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.hilt.navigation.compose.hiltViewModel
import com.mantz_it.rfanalyzer.FileCopyService
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.source.UsbSdrType
import com.mantz_it.rfanalyzer.ui.composable.FossDonationDialog
import com.mantz_it.rfanalyzer.ui.composable.toTimeSpanString
import com.mantz_it.rfanalyzer.ui.screens.StationsPage
import com.mantz_it.rfanalyzer.ui.screens.BookmarkManagerScreen
import com.mantz_it.rfanalyzer.ui.screens.BookmarkManagerViewModel
import com.mantz_it.rfanalyzer.ui.screens.TutorialScreenCard
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalExtensionApps
import kotlin.collections.listOf

/**
 * <h1>RF Analyzer - Main Activity</h1>
 *
 * Module:      MainActivity.kt
 * Description: The main activity of the app.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */


const val RECORDINGS_DIRECTORY = "recordings"

@AndroidEntryPoint
class MainActivity: ComponentActivity() {

    @Inject lateinit var appStateRepository: AppStateRepository
    @Inject lateinit var billingRepository: BillingRepository
    private val mainViewModel: MainViewModel by viewModels()
    private var analyzerService: AnalyzerService? = null
    private var isBound = false
    private lateinit var analyzerSurface: AnalyzerSurface
    // Registered with the activity (before it starts): a launcher registered later - from the
    // export snackbar's button - throws.
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) Log.d(TAG, "POST_NOTIFICATIONS permission granted.")
        else Log.w(TAG, "POST_NOTIFICATIONS permission denied.")
    }
    // Android 17's local network permission, asked right before a network source starts (LocalNetworkAccess)
    private var localNetworkHost: String? = null
    private var localNetworkExplainedBefore = false
    private val localNetworkPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // (the host is gone if the activity was recreated meanwhile)
        val host = localNetworkHost ?: networkSourceHost() ?: ""
        localNetworkHost = null
        Log.i(TAG, "localNetworkPermission: granted=$granted (for $host)")
        if (granted) {
            startAnalyzerService()
            return@registerForActivityResult
        }
        abandonStart()
        if (!localNetworkExplainedBefore && !shouldShowRequestPermissionRationale(LocalNetworkAccess.PERMISSION))
            showLocalNetworkSettingsHint(host) // refused without a dialog: the user denied it for good earlier
        else
            mainViewModel.showSnackbar(SnackbarEvent("Without local network access, RF Analyzer can't reach $host."))
    }

    companion object {
        private const val TAG = "MainActivity"

        // USB SDRs (by UsbDevice.deviceName, which changes on every re-plug) already handled in
        // this process. Process-wide so activity recreation doesn't re-trigger, and empty on a
        // cold start so an SDR attached at launch selects its source type.
        private val seenSdrDeviceNames = mutableSetOf<String>()

        // SourceType name of the SDR whose plug-in opened the app (UsbAttachActivity)
        const val EXTRA_USB_SDR_ATTACHED = "com.mantz_it.rfanalyzer.extra.USB_SDR_ATTACHED"

        // Read by UsbAttachActivity: whether the app's UI exists / is in front
        @Volatile
        var isCreated = false
            private set
        @Volatile
        var isStarted = false
            private set
    }

    // set from EXTRA_USB_SDR_ATTACHED, handed to the service once it is bound
    private var usbSdrAttachedAtLaunch: SourceType? = null

    // Callbacks for bind/unbindService
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "onServiceConnected: Service connected.")
            val localBinder = binder as AnalyzerService.LocalBinder
            analyzerService = localBinder.getService()
            isBound = true
            usbSdrAttachedAtLaunch?.let { sourceType ->
                usbSdrAttachedAtLaunch = null
                lifecycleScope.launch {
                    // a source type set before the settings are loaded would be overwritten
                    appStateRepository.sourceType.awaitInitialized()
                    appStateRepository.startAnalyzerOnUsbAttach.awaitInitialized()
                    analyzerService?.onUsbSdrAttached(sourceType)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "onServiceDisconnected: Service disconnected.")
            isBound = false
            analyzerService = null
        }
    }

    // broadcast receiver to auto-detect USB SDRs attached while the activity is started:
    val usbBroadcastReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if(intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                UsbSdrType.deviceFromIntent(intent)?.let { device ->
                    Log.d(TAG, "Device attached: ${device.deviceName} (vendor: ${device.vendorId}, product: ${device.productId}, name: ${device.manufacturerName}|${device.productName})")
                    val type = UsbSdrType.of(device) ?: return
                    if (seenSdrDeviceNames.add(device.deviceName))
                        onSdrAttached(device, type)
                }
            } else if(intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                UsbSdrType.deviceFromIntent(intent)?.let { device ->
                    Log.d(TAG, "usbBroadcastReceiver:onReceive: Device detached (${device.vendorId}:${device.productId} - ${device.productName})")
                    seenSdrDeviceNames.remove(device.deviceName)
                }
            }
        }
    }

    // RFA Digital installed, updated or removed while the activity is started (onStart rechecks the
    // rest of the time): the Digital tab's notices follow at once.
    private val digitalExtensionPackageReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.data?.schemeSpecificPart == DigitalExtensionApps.EXTENSION_PACKAGE)
                mainViewModel.recheckDigitalExtension()
        }
    }

    // The receiver above only runs while the activity is started, so an SDR plugged in while the
    // app was in the background (or not running) is picked up by rescanning in onStart instead.
    private suspend fun detectSdrsAttachedWhileStopped() {
        // Settings load asynchronously; a switch made before sourceType is loaded would be
        // overwritten by the persisted value.
        appStateRepository.sourceType.awaitInitialized()
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val attached = UsbSdrType.findAttached(usbManager)
        attached.filter { (device, _) -> device.deviceName !in seenSdrDeviceNames }
            .forEach { (device, type) -> onSdrAttached(device, type) }
        seenSdrDeviceNames.clear()
        seenSdrDeviceNames.addAll(attached.map { (device, _) -> device.deviceName })
    }

    private fun onSdrAttached(device: UsbDevice, type: UsbSdrType) {
        Log.i(TAG, "onSdrAttached: ${type.displayName} device '${device.productName}' (${device.deviceName})")
        Toast.makeText(this, "${type.displayName} device '${device.productName}' attached.", Toast.LENGTH_SHORT).show()
        if (!appStateRepository.analyzerRunning.value)
            appStateRepository.sourceType.set(type.sourceType)
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCreated = true
        usbSdrAttachedAtLaunch = intent.getStringExtra(EXTRA_USB_SDR_ATTACHED)?.let { name -> SourceType.entries.firstOrNull { it.name == name } }
        Log.d(TAG, "onCreate: Activity created.")

        // Get version name:
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "<unknown version>"
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "onCreate: Cannot read version name: " + e.message);
            "<unknown version>"
        }
        appStateRepository.appVersion.set(versionName)

        // Get build type (debug/release)
        val buildType = BuildConfig.BUILD_TYPE
        Log.i(TAG, "This is RF Analyzer $versionName ($buildType) by Dennis Mantz.");
        appStateRepository.appBuildType.set(buildType)

        // Device Info
        val deviceName = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) {
            Build.MODEL
        } else {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        }
        Log.i(TAG, "onCreate: Android Device Info: $deviceName, Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")

        // create directory for recordings if it does not exist:
        val recordingsDir = File(filesDir, RECORDINGS_DIRECTORY)
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }
        // Drops rows whose file is gone, asks about a recording the app was closed during
        mainViewModel.checkRecordingsAtStart(recordingsDir)

        // Check and request POST_NOTIFICATIONS permission if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "onCreate: Check for permissions..")
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                lifecycleScope.launch {
                    appStateRepository.dontAskForNotificationPermission.awaitInitialized() // block until this setting is loaded and safe to access
                    if (!appStateRepository.dontAskForNotificationPermission.value) {
                        requestNotificationPermission(notificationPermissionLauncher)
                    }
                }
            }
        }

        val maxHeap = Runtime.getRuntime().maxMemory() / 1024 / 1024
        Log.d(TAG, "onCreate: Max Heap size: ${maxHeap}MB")
        if (maxHeap < 512)
            Log.w(TAG, "onCreate: Max Heap is less than 512MB! This may cause issues with waterfall rendering!")

        // set initial screen orientation
        setScreenOrientation(appStateRepository.screenOrientation.value)

        // Create the AnalyzerSurface
        analyzerSurface = AnalyzerSurface(
            context = this,
            sourceName = appStateRepository.sourceName,
            analyzerRunning = appStateRepository.analyzerRunning,
            sourceOptimalSampleRates = appStateRepository.sourceSupportedSampleRates,
            sourceFrequency = appStateRepository.sourceFrequency,
            sourceSampleRate = appStateRepository.sourceSampleRate,
            sourceSignalStartFrequency = appStateRepository.sourceSignalStartFrequency,
            sourceSignalEndFrequency = appStateRepository.sourceSignalEndFrequency,
            fftAverageLength = appStateRepository.fftAverageLength,
            fftPeakHold = appStateRepository.fftPeakHold,
            maxFrameRate = appStateRepository.effectiveMaxFrameRate,
            waterfallColorMap = appStateRepository.waterfallColorMap,
            fftDrawingType = appStateRepository.fftDrawingType,
            fftRelativeFrequency = appStateRepository.fftRelativeFrequency,
            fftWaterfallRatio = appStateRepository.fftWaterfallRatio,
            demodulationMode = appStateRepository.demodulationMode,
            demodulationEnabled = appStateRepository.demodulationEnabled,
            channelFrequency = appStateRepository.channelFrequency,
            channelWidth = appStateRepository.channelWidth,
            channelStartFrequency = appStateRepository.channelStartFrequency,
            channelEndFrequency = appStateRepository.channelEndFrequency,
            squelchEnabled = appStateRepository.effectiveSquelchEnabled,
            squelch = appStateRepository.squelchThresholdDb,
            squelchMargin = appStateRepository.effectiveSquelchMargin,
            recordingRunning = appStateRepository.anyRecordingRunning,
            basebandRecordingRunning = appStateRepository.basebandRecordingRunning,
            recordOnlyWhenSquelchIsSatisfied = appStateRepository.recordOnlyWhenSquelchIsSatisfied,
            recordingCurrentFileSize = appStateRepository.recordingCurrentFileSize,
            fontSize = appStateRepository.fontSize,
            showDebugInformation = appStateRepository.showDebugInformation,
            viewportFrequency = appStateRepository.viewportFrequency,
            viewportSampleRate = appStateRepository.viewportSampleRate,
            viewportVerticalScaleMin = appStateRepository.viewportVerticalScaleMin,
            viewportVerticalScaleMax = appStateRepository.viewportVerticalScaleMax,
            viewportStartFrequency = appStateRepository.viewportStartFrequency,
            viewportEndFrequency = appStateRepository.viewportEndFrequency,
            averageSignalStrength = appStateRepository.averageSignalStrength,
            squelchSatisfied = appStateRepository.squelchSatisfied,
            license = appStateRepository.license,
            fftProcessorData = appStateRepository.fftProcessorData,
            stationListFlow = mainViewModel.displayedStations,
            bandListFlow = mainViewModel.displayedBands,
            displayStationsInFft = appStateRepository.displayStationsInFft,
            displayBandsInFft = appStateRepository.displayBandsInFft,
            demodStatusFlow = mainViewModel.demodStatus,
            demodPacketCountsFlow = mainViewModel.demodPacketCounts,
            extensionConnectionStateFlow = mainViewModel.extensionConnectionState,
            demodulationWfmStereoAndRdsEnabled = appStateRepository.demodulationWfmStereoAndRdsEnabled,
            analyzerSurfaceActions = mainViewModel.analyzerSurfaceActions
        )


        // Setup the composable UI with NavController
        val showDonationDialog = mutableStateOf(false)
        setContent {
            val colorTheme by appStateRepository.colorTheme.stateFlow.collectAsState()
            RFAnalyzerTheme(colorTheme = colorTheme) {
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    mainViewModel.navigationEvent.collect { screen ->
                        // Navigating to MainScreen from MainScreen would pop and recreate it, losing its state
                        if (screen == AppScreen.MainScreen && navController.currentDestination?.route == AppScreen.MainScreen.route)
                            return@collect
                        var navigationTarget = screen.route + screen.subUrl
                        if(screen.route == AppScreen.BookmarkManagerScreen().route && appStateRepository.displayBookmarkTutorial.value) {
                            navigationTarget = AppScreen.BookmarksTutorial.route
                        }
                        navController.navigate(navigationTarget) {
                            // if we navigate to the main screen, make sure that the backstack is cleared:
                            Log.d(TAG, "onCreate: navigationEvent: $screen")
                            if(screen == AppScreen.MainScreen) {
                                // Pop up to the start destination (or a specific destination) and clear the backstack
                                popUpTo(navController.graph.startDestinationId) {
                                    inclusive = true // Clear all destinations up to and including the start destination
                                }
                                launchSingleTop = true // Avoid re-creating the destination if it's already at the top
                            }
                            // if we navigate to the BookmarkManagerScreen, make sure the station tutorial is cleared from the backstack:
                            if(screen == AppScreen.BookmarkManagerScreen()) {
                                popUpTo(AppScreen.BookmarksTutorial.route) {
                                    inclusive = true
                                }
                                launchSingleTop = true // Avoid re-creating the destination if it's already at the top
                            }
                        }
                    }
                }
                val focusManager = LocalFocusManager.current
                val isLoading by mainViewModel.isLoadingIndicatorVisible.collectAsState()
                val showHelp = { subUrl: String ->
                    val encodedUrl = Uri.encode(subUrl)
                    Log.d(TAG, "showHelp: got subUrl=$subUrl  encoded=$encodedUrl  enabled=${appStateRepository.longPressHelpEnabled.value}")
                    if(appStateRepository.longPressHelpEnabled.value)
                        navController.navigate("${AppScreen.ManualScreen().route}$encodedUrl")
                }
                CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
                    CompositionLocalProvider(LocalShowHelp provides showHelp) {
                        Scaffold(
                            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                            modifier = Modifier
                                .background(Color.Black)
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .pointerInput(Unit) {
                                detectTapGestures { focusManager.clearFocus() } // Clears focus when tapping anywhere
                            }
                        ) { innerPadding ->
                            LaunchedEffect(Unit) {
                                mainViewModel.snackbarEvent.collect { snackbarEvent ->
                                    val result = snackbarHostState.showSnackbar(
                                        message = snackbarEvent.message,
                                        actionLabel = snackbarEvent.buttonText,
                                        duration = SnackbarDuration.Short
                                    )
                                    snackbarEvent.callback?.let { it(result) }
                                }
                            }
                            NavHost(
                                navController = navController,
                                startDestination = when {
                                                       // Opened from the Digital Extension's launcher icon: straight to the Digital tab.
                                                       // The first-start screens stay pending for the app's own icon.
                                                       isOpenDigitalTabIntent(intent) -> AppScreen.MainScreen.route
                                                       !appStateRepository.welcomeScreenFinished.value -> AppScreen.WelcomeScreen.route
                                                       appStateRepository.lastSeenWhatsNewVersion.value < AppStateRepository.CURRENT_WHATS_NEW_VERSION -> AppScreen.WhatsNewScreen.route
                                                       else -> AppScreen.MainScreen.route
                                                   },
                                modifier = Modifier.padding(innerPadding)
                            ) {
                                composable(AppScreen.WelcomeScreen.route) {
                                    TutorialScreen(
                                        pages = listOf(
                                            TutorialScreenCard("Welcome to RF Analyzer", image = R.drawable.rfanalyzer2, description =
                                                "RF Analyzer turns your Android device into a real-time spectrum analyzer for " +
                                                "Software Defined Radio (SDR). \n\nVisualize and listen to radio signals around you" +
                                                " - from amateur radio to broadcast signals and beyond." +
                                                (if (BuildConfig.IS_FOSS) "" else
                                                    "\n\nThe TRIAL VERSION allows you to test compatibility with your hardware and lets you try all features.")),
                                            TutorialScreenCard("How To Start",
                                                description = "This tutorial is also available on YouTube. \n\nThe 6-minute video guides you through the first steps with RF Analyzer. \n\nOr simply tap 'Next' to continue with the in-app tutorial.",
                                                image = R.drawable.youtube_quick_start_tutorial,
                                                imageLink = "https://www.youtube.com/watch?v=sui54fqbImw"),
                                            TutorialScreenCard("Connect SDR","Plug your SDR dongle into the USB-OTG adapter and the USB-OTG adapter into your Android device.\n\nThe USB-OTG adapter sometimes comes with your phone or can be bought at very low cost. \nInstead of a USB-OTG adapter it is also possible to use a USB hub or docking station with USB-C connector.", image = R.drawable.tutorial_connect_sdr),
                                            TutorialScreenCard("Select Source Type", "When you start the app, first select your SDR type in the Signal Source dropdown menu. \n\nThen press the PLAY button on the right.\n\nAndroid will ask for USB permission the first time - grant it, and the FFT plot should start displaying live spectrum data.", image = R.drawable.tutorial_select_source),
                                            TutorialScreenCard("Explore the Spectrum","Explore the spectrum with scroll and zoom gestures. \n\nSwipe left or right inside the FFT or waterfall plot to browse the spectrum. The tuner automatically follows as you scroll.\n\nTo adjust how signals appear vertically, interact with the left axis of the FFT plot. Swipe up/down to shift the visible range or use pinch-to-zoom to adjust the scaling.", image = R.drawable.tutorial_explore),
                                            TutorialScreenCard("Demodulate Signal","Go to the 'Demodulation' tab. \nSelect a mode, e.g. 'FM (wide)' for broadcast radio. \n\nTap on the signal in the FFT plot to move the channel selector. You can also use the tuning wheel. \nCongrats, now you should hear audio :-)", image = R.drawable.tutorial_enable_demodulation),
                                            TutorialScreenCard("Tip: Context Help","A long-press on any label brings you directly to the respective section in the user manual. \n\nHave fun exploring!", image = R.drawable.tutorial_context_help),
                                        ),
                                        onFinish = {
                                        appStateRepository.welcomeScreenFinished.set(true)
                                        // New users start out already "caught up" - they just saw everything in the tutorial above.
                                        appStateRepository.lastSeenWhatsNewVersion.set(AppStateRepository.CURRENT_WHATS_NEW_VERSION)
                                        mainViewModel.navigate(AppScreen.MainScreen)
                                    })
                                }
                                composable(AppScreen.WhatsNewScreen.route) {
                                    TutorialScreen(
                                        skipButtonText = "Skip",
                                        // What 2.3 brings outside the digital modes for everyone; the digital pages
                                        // only while digital features are shown (DigitalFeatures).
                                        pages = listOfNotNull(
                                            TutorialScreenCard("What's New in RF Analyzer 2.3",
                                                description = "The biggest update yet: built-in drivers for every supported SDR, KiwiSDR web receivers, a new quick tune gesture, " +
                                                    "a squelch for every mode and much more" + (if (DigitalFeatures.shown) " - and the digital modes of RFA Digital." else ".") +
                                                    "\n\nTap through the next pages for the highlights, or skip ahead if you'd rather explore on your own."),
                                            TutorialScreenCard("Built-in SDR Drivers", image = R.drawable.whatsnew_sources, description =
                                                "RTL-SDR dongles now work with a built-in driver, like HackRF, Airspy, Airspy HF+ and HydraSDR - no separate driver app needed." +
                                                "\n\nPlugging in an SDR selects its source type, RF Analyzer can start by itself when one is plugged in (Settings), " +
                                                "and it restarts on its own after a short USB dropout."),
                                            TutorialScreenCard("KiwiSDR Web Receivers", image = R.drawable.whatsnew_kiwisdr, description =
                                                "No SDR at hand? Listen through public KiwiSDR web receivers all over the world." +
                                                "\n\nIn the Source tab choose KiwiSDR (web), then Choose from Public List: pick a receiver from the list or the map."),
                                            TutorialScreenCard("Quick Tune & Zoom", image = R.drawable.whatsnew_quick_tune, description =
                                                "Long-press on empty space in the spectrum or waterfall to bring up the quick tune control." +
                                                "\n\nDrag up or down to zoom, left or right to fine-tune the channel - all without lifting your finger."),
                                            TutorialScreenCard("A Squelch for Every Mode", image = R.drawable.whatsnew_squelch, description =
                                                "The squelch level is now set in dB above the noise, so it fits every SDR, gain and antenna. " +
                                                "Each mode keeps its own squelch, and whole transmissions get through, their start included." +
                                                "\n\nSquelch settings from before the update start out off."),
                                            TutorialScreenCard("Baseband Recording", image = R.drawable.whatsnew_recording, description =
                                                "Besides the raw IQ samples, you can now record just the demodulated channel (Record Baseband) - much smaller files." +
                                                "\n\nRecordings keep running in the background, also when you swipe the app away."),
                                            TutorialScreenCard("Band Plans for Your Region", image = R.drawable.whatsnew_band_plans, description =
                                                "The bundled band plans follow your ITU region, with new lists of license-free bands (CB, PMR446, FRS, GMRS) " +
                                                "and of ISM, maritime and air bands. Import them in the Bookmark Manager."),
                                            TutorialScreenCard("Digital Modes", image = R.drawable.whatsnew_digital_overview, description =
                                                "With the RFA Digital app (free to install), RF Analyzer decodes FT8, APRS and packet radio, ADS-B, POCSAG, " +
                                                "LoRa mesh networks (MeshCore, Meshtastic), RDS, RTTY, PSK31 and R09 in its new Digital tab." +
                                                "\n\nDigital modes are part of the Full Version - bought it before? They are included. " +
                                                "Without it, try them for two hours of decoding time.").takeIf { DigitalFeatures.shown },
                                            TutorialScreenCard("Lists, Search & Map", image = R.drawable.whatsnew_digital_map, description =
                                                "Everything decoded is collected per station, aircraft, pager or mesh node: searchable, exportable, " +
                                                "and on the map when it has a position.").takeIf { DigitalFeatures.shown },
                                            TutorialScreenCard("More Improvements",
                                                description = "Also in this release:" +
                                                    "\n\n• True dBFS levels, an automatic FFT size and a smoother waterfall with much lower CPU use" +
                                                    "\n• A new mode picker, a tune step for each mode" +
                                                    "\n• Gain modes for Airspy and HydraSDR, fixes for HackRF, Airspy and HydraSDR" +
                                                    "\n• rtl_tcp and KiwiSDR ride out short network stalls" +
                                                    (if (DigitalFeatures.shown) "\n• Station lists for the digital modes" else "") +
                                                    "\n\nThe FFT size is now chosen automatically; turn on Manual FFT Size in the Display tab to pick your own."),
                                        ),
                                        onFinish = {
                                            appStateRepository.lastSeenWhatsNewVersion.set(AppStateRepository.CURRENT_WHATS_NEW_VERSION)
                                            mainViewModel.navigate(AppScreen.MainScreen)
                                            // On to the biggest new feature: the Digital tab says what is
                                            // missing (install the extension) and rechecks on return.
                                            if (DigitalFeatures.shown) mainViewModel.requestTab(AnalyzerTabs.PACKETS)
                                        })
                                }
                                composable(AppScreen.MainScreen.route) { MainScreen(analyzerSurface, mainViewModel, appStateRepository) }
                                composable(AppScreen.RecordingScreen.route) { RecordingsScreen(navController, mainViewModel.recordings, appStateRepository.displayOnlyFavoriteRecordings.stateFlow, mainViewModel.recordingsScreenActions) }
                                composable(AppScreen.BookmarksTutorial.route) {
                                    TutorialScreen(
                                        pages = listOf(
                                            TutorialScreenCard("Bookmark Manager", image = R.drawable.bookmark_manager, description =
                                                "In the Bookmark Manager you can view and manage all your station and band bookmarks. " +
                                                "Bookmarks are organized in lists. Create your own lists or use the default lists." +
                                                "\n\nYou can also download online lists (e.g. shortwave radio stations) and enable " +
                                                "automatic updates (e.g. for POTA/SOTA spot lists)."),
                                            TutorialScreenCard("Video Tutorial",
                                                description = "This tutorial is also available on YouTube. \n\nThe video guides you through the Bookmark Manager and has a short Quick Start Tutorial at the beginning. \n\nOr simply tap 'Next' to continue with the in-app tutorial.",
                                                image = R.drawable.youtube_bookmarks_tutorial,
                                                imageLink = "https://youtu.be/dY---2q4-Ag"),
                                            TutorialScreenCard("Filter Bookmarks", image = R.drawable.bookmarks_filter, description =
                                                "For your convenience, you can search through bookmarks and filter them by list, frequency, modulation, etc."),
                                            TutorialScreenCard("Station & Band Labels", image = R.drawable.bookmarks_labels, description =
                                                "Station and band labels are visible in the FFT display. A single press on a station label tunes in on a station, a long press shows information about the station." +
                                                "\n\nThe labels can also be filtered or disabled in the Settings (see next slide)."),
                                            TutorialScreenCard("Filter Labels in FFT", image = R.drawable.bookmarks_label_filter, description =
                                                "Station and band labels in the FFT can be filtered to fit your needs." +
                                                "\n\nYou may also choose to use the same filter settings for the labels in the FFT and the Bookmark Manager. " +
                                                "Disabling 'Custom Filter for FFT' will show the same filtered bookmark labels as in the Bookmark Manager list view."),
                                            TutorialScreenCard("Favorites", image = R.drawable.bookmarks_quick_access_dialog, description =
                                                "Mark bookmarks as favorites (\u2665) to access them from the Quick Access Dialog.\n\n" +
                                                "Open the Quick Access Dialog with this button (Demodulation Tab):",
                                                secondImage = R.drawable.bookmark_favorites_button),
                                            TutorialScreenCard("Import & Export", image = R.drawable.bookmarks_settings, description =
                                                "You can import bookmarks from SDR# or SDR++.\n\n" +
                                                "Export bookmarks to share them among RF Analyzer users or with other devices." +
                                                "\n\nAlso remember to create a backup of your bookmarks regularly. Bookmarks are not synced to the cloud - they will be deleted when you reinstall the app!"),
                                        ),
                                        onFinish = {
                                            appStateRepository.displayBookmarkTutorial.set(false)
                                            mainViewModel.navigate(AppScreen.BookmarkManagerScreen())
                                        })
                                }
                                composable(
                                    route = "${AppScreen.BookmarkManagerScreen().route}{subUrl}",
                                    arguments = listOf(
                                        navArgument("subUrl") {
                                            type = NavType.StringType
                                            defaultValue = StationsPage.STATIONS.name
                                            nullable = true
                                        }
                                    )
                                ) { backstackEntry ->
                                    val viewModel: BookmarkManagerViewModel = hiltViewModel()
                                    val page: StationsPage? = backstackEntry.arguments?.getString("subUrl")?.let { subUrl -> StationsPage.entries.firstOrNull { it.name == subUrl } }
                                    if (page != null) appStateRepository.bookmarkManagerScreenPage.set(page)
                                    BookmarkManagerScreen(
                                        viewModel = viewModel,
                                        bookmarkManagerScreenActions = mainViewModel.bookmarkManagerScreenActions,
                                        onPopBackStack = navController::popBackStack,
                                    )
                                }
                                composable(AppScreen.LogFileScreen.route) { LogFileScreen(navController, mainViewModel.logContent) }
                                composable(AppScreen.AboutScreen.route) { AboutScreen(versionName, navController) }
                                composable(
                                    route = "${AppScreen.ManualScreen().route}{subUrl}",
                                    arguments = listOf(
                                        navArgument("subUrl") {
                                            type = NavType.StringType
                                            defaultValue = "index.html"
                                            nullable = true
                                        }
                                    )) { backStackEntry -> ManualScreen(navController, subUrl = Uri.decode(backStackEntry.arguments?.getString("subUrl") ?: "index.html")) }
                            }
                            // Loading Overlay (only visible when isLoading = true)
                            if (isLoading) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable(enabled = true) { }   // Blocks all touch input
                                        .background(Color.Black.copy(alpha = 0.5f)), // Semi-transparent overlay
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = Color.White)
                                }
                            } else if (showDonationDialog.value) {
                                FossDonationDialog(
                                    usageTimeStr = (appStateRepository.appUsageTimeInSeconds.value*1000L).toTimeSpanString(),
                                    dismissAction = { showDonationDialog.value = false }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Observe and handle the UI actions
        lifecycleScope.launch {
            mainViewModel.uiActions.collect { action ->
                when (action) {
                    is UiAction.OnStartClicked -> onStartClickedAction()
                    is UiAction.StartAnalyzerIfUsbSdrAttached -> {
                        val usbManager = getSystemService(USB_SERVICE) as UsbManager
                        if (UsbSdrType.findAttached(usbManager).any { (_, type) -> type.sourceType == appStateRepository.sourceType.value })
                            mainViewModel.startAnalyzerIfStopped()
                    }
                    is UiAction.OnStopClicked  -> onStopClickedAction()
                    is UiAction.OnAutoscaleClicked -> analyzerSurface.autoscale()
                    is UiAction.ShowDialog -> AlertDialog.Builder(this@MainActivity)
                        .setTitle(action.title)
                        .setMessage(action.msg)
                        .setPositiveButton(action.positiveButton) { _, _ ->
                            action.action?.let { it() }
                        }
                        .setNegativeButton(action.negativeButton) { dialog, _ ->
                            action.negativeAction?.let { it() }
                            dialog.dismiss()
                        }
                        .show()
                    is UiAction.ShowDonationDialog -> showDonationDialog.value = true
                    is UiAction.OnShowLogFileClicked -> {
                        Log.i(TAG,"opening LogFileScreen")
                        mainViewModel.navigate(AppScreen.LogFileScreen)
                        Log.i(TAG,"loading logs..")
                        mainViewModel.loadLogs(File(filesDir, LogcatLogger.logfileName))
                        Log.i(TAG,"logs loaded!")
                    }
                    is UiAction.OnDeleteLogFileClicked -> {
                        Log.i(TAG, "UiAction.OnDeleteLogFileClicked: Deleting logfile ${LogcatLogger.logfileName}")
                        File(filesDir, LogcatLogger.logfileName).delete()
                    }
                    is UiAction.OnSaveLogToFileClicked -> saveFileToUserDirectory(action.destUri, File(filesDir, LogcatLogger.logfileName))
                    is UiAction.OnShareLogFileClicked -> shareFile(File(filesDir, LogcatLogger.logfileName), "text/plain", "Share log file via")
                    is UiAction.OnStartRecordingClicked -> if(isBound) analyzerService?.startRecording()
                    is UiAction.OnStopRecordingClicked -> if(isBound) analyzerService?.stopRecording()
                    is UiAction.OnStartBasebandRecordingClicked -> if(isBound) analyzerService?.startBasebandRecording()
                    is UiAction.OnStopBasebandRecordingClicked -> if(isBound) analyzerService?.stopBasebandRecording()
                    is UiAction.OnDeleteRecordingClicked -> {
                        val file = File(action.filePath)
                        if(file.exists()) file.delete()
                        else Log.e(TAG, "UiAction.OnDeleteRecordingClicked: File ${file.absoluteFile} [i.e. ${action.filePath}] does not exist")
                    }
                    is UiAction.OnDeleteAllRecordingsClicked -> {
                        recordingsDir.deleteRecursively()
                        recordingsDir.mkdirs()
                    }
                    is UiAction.RenameFile -> renameFile(action.file, action.newName)
                    is UiAction.WriteInternalFileToFile -> saveFileToUserDirectory(action.destUri, File(action.filename))
                    is UiAction.OnShareRecordingClicked -> shareFile(File(action.filename), "application/octet-stream", "Share recording via")
                    is UiAction.OnShareJsonExportClicked -> {
                        val file = File.createTempFile(action.filenamePrefix, ".json", cacheDir)
                        file.writeText(action.json)
                        shareFile(file, "application/json", "Share data via")
                    }
                    is UiAction.OnShareJsonFileClicked -> {
                        mainViewModel.showLoadingIndicator(true)
                        try {
                            val file = withContext(Dispatchers.IO) {
                                File.createTempFile(action.filenamePrefix, ".json", cacheDir).also { file ->
                                    file.bufferedWriter().use { action.write(it) }
                                }
                            }
                            shareFile(file, "application/json", "Share data via")
                        } finally {
                            mainViewModel.showLoadingIndicator(false)
                        }
                    }
                    is UiAction.LaunchPurchase -> lifecycleScope.launch { mainViewModel.purchase(this@MainActivity, action.product) }
                    is UiAction.OnStartExternalActivity -> startExternalActivity(action.intent)
                    is UiAction.RetryDigitalExtension -> analyzerService?.retryDigitalExtension()
                    null -> Log.e(TAG, "mainViewModel.uiActions.collect: action is NULL!")
                }
            }
        }

        // Observe App State
        lifecycleScope.collectAppState(appStateRepository.screenOrientation) {
            setScreenOrientation(it)
        }

        // Handle the incoming intent (an IQ file opened with RF Analyzer, the extension's launcher icon)
        if(intent != null) {
            Log.d(TAG, "onCreate: Incoming intent: action=${intent.action} categories=${intent.categories} data=${intent.data}")
            // Not again after a configuration change, which recreates the activity with the same intent
            if (savedInstanceState == null && "android.intent.action.VIEW" == intent.action && intent.data != null)
                handleIncomingFile(intent)
            if (savedInstanceState == null && isOpenDigitalTabIntent(intent)) {
                mainViewModel.recheckDigitalExtension() // the switch may not know of the install yet (DigitalFeatures)
                mainViewModel.requestTab(AnalyzerTabs.PACKETS)
            }
        }
    }

    private fun requestNotificationPermission(
        requestPermissionLauncher: ActivityResultLauncher<String>,
        message: String = "This app needs permission to display a notification while the analyzer service is running in the background.",
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            val askedAlready = appStateRepository.notificationPermissionAskedAtLeastOnce.value
            // Show a dialog that explains why we need the permission
            AlertDialog.Builder(this)
                .setTitle("Background Notification")
                .setMessage(message)
                .setPositiveButton(
                    if(askedAlready) "Go to Settings" else "OK"
                ) { dialog, whichButton ->
                    if(askedAlready) {
                        // go to settings:
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = Uri.fromParts("package", this.packageName, null)
                        intent.data = uri
                        this.startActivity(intent)
                    } else {
                        // show permission dialog:
                        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        appStateRepository.notificationPermissionAskedAtLeastOnce.set(true)
                    }
                }
                .setNegativeButton(
                    "Don't ask again"
                ) { dialog, whichButton ->
                    appStateRepository.dontAskForNotificationPermission.set(true)
                    appStateRepository.notificationPermissionAskedAtLeastOnce.set(true)
                }
                .show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        Log.d(TAG, "onNewIntent: received intent: action=${intent.action} categories=${intent.categories} data=${intent.data}")
        super.onNewIntent(intent)
        handleIncomingFile(intent)
        if (isOpenDigitalTabIntent(intent)) {
            mainViewModel.recheckDigitalExtension() // the switch may not know of the install yet (DigitalFeatures)
            mainViewModel.navigate(AppScreen.MainScreen)
            mainViewModel.requestTab(AnalyzerTabs.PACKETS)
        }
    }

    // Sent by the extension's launcher icon. The sender isn't checked: the Digital tab exists only
    // while digital features are shown (MainScreen), which the callers recheck first.
    private fun isOpenDigitalTabIntent(intent: Intent?) = intent?.action == DigitalExtensionApps.ACTION_OPEN_DIGITAL_TAB && DigitalFeatures.inBuild

    private fun handleIncomingFile(intent: Intent) {
        val uri = intent.data
        Log.d(TAG, "handleIncomingFile: Intent.data=$uri")
        if (uri != null) {
            if (appStateRepository.analyzerRunning.value) {
                lifecycleScope.launch {
                    Log.d(TAG, "handleIncomingFile: Analyzer is running. Show dialog to the user after 1 second delay")
                    delay(1000)
                    mainViewModel.showSnackbar(SnackbarEvent(
                        message = "Load incoming file into File Source? (Analyzer will stop!)",
                        buttonText = "Load File",
                        callback = {
                            onStopClickedAction()
                            setFileSourceFromContentUri(uri)
                            mainViewModel.showSnackbar(SnackbarEvent("File loaded. Press PLAY to start Analyzer!"))
                        }
                    ))
                }
            } else
                setFileSourceFromContentUri(uri)
        }
    }

    override fun onResume() {
        super.onResume()
        // Purchases made, completed or refunded while the app was in the background - also right
        // after Google Play's purchase sheet closes (Google's recommendation).
        billingRepository.refresh()
    }

    override fun onStart() {
        super.onStart()
        isStarted = true
        mainViewModel.recheckDigitalExtension() // back from installing or updating RFA Digital?
        mainViewModel.refreshLocation()
        Log.d(TAG, "onStart: Binding to service if running.")
        // Bind Service:
        Intent(this, AnalyzerService::class.java).also { intent ->
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }

        // Register USB Broadcast Receiver
        val filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        registerReceiver(usbBroadcastReceiver, filter)
        registerReceiver(digitalExtensionPackageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        })
        lifecycleScope.launch { detectSdrsAttachedWhileStopped() }
    }

    override fun onStop() {
        super.onStop()
        isStarted = false
        Log.d(TAG, "onStop: Unbinding from service.")
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        // Unregistering the USB broadcast receiver
        unregisterReceiver(usbBroadcastReceiver)
        unregisterReceiver(digitalExtensionPackageReceiver)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Activity destroyed.")
        isCreated = false
        super.onDestroy()
    }

    private fun onStartClickedAction() {
        Log.d(TAG, "onStartPressedAction")
        // An rtl_tcp server or a KiwiSDR in the local network needs Android 17's permission first
        val host = networkSourceHost()
        if (host != null && LocalNetworkAccess.isBlocked(this)) {
            lifecycleScope.launch {
                if (LocalNetworkAccess.isLocalHost(host)) askForLocalNetworkAccess(host) else startAnalyzerService()
            }
            return
        }
        startAnalyzerService()
    }

    /** The host a network source connects to (an rtl_tcp server, a KiwiSDR); null for the others. */
    private fun networkSourceHost(): String? = when (appStateRepository.sourceType.value) {
        SourceType.RTLSDR -> appStateRepository.rtlsdrExternalServerIP.value.takeIf { appStateRepository.rtlsdrExternalServerEnabled.value }
        SourceType.KIWISDR -> appStateRepository.kiwiSdrHost.value
        else -> null
    }

    private fun askForLocalNetworkAccess(host: String) {
        AlertDialog.Builder(this)
            .setTitle("Local network access")
            .setMessage("RF Analyzer connects to $host in your local network. Android asks for your permission for this " +
                        "(\"Nearby devices\"). RF Analyzer only connects to the servers you enter.")
            .setPositiveButton("Continue") { _, _ ->
                localNetworkHost = host
                localNetworkExplainedBefore = shouldShowRequestPermissionRationale(LocalNetworkAccess.PERMISSION)
                localNetworkPermissionLauncher.launch(LocalNetworkAccess.PERMISSION)
            }
            .setNegativeButton("Cancel") { _, _ -> abandonStart() }
            .setOnCancelListener { abandonStart() }
            .show()
    }

    /** Start was pressed, but the analyzer won't start (a permission was refused): the button's
     *  spinner (analyzerStartPending, set by the view model) goes back to the Start button. */
    private fun abandonStart() {
        if (!appStateRepository.analyzerRunning.value) appStateRepository.analyzerStartPending.set(false)
    }

    private fun showLocalNetworkSettingsHint(host: String) {
        AlertDialog.Builder(this)
            .setTitle("Local network access is off")
            .setMessage("Android no longer asks for RF Analyzer's local network permission, so it can't reach $host. " +
                        "Allow it in the app's settings: Permissions → Nearby devices.")
            .setPositiveButton("Open settings") { _, _ -> openAppSettings(this) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startAnalyzerService() {
        // Start the AnalyzerService:
        if (isBound) {
            analyzerService?.startAnalyzer()
        } else {
            Log.w(TAG, "onStartClickedAction: Service is not bound! Cannot start Analyzer")
            abandonStart()
        }
    }

    private fun onStopClickedAction() {
        Log.d(TAG, "onStopClickedAction: Stopping Service..")
        if(isBound) {
            analyzerService?.stopAnalyzer()
        } else {
            Log.w(TAG, "onStopClickedAction: Service is not bound! Cannot stop Analyzer")
        }
    }

    private fun setFileSourceFromContentUri(uri: Uri) {
        var filename: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst())
                filename = cursor.getString(nameIndex)
        }
        Log.d(TAG, "setFileSourceFromContentUri: URI: $uri (Original Filename: $filename)")
        mainViewModel.setFilesourceUri(uri.toString(), filename)
    }

    private fun setScreenOrientation(screenOrientation: ScreenOrientation) {
        when(screenOrientation) {
            ScreenOrientation.AUTO -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            ScreenOrientation.PORTRAIT -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            ScreenOrientation.LANDSCAPE -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            ScreenOrientation.REVERSE_PORTRAIT -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT)
            ScreenOrientation.REVERSE_LANDSCAPE -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
        }
    }

    private fun saveFileToUserDirectory(destUri: Uri, srcFile: File) {
        try {
            val contentResolver = contentResolver
            if (!srcFile.exists()) {
                Log.w(TAG, "saveFileToUserDirectory: File ${srcFile.absolutePath} does not exist!")
                return
            }
            if (srcFile.length() > 10*1024*1024) { // use background service for files larger than 10MB
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        Log.d(TAG, "saveFileToUserDirectory: No permission for background notification.")
                        mainViewModel.showSnackbar(SnackbarEvent(
                            message = "Missing Permission. Cannot export file.",
                            buttonText = "Give Permission",
                            callback = {
                                requestNotificationPermission(notificationPermissionLauncher, "This app needs the permission to display notifications while exporting files in the background.")
                            }
                        ))
                        return
                    }
                }
                if (FileCopyService.Companion.FileCopyState.isRunning.value)
                    mainViewModel.showSnackbar(SnackbarEvent(
                        message = "Another Export is running...",
                        buttonText = null,
                        callback = {}
                    ))
                else
                    FileCopyService.start(this, srcFile, destUri)
            } else {
                // Small files don't need the background service
                contentResolver.openOutputStream(destUri)?.use { outputStream ->
                    srcFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream) // Copy file contents
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to save file ($e)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: File, mimeType: String, message: String) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

        ShareCompat.IntentBuilder(this)
            .setType(mimeType)
            .setStream(uri)
            .setChooserTitle(message)
            .startChooser()
    }

    private fun renameFile(file: File, newName: String, overrideExisting: Boolean = true): Boolean {
        if (!file.exists()) {
            Log.e(TAG, "renameFile: Old file does not exist: ${file.absolutePath}")
            return false
        }
        val newFile = File(file.parentFile, newName)

        if (newFile.exists()) {
            Log.w(TAG, "renameFile: New file already exists: ${newFile.absolutePath}")
            if (overrideExisting) {
                if(!newFile.delete()) {
                    Log.e(TAG, "renameFile: Failed to delete existing destination file")
                    return false
                } else {
                    Log.i(TAG, "renameFile: Deleted ${newFile.absolutePath}")
                }
            } else {
                return false
            }
        }

        Log.i(TAG, "renameFile: Renaming file from ${file.absolutePath} to ${newFile.absolutePath}")
        val success = file.renameTo(newFile)
        if (!success) {
            Log.e(TAG, "renameFile: Failed to rename file from ${file.absolutePath} to ${newFile.absolutePath}")
        }
        return success
    }

}