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

!!! info "About the dB Unit"
    The vertical axis of the FFT shows the signal strength relative to the
    maximum value which is supported by the device. It is measured in dB (or
    dbFS). Additional information about this can be found in the section
    [Understanding dB, dBm and dBFS](./advanced.md#understanding-db-dbm-and-dbfs).


## FFT Size

Controls the number of frequency bins used for the [FFT (Fast Fourier Transform)](./advanced.md#understanding-the-fast-fourier-transformation).

Larger FFT sizes (e.g., 32768) provide finer frequency resolution but require
more RAM for the storage of the waterfall diagram.

Smaller sizes (e.g., 1024, 2048) require a lot less memory but offer less resolution.

!!! info "Background Info: FFT Performance"
    The FFT operation is done in Native Code which is very fast even for large FFT
    sizes. The restriction for the maximum FFT size is rather the memory usage for
    the waterfall plot (especially if the waterfall speed is set to 'slow' and
    therefore needs to keep more FFT samples in RAM). It is recommended to use the
    highest setting for the FFT size. On very old hardware you might experience
    crashes related to Out-of-Memory issues. In this case either reduce the FFT
    size or increase the FFT speed.

## Max Frame Rate

Limits how often the FFT and waterfall are redrawn per second.

Higher frame rates (up to 60 FPS) make the display smoother.

Lower values reduce CPU and battery usage.

## Averaging

Smooths out short-term fluctuations by averaging multiple FFT frames (also
affects the waterfall display). The setting controls how many FFT results are averaged.

A value of 0 disables averaging.

## Waterfall Speed

Adjusts how fast the waterfall plot is scrolling.

Options: Slow, Normal, Fast.

Slower speeds compress time, helping you see longer activity history.
Faster speeds offer more real-time behavior.

Internally, a slower waterfall speed results in larger memory usage (RAM)
because more FFT samples need to be stored (see [FFT Size](#fft-size)).

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

Move the slider left for more spectrum, right for more waterfall.

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
