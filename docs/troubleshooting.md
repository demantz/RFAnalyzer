# Troubleshooting & FAQs

## SDR Device Not Recognized

If you get errors like "Source not available" or "Error with Source",
the app is not able to communicate with the SDR device.

Possible reasons are:

- Wrong SDR selected in **Source Tab**.
- Phone’s USB port doesn’t provide enough power.
- USB OTG adapter is faulty.
- Another app is already using the SDR.

**Solution:**

1. Try a powered USB hub.
2. Ensure no other apps are using the SDR.
3. Try different USB OTG adapters.
4. Turn on logging in the [Settings](./settings.md#logging) and look in the log file for more details.

!!! note "USB permission denied"
    Android asks once for permission to use the SDR. If you denied it, tap
    **Start** again and allow access.

## Network Sources (rtl_tcp, KiwiSDR)

- **Android 17 and newer:** a server in your own network (rtl_tcp, a local
  KiwiSDR) needs RF Analyzer's local network permission. If Android no longer
  asks, allow it in the app's settings under Permissions → Nearby devices.
- Short network stalls (up to 15 seconds) are bridged; after that the source
  stops with "Streaming stopped unexpectedly".
- A KiwiSDR with **no free channel slots** is busy: pick another receiver.

## Purchase Not Recognized

Purchases belong to the Google account that is active in the Play Store. Switch
the Play Store to the account you bought with, return to RF Analyzer and tap
**Check again** in the About tab.

## SDR Stops During Use (USB Connection Lost)

A loose USB plug or cable makes the SDR disconnect for a moment; the app then
shows "... stopped streaming unexpectedly (USB connection lost?)". When the same
SDR is connected again within 10 seconds, the analyzer restarts by itself - also
with the app in the background, which stays there. Meanwhile the notification
says "Waiting for ... to be reconnected"; its **Stop** button ends the wait.

When the SDR comes back, Android asks whether to open RF Analyzer for it; tick
**Always open RF Analyzer when ... is connected** so that later reconnects work
without the question. (Outside of these 10 seconds, and unless
[Start When an SDR Is Plugged In](./settings.md#start-when-an-sdr-is-plugged-in)
is enabled, Android doesn't offer RF Analyzer when an SDR is plugged in.)

## Performance Issues (Slow FFT, Choppy Audio)

Older hardware might not be powerful enough to ensure smooth operation of the
app. In this case try the following:

- Reduce the **sample rate** of the source.
- Lower the **Max Frame Rate** in the Display tab.
- Keep the **Waterfall Speed** at 30 fps or below: above that, the FFT frames
  overlap and cost more processing.
- With **Manual FFT Size** on, choose a smaller **FFT Size** (otherwise it is
  chosen automatically).

If the app shows an **Overload** warning, see [Processing Overload](#processing-overload).

## Processing Overload

The app processes the signal of the SDR in real time: every second of signal
has to be filtered, demodulated and decoded within one second. If your device
is too slow for the current settings, it can't keep up and has to throw parts
of the signal away. When this happens, the Demodulation tab shows the warning
chip **Overload – samples dropped**; tap its **?** to open this page. The audio
stutters or is choppy. The chip disappears a few seconds after the overload
stops.

**What you can do** (start at the top, it usually helps most):

1. Reduce the **sample rate** of the source in the Source tab. This lowers the
   load of the whole processing chain. You see less bandwidth in the FFT, but
   the demodulator only needs the part around the channel anyway.
2. Lower the **Max Frame Rate** and the **Waterfall Speed** in the
   [Display tab](./fft.md#fft-size); with **Manual FFT Size** on, also the
   **FFT Size**. Drawing the waterfall competes with the demodulator for
   processing power.
3. Enable [Low Performance Mode](./settings.md#low-performance-mode) and lower
   the filter quality until the warning goes away.
4. Close other apps running in the background and turn off battery saver
   mode, which slows down the processor.

**Details for the curious:** enable
[Show Debug Information](./settings.md#show-debug-information) to see the load
of the busiest processing stage in the top right corner of the FFT (green below
70%, yellow up to 100%, red above), and, during an overload, how much of the
signal was dropped and where.

## Poor Signal Quality / High Noise

- Use a **good antenna** & place it in an open area.
- Disable **automatic gain** and manually adjust gain.
- Increase **sample rate** to reduce aliasing (HackRF).

## App Crashes

- If possible, submit crash reports via Android.
- RF Analyzer can log system errors; check the [Settings](./settings.md#logging).
