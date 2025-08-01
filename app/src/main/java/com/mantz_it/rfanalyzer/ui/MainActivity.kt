package com.mantz_it.rfanalyzer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mantz_it.rfanalyzer.BuildConfig
import com.mantz_it.rfanalyzer.LogcatLogger
import com.mantz_it.rfanalyzer.analyzer.AnalyzerService
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.BillingRepositoryInterface
import com.mantz_it.rfanalyzer.database.collectAppState
import com.mantz_it.rfanalyzer.ui.MainViewModel.UiAction
import com.mantz_it.rfanalyzer.ui.composable.LocalShowHelp
import com.mantz_it.rfanalyzer.ui.composable.LocalSnackbarHostState
import com.mantz_it.rfanalyzer.ui.screens.LogFileScreen
import com.mantz_it.rfanalyzer.ui.screens.MainScreen
import com.mantz_it.rfanalyzer.ui.screens.RecordingsScreen
import com.mantz_it.rfanalyzer.ui.composable.ScreenOrientation
import com.mantz_it.rfanalyzer.ui.composable.SourceType
import com.mantz_it.rfanalyzer.ui.screens.AboutScreen
import com.mantz_it.rfanalyzer.ui.screens.ManualScreen
import com.mantz_it.rfanalyzer.ui.screens.WelcomeScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/**
 * <h1>RF Analyzer - Main Activity</h1>
 *
 * Module:      MainActivity.kt
 * Description: The main activity of the app.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */


const val RECORDINGS_DIRECTORY = "recordings"

@AndroidEntryPoint
class MainActivity: ComponentActivity() {

    @Inject lateinit var appStateRepository: AppStateRepository
    @Inject lateinit var billingRepository: BillingRepositoryInterface
    private val mainViewModel: MainViewModel by viewModels()
    private var analyzerService: AnalyzerService? = null
    private var isBound = false
    private lateinit var analyzerSurface: AnalyzerSurface

    // Activity Launchers
    private lateinit var startRtlsdrDriverLauncher: ActivityResultLauncher<Intent>
    private lateinit var openIQFileLauncher: ActivityResultLauncher<Intent>

    companion object {
        private const val TAG = "MainActivity"
    }

