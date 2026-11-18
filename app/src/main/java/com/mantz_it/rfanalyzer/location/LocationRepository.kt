package com.mantz_it.rfanalyzer.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.mantz_it.rfanalyzer.database.AppStateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * <h1>RF Analyzer - Location Repository</h1>
 *
 * Module:      LocationRepository.kt
 * Description: The one source of the user's location (UserLocation.kt) for the whole app. The
 *              choice is stored in AppStateRepository; the country guess is taken again whenever
 *              the app comes to the front ([refresh]), so it follows a traveller's phone. A device
 *              fix is only taken while the user chose "this device's location" and granted the
 *              location permission - the approximate one is enough - through the platform
 *              LocationManager (neither flavor has Play Services), at most every few hours and
 *              never in the background. The maps start on the user's area ([mapHome]).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Singleton
class LocationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appStateRepository: AppStateRepository,
) {
    companion object {
        private const val TAG = "LocationRepository"
        /** A device fix younger than this is good enough - nothing here moves faster than a car. */
        private const val FIX_MAX_AGE_MS = 6 * 60 * 60 * 1000L
        private const val FIX_TIMEOUT_MS = 20_000L
        private const val COUNTRY_BOUNDS_ASSET = "location/country_bounds.csv"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val guess = MutableStateFlow(currentGuess())
    private val fixMutex = Mutex()

    val location: StateFlow<UserLocation> = combine(
        appStateRepository.locationArea.stateFlow,
        guess,
        appStateRepository.locationPositionMode.stateFlow,
        appStateRepository.locationLocator.stateFlow,
        appStateRepository.locationDeviceFix.stateFlow,
    ) { area, guess, positionMode, locator, deviceFix ->
        resolveUserLocation(area, guess, positionMode, locator, deviceFix)
    }.stateIn(scope, SharingStarted.Eagerly, UserLocation())

    /** Each country's box for [mapHome], read when it is first needed (off the main thread). */
    private val countryBounds: Map<String, GeoBounds> by lazy {
        runCatching { context.assets.open(COUNTRY_BOUNDS_ASSET).bufferedReader().useLines(::parseCountryBounds) }
            .onFailure { Log.e(TAG, "countryBounds: cannot read $COUNTRY_BOUNDS_ASSET", it) }
            .getOrDefault(emptyMap())
    }

    /** Where the maps start and what they mark as home - see [MapHome]. */
    val mapHome: StateFlow<MapHome?> = location.map { it.mapHome(countryBounds::get) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Suspends until the stored choice is loaded - before it, [location] holds only the guess. */
    suspend fun awaitLoaded() {
        listOf(appStateRepository.locationArea, appStateRepository.locationLocator, appStateRepository.locationDeviceFix).forEach { it.awaitInitialized() }
        appStateRepository.locationPositionMode.awaitInitialized()
    }

    /** The country as the phone suggests it, ignoring a choice of the user - "Automatic (Germany)". */
    val guessedCountry: StateFlow<Pair<String, CountrySource>?> get() = guess

    /** Takes the country guess again and a device fix if the last one is old. Called whenever the
     *  app comes to the front. */
    fun refresh() {
        guess.value = currentGuess()
        if (appStateRepository.locationPositionMode.value == PositionMode.DEVICE) scope.launch { updateDeviceFix(force = false) }
    }

    /** "" = the guess, else a country code; see [chooseRegion] for a region alone. */
    fun chooseCountry(countryCode: String) = appStateRepository.locationArea.set(countryCode)
    fun chooseRegion(region: ItuRegion) = appStateRepository.locationArea.set(AREA_REGION_PREFIX + region.number)

    /** For [PositionMode.DEVICE] the caller takes the first fix ([updateDeviceFix]) - it has the permission dialog's result. */
    fun choosePositionMode(mode: PositionMode) {
        appStateRepository.locationPositionMode.set(mode)
        if (mode != PositionMode.DEVICE) appStateRepository.locationDeviceFix.set("")
    }

    fun chooseLocator(locator: String) = appStateRepository.locationLocator.set(locator.trim())

    fun hasLocationPermission(): Boolean = hasLocationPermission(context)

    /**
     * Takes a device fix (the newest last-known one if it is recent, else a current one) and stores
     * it. False if there is no permission, location is off, or no provider delivered in time.
     */
    suspend fun updateDeviceFix(force: Boolean): Boolean = fixMutex.withLock {
        if (!hasLocationPermission()) return false
        if (!force) {
            val last = parseDeviceFix(appStateRepository.locationDeviceFix.value)
            if (last != null && System.currentTimeMillis() - last.third < FIX_MAX_AGE_MS) return true
        }
        val fix = withContext(Dispatchers.IO) { deviceLocation() } ?: return false
        Log.i(TAG, "updateDeviceFix: fix from ${fix.provider}, accuracy ${fix.accuracy} m")
        appStateRepository.locationDeviceFix.set(formatDeviceFix(fix.latitude, fix.longitude, fix.time))
        return true
    }

    @SuppressLint("MissingPermission") // checked by the caller; a provider that needs more throws, caught below
    private suspend fun deviceLocation(): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        if (!LocationManagerCompat.isLocationEnabled(manager)) return null
        val providers = manager.getProviders(true)
        val lastKnown = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < FIX_MAX_AGE_MS) return lastKnown
        // The network/fused providers answer in seconds and are enough; GPS only if nothing else is there.
        val preferred = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { it in providers }
        for (provider in preferred) {
            val current = withTimeoutOrNull(FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    try {
                        LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
                    } catch (e: SecurityException) {
                        cont.resume(null) // GPS with only the approximate permission (before Android 12)
                    }
                }
            }
            if (current != null) return current
        }
        return lastKnown
    }

    private fun currentGuess(): Pair<String, CountrySource>? {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val timeZoneRegion = runCatching { android.icu.util.TimeZone.getRegion(TimeZone.getDefault().id) }.getOrNull()
        return guessCountry(
            network = telephony?.takeIf { it.phoneType != TelephonyManager.PHONE_TYPE_CDMA }?.networkCountryIso,
            timeZoneRegion = timeZoneRegion,
            sim = telephony?.simCountryIso,
            language = Locale.getDefault().country,
        )
    }
}
