# About the App

RF Analyzer is an open source spectrum analyzer that lets you use you
SDR hardware together with an Android device. A detailed description
can be found in the [manual](https://demantz.github.io/RFAnalyzer/index.html)

Changelog: [changelog.txt](https://raw.githubusercontent.com/demantz/RFAnalyzer/master/changelog.txt)

## About the author

Hi! I’m DM4NTZ, a ham radio operator and open-source developer.

I'm the creator and maintainer of RF Analyzer, an open-source software-defined
radio (SDR) app for Android. The app lets you visualize and analyze RF signals
in real time using SDR hardware such as RTL-SDR dongles.

My goal is to make SDR technology more accessible, portable, and open for radio
enthusiasts, experimenters, and learners.

As a licensed amateur radio operator, I'm passionate about signal processing,
wireless communication, and the broader SDR ecosystem. I also contribute to
open-source projects, share knowledge with the ham radio community, and
continuously improve my tools based on user feedback.

If you like what I'm building or want to support the development of RF Analyzer
and related open-source SDR tools, you can support me with one of the following
options. Every contribution helps keep the project alive and open for everyone.

- [Ko-fi](https://ko-fi.com/rfanalyzer)
- [Liberapay](https://liberapay.com/DM4NTZ/donate)
- [Paypal](https://paypal.me/dennismantz)

How to reach me:

- Amateur Radio Callsign: DM4NTZ
- Mastodon: [@dm4ntz@chaos.social](https://chaos.social/@dm4ntz)
- Mail: rfanalyzerapp -at- gmail -dot- com

## Get Beta Releases

If you want to help me by testing beta releases you can join the beta program
on Google Play:

[https://play.google.com/apps/testing/com.mantz_it.rfanalyzer](https://play.google.com/apps/testing/com.mantz_it.rfanalyzer)

## Reporting Bugs

If you find any bugs or translation mistakes or if you want to request
a new feature please use the GitHub page of the project:
[https://github.com/demantz/RFAnalyzer/issues](https://github.com/demantz/RFAnalyzer/issues)

Alternatively you could write me an email to **rfanalyzerapp -at- gmail.com**

## Privacy Policy

*Last updated: November 11, 2026*

This policy covers the apps RF Analyzer and RF Analyzer Digital Extension by Dennis Mantz
(DM4NTZ). Contact: rfanalyzerapp -at- gmail.com

Neither app has user accounts, advertising, analytics or a crash-reporting service, and neither
sends data to the developer. Google Play may give the developer anonymous crash and performance
statistics, depending on your device settings. If you write to the developer, your message is
used only to answer it.

### RF Analyzer

- **Data on your device:** settings, bookmarks, station lists, recordings and the packets decoded
  in the Digital tab stay on your device. If backups are enabled, Android may include settings
  and bookmarks in your device backup in your Google account; decoded packets are excluded from
  cloud backups. Recordings and exports leave the device only where you share or save them.
- **Location:** RF Analyzer estimates your country on the device (mobile network, SIM, time zone,
  language) to choose band plans and station lists. Optionally you can enter a grid locator or
  allow access to the device location, for the map, distances and nearby stations. Your location
  is not sent anywhere; it is stored with your captures only if you turn that on.
- **Internet connections:** RF Analyzer connects only to the servers below. Each of them sees
  your IP address and what is requested, under its own privacy policy:
    - SDR servers you connect to (rtl_tcp, KiwiSDR - KiwiSDR operators can see the frequency you
      listen to) and the public KiwiSDR list (kiwisdr.com)
    - map tiles from OpenFreeMap (openfreemap.org) for the area you view
    - station lists: the digital station list (demantz.github.io, GitHub Pages; updated
      automatically) and, if you enable them, EiBi (afu-base.de), POTA (pota.app) and SOTA
      (sota.org.uk)
    - time servers (time.android.com, pool.ntp.org) for FT8's clock correction
  your IP address and what is requested, under its own privacy policy:
    - SDR servers you connect to (rtl_tcp, KiwiSDR - KiwiSDR operators can see the frequency you
      listen to) and the public KiwiSDR list (kiwisdr.com)
    - map tiles from OpenFreeMap (openfreemap.org) for the area you view
    - station lists: the digital station list (demantz.github.io, GitHub Pages; updated
      automatically) and, if you enable them, EiBi (afu-base.de), POTA (pota.app) and SOTA
      (sota.org.uk)
    - time servers (time.android.com, pool.ntp.org) for FT8's clock correction
    - links you tap (e.g. QRZ.com, aprs.fi, FlightAware) open in your browser
- **Purchases** (Google Play version): processed by Google Play under Google's privacy policy;
  the developer receives only the order information Google Play provides, no payment details.

### RF Analyzer Digital Extension

The extension (com.mantz_it.rfanalyzer_digital) decodes digital radio modes for RF Analyzer on
the same device. It has no internet access (it doesn't request Android's INTERNET permission).
It processes the radio samples RF Analyzer passes to it in memory only and returns the decoded
results to RF Analyzer, which stores and shows them as described above. The only thing the
extension stores itself is whether you have seen its tutorial.

## License

[GPL version 2 or higher](http://www.gnu.org/licenses/gpl.html)

```
This application is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public
License as published by the Free Software Foundation; either
version 2 of the License, or (at your option) any later version.

This application is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public
License along with this library; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
```

Get the source code of the app on [GitHub](https://github.com/demantz/RFAnalyzer)

Copyright &copy; 2025 Dennis Mantz

### PFFFT License

[PFFFT : a Pretty Fast FFT](https://bitbucket.org/jpommier/pffft)

```
Copyright (c) 2013  Julien Pommier ( pommier@modartt.com )

Based on original fortran 77 code from FFTPACKv4 from NETLIB
(http://www.netlib.org/fftpack), authored by Dr Paul Swarztrauber
of NCAR, in 1985.

As confirmed by the NCAR fftpack software curators, the following
FFTPACKv5 license applies to FFTPACKv4 sources. My changes are
released under the same terms.

FFTPACK license:

http://www.cisl.ucar.edu/css/software/fftpack5/ftpk.html

Copyright (c) 2004 the University Corporation for Atmospheric
Research ("UCAR"). All rights reserved. Developed by NCAR's
Computational and Information Systems Laboratory, UCAR,
www.cisl.ucar.edu.

Redistribution and use of the Software in source and binary forms,
with or without modification, is permitted provided that the
following conditions are met:

- Neither the names of NCAR's Computational and Information Systems
Laboratory, the University Corporation for Atmospheric Research,
nor the names of its sponsors or contributors may be used to
endorse or promote products derived from this Software without
specific prior written permission.

- Redistributions of source code must retain the above copyright
notices, this list of conditions, and the disclaimer below.

- Redistributions in binary form must reproduce the above copyright
notice, this list of conditions, and the disclaimer below in the
documentation and/or other materials provided with the
distribution.

THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING, BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE CONTRIBUTORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES OR OTHER LIABILITY, WHETHER IN AN
ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
SOFTWARE.
```


### HackRF License

RFAnalyzer uses [libhackrf](https://github.com/greatscottgadgets/hackrf/tree/master/host/libhackrf)

```
Copyright (c) 2012-2022 Great Scott Gadgets <info@greatscottgadgets.com>
Copyright (c) 2012, Jared Boone <jared@sharebrained.com>
Copyright (c) 2013, Benjamin Vernoux <titanmkd@gmail.com>

All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

    Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
    Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the 
	documentation and/or other materials provided with the distribution.
    Neither the name of Great Scott Gadgets nor the names of its contributors may be used to endorse or promote products derived from this software
	without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, 
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Airspy License

RF Analyzer uses [libairspy](https://github.com/airspy/airspyone_host/tree/master/libairspy)

```
Copyright (c) 2012, Jared Boone <jared@sharebrained.com>
Copyright (c) 2013, Michael Ossmann <mike@ossmann.com>
Copyright (c) 2013-2016, Benjamin Vernoux <bvernoux@airspy.com>
Copyright (C) 2013-2016, Youssef Touil <youssef@airspy.com>

All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

    Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
    Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the 
    documentation and/or other materials provided with the distribution.
    Neither the name of AirSpy nor the names of its contributors may be used to endorse or promote products derived from this software
    without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, 
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### AirspyHF License

RF Analyzer uses [libairspyhf](https://github.com/airspy/airspyhf/tree/master/libairspyhf)

```
Copyright (c) 2013-2023, Youssef Touil <youssef@airspy.com>
Copyright (c) 2013-2017, Ian Gilmour <ian@sdrsharp.com>
Copyright (c) 2013-2017, Benjamin Vernoux <bvernoux@airspy.com>
Copyright (c) 2013, Michael Ossmann <mike@ossmann.com>
Copyright (c) 2012, Jared Boone <jared@sharebrained.com>

All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

		Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
		Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
		documentation and/or other materials provided with the distribution.
		Neither the name of Airspy HF+ nor the names of its contributors may be used to endorse or promote products derived from this software
		without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### HydraSDR License

RF Analyzer uses [libhydrasdr](https://github.com/hydrasdr/rfone_host/tree/main/libhydrasdr)

```
Copyright (c) 2012, Jared Boone <jared@sharebrained.com>
Copyright (c) 2013, Michael Ossmann <mike@ossmann.com>
Copyright (C) 2013-2016, Youssef Touil <youssef@airspy.com>
Copyright (c) 2013-2026, Benjamin Vernoux <bvernoux@hydrasdr.com>

All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the 
documentation and/or other materials provided with the distribution.
Neither the name of HydraSDR nor the names of its contributors may be used to endorse or promote products derived from this software
without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, 
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```


