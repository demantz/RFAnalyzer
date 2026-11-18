# TMC / ALERT-C reference data — third-party attribution

The files `tmc_event_list.tsv` and `tmc_supplementary_info.tsv` in this directory contain the
ALERT-C (RDS Traffic Message Channel) event-code and supplementary-information lookup tables
used to turn decoded TMC messages into human-readable text.

These tables are **derived from [RdsSurveyor](https://github.com/ChristopheJacquet/RdsSurveyor)**,
an open-source RDS/TMC decoder licensed under the **GNU Lesser General Public License v3.0
(LGPL-3.0)**. Specifically, they are reformatted exports of RdsSurveyor's bundled `EL.DAT`
(event list) and `SIL.DAT` (supplementary information list) data files.

The formulas and texts that turn a message's optional fields (quantifiers, durations, start and
stop times, route lengths) into words, in `digiext/rds/Tmc.kt`, are ported from RdsSurveyor's
`AlertC.java` and `tmc/TMCEvent.java` (Copyright (c) 2009, 2010 Christophe Jacquet and the RDS
Surveyor contributors) and are under the LGPL-3.0 or later as well.

This is a practical, community-maintained substitute for the official ALERT-C event list
(ISO 14819-2), which is a paywalled standard with no freely available text. Event-code numbering
in this data may not exactly match every deployed encoder's numbering.

See https://github.com/ChristopheJacquet/RdsSurveyor for the RdsSurveyor project and the full
text of the LGPL-3.0 license.
