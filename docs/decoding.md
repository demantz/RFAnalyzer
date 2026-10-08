# Packet Decoding (rtl_433)

RF Analyzer can optionally decode digital sensor packets in the currently
received band using [rtl_433](https://github.com/merbanan/rtl_433). Typical
examples are weather stations, remote controls, TPMS sensors and energy meters
on the 433, 315 and 868 MHz bands.

!!! note
    Decoding is a best-effort side channel: it does not affect the FFT,
    waterfall, demodulation or recording. If the decoder cannot keep up, it
    drops whole IQ buffers instead of slowing down the receiver.

## How it works

rtl_433 normally opens the SDR itself. RF Analyzer already owns the SDR, so the
decoder is driven in "push" mode instead:

1. The Scheduler hands the interleaved IQ buffer (the same data used for the
   FFT) to the decoder.
2. The decoder converts the samples to rtl_433's native CU8 format and, if
   configured, decimates to the **Decoder Input Rate**.
3. Decoded events are published to the Decoder tab and, optionally, appended to
   a JSONL log file.

Because decoding works on the raw IQ around the current center frequency, tune
the SDR so that the signal of interest is within the decoder's bandwidth
(roughly ± half of the decoder input rate around the center frequency).

## Enabling decoding

Open the control drawer and select the **Decoder** tab:

- **Decode with rtl_433** – enables decoding while the analyzer is running. A
  small `rtl_433` badge appears over the spectrum view while decoding is active
  and briefly highlights the model name whenever a packet is decoded.
- **Decoder Input Rate** – the sample rate the decoder runs at. Lower rates use
  less CPU; 250 kSps is a good default for OOK/FSK sensors. The input stream is
  decimated with an integer factor, so the actual rate is the SDR sample rate
  divided by an integer.
- **Units** – `Native` (values exactly as decoded), `SI` (metric, e.g. °C) or
  `US customary` (e.g. °F). This maps to rtl_433's unit conversion.
- **Auto Level** – adapts the detection threshold to the noise floor. Keep it on
  while roaming; turn it off if you prefer a fixed threshold.
- **Minimum SNR** – the minimum signal-to-noise ratio (dB) for a pulse to be
  considered a signal. Raise it to reduce false positives, lower it to catch
  weaker sensors.
- **Report Signal Metadata** – include modulation, frequency, RSSI, SNR and
  noise in each decoded event.
- **Status** – shows whether the decoder is running and how many events were
  decoded.
- **Latest** – displays the fields and values of the most recent event.
- **Log Decoded Events** – appends every event as one JSON object per line to a
  file in the app's storage. Use **View**, **Save** or **Share** to inspect or
  export it.

All protocols that rtl_433 enables by default are active; no protocol filter is
applied. The decoder shares the SDR with the rest of the app, so only the raw IQ
stream is used — rtl_433 never opens the device itself.

## Mapping to rtl_433 options

The controls map onto rtl_433 command line options:

| App setting | rtl_433 equivalent |
|---|---|
| Decoder Input Rate | `-s` (the SDR rate is decimated to this value) |
| Channelized Decoding | mix to the tuned channel + low-pass before decoding |
| Units | `-C native` / `-C si` / `-C customary` |
| Auto Level | `-Y autolevel` |
| Minimum SNR | `-Y minsnr=<dB>` |
| Report Signal Metadata | `-M level,noise,...` (`report_meta`) |
| Log Decoded Events | `-F json` (one JSON object per line) |

The center frequency and gain follow the SDR settings and are passed to the
decoder automatically. Custom (unknown) protocols can be described with the
**flex decoder** editor (see below), which maps to rtl_433's `-X`. Other
advanced rtl_433 features that are intentionally not exposed (protocol
selection `-R`, FSK detector mode, frequency hopping) can be added without
changing the integration surface: the decoder exposes a small
`setOptions`/`configure` API and the UI is a plain Compose tab.

## Custom (flex) decoders

Not every device has a built-in rtl_433 protocol. For those, rtl_433 ships the
"flex" decoder (`-X`), which describes a decoder as a single spec string. RF
Analyzer exposes it under **Custom Decoders (flex)** in the Decoder tab:

- **Add decoder** opens an editor pre-filled with a preset. Pick a preset such
  as *OOK PWM 467/927 us*, adjust the fields, and **Save**.
- Each entry is stored as a spec string and can be **edited** or **deleted**.
  Several flex decoders can be active at the same time; they run alongside the
  built-in protocols.
- The structured fields build the spec:

  | Field | Spec key | Meaning |
  |---|---|---|
  | Name | `n=` | label shown in decoded events |
  | Modulation | `m=` | `OOK_PWM`, `OOK_PPM`, `OOK_PCM`, `OOK_MC_ZEROBIT`, `OOK_DMC`, `FSK_PCM`, … |
  | Short width | `s=` | short pulse width in µs |
  | Long width | `l=` | long pulse width in µs |
  | Reset limit | `r=` | reset the slicer after this gap (µs) |
  | Gap limit | `g=` | maximum gap inside a frame (µs) |
  | Sync width | `y=` | sync/preamble pulse width (µs) |
  | Tolerance | `t=` | timing tolerance (µs) |
  | Bits | `bits=` | expected frame length |

- **Raw spec (advanced)** overrides the structured fields and accepts the full
  rtl_433 `-X` syntax, including keys the form does not expose (e.g. `invert`,
  `reflect`, `repeats>`, `match`).

Example spec for an unknown OOK remote with short 467 µs / long 927 µs pulses
and 29-bit frames:

```
n=ook,m=OOK_PWM,s=467,l=927,r=2000,g=0,t=0,bits=29
```

Set **Bits** (`bits=`) whenever the frame length is known. rtl_433's flex
decoder emits *every* pulse train that reaches the slicer, so on a noisy band a
spec without `bits=` floods the event list with 1–13 bit fragments and hides the
real frame. `bits=<n>` keeps only rows of exactly that length.

When decoding is running, saving a changed list restarts the decoder so the new
flex decoders take effect. Flex decoders are included in bookmark
**export/import** (the `flexDecoders` field of the JSON), so a working set can be
shared or restored with a backup.

## Channelized decoding

By default the decoder sees the whole SDR band, like rtl_433 normally does. A
strong signal elsewhere in the band (a broadcast carrier a few hundred kHz away,
for example) can desensitise rtl_433's pulse detector and hide a weak sensor.

**Channelized Decoding** filters the decoder input to a narrow channel before
decoding: it mixes the tuned channel to baseband and low-pass filters it to the
channel width, rejecting the out-of-channel interferers. Enable it when you are
hunting weak signals next to strong ones.

It reuses the **demodulation channel**:

- **Tune the demodulation channel to the signal** (tap the waterfall); its
  frequency gives the channel offset.
- Pick a **demodulation mode whose channel width matches the signal**, e.g.
  **AM** or **narrow FM** with a **10–25 kHz** width for typical 433 MHz OOK
  remotes. The demodulation mode's audio is irrelevant to decoding, so you can
  mute it if you only want to decode.

If the demodulation channel is outside the captured band, the filter falls back
to the SDR center. Channelized decoding adds a small amount of CPU (a mixer and
a FIR), and is off by default.

## Changing settings while decoding

Retuning the SDR or changing the sample rate keeps decoding active: the decoder
is reconfigured with the new center frequency and rate. Changing the
demodulation channel frequency or width while channelized decoding is enabled
re-filters the decoder input. Stopping the analyzer stops the decoder as well.

## Data format

Each decoded line is a JSON object as produced by rtl_433, for example:

```json
{"time":"2026-10-06 22:07:45","model":"X10-RF","id":1,"channel":"B","state":"ON","data":1888420095,"mic":"PARITY","mod":"ASK","freq":309.98714,"rssi":0.74611,"snr":20.53025,"noise":-20.5305}
```

The `mod`, `freq`, `rssi`, `snr` and `noise` fields are included because RF
Analyzer enables rtl_433's metadata reporting.

## License

rtl_433 is licensed under the GNU General Public License version 2 or later.
See the app's About screen and `librtl433/src/main/cpp/rtl_433/COPYING` for
details.
