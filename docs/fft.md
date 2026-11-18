# Display Tab (FFT & Waterfall Settings)

The Display tab lets you customize how the FFT (spectrum) and waterfall plots
are rendered. If you are new to signal processing, have a look at section
[Understanding the Fast Fourier
Transformation](./advanced.md#understanding-the-fast-fourier-transformation)
for more information about the FFT. These plots are essential for visualizing
RF activity and identifying signals. The Display tab is all about tailoring the
FFT and waterfall display to suit your needs, whether you're scanning for faint signals or
adjusting for smoother performance on limited hardware.

## Scale of Vertical Axis

This setting defines the dB range shown in the FFT and waterfall displays.
It can be used to adjust the vertical scaling and offset. Use it to adapt
the view to the strength of the incoming signal. Usually it is recommended
to set the lower limit just below the noise floor of the signal and the upper
limit a bit higher than the strongest signal. This maximizes the contrast in
the waterfall display.

!!! tip 
    The vertical scale can also be adjusted via scroll or zoom gestures on the
    vertical grid area on the left of the FFT plot.

Use **Autoscale** to automatically adjust the range based on the current signal.

Use **Reset Scale** to return to a default range.

The range is remembered per source type: signal levels differ between SDRs (and
antennas), so switching from e.g. an RTL-SDR to a HydraSDR brings back the range
last used with the HydraSDR.

!!! info "About the dB Unit"
    The vertical axis of the FFT shows the signal strength relative to the
    maximum value which is supported by the device. It is measured in dBFS: a
    sine wave at the full input range of the SDR reads 0 dB, independent of the
    FFT size and padding. The noise floor, however, depends on the FFT size: a
    larger FFT spreads the noise over more (narrower) bins, so it is drawn lower,
    while a narrowband signal stays at its level. Additional information about
    this can be found in the section
    [Understanding dB, dBm and dBFS](./advanced.md#understanding-db-dbm-and-dbfs).

!!! info "Zoomed out"
    When the FFT has more bins than the screen has pixels, each pixel shows the
    strongest bin it covers. That keeps narrowband signals visible when zoomed
    out, and lets the noise floor appear a few dB higher than when zoomed in.


## FFT Size

Controls the number of frequency bins used for the [FFT (Fast Fourier Transform)](./advanced.md#understanding-the-fast-fourier-transformation).

Larger FFT sizes (e.g., 32768) provide finer frequency resolution but require more
samples for each FFT frame which means it causes an averaging effect in the time domain.
For small sample rates, a high FFT size will cause the waterfall to look smooth even
though there are signals which change rapidly. If you want to see quick changes in signals
choose a lower FFT size. Additionally, higher FFT sizes require more RAM for the storage 
of the waterfall diagram which might cause issues on old devices.

!!! info "Background Info: FFT Performance"
    The FFT operation is done in Native Code which is very fast even for large FFT
    sizes. The restriction for the maximum FFT size is rather the memory usage for
    the waterfall plot. On very old hardware you might experience crashes related 
    to Out-of-Memory issues. In this case reduce the FFT size or enable the [Low Performance Mode](./settings.md#low-performance-mode).

## FFT Padding Ratio

At low sample rates, a large FFT size (needed for fine frequency resolution) means
each FFT frame spans a long stretch of real time, which shows up as blur along the
time axis of the waterfall. FFT Padding Ratio lets you reduce that blur without
sacrificing the number of frequency bins: it shrinks the number of real samples
analyzed per frame and fills the rest with zeros (up to 75%), so each waterfall
row represents less real time.

This is not free: the true ability to distinguish two closely-spaced signals
(frequency resolution) is governed by how many real samples are analyzed, not by
the number of bins shown, so a higher padding ratio trades some of that
resolution for a sharper time axis. The number of bins itself, and thus how
smooth the frequency axis looks, stays unchanged.

## Manual FFT Size

By default, FFT Size and FFT Padding Ratio are chosen automatically from the
current sample rate and the [Waterfall Speed](#waterfall-speed):

- Each FFT frame analyzes the samples of one waterfall row (sample rate divided
  by the waterfall speed). Nothing between two rows is skipped and nothing is
  smeared over several rows, and the frequency resolution is the finest the
  waterfall speed allows (about 50 Hz at 30 rows per second). A slower waterfall
  therefore gives a finer frequency resolution.
- Above 30 rows per second the frames keep the length of a 30 rows per second
  row and overlap, so a faster waterfall stretches the time axis (e.g. to see
  the bits of an RTTY signal) without making the frequency resolution coarser.
- At low sample rates (e.g. 12 or 48 kSps) the frames are kept longer than one
  row, so that narrowband signals stay sharp.
- The FFT size zero-pads the frame by at least 1.5x, which keeps zoomed-in views
  smooth.
- At high sample rates the FFT size is limited by the memory and speed of the
  device (up to 131072 on devices with a large memory budget). Above 60 rows per
  second the limit gets smaller as the waterfall gets faster, to keep the CPU
  load in check.

Enable Manual FFT Size to take direct control of both values yourself; the
sliders will initially show whatever was last chosen automatically, so you can
fine-tune from there.

When [Low Performance Mode](./settings.md#low-performance-mode) is enabled, the
automatically chosen FFT size is limited to 16384 to ease CPU and memory load on
older devices.

## Max Frame Rate

Limits how often the FFT and waterfall plots are redrawn per second. This does
only affect the drawing of the FFT (and waterfall) frames. The rate at which FFT
frames are calculated from the input samples is controlled via the [Waterfall Speed](#waterfall-speed).

Higher frame rates (up to 60 FPS) make the display smoother, especially when scrolling and zooming.

Lower values reduce CPU and battery usage. The default is 60 FPS; the
[Low-Performance Mode](./settings.md#low-performance-mode) limits drawing to 30 FPS.

## Averaging

Smooths out short-term fluctuations of the FFT plot by averaging multiple FFT
frames. The setting controls how many FFT results are averaged. The waterfall
keeps showing the individual frames.

A value of 0 disables averaging.

## Waterfall Speed

Adjusts how fast the FFT frames are calculated (measured in frames per second, i.e. fps).
The actual speed (FPS) at which the frames are calculated are gated by the speed of the
processor of the Android device. Unless Manual FFT Size is enabled, the FFT size follows
the waterfall speed (see [Manual FFT Size](#manual-fft-size)).

!!! info "Show actual FFT FPS"
    The actual FFT FPS value can be displayed by enabling the option [Show Debug Information](./settings.md#show-debug-information).

## Peak Hold

When enabled, this shows small yellow dot indicators above the FFT curve
marking the highest observed signal strength at each frequency.

Useful for spotting intermittent or transient signals.

## Relative Frequency

When enabled, the spectrum and waterfall plots are centered around the current
tuned frequency of the SDR (shown as 0 Hz), showing offsets in ±kHz rather than absolute frequency.

Turn this off if you prefer absolute frequency display.

## Spectrum/Waterfall Ratio

Adjusts how much vertical space is given to the spectrum plot vs. the waterfall display.

Move the slider right for more spectrum, left for more waterfall.

## Waterfall Color Map

Choose how the waterfall color gradient maps to signal strength.

Available color maps are:

- Jet
- Turbo
- GQRX (Color Map from the GQRX application by Alexandru Csete OZ9AEC)

Try different maps to see which one suits your eyes and environment best.

## FFT Drawing Type

Determines how the FFT (spectrum) is drawn:

- Line: A connected curve.
- Bars: Fills the space under the curve.

Line mode is the default and typically recommended and a bit more efficient
during rendering.

---

## Wrapping Up

You should now be able to configure the FFT and Waterfall Plot to your liking.
If you spot an interesting signal, continue with the [Demodulation
Tab](./demodulation.md) to demodulate and listen to the signal.
