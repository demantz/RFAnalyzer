# Settings

The **Settings** tab lets you customize the app's behavior to your preferences.

## Screen Orientation

On default, the **AUTO** setting follows your system's behavior for screen
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

## Control Drawer Alignment (Landscape)

The Control Drawer is accessible on the side of the screen when the device is
in landscape orientation. This setting lets you choose on which side the
drawer should appear.

## Allow Out-of-Bound Frequency (RTL-SDR)

The RTL-SDR devices have a tune frequency range which is determined by the
tuner chip:

| Tuner Chip         | Lowest Frequency | Highest Frequency |
|--------------------|------------------|-------------------|
| Elonics E4000      | 52 MHz           | 2200 MHz          |
| Rafael Micro R820T | 24 MHz           | 1766 MHz          |
| Rafael Micro R828D | 24 MHz           | 1766 MHz          |
| Fitipower FC0013   | 22 MHz           | 1100 MHz          |
| Fitipower FC0012   | 22 MHz           | 948.6 MHz         |
| FCI FC2580         | 146 MHz          | 924 MHz           |

RF Analyzer is programmed to restrict the tune frequency according to
the tuner chip that is currently used. If this is undesired, this setting
allows you to disable these restrictions. That means any frequency can
be entered. The result however, may be incorrect if the chip does not
support the frequency.

If you use a RTL-SDR Blog v4 dongle it is recommended to enable this setting.
This will allow you to tune below the 24MHz which is usually the lowest
frequency which the R828D tuner can tune. But the Blog v4 has an automatic
upconverter build in which allows you to tune below this frequency.

!!! warning 
    This is an expert setting that should only be enabled if you know what you are doing.

## Show Debug Information

With this switch enabled, debug information (e.g. current FPS) is printed in
the upper right corner of the FFT plot.

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
