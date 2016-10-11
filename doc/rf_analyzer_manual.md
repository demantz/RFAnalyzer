RF Analyzer Manual
==================

RF Analyzer is a Software Defined Radio (SDR) App for Android which can 
be used to view a FFT plot and a waterfall plot of the frequency spectrum 
received by a HackRF or RTL-SDR. It can also demodulate audio from AM and 
FM signals as well as record raw IQ samples and scan the spectrum for
activity!

![RF Analyzer](https://raw.githubusercontent.com/demantz/RFAnalyzer/master/doc/screenshots/IMG_1940.jpg)

This manual is intended to help people to use the app for their needs. I
try to explain how to use all the features and also explain (to a certain
degree) how the features are implemented. This might help some advanced
users with SDR and DSP background to get a better understanding of the
results.

**License:      GPLv2**

**Copyright (C) 2015  Dennis Mantz**

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.


1. System Requirements and Prerequisites
----------------------------------------

In order to run RF Analyzer, your device must meet the following system
requirements:

* Recent processor: I suggest at least a quad-core CPU. The Nexus 7 2012
  can be used as a reference device.
* USB port with OTG support and enough output power.
  Tip: In case the device doesn't support OTG with it's stock ROM, there might
  exist a custom ROM that enables this feature.
  Tip: In case the SDR hardware doesn't get enough power from the Android
  device consider using higher quality (and shorter) USB cables and adapters.
  Use a powered USB hub or a y-cable if power output is still too low.
* Android 4.0 or higher
* Only if you want support for the RTL-SDR: The latest version of the
  [RTL2832U driver for Android]
  (https://play.google.com/store/apps/details?id=marto.rtl_tcp_andro)

In addition to an Android device with the RF Analyzer app installed, the
following components are needed:

* SDR hardware: Supported SDR devices are:
	* HackRF
	* RTL-SDR (with extern RTL2832U driver - see 'Installation')
	* IQFile: RF Analyzer also supports replaying of captured sample files. 
	  You don't need SDR hardware just to test the app with precaptured 
	  samples!
* USB-OTG cable: This adapter connects the SDR hardware to the USB port
  of the Android device.
  Tip: For the HackRF you could also use the [Kos cable]
  (http://hakshop.myshopify.com/products/micro-to-micro-otg?variant=211796287)


2. Installation
---------------

Installation of the RF Analyzer app is very easy from the Google Play Store.
It will automatically be updated once a new version is released.
Link: https://play.google.com/store/apps/details?id=com.mantz_it.rfanalyzer
Beta-Program: https://play.google.com/apps/testing/com.mantz_it.rfanalyzer

Alternativeley the app can be downloaded from GitHub and be installed manually
on the device. Just download the APK file on your device and open it. This
only works if 'Apps from unkown sources' is enabled in the Android security
settings. Keep in mind that with this method updates have to be installed
manually too!
Link: https://github.com/demantz/RFAnalyzer/blob/master/RFAnalyzer.apk?raw=true

Some SDR hardware drivers are not directly included into the app, but available
as external apps (e.g. the rtl2832u driver for RTL-SDR dongles). These
driver apps have to be installed on the device. If RF Analyzer cannot find
the correct driver app, it will show the option to download it from
Google Play.


3. Basic Usage
--------------

Exploring the electro-magnetic spectrum with RF Analyzer is easy. Figure 1
shows the main screen of the app after the first start. The default device
type is set to HackRF. If you are using a RTL stick or if you want to use
a precaptured IQ file as source you have to change the source type in the
settings (see DSP settings).

![Figure 1](https://raw.githubusercontent.com/demantz/RFAnalyzer/master/doc/screenshots/rf_analyzer_not_running.png)

To start the Analyzer press the 'play' button in the Action Bar of the app.
This will put the SDR device into receive mode and starts the FFT (Fast
Fourier Transform) processing (see figure 2).

![Figure 2](https://raw.githubusercontent.com/demantz/RFAnalyzer/master/doc/screenshots/rf_analyzer_running.png)


3.1 FFT & Waterfall Plot
------------------------

The Analyzer shows a FFT plot on the top and a waterfall plot on the bottom
of the screen. The horizontal axis is the frequency axis. By swiping left or
right it is possible to browse the spectrum. The SDR hardware will automatically
re-tune in order to allow fluent browsing. By using horizontal pinch-to-zoom
gestures you can adjust the frequency resolution. Again, the sample rate of
the SDR device will automatically be adjusted accordingly. Pressing the
'Jump to frequency' button in the Action Bar will show a dialog to enter an
absolute frequency and sample rate (figure 3).

![Figure 3](https://raw.githubusercontent.com/demantz/RFAnalyzer/master/doc/screenshots/tune_to_frequency.png)

The vertical axis of the FFT plot show the magnitude in dB. This value is
relative to the input signal from the SDR device. It is currently NOT possible
to show the absolute magnitude in dBm. The vertical scale can be adjusted by
swiping up or down on the vertical axis and by using vertical pinch-to-zoom
gestures. The adjustment will also affect the colors of the waterfall plot.
By pressing the 'Autoscale' button in the Action Bar (figure 1 xx) the
vertical axis will be scaled to fit the current signal.

If the SDR device supports gain adjustment, the 'gain' button in the Action
Bar will show you a dialog with gain control settings (figure 4).

![Figure 4](https://raw.githubusercontent.com/demantz/RFAnalyzer/master/doc/screenshots/gain_settings_hackrf.png)


3.2 Audio Demodulation
----------------------

With RF Analyzer it is possible to demodulate analog audio signals in real
time. Demodulation is turned off by default. To turn it on, press the icon
in the Action Bar labeled 'off'. A dialog will show up and you can select
the demodulation mode (AM, narrow/wide FM, LSB, USB). With the demodulation
turned on, the FFT plot shows a channel selector (needle) for tuning (see 
Figure 5). The needle can be positioned by dragging it with a finger (in 
the center of the needle) or by tapping to the desired position. The 
channel width can also be adjusted by dragging the left or right boundaries 
of the selector. 

![Figure 5](https://raw.githubusercontent.com/demantz/RFAnalyzer/master/doc/screenshots/demodulation_running.png)

The channel selector contains a horizontal line which indicates the squelch
threshold that is currently set. It changes the color from red to green
once the signal satisfies the squelch setting. The squelch can be adjusted
by dragging the horizontal line up or down. Note that you can read the
current signal strength from the signal strength indicator in the top right
corner.

Real time audio demodulation requires a decent processor and will drain
your battery fast!


4. Settings
-----------

4.1 General Settings
--------------------

All settings in the category 'View' will affect the appearance and basic
behaviour of the app:
* Color map type: Select a color map for the waterfall plot (JET, HOT, GQRX)
* FFT drawing type: Use lines or bars to draw the FFT plot (see figure 6)
* Screen orientation: Auto / (inverse) portrait / (inverse) landscape
* Spectrum-Waterfall ratio: Proportion of FFT and waterfall plot
* Font Size:  Adjust the font size (e.g. if your device has a uncommon resolution)
* Vertical pinch to zoom: Enable/disable pinch to zoom in vertical direction
* Vertical scrolling: Enable/disable drag to scroll in vertical direction
* Separate horizontal/vertical zoom/scroll: If enabled, vertical gestures
  are only recognized in the area of the vertical axis. If disabled, vertical
  gestures are recognized on the entire FFT area.
* Relative Frequencies: If enabled, the horizontal axis will show the
  frequency relative to the current center frequency of the SDR hardware.

![Figure 6](https://raw.githubusercontent.com/demantz/RFAnalyzer/master/doc/screenshots/alternative_draw_mode_and_peak_hold.png)


4.2 DSP Settings
----------------

* Source Type: Set this according to your hardware: HackRF, RTL-SDR, IQ-File
* Source Settings: Open the source settings screen (see chapter 4.3 - 4.5)
* FFT size: Set the size of the FFT (256,..,65536). Higher values will increase
  the precision of the results but also the load on the CPU.
* Averaging: Averaging the FFT results with previous results will make it
  easy to spot a very noisy but continous signal. Default is off. Can be set
  to values between 1 and 20.
* Peak hold: If enabled, this option will show small dots to indicate the
  maximum magnitude for each frequency component. This helps to spot very
  short and spontanious signals. (see figure 6)
* Auto start: If enabled, RF Analyzer will start the FFT on each app start.

More signal processing settings are available for each specific hardware type.

![Figure 7](https://raw.githubusercontent.com/demantz/RFAnalyzer/master/doc/screenshots/source_type_setting.png)

![Figure 8](https://raw.githubusercontent.com/demantz/RFAnalyzer/master/doc/screenshots/source_settings.png)

4.3 HackRF Settings
-------------------

 * RF Amplifier: Activate or deactivate the amplifier build into the HackRF.
   CAUTION: Do not turn on the amplifier and expose the HackRF to strong
            RF signals as that might destroy the amplifier circuit!
 * Antenna Power: Activate or deactivate the antenna port power of the
   HackRF (max. 50mA at 3.3V). This can be used with powered antennas and
   low power external amplifiers.
 * Up-/Down-converter frequency offset: Use this setting to apply a frequency
   offset (positive or negative) to the frequency axis of RF Analyzer when
   using a HackRF. This is intended to be used with external up- or down
   converters.


4.4 RTL-SDR Settings
--------------------

 * Use external rtl_tcp server: If enabled, this option allows the app to
   use an external rtl_tcp server instance (not running on the Android
   device). E.g. it is possible to run rtl_tcp on another machine on the
   same Wi-Fi network and then connect RF Analyzer to this instance. Note
   that the bandwidth of the network has to be sufficiently high.
   * IP address: IP address of the external rtl_tcp instance
   * TCP port: TCP port on which the external rtl_tcp instance listens
   Example: start rtl_tcp with the following command:
   > rtl_tcp -a <IP address> -p <TCP port>
 * Frequency correction: Enter a frequency correction value in ppm
   (parts-per-million). The correct ppm value can be measured manually
   (tune to a known frequency and calculating the offset in ppm) or
   on another machine by using rtl_test -p.
 * Up-/Down-converter frequency offset: Use this setting to apply a frequency
   offset (positive or negative) to the frequency axis of RF Analyzer when
   using a RTL-SDR. This is intended to be used with external up- or down
   converters such as the Ham-It-Up.
   Example: For the Ham-It-Up converter the frequency offset setting should
   be set to -125000000Hz. That means that the app will display the incoming
   signals as if they were 125MHz lower than they actually are. Now
   when tuning the app to 14MHz you see the signals that are actually
   incoming at 139MHz (this is where the 14MHz band is found after up-
   converting it with the Ham-It-Up converter).


4.5 File Source Settings
------------------------

 * File Source Frequency: The baseband frequency at which the replayed IQ
   file was recorded. This setting is automatically adjusted when selecting
   an IQ file with XXXHz (or similiar) in its file name. Therefore it is
   not necessary to manually edit this setting if using recorded files from
   RF Analyzer that have the default naming scheme.
 * File Source Sample Rate: The sample rate at which the replayed IQ file
   was recorded. This setting is automatically adjusted when selecting
   an IQ file with XXXSps (or similiar) in its file name. Therefore it is
   not necessary to manually edit this setting if using recorded files from
   RF Analyzer that have the default naming scheme.
 * Source File: Path to the IQ file that should be replayed.
 * File Format: Currently two common IQ file formats are supported:
   * 8-bit signed IQ values (e.g. used by the HackRF)
   * 8-bit unsigned IQ values (e.g. used by RTL-SDR dongles)
   This setting is automatically adjusted when selecting an IQ file with
   'rtlsdr' or 'hackrf' in its file name. Therefore it is not necessary to
   manually edit this setting if using recorded files from RF Analyzer that
   have the default naming scheme.
 * Repeat: If enabled, the file will be replayed in a loop (start from the
   beginning after the end of the file has reached. Otherwise the analyzer
   stops at the end of the file.


4.6 Advanced Settings
---------------------

 * Show debug information: Display additional information on the screen
   (current frame rate and load on the FFT calculation process).
 * Dynamic frame rate: Automatically adjust the frame rate to the current
   load on the FFT calculation thread.
 * Frame Rate: Fixed frame rate that shall be used if the dynamic frame
   rate option is not enabled.
 * Logging: Enable the creation of a log file.
 * Log File: File path that shall be used to write the log file.
 * Show Log: Start a text viewer application to show the current log
   file. Usually the text viewer allows to share the log via email (e.g.
   in order to report bugs and issues to the developer of RF Analyzer).


5. Recording
---------

RF Analyzer allows the recording of raw IQ data into a file. The feature
is equivalent to using rtl_sdr or hackrf_transfer -r. The recording dialog
can be opened if the FFT is currently running by using the record button
(white circle) in the action bar (see figure 9). The following settings can
be selected:
 * Center frequency: The frequency to which the SDR hardware will be tuned
   (this corresponds to the -f option of rtl_sdr and hackrf_transfer).
 * Sample rate: The sample rate at which the SDR should be operated.
   NOTE: During audio demodulation this value is always fixed to 1MSps.
 * File name: Name of the recorded file. The file will be stored in the
   RFAnalyzer directory in the default directory for external media.
   On most Android devices this results in /sdcard/RFAnalyzer/ or
   /storage/emulated/0/RFAnalyzer/. However, the actual directory can be
   different on some devices.
 * Stop after: If this option is enabled, the recording will automatically
   stop after the defined time intervall has passed or the file reached
   the defined file size.
Once the recording is running, it is indicated on the screen and can be
stopped by pressing the recording button again. Files that were recorded
can be replayed using the File Source option of RF Analyzer.

![Figure 9](https://raw.githubusercontent.com/demantz/RFAnalyzer/master/doc/screenshots/recording_dialog.png)


6. Bookmarks
------------

The bookmarks dialog allows to save frequencies in order to easily tune
to them later again. The dialog can be opend through the bookmark buttion
(white star) in the action bar. Bookmarks are organised in categories
(see figure 10).
It is possible to add, edit and delete bookmarks as well as categories.
A long press on a category or bookmark opens a context menu with the
option to edit or delete the selected item.

A bookmark stores the following attributes (see figure 11):
 * Name: User defined name of the bookmark (does not have to be unique).
 * Category: The category to which the bookmark belongs.
 * Frequency: Channel frequency of the station that is bookmarked.
 * Channel Width: Bandwidth of the station (this corresponds to the
   setting of the channel filter when demodulating audio).
 * Mode: Demodulation mode that shall be used with this channel.
 * Squelch: Squelch threshold that shall be used with this channel.
 * Comment: Arbitrary comment by the user.

By selecting a bookmark from the dialog, RF Analyzer will automatically
tune to the corresponding channel frequency and set up the demodulation
according to the bookmark attributes.

![Figure 10](https://raw.githubusercontent.com/demantz/RFAnalyzer/master/doc/screenshots/bookmark_categories.png)

![Figure 11](https://raw.githubusercontent.com/demantz/RFAnalyzer/master/doc/screenshots/bookmark_list.png)


7. Trouble Shooting
-------------------

This section is intended to help those who can't get the app to work. Please
note that RF Analyzer is currently developed by only one person and this
project was born out of a hobby. I depent on helpful people who tell me
their problems with the app and provide the necessary information so that
I can fix bugs in the software.

I collected a small list of common problems people seem to have with the app.
If you can not solve your problem with these tips please contact me and
explain your issue. My email address is dennis.mantz@googlemail.com but it
would be even better if you use the issue tracking system of GitHub:
https://github.com/demantz/RFAnalyzer/issues

In many cases I will ask for the log file which can be found under the
Advanced Settings!


7.1 Installation and Update Issues
----------------------------------

If you have trouble with installing the app or doing updates please note
the following:
* The app is available on Google Play and from GitHub. Both versions are
  identical and have the same feature. The only difference is the signature.
  The Google Play version is signed with my developer key while the GitHub
  version is signed with a debug key. This makes the versions incompatible!
* Installing the Google Play version on top of the GitHub version or vice
  verca is not possible. You have to uninstall the old version first!
* The GitHub version does not get updated automatically. You have to check
  for updates yourself and install them manually!
* The app is open source and everyone can build their own version of the
  app. Please be careful when installing software from untrusted sources
  and note that I do not take responsibility for software build by anyone
  other than myself!


7.2 App Crashes
---------------

The issue: The RF Analyzer app crashes.

Crashes are always related to bugs in the RF Analyzer code. I try to get
rid of them as good as I can, but nobody is perfect. If the app crashes
please send the bug report through the Android system. This report will
appear on my Google developer console and provides me with all information
that is needed to locate the bug in the code. If you found out that the
crash is reproducable please add a note to the bug report and tell me how
to reproduce the crash!


7.3 SDR Hardware Issues
-----------------------

The issue: You managed to install the app and you are able to start it but
you somehow can't get the FFT to work.

* Source not found:
  If you get a message like 'source not found' or 'Cannot open HackRF/RTL-SDR'
  the app can't see the USB device listed.
	* Make sure you selected the correct source type (HackRF, RTL-SDR or 
	  Filesource) in the settings!
	* Make sure your smartphone/tablet supports USB OTG
	* Check if the SDR device gets enough power. Maybe try a shorter or
	  higher quality USB cable / adapter. You can also try to use a powered
	  USB hub or a Y-cable.
* 'RTL-SDR: root required':
  This is an error from the RTL2832U driver. Please use the RTL2832U driver
  with the same settings as with the SDR Touch app (if you use this app too).
  For most Android devices and versions you don't need root priviledges to
  access the RTL dongle. But wrong settings in the RTL2832U app might cause
  this issue. Also make sure you are using the latest version of this driver.
  Uninstalling and reinstalling it might also help with strange issues.


7.4 Demodulation Issues
-----------------------

The issue: Demodulation is running (is not OFF) but you can't hear anything.

* Squelch threshold too high:
  Check your squelch setting. Is the vertical bar between the channel boundaries
  low enough (bar is colored green) or too high (bar is colored red)?
* Media volume is too low:
  Check your volume settings. If RF Analyzer is the foreground app you can
  adjust the volume with the volume keys of your device.


The issue: The demodulated audio is stuttering.

* Device processor to slow:
  In order to demodulate audio a recent multicore processer is recomended
* Background activiy:
  Is another app running expensive tasks in the background. Is RF Analyzer
  recording to a file?

