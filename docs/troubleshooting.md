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

## Performance Issues (Slow FFT, Choppy Audio)

Older hardware might not be powerful enough to ensure smooth operation of the
app. In this case try the following:

- Reduce **sample rate** of the source.
- Lower **FFT size & frame rate** in the FFT tab.
- Increase **waterfall speed** (fewer history samples).

## Poor Signal Quality / High Noise

- Use a **good antenna** & place it in an open area.
- Disable **automatic gain** and manually adjust gain.
- Increase **sample rate** to reduce aliasing (HackRF).

## App Crashes

- If possible, submit crash reports via Android.
- RF Analyzer can log system errors; check the [Settings](./settings.md#logging).
