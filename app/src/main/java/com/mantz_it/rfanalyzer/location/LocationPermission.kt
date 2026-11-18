package com.mantz_it.rfanalyzer.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * <h1>RF Analyzer - Location Permission</h1>
 *
 * Module:      LocationPermission.kt
 * Description: Asking for the location permission from a button. Android shows its dialog at most
 *              twice: after the second "Don't allow" every request is refused at once, without a
 *              dialog - the button would do nothing. Then this says where to allow it instead and
 *              offers the app's settings.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** Approximate location only - the manifest says why. */
private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)

fun hasLocationPermission(context: Context): Boolean =
    LOCATION_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}

private fun Activity.shouldExplainLocation(): Boolean =
    LOCATION_PERMISSIONS.any { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }

/**
 * Returns the action for a "my location" button: [onGranted] at once if the permission is there,
 * else after Android's dialog granted it (approximate location, about 2 km). A request
 * that Android refused without showing its dialog opens a hint with the way to the app's settings.
 */
@Composable
fun rememberLocationPermissionRequest(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val currentOnGranted by rememberUpdatedState(onGranted)
    var showSettingsHint by remember { mutableStateOf(false) }
    // Android advises an explanation only between the first and the second denial; false before
    // and after a request means its dialog wasn't shown - the user denied for good earlier.
    var explainedBefore by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        when {
            results.values.any { it } -> currentOnGranted()
            // an empty result is a dismissed dialog, not a denial
            results.isNotEmpty() && !explainedBefore && activity?.shouldExplainLocation() == false -> showSettingsHint = true
        }
    }
    if (showSettingsHint) {
        AlertDialog(
            onDismissRequest = { showSettingsHint = false },
            title = { Text("Location access is off") },
            text = { Text("Android no longer asks for RF Analyzer's location permission. Allow it in the app's settings: Permissions → Location.") },
            confirmButton = {
                TextButton(onClick = { showSettingsHint = false; openAppSettings(context) }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { showSettingsHint = false }) { Text("Cancel") } },
        )
    }
    return {
        if (hasLocationPermission(context)) currentOnGranted()
        else {
            explainedBefore = activity?.shouldExplainLocation() == true
            launcher.launch(LOCATION_PERMISSIONS)
        }
    }
}
