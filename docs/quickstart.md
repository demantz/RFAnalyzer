# Quick Start

The following YouTube video explains how to set up your SDR with RF Analyzer:

<div style="text-align: center;">
  <a target="_blank" href="https://www.youtube.com/watch?v=sui54fqbImw">
    <img src="./assets/RF Analyzer Quick Start Tutorial Thumbnail.png" style="max-width: 100%; height: auto; width: 400px; border: 2px solid #888; border-radius: 28px;" alt="YouTube Tutorial">
  </a>
</div>

Alternatively, follow these steps to get started quickly:

1. **Install the app** from Google Play Store: 
   [RF Analyzer for Android](https://play.google.com/store/apps/details?id=com.mantz_it.rfanalyzer)
2. **Connect your SDR device**:
    - For **USB SDRs (HackRF, RTL-SDR)** → Use an **USB-OTG adapter**.
    - If you don't own a SDR device, you could start by replaying an **IQ file**
      (select **Filesource** in the next step)
    - More information about connecting the SDR device can be found under [Installation & Setup](./setup.md)
3. In the app navigate to the Source Tab (see [SDR Source](./sdr-source.md)) and select your
source type from the dropdown menu.
4. Set a tune frequency and initial sample rate, as well as gain values and
other source settings (optional; see [SDR Source](./sdr-source.md) for more explanation on these settings).
5. Press the play button to start the sampling.
    - First time usage: **Grant USB permissions** when prompted.
    - You should now see a FFT and waterfall plot. 
    - Scroll horizontally to automatically retune the source and find signals
      in the spectrum.
    - Use pinch-to-zoom gestures to zoom in on signals.
6. Go to the Demodulation Tab ([Demodulation](./demodulation.md)) and **select a demodulation mode** from the
dropdown menu.
    - Align the channel selector with a signal.
    - Adjust squelch (and potentially the gain of your source).
    - You should hear the demodulated audio signal

Please have a look at the following sections to learn more about all [elements
of the UI](./ui-overview.md), [SDR Source Tab](./sdr-source.md), [Display Tab (FFT settings)](./fft.md) and
[demodulation](./demodulation.md) settings, [signal recording](./recording.md)
and more. If you have trouble with the app, have a look at the
[troubleshooting](./troubleshooting.md) section!

I wish you a lot of fun analyzing the RF spectrum! :)