    // Callbacks for bind/unbindService
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "onServiceConnected: Service connected.")
            val localBinder = binder as AnalyzerService.LocalBinder
            analyzerService = localBinder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "onServiceDisconnected: Service disconnected.")
            isBound = false
            analyzerService = null
        }
    }

    // broadcast receiver to auto-detect HACKRF and RTL-SDR USB devices:
    val usbBroadcastReceiver = object: BroadcastReceiver() {
        private val rtlsdrIds = setOf( // from https://github.com/osmocom/rtl-sdr/blob/master/rtl-sdr.rules
            Pair(0x0bda, 0x2832), Pair(0x0bda, 0x2838), Pair(0x0413, 0x6680), Pair(0x0413, 0x6f0f), Pair(0x0458, 0x707f), Pair(0x0ccd, 0x00a9),
            Pair(0x0ccd, 0x00b3), Pair(0x0ccd, 0x00b4), Pair(0x0ccd, 0x00b5), Pair(0x0ccd, 0x00b7), Pair(0x0ccd, 0x00b8), Pair(0x0ccd, 0x00b9),
            Pair(0x0ccd, 0x00c0), Pair(0x0ccd, 0x00c6), Pair(0x0ccd, 0x00d3), Pair(0x0ccd, 0x00d7), Pair(0x0ccd, 0x00e0), Pair(0x1554, 0x5020),
            Pair(0x15f4, 0x0131), Pair(0x15f4, 0x0133), Pair(0x185b, 0x0620), Pair(0x185b, 0x0650), Pair(0x185b, 0x0680), Pair(0x1b80, 0xd393),
            Pair(0x1b80, 0xd394), Pair(0x1b80, 0xd395), Pair(0x1b80, 0xd397), Pair(0x1b80, 0xd398), Pair(0x1b80, 0xd39d), Pair(0x1b80, 0xd3a4),
            Pair(0x1b80, 0xd3a8), Pair(0x1b80, 0xd3af), Pair(0x1b80, 0xd3b0), Pair(0x1d19, 0x1101), Pair(0x1d19, 0x1102), Pair(0x1d19, 0x1103),
            Pair(0x1d19, 0x1104), Pair(0x1f4d, 0xa803), Pair(0x1f4d, 0xb803), Pair(0x1f4d, 0xc803), Pair(0x1f4d, 0xd286), Pair(0x1f4d, 0xd803)
            )
        override fun onReceive(context: Context?, intent: Intent?) {
            if(intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    // else branch necessary as long as minSdk is < 33 (tiramisu)
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                }
                device?.let {
                    Log.d(TAG, "Device attached: ${it.deviceName} (vendor: ${device.vendorId}, product: ${device.productId})")
                    if (device.getVendorId() == 7504) {
                        Toast.makeText( this@MainActivity, "HackRF Device attached.", Toast.LENGTH_SHORT).show()
                        if (!appStateRepository.analyzerRunning.value)
                            appStateRepository.sourceType.set(SourceType.HACKRF)
                    } else if(Pair(it.vendorId, it.productId) in rtlsdrIds) {
                        Toast.makeText( this@MainActivity, "RTL-SDR Device attached.", Toast.LENGTH_SHORT).show()
                        if (!appStateRepository.analyzerRunning.value)
                            appStateRepository.sourceType.set(SourceType.RTLSDR)
                    }
                }
            }
        }
    }

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Activity created.")

        // Get version name:
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName;
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "onCreate: Cannot read version name: " + e.message);
            "<unknown version>"
        }
        val buildType = BuildConfig.BUILD_TYPE
        Log.i(TAG, "This is RF Analyzer $versionName ($buildType) by Dennis Mantz.");

        // Device Info
        val deviceName = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) {
            Build.MODEL
        } else {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        }
        Log.i(TAG, "onCreate: Android Device Info: $deviceName, Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")

        // Purchase Status
        Log.i(TAG, "onCreate: remainingTrialDays=${billingRepository.remainingTrialPeriodDays.value}")

        // create directory for recordings if it does not exist:
        val recordingsDir = File(filesDir, RECORDINGS_DIRECTORY)
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }

        // Check and request POST_NOTIFICATIONS permission if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "onCreate: Check for permissions..")
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission()
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
            sourceOptimalSampleRates = appStateRepository.sourceOptimalSampleRates,
            sourceFrequency = appStateRepository.sourceFrequency,
            sourceSampleRate = appStateRepository.sourceSampleRate,
            sourceSignalStartFrequency = appStateRepository.sourceSignalStartFrequency,
            sourceSignalEndFrequency = appStateRepository.sourceSignalEndFrequency,
            fftAverageLength = appStateRepository.fftAverageLength,
            fftPeakHold = appStateRepository.fftPeakHold,
            maxFrameRate = appStateRepository.maxFrameRate,
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
            squelchEnabled = appStateRepository.squelchEnabled,
            squelch = appStateRepository.squelch,
            recordingRunning = appStateRepository.recordingRunning,
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
            isFullVersion = appStateRepository.isFullVersion,
            fftProcessorData = appStateRepository.fftProcessorData,
            analyzerSurfaceActions = mainViewModel.analyzerSurfaceActions
        )


        // Setup the composable UI with NavController
        setContent {
            val colorTheme by appStateRepository.colorTheme.stateFlow.collectAsState()
            RFAnalyzerTheme(colorTheme = colorTheme) {
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    mainViewModel.navigationEvent.collect { screen ->
                        navController.navigate(screen.route + screen.subUrl) {
                            // if we navigate to the main screen, make sure that the backstack is cleared:
                            if(screen == AppScreen.MainScreen) {
                                // Pop up to the start destination (or a specific destination) and clear the backstack
                                popUpTo(navController.graph.startDestinationId) {
                                    inclusive = true // Clear all destinations up to and including the start destination
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
                                .pointerInput(Unit) {
                                detectTapGestures { focusManager.clearFocus() } // Clears focus when tapping anywhere
                            }
                        ) {
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
                                startDestination = if (appStateRepository.welcomeScreenFinished.value)
                                                       AppScreen.MainScreen.route
                                                   else
                                                       AppScreen.WelcomeScreen.route
                            ) {
                                composable(AppScreen.WelcomeScreen.route) { WelcomeScreen(onFinish = {
                                    appStateRepository.welcomeScreenFinished.set(true)
                                    mainViewModel.navigate(AppScreen.MainScreen)
                                }) }
                                composable(AppScreen.MainScreen.route) { MainScreen(analyzerSurface, mainViewModel, appStateRepository, billingRepository) }
                                composable(AppScreen.RecordingScreen.route) { RecordingsScreen(navController, mainViewModel.recordings, appStateRepository.displayOnlyFavoriteRecordings.stateFlow, mainViewModel.recordingsScreenActions) }
                                composable(AppScreen.LogFileScreen.route) { LogFileScreen(navController, mainViewModel.logContent) }
                                composable(AppScreen.AboutScreen.route) { AboutScreen(versionName ?: "<unknown version>", navController) }
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
                    is UiAction.OnStopClicked  -> onStopClickedAction()
                    is UiAction.OnOpenIQFileClicked  -> onOpenIQFileClicked()
                    is UiAction.OnAutoscaleClicked -> analyzerSurface.autoscale()
                    is UiAction.ShowDialog -> AlertDialog.Builder(this@MainActivity)
                        .setTitle(action.title)
                        .setMessage(action.msg)
                        .setPositiveButton(action.positiveButton) { _, _ ->
                            action.action?.let { it() }
                        }
                        .setNegativeButton(action.negativeButton) { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
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
                    is UiAction.OnSaveLogToFileClicked -> saveFileToUserDirectory(this@MainActivity, action.destUri, File(filesDir, LogcatLogger.logfileName))
                    is UiAction.OnShareLogFileClicked -> shareFile(File(filesDir, LogcatLogger.logfileName), "text/plain", "Share log file via")
                    is UiAction.OnStartRecordingClicked -> if(isBound) analyzerService?.startRecording()
                    is UiAction.OnStopRecordingClicked -> if(isBound) analyzerService?.stopRecording()
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
                    is UiAction.OnSaveRecordingClicked -> saveFileToUserDirectory(this@MainActivity, action.destUri, File(action.filename))
                    is UiAction.OnShareRecordingClicked -> shareFile(File(action.filename), "application/octet-stream", "Share recording via")
                    is UiAction.OnBuyFullVersionClicked -> mainViewModel.buyFullVersion(this@MainActivity)
                    null -> Log.e(TAG, "mainViewModel.uiActions.collect: action is NULL!")
                }
            }
        }

        // Observe App State
        lifecycleScope.collectAppState(appStateRepository.screenOrientation) {
            setScreenOrientation(it)
        }

        // Register the Activity Result Launcher for Intent that start the Rtlsdr driver
        startRtlsdrDriverLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.i(TAG, "startRtlsdrDriverLauncher: RTL2832U driver was successfully started.")
                // Start the AnalyzerService:
                if (isBound) {
                    analyzerService?.startAnalyzer()
                } else {
                    Log.w(TAG, "startRtlsdrDriverLauncher.onActivityResult: Service is not bound! Cannot start Analyzer")
                    appStateRepository.analyzerStartPending.set(false)
                }
            } else {
                val errorId = result.data?.getIntExtra("marto.rtl_tcp_andro.RtlTcpExceptionId", -1) ?: -1
                val exceptionCode = result.data?.getIntExtra("detailed_exception_code", 0) ?: 0
                val detailedDescription: String = result.data?.getStringExtra("detailed_exception_message") ?: ""
                // err_info from RTL2832U:
                val rtlsdrErrInfo = arrayOf(
                    "permission_denied", "root_required", "no_devices_found", "unknown_error", "replug", "already_running"
                )
                val errorMsg =  if (errorId >= 0 && errorId < rtlsdrErrInfo.size) rtlsdrErrInfo[errorId]
                                else "ERROR NOT SPECIFIED"
                Log.e(TAG, "startRtlsdrDriverLauncher.onActivityResult: RTL2832U driver returned with error: " +
                            "$errorMsg ($errorId): $detailedDescription ($exceptionCode)")

                appStateRepository.analyzerStartPending.set(false)

                mainViewModel.showSnackbar(
                    MainViewModel.SnackbarEvent(
                        message = "Error with RTL-SDR Driver: $errorMsg ($errorId): $detailedDescription ($exceptionCode)",
                        buttonText = null,
                        callback = { }
                    ),
                )
            }
        }

        // Initialize the launcher for opening the IQ file
        openIQFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri: Uri? = result.data?.data
                Log.d(TAG, "openIQFileLauncher result: URI: $uri")
                if (uri != null)
                    setFileSourceFromContentUri(uri)
            }
        }

        // Handle incoming intent (when app was started by opening an .iq file with RF Analyzer
        if(intent != null) {
            Log.d(TAG, "onCreate: Incoming intent: action=${intent.action} categories=${intent.categories} data=${intent.data}")
            if("android.intent.action.VIEW" == intent.action && intent.data != null)
                handleIncomingFile(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (isGranted) {
                    Log.d(TAG, "POST_NOTIFICATIONS permission granted.")
                } else {
                    Log.w(TAG, "POST_NOTIFICATIONS permission denied.")
                }
            }
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        Log.d(TAG, "onNewIntent: received intent: action=${intent.action} categories=${intent.categories} data=${intent.data}")
        super.onNewIntent(intent)
        handleIncomingFile(intent)
    }

    private fun handleIncomingFile(intent: Intent) {
        val uri = intent.data
        Log.d(TAG, "handleIncomingFile: Intent.data=$uri")
        if (uri != null) {
            if (appStateRepository.analyzerRunning.value) {
                lifecycleScope.launch {
                    Log.d(TAG, "handleIncomingFile: Analyzer is running. Show dialog to the user after 1 second delay")
                    delay(1000)
                    mainViewModel.showSnackbar(MainViewModel.SnackbarEvent(
                        message = "Load incoming file into File Source? (Analyzer will stop!)",
                        buttonText = "Load File",
                        callback = {
                            onStopClickedAction()
                            setFileSourceFromContentUri(uri)
                            mainViewModel.showSnackbar(MainViewModel.SnackbarEvent("File loaded. Press PLAY to start Analyzer!"))
                        }
                    ))
                }
            } else
                setFileSourceFromContentUri(uri)
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: Binding to service if running.")
        // Bind Service:
        Intent(this, AnalyzerService::class.java).also { intent ->
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }

        // Register USB Broadcast Receiver
        val filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        registerReceiver(usbBroadcastReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: Unbinding from service.")
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        // Unregistering the USB broadcast receiver
        unregisterReceiver(usbBroadcastReceiver)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Activity destroyed.")
        super.onDestroy()
    }

    private fun onStartClickedAction() {
        Log.d(TAG, "onStartPressedAction")
        // If RTL-SDR Source is configured (without external server) we need to start the driver activity:
        if (appStateRepository.sourceType.value == SourceType.RTLSDR &&
            !appStateRepository.rtlsdrExternalServerEnabled.value) {
            startRtlsdrDriver() // this will call analyzerService.startAnalyzer() once successful
        } else {
            // Start the AnalyzerService:
            if (isBound) {
                analyzerService?.startAnalyzer()
            } else {
                Log.w(TAG, "onStartClickedAction: Service is not bound! Cannot start Analyzer")
            }
        }
    }

    private fun onStopClickedAction() {
        Log.d(TAG, "onStopPressedAction: Stopping Service..")
        if(isBound) {
            analyzerService?.stopAnalyzer()
        } else {
            Log.w(TAG, "onStopClickedAction: Service is not bound! Cannot stop Analyzer")
        }
    }

    private fun onOpenIQFileClicked() {
        // Show system file chooser
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // Allow any file type
        }
        openIQFileLauncher.launch(intent)
    }

    private fun setFileSourceFromContentUri(uri: Uri) {
        var filename: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                cursor.moveToFirst()
                filename = cursor.getString(nameIndex)
            }
        }
        Log.d(TAG, "setFileSourceFromContentUri: URI: $uri (Original Filename: $filename)")
        mainViewModel.setFilesourceUri(uri.toString(), filename)
    }

    private fun startRtlsdrDriver(): Boolean {
        // start local rtl_tcp instance:
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setClassName("marto.rtl_tcp_andro", "com.sdrtouch.rtlsdr.DeviceOpenActivity");
                data = "iqsrc://-a 127.0.0.1 -p 1234 -n 1".toUri()
            }
            startRtlsdrDriverLauncher.launch(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            // Driver not installed
            Log.e(TAG, "openSource: RTL2832U is not installed")
            appStateRepository.analyzerStartPending.set(false)
            // Show a dialog that links to the play market:
            AlertDialog.Builder(this)
                .setTitle("RTL2832U driver not installed!")
                .setMessage("You need to install the (free) RTL2832U driver to use RTL-SDR dongles.")
                .setPositiveButton(
                    "Install from Google Play"
                ) { dialog, whichButton ->
                    val marketIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=marto.rtl_tcp_andro")
                    )
                    startActivity(marketIntent)
                }
                .setNegativeButton(
                    "Cancel"
                ) { dialog, whichButton ->
                    // do nothing
                }
                .show()
            return false// abort the start of the analyzer service
        }
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

    private fun saveFileToUserDirectory(context: Context, destUri: Uri, srcFile: File) {
        try {
            val contentResolver = context.contentResolver
            if (!srcFile.exists()) {
                Log.w(TAG, "saveFileToUserDirectory: File ${srcFile.absolutePath} does not exist!")
                return
            }
            contentResolver.openOutputStream(destUri)?.use { outputStream ->
                srcFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream) // Copy file contents
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to save file ($e)", Toast.LENGTH_SHORT).show()
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