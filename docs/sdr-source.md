# Source Tab

The **Source Tab** is where you choose and configure the signal input. Before
you can analyze signals, you need to select a **source type** and start the
analyzer.

!!! tip "Starting the Analyzer"
    The **Start Button** (▶️) is located to the right of the **source
    type dropdown menu**.  After selecting a source, tap this button to start
    sampling and display the FFT and waterfall plot.

Most settings can be changed **while the analyzer is running**, but there are
some exceptions, especially for file playback.

## Selecting a Source

The dropdown menu in the Source Tab lets you choose between different signal sources:

- **RTL-SDR** - Receive live radio signals using an RTL-SDR USB dongle.
- **HackRF** - Works similarly to RTL-SDR but supports wider bandwidths.
- **Airspy** - A high quality SDR supporting 12bit resolution and up to 10MHz bandwidth.
- **HydraSDR** - A high quality SDR supporting 12bit resolution and up to 10MHz bandwidth.
- **File Source** - Replay a previously recorded IQ file.

!!! note "Automatic Source Detection"
    When you plug in a RTL-SDR or HackRF while the app is in the foreground,
    the correct source type is automatically set by the app.

### Start/Stop Button

The **play button** in the upper right starts and stops the signal analyzer.
When started, the app begins receiving and displaying IQ samples from the
source. Once started, the analyzer keeps running even when the app is moved
into the background. A notification icon is displayed in the status bar. Tap
the notification to bring the RF Analyzer app back into the foreground or use
the **Stop Analyzer** button in the notification to stop and terminate the app.

---

Each source type changes the available settings. Let’s go over them one by one.
We start with some settings that are common to most of the source types:

## Common Source Settings

### Tune Frequency

