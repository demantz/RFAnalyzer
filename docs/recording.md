# Recording

The **Recording** feature allows you to capture IQ (In-phase/Quadrature) data:
either the raw samples of your SDR, or the demodulator's baseband (just the
channel). The data is saved in a file, which can later be replayed using the
**File Source** in the **Source** tab or exported for analysis in other tools.

A recording keeps running in the background, also when you swipe RF Analyzer
away; the notification then offers **Stop recording**. If RF Analyzer is closed
while a recording runs (force-stopped, or after a crash), it asks at the next
start whether to keep the unfinished recording or delete it.

## Recording Tab

### View Recordings

Press the **View Recordings** button to open a list of all saved recordings (see [Recording List](#recording-list)).

### File Name

Use this text input to name your recording. You can enter or update the file
name even while the recording is in progress. The name will only be applied
when the recording is stopped.

### Record Raw IQ / Record Baseband

Two buttons start a recording; press the running one again to stop it. Only
one of them runs at a time.

- **Record Raw IQ** writes the samples of the source as they arrive: the whole
  received bandwidth at the source's sample rate (large files - an RTL-SDR at
  2 MSps writes 4 MB per second).
- **Record Baseband** writes only the demodulated channel: complex 32-bit float
  samples (`.cf32`) at the mode's baseband rate (e.g. 192 kSps for WFM, far less
  for narrow modes), centred on the channel frequency. It needs a demodulation
  mode, follows the squelch in analog modes, and the channel frequency stays
  where it is while it runs.

While a recording runs, the button shows how long it has been running, and the
FFT display shows its frequency, sample rate and current file size with a red
dot.

---

### Recording Options

#### Channel Squelch

Toggle this switch to record only while the **squelch** is open. Each signal is
recorded completely: with a little of what came before it (the squelch notices
a signal only shortly after it began) and a short tail after it - so digital
packets in a squelch-gated recording stay decodable. The recording holds the
signals one after another, without the pauses between them. A baseband
recording (**Record Baseband**) of an analog mode follows the squelch whenever
the squelch is on.

Note that this option is only available if the **Squelch** setting in the
[Demodulation Tab](./demodulation.md#squelch-control) is enabled.

Recording based on **squelch** is especially useful to avoid storing long periods of silence.

#### Stop Recording After

This setting lets you define an automatic stop condition for the recording. You
can choose one of the following limits:

- **Seconds**
- **Minutes**
- **Megabytes**
- **Gigabytes**
- **Disabled** (default; manual stop required)

Once the selected condition is met, recording will stop automatically.

---

At the bottom of the tab, the app also displays how much device storage is
currently used by recordings.

!!! warning 
    Keep an eye on available storage space. Raw IQ recordings can grow
    large quickly, especially at higher sample rates. Recordings can be deleted
    from the [Recording List](#recording-list).



## Recording List

View the recording list by pressing the **View Recordings** button in the
Recording Tab (or in the Source Tab when File Source is selected).

### Filter and Delete All

In the top right corner of the recording list are two buttons:

- **Filter Favorites**: When enabled, only recordings which are marked as **favorite** are displayed
- **Delete All**: This button deletes **all** recordings.

The recording list shows all recordings as cards displaying the following information:

- Recording Name (bold)
- Tune (Center) Frequency (Hz)
- Sample Rate (Sps)
- File Format (RTLSDR, HACKRF, AIRSPY, AIRSPYHF or HYDRASDR; baseband
  recordings are AIRSPYHF, a KiwiSDR's raw recordings HYDRASDR)
- Date and Time
- File Size

Tap a recording card to reveal the following actions:

- Replay Recording (via [File Source](./sdr-source.md#file-source-settings))
- Rename Recording
- Delete Recording
- Share Recording File
- Download Recording File to Folder
- Mark Recording as Favorite

!!! info "File Format"
    **Record Raw IQ** stores the samples in the SDR's own format - the same as
    `rtl_sdr`, `hackrf_transfer` and similar tools write (a KiwiSDR's as 16-bit
    IQ). **Record Baseband** stores 32-bit float IQ (`.cf32`). The file name holds
    start time, name, format, frequency and sample rate, e.g.
    `20261113-012148_My Recording_RTLSDR_97MHz_1MSps.cu8`.

!!! tip "Import Recordings into SDR#"
    SDR# uses the WAV Fileformat. There are converters for the raw IQ files to the
    SDR# WAV format. A python converter script for HackRF IQ files is available in
    the [Tools directory of the RF Analyzer GitHub
    Repository](https://github.com/demantz/RFAnalyzer/blob/master/tools/prepWAV.py).
    For RTL-SDR IQ files there is a tool called
    [iqtosharp](https://github.com/Marcin648/iqToSharp).

--- 

## Wrapping Up

Now you are familiar with most of RF Analyzer's features. What's left is the
[Settings Tab](./settings.md) where you can tweak some parts of the app to your
preferences and the **About Tab** where you find this manual, what you
purchased, and the options to purchase RF Analyzer.
