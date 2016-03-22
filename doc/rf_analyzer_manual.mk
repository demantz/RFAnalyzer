RF Analyzer Manual
==================

RF Analyzer is a Software Defined Radio (SDR) App for Android which can 
be used to view a FFT plot and a waterfall plot of the frequency spectrum 
received by a HackRF or RTL-SDR. It can also demodulate audio from AM and 
FM signals as well as record raw IQ samples and scan the spectrum for
activity!

![RF Analyzer](http://4.bp.blogspot.com/-gdSsQ1COybM/VEQkplyqFOI/
AAAAAAAADt8/hJhA0i6WyYY/s0/RF%2BAnalyzer.jpg)

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

Alternativeley the app can be downloaded from GitHub and manually be installed
on the device. Just download the APK file on your device and open it. This
only works if 'Apps from unkown sources' is enabled in the Android security
settings. Keep in mind that with this method updates have to be installed
manually too!

#todo: rtl2832u driver


3. Basic Usage
--------------

Exploring the electro-magnetic spectrum with RF Analyzer is easy. Figure 1
shows the main screen of the app after the first start. The default device
type is set to HackRF. If you are using a RTL stick or if you want to use
a precaptured IQ file as source you have to change the source type in the
settings (see DSP settings).

[Figure 1](bild)

To start the Analyzer press the 'play' button in the Action Bar of the app.
This will put the SDR device into receive mode and starts the FFT (Fast
Fourier Transform) processing (see figure 2).

[Figure 2](bild)


3.1 FFT & Waterfall Plot
------------------------

The Analyzer shows a FFT plot on the top and a waterfall plot on the bottom
of the screen. The horizontal axis is the frequency axis. By swiping left or
right it is possible to browse the spectrum. The SDR hardware will automatically
re-tune in order to allow fluent browsing. By using horizontal pinch-to-zoom
gestures you can adjust the frequency resolution. Again, the sample rate of
the SDR device will automatically be adjusted accordingly. Pressing the
'Jump to frequency' button in the Action Bar (figure 1 xx) will show a
dialog to enter an absolute frequency and sample rate (figure 3).

[Figure 3](bild)

The vertical axis of the FFT plot show the magnitude in dB. This value is
relative to the input signal from the SDR device. It is currently NOT possible
to show the absolute magnitude in dBm. The vertical scale can be adjusted by
swiping up or down on the vertical axis and by using vertical pinch-to-zoom
gestures. The adjustment will also affect the colors of the waterfall plot.
By pressing the 'Autoscale' button in the Action Bar (figure 1 xx) the
vertical axis will be scaled to fit the current signal.

If the SDR device supports gain adjustment, the 'gain' button in the Action
Bar will show you a dialog with gain control settings (figure 4).

[Figure 4](bild)



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

[Figure 5](bild)

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
* FFT drawing type: Use lines or bars to draw the FFT plot
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


4.2 DSP Settings
----------------

* Source Type: Set this according to your hardware: HackRF, RTL-SDR, IQ-File
* FFT size: Set the size of the FFT (256,..,65536). Higher values will increase
  the precision of the results but also the load on the CPU.
* Averaging: Averaging the FFT results with previous results will make it
  easy to spot a very noisy but continous signal. Default is off. Can be set
  to values between 1 and 20.
* Peak hold: If enabled, this option will show small dots to indicate the
  maximum magnitude for each frequency component. This helps to spot very
  short and spontanious signals.
* Auto start: If enabled, RF Analyzer will start the FFT on each app start.

More signal processing settings are available for each specific hardware type.


4.3 HackRF Settings
-------------------




4.4 RTL-SDR Settings
--------------------



4.5 File Source Settings
------------------------


4.6 Advanced Settings
---------------------




5. Recording
---------



6. Bookmarks
------------

<comming soon>


7. Scanning
-----------

<comming soon>


8. Trouble Shooting
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


8.1 Installation and Update Issues
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


8.2 App Crashes
---------------

The issue: The RF Analyzer app crashes.

Crashes are always related to bugs in the RF Analyzer code. I try to get
rid of them as good as I can, but nobody is perfect. If the app crashes
please send the bug report through the Android system. This report will
appear on my Google developer console and provides me with all information
that is needed to locate the bug in the code. If you found out that the
crash is reproducable please add a note to the bug report and tell me how
to reproduce the crash!


8.3 SDR Hardware Issues
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


8.4 Demodulation Issues
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

