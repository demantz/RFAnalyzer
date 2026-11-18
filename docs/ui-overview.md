# User Interface

The app is designed to give you interactive insights into radio signals,
whether you are analyzing live signals from an SDR or replaying recorded IQ
data. The user interface is structured to provide both **real-time spectrum
visualization** and **intuitive controls** to configure your setup.

The UI consists of two main components:

- **FFT and Waterfall Plot** - Displays the real-time frequency spectrum and
  signal history over time.
- **Control Drawer** - Allows you to configure the analyzer, select input
  sources, and manage settings.

---

## FFT and Waterfall Plot

When the analyzer is running, the **FFT plot** provides a live view of the
frequency spectrum, while the **waterfall plot** gives a history of past
signals, scrolling downwards as new data arrives.

### Interacting with the FFT Plot

You can explore the frequency spectrum in several ways:

- **Scrolling Left/Right** - Move the center frequency up or down.
- **Pinch-to-Zoom** - Adjust the frequency resolution for a more detailed or
  broader view.
- **Quick Tune & Zoom** - Long-press an empty spot in the FFT or waterfall
  until a small control appears, then drag without lifting your finger:
  **up/down zooms**, **left/right fine-tunes** the channel. The first direction
  you drag in stays until you lift the finger; with demodulation off, only
  zooming works. A second finger switches to pinch-to-zoom.
- **Power Level Axis** - Located on the left, this shows signal strength in dBFS.
  It can be scrolled up/down or zoomed using pinch gestures.
- **Autoscale Button** - Found in the Display tab ([FFT & Waterfall](./fft.md)),
  this automatically adjusts the vertical dB scale for the best signal
  visibility. The Display tab also has a button to reset the scale and a slider to
  fine tune the vertical viewport.

The FFT plot is the heart of the RF Analyzer, and these interactive tools help
you navigate and fine-tune your view of the spectrum.

---

## Control Drawer

The **control drawer** is your main hub for adjusting settings. It appears at
the bottom of the screen (or as a side panel in landscape mode). Drag the handle at
its edge to open or close it. The tabs are shown as icons; the selected tab also
shows its name.

### Available Tabs

- **[Source](./sdr-source.md)** - Select and configure your signal input.
- **[Demodulation](./demodulation.md)** - Set up signal demodulation (AM, FM, etc.).
- **[Recording](./recording.md)** - Record raw IQ samples or the demodulated
  baseband to a file and manage recordings.
- **[Display](./fft.md)** - Adjust FFT and waterfall display settings like scaling and resolution.
- **[Settings](./settings.md)** - General application preferences.
- **About** - App version, the manual, the tutorials (Quick Start, Bookmark
  Manager, What's New), your license and the purchase options, acknowledgments
  and licenses.

A [Bookmark Manager screen](./bookmarks.md) can be accessed via the button
floating above the control drawer or from the demodulation tab.

Let’s start with the [Source Tab](./sdr-source.md), which is the first step in setting up your
analyzer.

