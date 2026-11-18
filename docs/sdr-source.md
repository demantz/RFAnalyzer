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

The **Signal Source** dropdown in the Source Tab lets you choose between different signal sources:

- **RTLSDR** - Receive live radio signals using an RTL-SDR USB dongle, or an
  rtl_tcp server in the network.
- **HACKRF** - Works similarly to RTL-SDR but supports wider bandwidths.
- **AIRSPY** - A high quality SDR supporting 12bit resolution and up to 10MHz bandwidth.
- **AIRSPYHF** - The Airspy HF+: HF and VHF (0.5 - 260 MHz) at up to 768 kSps.
- **HYDRASDR** - A high quality SDR supporting 12bit resolution and up to 10MHz bandwidth.
- **KiwiSDR (web)** - Connect to a remote KiwiSDR receiver over the network instead of using local hardware.
- **File Source** - Replay a previously recorded IQ file.

!!! note "Automatic Source Detection"
    When you plug in an SDR (RTL-SDR, HackRF, Airspy, Airspy HF+ or HydraSDR)
    while the app is in the foreground - or plugged it in while the app was in
    the background - the correct source type is set automatically.

### Start/Stop Button

The **play button** in the upper right starts and stops the signal analyzer.
When started, the app begins receiving and displaying IQ samples from the
source. Once started, the analyzer keeps running when the app is moved into
the background, as long as it demodulates or records (otherwise it stops after a
few seconds). A notification icon is displayed in the status bar. Tap the
notification to bring the RF Analyzer app back into the foreground or use the
**Stop** button in the notification to stop and terminate the app.

Swiping RF Analyzer away from the recent apps closes it and stops the analyzer -
except while a recording runs: the recording goes on, and a notice says so and
offers **Stop recording**.

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
    The app limits the frequency values to the range of the connected SDR. The
    RTL-SDR is not limited (0 Hz to 3 GHz), because dongles differ so much; if
    the tuner chip can't receive the chosen frequency (e.g. below 24 MHz without
    a Blog V4's upconverter or direct sampling), RF Analyzer says so once.

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
is also just referred to as 'Gain'.

#### VGA Gain

Controls the **Variable Gain Amplifier** (VGA). Useful for fine-tuning the
baseband signal level before digitization.

#### Mixer Gain

Adjusts the **Mixer** gain at the conversion stage from radio frequency (RF) to
intermediate frequency (IF).

#### IF Gain

