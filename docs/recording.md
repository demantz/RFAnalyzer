# Recording

The **Recording** feature allows you to capture raw IQ (In-phase/Quadrature)
data directly from your SDR hardware. This data is saved in a file, which can
later be replayed using the **FileSource** option in the **Source** tab or
exported for analysis in other tools.

## Recording Tab

![Recording Tab](todo)

### View Recordings

Press the **View Recordings** button to open a list of all saved recordings (see [Recording List](#recording-list).

### File Name

Use this text input to name your recording. You can enter or update the file
name even while the recording is in progress. The name will only be applied
when the recording is stopped.

### Start/Stop Recording

This button toggles the recording process. When recording starts, raw IQ
samples are written to a file. Press the button again to stop the recording.
While active, the button displays a red recording indicator. The recording
process is also indicated in the FFT display were the current file size is
shown.

---

### Recording Options

#### Channel Squelch

Toggle this switch to enable or disable recording based on signal strength.
When enabled, recording will only capture data if the signal level at the
currently tuned channel exceeds the **squelch** threshold.

Note that this option is only available, if the **Squelch** setting in the
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
Recording Tab (or in the Source Tab when Filesource is selected).

### Filter and Delete All

In the top right corner of the recording list are two buttons:

- **Filter Favorites**: When enabled, only recordings which are marked as **favorite** are displayed
- **Delete All**: This button deletes **all** recordings.

The recording list shows all recordings as cards displaying the following information:

- Recording Name (bold)
- Tune (Center) Frequency (Hz)
- Sample Rate (Sps)
- File Format (RTLSDR vs. HACKRF)
- Date and Time
- File Size

Tap a recording card to reveal the following actions:

- Replay Recording (via [File Source](./sdr-source.md#file-source-settings))
- Rename Recording
- Delete Recording
- Share Recording File
- Download Recording File to Folder
- Mark Recording as Favorite

The file format of the recordings is identical to the files created by the
`rtl_sdr` and `hackrf_transfer` commands on PC.

--- 

## Wrapping Up

Now you are familiar with most of RF Analyzer's features. What's left is the
[Settings Tab](./settings.md) where you can tweak some parts of the app to your
preferences and the **About Tab** where you find this manual and
the option to purchase the full version of RF Analyzer.
