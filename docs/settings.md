# Settings

The **Settings** tab lets you customize the app's behavior to your preferences.

## Location

Where you listen from. RF Analyzer uses it to preselect the region of the band
plans when importing default lists and to start the maps on your area.

- **Country or region** - Out of the box, the country is taken from what the
  phone already knows, without any permission: the mobile network, the time
  zone, the SIM card or the language setting (shown next to the country, e.g.
  "from your mobile network"). Tap to choose a country yourself, only an ITU
  region if you'd rather not tell more, or **Automatic** to go back to the
  phone's guess.
- **Your position (optional)** - The maps start there instead of on the whole
  country. Either type a Maidenhead
  grid locator (e.g. JN49 or JN49eh) - the maps draw its square as your home - or
  choose **This device's location**: RF Analyzer then asks for the approximate
  location permission and takes a location fix now and then while the app is
  open (never in the background).

| | Used for |
|---|---|
| Country or region | the region preselected when importing band plans, where the maps start |
| Position | where the maps start, the locator's square on the maps |

The location stays on your device. The maps' "my location" button shows where
the phone is right now, to about 2 km (RF Analyzer only asks for the approximate
location), and asks for the location permission on its own,
independent of this setting. If you denied it twice, Android no longer asks;
the button then offers to open the app's settings, where you can allow it
under Permissions → Location.

## Screen Orientation

By default, the **AUTO** setting follows your system's behavior for screen
orientation. If you'd like to lock in a fixed screen orientation choose one
of the following from the dropdown list:

- Portrait
- Landscape
- Reverse Portrait
- Reverse Landscape

## App Color Theme

This setting lets you choose the color theme of the app's controls. RF Analyzer
has a standard theme but you may also choose the dynamic coloring of the Android
System (available from Android Version 12):

- RF Analyzer Theme (dark)
- RF Analyzer Theme (light)
- RF Analyzer Theme (system setting)
- System Theme (dark)
- System Theme (light)
- System Theme (system setting)

Selecting a 'system setting' theme will use the systems' current setting for the
light/dark mode. Currently RF Analyzer looks best using the RF Analyzer (dark)
theme which is the default setting.

## Font Size (FFT Plot)

The font size setting affects the size of the labels and the grid in the FFT and
Waterfall plot.

## Context Help System

This setting enables or disables the context-aware help system. If enabled, a
long press on any UI element **label** will open the user manual section for
this UI element. The label is located in the upper left gap of the border of
each UI element.

## Reverse Tuning Wheel

This checkbox allows you to reverse the direction in which the tuning wheel
moves the channel indicator. The default (disabled) is that a swipe to the
left on the tuning wheel also moves the channel to the left (i.e. decreasing
the frequency). However, a physical tuning wheel on an old radio did actually
work the other way around which can be enabled with this setting. Choose which
behavior feels better or more natural for you.

## Keep Channel Centered

In the Settings tab, the switch **Keep Channel Centered** decides how the view follows the channel:

- **Enabled** - The FFT display will automatically move to keep the channel
  selector centered when tuning to a new frequency.
- **Disabled** - The FFT view remains stationary; only the channel indicator
  moves. If the indicator scrolls out of view, the display will then follow.

This gives you flexibility in how you want to interact with the spectrum
display - either keeping the selected signal in the spotlight, or keeping a
broader overview of the band.

## Start When an SDR Is Plugged In

If enabled, plugging in a USB SDR starts the analyzer with that SDR - also with
the app in the background, which stays there, and by opening the app if it isn't
running. Android asks once per SDR whether RF Analyzer may
access it; tick **Always open RF Analyzer when ... is connected** so that
plugging in works without that question.

If disabled (the default), Android doesn't offer RF Analyzer when an SDR is
plugged in, so it doesn't compete with other SDR apps on your device.

## Control Drawer Alignment (Landscape)

The Control Drawer is accessible on the side of the screen when the device is
in landscape orientation. This setting lets you choose on which side the
drawer should appear.

## Low Performance Mode

!!! warning "Enable only if necessary!"
    This mode is intended for old and less powerful devices. Only enable it if
    you have performance issues.

This mode reduces the quality of the filters inside the demodulation path of
the app. If your demodulated audio is stuttering or choppy, it might be because
your Android device is not capable at running these filters at their intended
quality. In this case, enable this setting and use the filter quality slider to
reduce the filter quality until the audio is not choppy anymore. Note that with
reduced filter quality you will hear more noise and more importantly: you will
hear aliases (think of aliases as ghost signals: they are not visible in the
FFT but you can hear them in the demodulation - they are only there because the
filters are too weak).

Currently, the only filter which is affected by this setting is the filter for
the resampler. The resampler is used to downsample the signal from the sample
rate of the SDR source (e.g. 2MHz for a RTL-SDR) to a lower sample rate for
demodulation. If you enable [Show Debug Information](#show-debug-information)
you can see the load of the resampler as percentage. If this load is greater or
very close to 100%, your Android device is not able to run the filter at the
necessary speed. The load on your phone increases if the input sample rate
increases (e.g. with a HackRF you can go up to 20MHz) or if the number of
filter taps increase. This setting reduces the filter taps to reduce the load.
If possible you should rather reduce the input sample rate: This will give you
better audio quality at the cost of seeing less bandwidth.

The mode also limits the drawing of the FFT and waterfall to 30 frames per
second (see [Max Frame Rate](./fft.md#max-frame-rate)).

## Show Debug Information

With this switch enabled, debug information - the frame rate and the
processing load (see [Processing Overload](./troubleshooting.md#processing-overload))
- is printed in the upper right corner of the FFT plot.

## Logging

For debugging purposes, the app can write all log messages to a text file. Once
logging is enabled, the text file can be viewed, saved to a folder, shared or
deleted.

In case you encounter software errors in the app please consider giving me
feedback via email (rfanalyzerapp -at- gmail.com) or via a [GitHub
Issue](https://github.com/demantz/rfanalyzer/issues) and include the log file.

In case the app crashes, please allow the Android System to collect data for
a crash report. This is the most efficient way for me to be able to fix such
issues.

--- 

## Wrapping Up

This marks the end of the introduction to the RF Analyzer control drawer
elements. Further information on the app (e.g. licensing and access to the
manual/tutorial) can be found on the *About Tab*. If you are interested in
further reading on signal processing and amateur radio you may head over
to the [Additional Information](./advanced.md) section of this manual.
