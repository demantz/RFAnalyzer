package com.mantz_it.rfanalyzer.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.mantz_it.rfanalyzer.analyzer.AnalyzerService
import com.mantz_it.rfanalyzer.source.UsbSdrType
import com.mantz_it.rfanalyzer.ui.tabs.SourceType

/**
 * <h1>RF Analyzer - USB Attach Activity</h1>
 *
 * Module:      UsbAttachActivity.kt
 * Description: Receives USB_DEVICE_ATTACHED for the supported SDRs
 *              (res/xml/usb_sdr_devices.xml). Android offers to open RF Analyzer when one is
 *              plugged in; accepting grants the USB permission, and with "Always open" it is
 *              granted on every re-plug without a dialog. Enabled only while the user opted in
 *              (start on plug-in) or a dropped-out SDR is awaited
 *              (AnalyzerService.updateUsbAttachHandling); otherwise Android doesn't offer RF
 *              Analyzer at all. Runs in its own task and shows nothing, so a running app stays
 *              where it is:
 *               - app in front, or a USB dropout pending (AnalyzerService waits in the
 *                 foreground): the service restarts or starts the analyzer
 *                 (AnalyzerService.onUsbSdrAttached)
 *               - app in the background: the service starts in the foreground and starts the
 *                 analyzer there (AnalyzerService.startForUsbSdr - allowed while this activity is
 *                 in front)
 *               - app not running (enabled, so the user opted in): opens it
 *                 (MainActivity.EXTRA_USB_SDR_ATTACHED)
 *              The permission is already granted when this starts, so starting the analyzer here
 *              never shows a second dialog.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class UsbAttachActivity : Activity() {

    companion object {
        fun setEnabled(context: Context, enabled: Boolean) {
            val component = ComponentName(context, UsbAttachActivity::class.java)
            val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (context.packageManager.getComponentEnabledSetting(component) != state)
                context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceType = UsbSdrType.deviceFromIntent(intent)?.let { UsbSdrType.of(it) }?.sourceType
        val service = AnalyzerService.instance
        when {
            sourceType == null -> {}
            service != null && (MainActivity.isStarted || service.isWaitingForUsbSdr) -> service.onUsbSdrAttached(sourceType)
            !MainActivity.isCreated || !AnalyzerService.startForUsbSdr(this, sourceType) -> openApp(sourceType)
        }
        finish()
    }

    private fun openApp(sourceType: SourceType) {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_USB_SDR_ATTACHED, sourceType.name))
    }
}
