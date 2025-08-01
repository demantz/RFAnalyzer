# Understanding dB, dBm and dBFS

The decibel (dB) is a logarithmic unit used to express ratios - usually power
or amplitude ratios - in a compact and human-friendly way. Instead of saying
"this signal is 10,000 times stronger than that one", you can just say "it's 40
dB stronger". Decibels are relative by design - you always need to know what
the reference point is.

Here are a few common variants:

- dB: A relative unit without a fixed reference. You’ll often see it used in
  comparisons like “signal-to-noise ratio = 50 dB”.
- dBm: This is a power measurement referenced to 1 milliwatt. A signal of 0 dBm
  means it's 1 milliwatt of power. This unit is useful for real-world
  measurements and requires proper calibration.
- dBFS: Short for decibels relative to full scale. It’s used in digital
  systems, where 0 dBFS is the maximum level that the system can represent. All
  other values are negative (e.g., -10 dBFS, -40 dBFS), since they are below
  this maximum.

## Why the App Uses dBFS, Not dBm

You might expect the signal strength in the app to be shown in dBm, but here’s
the catch: dBm requires exact knowledge of the hardware’s RF chain - including
gain stages, losses, antenna efficiency, and calibration constants. Every SDR
device is different, and many don’t provide accurate power calibration out of
the box.

Instead, the app shows signal levels in dBFS - decibels relative to
the full scale of the incoming digital signal from your SDR. This is a digital
measurement, and it’s consistent across devices because it’s based on the
signal’s amplitude relative to the device’s own maximum input range.

What this means for you:

- The values are still very useful for comparing signal strengths (e.g., “this
  signal is 20 dB stronger than the noise floor”).
- However, they are not absolute - you can’t use them to determine whether a
  signal is actually -73 dBm at the antenna, for example.

---

## Understanding the Fast Fourier Transformation

The **Fourier Transformation** is a powerful tool used throughout signal
processing, and it’s at the heart of what your SDR app shows in the spectrum
and waterfall displays. In simple terms, it allows us to "look inside" a
complex signal and break it down into its individual frequency components.

Imagine you are listening to an orchestra: although all the instruments play
together, your brain can often distinguish the violins from the trumpets. The
Fourier Transform does something similar for signals - it shows how much of each
frequency is present at any given moment.

In practice, we use a computational method called the **Fast Fourier Transform
(FFT)**. It's an efficient algorithm that performs the Fourier Transform on
discrete digital data. It’s what allows your device to visualize the signal
spectrum in real time without needing supercomputers.

### FFT Size

The **FFT size** determines how many signal samples are analyzed at once when
transforming the signal from the time domain to the frequency domain. This
directly affects the **frequency resolution** - how precisely we can
distinguish between different frequencies.

Importantly, **frequency resolution** depends on both:

- the **FFT size**
- and the **sample rate** of the input signal.

The formula is simple:

> Frequency Resolution = Sample Rate / FFT Size

This means that **higher sample rates** - like those used with wideband devices
such as the HackRF - require **larger FFT sizes** in order to achieve useful
frequency resolution. If you're trying to analyze narrow signals such as **SSB
(Single Sideband)** or **CW (Morse code)**, using a small FFT size with a high
sample rate can result in a blurry spectrum where individual signals are
difficult to distinguish.

In contrast, if you're using a lower sample rate or analyzing wideband signals
(like FM broadcast), smaller FFT sizes may already provide sufficient detail.

---

## Ham Radio

**Amateur radio**, often simply called **ham radio**, is a globally licensed
hobby and service that allows individuals to explore the airwaves, communicate
with others across the world, and experiment with radio technology.

Amateur radio operators use specific frequency bands allocated by international
agreements. They are allowed to communicate for non-commercial purposes, engage
in emergency services, and even bounce signals off the moon or satellites!

Unlike commercial radio or unlicensed bands (like CB or PMR), operating as a
ham requires passing an exam and obtaining a **callsign**. This may sound
daunting - but it’s also incredibly rewarding.

Ham radio blends **technical learning**, **practical skills**, and a **global
community**. Whether you are interested in voice contacts, Morse code, digital
data modes, or just tuning the bands to listen, there’s something in it for
everyone.

Learn more:

- **[IARU - International Amateur Radio Union](https://www.iaru.org/)**
- **[ARRL - American Radio Relay League](https://www.arrl.org/what-is-ham-radio)**
- **[RSGB - Radio Society of Great Britain](https://rsgb.org/)**
- **[DARC - Deutscher Amateur-Radio-Club](https://www.darc.de/)**

Want to get inspired? Try tuning into an amateur band using this app. You’ll
often hear contacts in progress, and different modes like USB or CW depending
on the band.

Best regards (or as we hams like to put it: **73**),
Dennis (callsign: DM4NTZ)