Only available with an [External RTL-SDR Server](#external-rtl-sdr-server)
whose dongle has an E4000 tuner chip; the built-in driver has no separate IF
gain. It allows the fine-tuning of the intermediate frequency (IF) gain.

### Antenna Port Power

This is sometimes referred to as **Bias Voltage**, **Bias Tee** or **RF Bias**.
It enables a **power supply** on the antenna port of the SDR. This is useful
for powering active antennas or external devices requiring a bias voltage.

- HackRF: 3.3V (max 50mA)
- RTL-SDR with a bias tee (e.g. Blog V3/V4): 4.5V (max 50mA) - not with an
  [External RTL-SDR Server](#external-rtl-sdr-server), whose protocol can't switch it
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

Activate the RTL-SDR's built-in automatic gain control. This is not usually
recommended as it will add too much noise to the signal.

#### Manual Gain

Enable this switch to set the **Gain** manually (and the **IF Gain** with an
rtl_tcp server that offers it), otherwise the RTL-SDR sets it automatically.
Refer to the [Gain Controls](#gain-controls) section for more information about
the specific gain stages.

### External RTL-SDR Server

Connect to an RTL-SDR network server instead of a local USB device. This must
be done before starting the analyzer. Input the IP and TCP port of the
`rtl_tcp` server and start the analyzer to connect. Make sure the Server is
not blocking the connection with a firewall.

On Android 17 and newer, a server in your local network (for example
`192.168.1.20` or `raspberrypi.local`) needs RF Analyzer's **local network
access**: RF Analyzer asks for it when you start the analyzer. Android lists it
under **Nearby devices**. If you denied it, allow it in the app's settings
(Permissions → Nearby devices) - without it the connection times out. Servers
on the internet don't need it.

The samples arrive over the network in bursts (`rtl_tcp` sends a quarter
megabyte at a time - half a second at 250 kSps), so the audio plays a little
behind the spectrum: RF Analyzer keeps enough audio in reserve to bridge the gaps
between bursts. If the audio breaks up, it enlarges the reserve (up to one second);
it starts small again with the next start of the analyzer. The same applies to a
KiwiSDR.

!!! warning
    Streaming IQ samples from a remote RTL-SDR onto your phone does consume
    a very high amount of data volume (~4MB/s)! It is highly recommended to do
    that only over a WiFi connection and not over a paid data plan.

### Frequency Correction

This setting is passed to the RTL-SDR driver and allows to compensate for small
frequency offsets due to hardware imperfections. The value has the unit PPM
(parts-per-million) and can also be negative.

### Direct Sampling Mode

!!! warning "Experimental Feature"
    This feature is experimental. Use at own risk.

Direct Sampling Mode can be used to extend the tunable range for the RTL-SDR device
and receive shortwave signals (0 - 28.8MHz).

However it needs a hardware modification of the RTL-SDR. Some RTL-SDR devices
already have the modification built in (e.g. the RTL-SDR Blog V3 or the NooElec
V5). Choose **I-Branch** or **Q-Branch** depending on the modification (the
RTL-SDR Blog V3 uses **Q-Branch**); **Off** uses the tuner. For more information
visit the following link: [Direct Sampling Mode
(rtl-sdr.com)](https://www.rtl-sdr.com/rtl-sdr-direct-sampling-mode/)

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

### Gain Mode

The Airspy has three gain stages one after the other: the **LNA** (low noise
amplifier), the **Mixer** and - after the tuner's channel filter - the **VGA**
(see the [Gain Controls](#gain-controls) section above). The **Gain Mode**
decides how they are set:

- **Linearity** and **Sensitivity** are presets: a single gain value (0 - 21)
  sets all three stages at once, following one of two tables. They are
  alternatives, not two gains that add up - only the selected one is applied.
- **Manual** sets LNA, Mixer and VGA gain individually.

The buttons at the top of the **Gain** box select the mode; below them is the
slider of the selected preset, or in Manual one slider per stage.

**Which preset?** The two presets differ in *where* the gain is applied:

- **Sensitivity** puts the gain into the first stages (LNA and Mixer) early.
  Amplifying a signal as early as possible keeps the receiver's own noise
  lowest, so the weakest signals come out best. But every strong signal the
  antenna picks up is amplified early as well, before the channel filter can
  remove it - with strong signals around, the first stages overload sooner.
- **Linearity** keeps LNA and Mixer lower and makes up the gain with the VGA,
  behind the channel filter. Strong signals outside the shown band are then
  already attenuated when most of the gain is applied, at the cost of a little
  more receiver noise.

Choose **Linearity** when there are strong signals around: in cities, with
outdoor or roof antennas, close to FM, TV or paging transmitters, and at wide
sample rates - or whenever raising the gain makes the noise floor jump or
"ghost" signals appear. It is the safer default. Choose **Sensitivity** for
weak signals in a quiet environment (rural locations, small indoor antennas,
satellites, narrow sample rates).

**Setting the gain:** raise it until the noise floor starts to rise along
with it, and stop there - more gain does not make signals stand out more after
that point. If the noise floor rises much faster than the signals, or wide
humps and copies of strong signals appear, the receiver is overloaded: lower
the gain or switch to Linearity. Values around 14 to 17 are a good start.

---

## Airspy HF Specific Settings

When **AIRSPYHF** is selected as the signal source, a range of
hardware-specific settings becomes available.

### Automatic Gain Control (AGC)

This setting can enable the built-in AGC of the Airspy HF+. If enabled, the
**AGC Threshold** can be set to either `Low` or `High` with a slider.

### LNA Preamp

The Airspy HF+ includes a Low Noise Amplifier (LNA) which can be enabled with
this setting. It increases the input signal level by 6dB.

### Attenuation

If the [AGC setting](#automatic-gain-control-agc) is turned off, an attenuation
level can be chosen instead. The input signal is attenuated by a chosen factor
(0 - 48dB). This is useful if strong nearby signals overload the SDR.

---

## HydraSDR Specific Settings

When **HYDRASDR** is selected as the signal source, a range of hardware-specific
settings becomes available.

### Gain Mode

The HydraSDR has the same three gain stages (LNA, Mixer, VGA) and the same
**Gain Modes** as the Airspy: the presets **Linearity** and **Sensitivity**
(one value sets all three stages) or **Manual** (each stage individually). See
the Airspy's [Gain Mode](#gain-mode) section for how the presets differ and
when to choose which.

### RF Port

The HydraSDR has 3 ports from which RF signals can be captured:

- `AIR_IN` (also called `ANT` or `RX0`): Default antenna port
- `CABLE1` (also called `RX1`): First internal port 
- `CABLE2` (also called `RX2`): Second internal port 

---

## KiwiSDR Specific Settings

When **KiwiSDR** is selected as the signal source, RF Analyzer connects to a
remote [KiwiSDR](http://kiwisdr.com/) receiver over the network instead of
using local USB hardware.

### Host / Port / Password

Enter the **DNS Name or IP Address** and **Port** (default `8073`) of the
KiwiSDR receiver. Most public KiwiSDR receivers are open and do not require
a **Password** - leave it blank unless the receiver operator has given you
one. The Password field is only editable when **Secure Connection** (below)
is enabled, since it would otherwise be transmitted unencrypted. This must
be configured before starting the analyzer.

A KiwiSDR in your own local network needs RF Analyzer's local network access on
Android 17 and newer, as described for the
[External RTL-SDR Server](#external-rtl-sdr-server).

### Secure Connection

Most public KiwiSDR receivers only support plain (unencrypted) connections.
If your receiver is known to support a secure connection (`wss://`, e.g.
behind a TLS-terminating reverse proxy), enable **Secure Connection**.
Otherwise, be aware that all data is sent unencrypted over the network -
this is why password authentication is disabled unless this is turned on.

### Choose from Public List

**Choose from Public List** opens the receivers listed on
[kiwisdr.com/public](http://kiwisdr.com/public/): a **List** (searchable by
name, location and host, with the number of users, the HF signal-to-noise
ratio and an OFFLINE badge) and a **Map** of the receivers around you. Tapping
a receiver (on the map: **Select this Receiver**) fills in host and port -
for a plain connection without a password - and starts the analyzer. The list
is kept for an hour; **Refresh** loads it again.

### Sample Rate and Gain

A KiwiSDR covers HF (0 to 30 MHz, or what the receiver reports) and sends only
a narrow slice of it: 12 kSps, about 10 kHz of usable spectrum (wideband
receivers about 20 kSps). The receiver decides the rate; RF Analyzer takes it
over when connected. Scroll or tune to move the slice. The receiver's own AGC
sets the level, so there are no gain controls.

If the connection fails, the message says why: the receiver has no free
channel slots (try another one), authentication failed, the server is down or
closed the connection, or it sent no IQ stream within 15 seconds.

!!! warning "Be a good guest"
    Most KiwiSDR receivers only support 4-8 simultaneous listeners. Please
    be considerate of other users: don't leave the analyzer running idle
    against a public receiver, and disconnect (stop the analyzer) when
    you're done.

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
    When you open a file, RF Analyzer fills in frequency, sample rate and file
    format from its **file name** where it can, e.g. `hackrf_123000kHz_2Msps.iq`:
    the parts in any order, each after `_`, `-` or a space, with the units MHz,
    kHz or Hz and MSps, kSps or Sps. The extension `.cu8`, `.cs8`, `.cs16` or
    `.cf32` sets the format; a device name in the file name (e.g. `HACKRF`) wins
    over it. A name that starts with date and time (`20261113-012148_…`, as RF
    Analyzer names its recordings) also tells when the recording was made.

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
- AIRSPYHF: interleaved, 32-bit **float** IQ samples (also RF Analyzer's baseband
  recordings and the `.cf32` files of other tools)
- HYDRASDR: interleaved, 16-bit, **signed** IQ samples

In all cases, the in-phase component (**I**) is first, followed by the
quadrature component (**Q**).

### Repeat

If this setting is enabled, the file will start playing again from the
beginning once the end of the file is reached. It will loop forever until
the Analyzer is manually stopped by the user.

---

## Wrapping Up

Now that you understand the **Source Tab** and the FFT is running, you are ready to
start exploring signals! Next, we will dive deeper into [FFT & Spectrum
Analysis](./fft.md) and discuss how to interpret and optimize the FFT
display.