This field sets the center frequency (in Hz) to which the SDR device should tune.
You can input the frequency manually. The app does also automatically adjust
the tune frequency when the [Channel Frequency](./demodulation.md#channel-frequency)
is set to a value outside of the current signal range. Scrolling outside of
the current signal also causes an automatic retune of the device.

!!! info
    The app limits the frequency values based on the tuner chip specification
    inside the connected SDR. For some custom RTL-SDR devices this may be undesired.
    In this case consider to enable the option
    [Allow Out-of-Bound Frequency](./settings.md#allow-out-of-bound-frequency-rtl-sdr)
    in the Settings Tab.

### Sample Rate

When **Automatic Sample Rate** is disabled, the dropdown lets you choose the
fixed sample rate manually.

By default, **Automatic Sample Rate** is enabled. The sample rate automatically
follows the zoom level of the spectrum display. This provides an intuitive way
to adjust bandwidth without needing to manage sampling directly. However, the
adjusting of the sample rate does affect the signal which is received from the
source which might be undesirable.

### Gain Controls

Depending on the specific SDR device there are multiple gain controls
available. These sliders allow you to optimize the signal level by adjusting
different gain stages.

Note that excessive gain may cause distortion or clipping in case of very
strong signals.

#### LNA Gain

Adjusts the **Low Noise Amplifier** gain at the RF stage. Sometimes this
is also just refered to as 'Gain'.

#### VGA Gain

Controls the **Variable Gain Amplifier** (VGA). Useful for fine-tuning the
baseband signal level before digitization.

#### Mixer Gain

Adjusts the **Mixer** gain at the conversion stage from radio frequency (RF) to
intermediate frequency (IF).

#### IF Gain

This option is only available for RTL-SDR devices with a E4000 tuner chip.
It allows the fine-tuning of the intermediate frequency (IF) gain.

### Antenna Port Power

This is sometimes refered to as **Bias Voltage**, **Bias Tee** or **RF Bias**.
It enables a **power supply** on the antenna port of the SDR. This is useful
for powering active antennas or external devices requiring a bias voltage.

- HackRF: 3.3V (max 50mA)
- RTL-SDR Blog V4: 4.5V (max 50mA)
- Airspy: 4.5V (max 25mA)
- HydraSDR: 4.5V (max 25mA)

!!! warning 
    Not all devices or antennas are compatible. Use caution to avoid damaging external equipment.


### Frequency Converter Offset

If you are using an **upconverter** or other frequency-shifting hardware, enter
the offset (in Hz) here. This value (which can be negative) is added to the
tuned frequency to display the actual RF frequency.

This feature helps when using hardware like an up-/downconverter that shifts the
spectrum into a different frequency range.

!!! tip "Example: Ham-It-Up"
    For the Ham-It-Up converter the frequency offset setting should
    be set to -125000000Hz. That means that the app will display the incoming
    signals as if they were 125MHz lower than they actually are. Now
    when tuning the app to 14MHz you see the signals that are actually
    incoming at 139MHz (this is where the 14MHz band is found after up-
    converting it with the Ham-It-Up converter).


## RTL-SDR Specific Settings

All of the following options can be adjusted while the Analyzer is already
running.

### Gain Controls

#### Hardware AGC

Activate the RTL-SDR's build-in automatic gain control. This is not usually
recommended as it will add too much noise to the signal.

#### Manual Gain

Enable this switch to manually set the **Gain** and **IF Gain**, otherwise the
RTL-SDR driver will automatically set these values.
Refer to the [Gain Controls](#gain-controls) section for more information about
the specific gain stages.

### External RTL-SDR Server

Connect to an RTL-SDR network server instead of a local USB device. This must
be done before starting the analyzer. Input the IP and TCP port of the
`rtl_tcp` server and start the analyzer to connect. Make sure the Server is
not blocking the connection with a firewall.

!!! warning
    Streaming IQ samples from a remote RTL-SDR onto your phone does consume
    a very high amount of data volume (~4MB/s)! It is highly recommended to do
    that only over a WiFi connection and not over a paid data plan.

### Frequency Correction

This setting is passed to the RTL-SDR driver and allows to compensate for small
frequency offsets due to hardware imperfections. The value has the unit PPM
(parts-per-million) and can also be negative.

---

## HackRF Specific Settings

When **HACKRF** is selected as the signal source, a range of hardware-specific
settings becomes available.

The HackRF has two gain stages (LNA and VGA) which are explained in the [Gain
Controls](#gain-controls) section. Additionally, it features an amplifier:

### Amplifier

This switch enables or disables the 11dB **internal RF amplifier** in the HackRF.
This is an additional gain stage and may be helpful when receiving very weak
signals, though it can introduce distortion if overused.

---

## Airspy Specific Settings

When **AIRSPY** is selected as the signal source, a range of hardware-specific
settings becomes available.

### Advanced Gain Control

The easiest way of controlling the gain of the Airspy is by specifying the
**Linearity** and **Sensitivity** gain values.

Internally, the Airspy has three gain stages (LNA, Mixer, VGA - see the [Gain
Controls](#gain-controls) section above). These three stages are automatically
managed by the Airspy when setting the linearity and sensitivity gain values.

If you wish to specify the LNA, Mixer and VGA values explicitly, enable the
**Advanced Gain Control** switch.

---

## HydraSDR Specific Settings

When **HYDRASDR** is selected as the signal source, a range of hardware-specific
settings becomes available.

### Advanced Gain Control

The easiest way of controlling the gain of the HydraSDR is by specifying the
**Linearity** and **Sensitivity** gain values.

Internally, the HydraSDR has three gain stages (LNA, Mixer, VGA - see the [Gain
Controls](#gain-controls) section above). These three stages are automatically
managed by the HydraSDR when setting the linearity and sensitivity gain values.

If you wish to specify the LNA, Mixer and VGA values explicitly, enable the
**Advanced Gain Control** switch.

### RF Port

The HydraSDR has 3 ports from which RF signals can be captured:

- `AIR_IN` (also called `ANT` or `RX0`): Default antenna port
- `CABLE1` (also called `RX1`): First internal port 
- `CABLE2` (also called `RX2`): Second internal port 

---

## File Source Settings

If you select **File Source**, you can load and replay an IQ recording. This is
useful for analyzing captured signals at a later time. IQ recordings can be
created via the PC software tools `rtl_sdr` and `hackrf_transfer`. RF Analyzer
can also record IQ files directly (see [Recordings](./recording.md)).

Once playback has started, the **tune frequency, sample rate, and file format**
**cannot be changed**. Unlike live SDR sources, an IQ file has fixed
characteristics and cannot be "retuned". If these values are incorrect the
recording will not be correctly replayed.

### Selecting a Source File

The **Open File** button opens a file chooser to choose an IQ file from the
phone's storage. These files contain raw IQ data and no information about
the file format, frequency and sample rate. Therefore, these values need
to be specified after selecting the file.

!!! note "Auto-Detection"
    If you select a recording file from the app’s internal storage, frequency,
    sample rate, and file format are **automatically filled in** from
    information extracted from the **file name**. The file name should contain
    the values in this format: `hackrf_123000kHz_2Msps.iq`. The arrangement can
    be different and the app recognizes the units MHz, KHz and Hz for frequency values
    and MSps, KSps and Sps for sample rate values.

The **View Recordings** button lets you browse saved recordings within the app.
By tapping on a recording and pressing the play button, the recording file
is automatically loaded into the File Source and started. File format, frequency
and sample rate do not need to be entered manually.

### Tune Frequency

This value sets the center frequency at which the recording was done.

Note that this frequency value is not important for the display and
demodulation of the signals. It is only used to display the frequency grid. If
unknown, you may set the frequency to 0Hz which is similar to enabling the
[Relative Frequency](./fft.md#relative-frequency) setting.

### Sample Rate

This value specifies the sample rate at which the file should be replayed.

### File Format

The SDR devices use different file formats:

- RTLSDR: interleaved, 8-bit, **unsigned** IQ samples
- HackRF: interleaved, 8-bit, **signed** IQ samples
- AIRSPY: interleaved, 16-bit, **signed** IQ samples
- HYDRASDR: interleaved, 16-bit, **signed** IQ samples

In all cases, the in-phase component (**I**) is first, followed by the
quadrature component (**Q**).

### Repeat

If this setting is enabled, the file will start playing again from the
beginning once the end of the file is reached. It will loop forever until
the Analyzer is manually stopped by the user.

---

## Wrapping Up

Now that you understand  **Source Tab** and the FFT is running, you are ready to
start exploring signals! Next, we will dive deeper into [FFT & Spectrum
Analysis](./fft.md) and discuss how to interpret and optimize the FFT
display.

