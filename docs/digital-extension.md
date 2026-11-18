---
search:
  exclude: true
---

# Digital Extension

The **Digital Extension** adds decoding of *digital* signals to RF Analyzer:
aircraft positions, pager messages, packet radio and APRS, mesh-network chats,
FT8 contacts, teleprinter weather reports, tram priority telegrams and the
station names and traffic messages of FM broadcast stations. Where
[Signal Demodulation](./demodulation.md) turns a signal into audio, the Digital
Extension turns it into text, numbers and positions that you can read, sort,
search and put on a map.

It is a free, separate app. RF Analyzer runs your SDR and shows everything that
is decoded; the extension does the decoding in the background. It is available
on Google Play only and works with RF Analyzer from Google Play; the
open-source (FOSS) version of RF Analyzer has no digital modes.

Digital modes are part of RF Analyzer's full version (also the Base Version
together with the Full Version Upgrade). Without it - also with the Base
Version - they have a trial of their own: two hours of decoding, counted only
while a digital mode is received - looking at what was received is free, and
it works even after the general trial has ended. The time left shows in a small pill above the
control drawer, next to the general trial's operating time (tap it for
details), at the top of the Digital tab's mode sheet and in the About tab. A
message appears five minutes before the end. When the time is used up, the analyzer
stops with a message; everything received stays viewable. Like the general
trial, each start after that still decodes for 30 seconds, so you can still
see a mode at work.

---

## Installing

1. Install **RF Analyzer Digital Extension** from Google Play. Both apps must
   come from the same source (for example both from Google Play), otherwise
   Android doesn't let them talk to each other.
2. Its launcher icon **RFA Digital** opens a short tutorial the first time. It
   shows how the extension works and checks that a suitable RF Analyzer is
   installed; its last page opens RF Analyzer on its **Digital** tab.

After that, the **RFA Digital** icon takes you straight to the Digital tab.
Long-press the icon and pick **How it works** to see the tutorial again.

The extension has no screen of its own and needs no settings. RF Analyzer starts
it whenever a digital mode is selected. Decoding keeps running in the
background as long as RF Analyzer's notification is shown, just like audio
demodulation.

---

## When Digital Modes Can't Be Received

RF Analyzer never starts a digital mode that would silently receive nothing.
When something is missing, the Digital tab, its mode sheet and the Demodulation
tab show a notice that says what is wrong, with a button for the fix. Tapping
**Start** or switching the mode list to **Digital** explains it too. Everything
received before stays viewable.

| Notice | What to do |
|---|---|
| **Digital modes trial used up** | **Unlock digital modes** shows the full version - or, if you have the Base Version, the upgrade - with its price; it unlocks digital modes for good. Until then each start decodes for 30 seconds. |
| **RFA Digital is not installed** | **Install RFA Digital** opens its Play Store page. Come back to RF Analyzer afterwards: it notices the extension by itself. |
| **Update RFA Digital** | The extension is too old for this RF Analyzer. **Update RFA Digital** opens its Play Store page. |
| **Update RF Analyzer** | RF Analyzer is too old for the installed extension. **Update RF Analyzer** opens its Play Store page. |
| **RFA Digital can't connect** | The two apps come from different sources (for example a self-built RF Analyzer). Install both from Google Play. |
| **Connection to RFA Digital lost** | RF Analyzer reconnects by itself within a few seconds. Nothing is decoded until then. |
| **RFA Digital is not responding** | Reconnecting failed several times within a minute. Tap **Retry**; if that doesn't help, stop and start the analyzer. |

While a digital mode runs, RF Analyzer checks every second that the extension
is still decoding; a lost or stuck connection shows up within a few seconds.
RF Analyzer's notification then says so too, so you notice it with the app in
the background.

Google Play sometimes updates one app hours or days before the other. Until
both are updated, the notice stays, and FM with **Stereo & RDS** plays in mono.

---

## Starting a Mode

There are two ways to start receiving a digital mode:

- **From the Digital tab** (recommended): tap the mode button at the top left
  of the [Digital tab](./digital-tab.md), then **›** next to the mode to pick a
  band, city or network from the presets for your area (or **Start** to start
  the one you started last). RF Analyzer selects the mode, tunes the channel,
  sets the mode's settings, adjusts the source frequency and sample rate if
  needed and starts the analyzer. See [Choosing a Mode](./digital-tab.md#choosing-a-mode).
- **By hand in the Demodulation tab**: open the mode list (the
  [Demodulation Mode](./demodulation.md#demodulation-mode) button), switch to
  **Digital** and pick a mode. Then tune the channel to the signal like for any
  other mode. Each mode's page explains its settings.

Only one mode is received at a time, analog or digital. Select your source
first (an SDR, a KiwiSDR web receiver or a recording, see
[SDR Source](./sdr-source.md)).

Decoded data appears in the [Digital tab](./digital-tab.md). Everything is
stored on the phone, so you can come back to it later, search it, put it on the
map and share it.

---

## The Modes

The modes in the order of the mode list:

| Mode | What you receive | Typical frequencies |
|---|---|---|
| [RTTY](./rtty.md) | Teleprinter text: the German weather service's broadcasts for shipping, radio amateurs | shortwave, 147.3 kHz |
| [PSK31](./psk31.md) | Keyboard-to-keyboard chats of radio amateurs (also PSK63, PSK125) | shortwave |
| [R09](./r09.md) | The radio telegrams trams and buses send to get priority at traffic lights (Germany) | 150-170 MHz |
| [FT8](./ft8.md) | The weak-signal mode behind most long-distance amateur contacts today | shortwave, 6 m, 2 m |
| [AX.25 / packet radio](./ax25.md) | APRS position reports, weather stations and messages; mailboxes and nodes of the packet networks | 144.800 MHz (Europe), 144.390 MHz (North America), shortwave |
| [ADS-B](./adsb.md) | Aircraft: callsign, altitude, speed and position | 1090 MHz |
| [POCSAG](./pocsag.md) | Pager messages (paging networks, amateur DAPNET) | 140-470 MHz |
| [LoRa](./lora.md) | The MeshCore and Meshtastic mesh messengers, LoRaWAN sensors, LoRa-APRS | 868 MHz (Europe), 915 MHz (Americas), 433 MHz |
| [RDS](./rds.md) | Station names, radio text and traffic messages of FM broadcast stations | 87.5-108 MHz |

RDS is received with the **FM (wide)** mode and its **Stereo & RDS** switch, so
you can listen to the station at the same time.

RF Analyzer only receives. It never transmits, and it shows only what is sent
openly: encrypted messages (private mesh channels, LoRaWAN data) stay
encrypted.

---

## Recordings

The Digital Extension decodes recordings just like live signals: select the
recording as the source and start the mode (see
[Recording & Playback](./recording.md)). A recording made with RF Analyzer is a
good way to try a mode again with other settings, or to look at a signal you
caught while you were out. See
[Replaying Recordings](./digital-tab.md#replaying-recordings) for how the
Digital tab treats the time of replayed data.

---

## When the Phone Can't Keep Up

Decoding needs processing power, and some modes need a lot of it (wide LoRa
channels, ADS-B). If the phone can't keep up, parts of the signal are dropped
and packets are missed. The Digital tab then shows a **Processing overload**
notice. See [Processing Overload](./troubleshooting.md#processing-overload) for
what helps.
