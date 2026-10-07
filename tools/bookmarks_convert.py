#!/usr/bin/env python3
"""
Convert bookmarks between GQrx (CSV) and RF Analyzer (station JSON).

  # GQrx CSV -> RF Analyzer JSON
  ./bookmarks_convert.py to-rfanalyzer gqrx.csv -o stations.json

  # RF Analyzer JSON -> GQrx CSV
  ./bookmarks_convert.py to-gqrx stations.json -o gqrx.csv

The GQrx CSV has two '#'-prefixed sections: tag names with colors, then
bookmark rows. The RF Analyzer side is the JSON produced by the app's
"Export bookmarks" feature.

Note: RF Analyzer does not serialize BookmarkList.id (it is @Transient), so a
station's numeric bookmarkListId cannot reference an exported list. This tool
therefore emits a single list and uses bookmarkListId 0, which the importer
maps to that list. GQrx tags are preserved in the station notes ("Tags: ...").

Only the Python standard library is required.
"""

import argparse
import json
import sys
import time

GQ_TO_RF_MODE = {
    "am": "AM",
    "am-sync": "AM",
    "narrow fm": "NFM",
    "fm": "NFM",
    "wfm (mono)": "WFM",
    "wfm (stereo)": "WFM",
    "wfm": "WFM",
    "lsb": "LSB",
    "usb": "USB",
    "cw-l": "CW",
    "cw-u": "CW",
    "cw": "CW",
    "demod off": "OFF",
    "raw i/q": "OFF",
}
RF_TO_GQ_MODE = {
    "AM": "AM",
    "NFM": "Narrow FM",
    "WFM": "WFM (mono)",
    "LSB": "LSB",
    "USB": "USB",
    "CW": "CW-U",
    "OFF": "Demod Off",
}


def _to_int(value, default=0):
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return default


def _hex_to_argb(text):
    text = (text or "").strip().lstrip("#")
    if not text:
        return None
    try:
        value = int(text, 16)
    except ValueError:
        return None
    if len(text) <= 6:
        value |= 0xFF000000
    return value - (1 << 32) if value >= (1 << 31) else value


def _argb_to_hex(value):
    if value is None:
        return None
    return "#{:06x}".format(value & 0xFFFFFF)


def parse_gqrx(text):
    """Returns (tag_colors, rows) from a GQrx bookmark CSV."""
    tag_colors = {}
    rows = []
    section = None
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#"):
            low = line.lower()
            # Check the row header first: it also contains the word "Tags".
            if "frequency" in low:
                section = "rows"
            elif "tag" in low and "name" in low:
                section = "tags"
            continue

        if section == "tags":
            name, _, color = line.partition(";")
            tag_colors[name.strip()] = color.strip()
        elif section == "rows":
            parts = [p.strip() for p in line.split(";")]
            if len(parts) < 4 or not parts[0]:
                continue
            tags = [t.strip() for t in ",".join(parts[4:]).split(",") if t.strip()]
            rows.append({
                "frequency": _to_int(parts[0]),
                "name": parts[1] or "Unnamed",
                "modulation": parts[2],
                "bandwidth": _to_int(parts[3]),
                "tags": tags,
            })
    return tag_colors, rows


def to_rfanalyzer(tag_colors, rows, list_name):
    now = int(time.time() * 1000)
    color = None
    for value in tag_colors.values():
        color = _hex_to_argb(value)
        if color is not None:
            break

    bookmark_list = {
        "name": list_name,
        "type": "STATION",
        "notes": "Imported from GQrx",
    }
    if color is not None:
        bookmark_list["color"] = color

    stations = []
    for row in rows:
        # "Untagged" is GQrx's catch-all and carries no information.
        tags = [t for t in row["tags"] if t.lower() != "untagged"]
        station = {
            "bookmarkListId": 0,
            "name": row["name"],
            "frequency": row["frequency"],
            "bandwidth": row["bandwidth"],
            "createdAt": now,
            "updatedAt": now,
            "mode": GQ_TO_RF_MODE.get(row["modulation"].strip().lower(), "OFF"),
            "favorite": False,
            "demodulationParameters": {
                "version": 1,
                "squelch": {"enabled": False, "thresholdDb": -100.0},
            },
        }
        if tags:
            station["notes"] = "Tags: " + ", ".join(tags)
        stations.append(station)

    return {
        "metadata": {
            "schemaVersion": 2,
            "appName": "RF Analyzer",
            "appVersion": "bookmarks_convert.py",
            "fullBackup": False,
        },
        "bookmarkLists": [bookmark_list],
        "stations": stations,
        "bands": [],
    }


def to_gqrx(doc):
    bookmark_lists = doc.get("bookmarkLists", []) or []
    default_tag = (bookmark_lists[0].get("name") if bookmark_lists else None) or "Untagged"

    # Tag section: one tag per list, with its color when available.
    tag_names = []
    tag_colors = {}
    for entry in bookmark_lists:
        name = entry.get("name") or "Untagged"
        if name not in tag_names:
            tag_names.append(name)
            tag_colors[name] = _argb_to_hex(entry.get("color")) or "#c0c0c0"
    if not tag_names:
        tag_names = ["Untagged"]
        tag_colors["Untagged"] = "#c0c0c0"

    lines = ["# Tag name          ;  color"]
    for name in tag_names:
        lines.append("{:<20}; {}".format(name, tag_colors[name]))
    lines.append("")
    lines.append("# Frequency ; Name ; Modulation ; Bandwidth; Tags")

    for station in doc.get("stations", []) or []:
        notes = station.get("notes") or ""
        tags = []
        if notes.lower().startswith("tags:"):
            tags = [t.strip() for t in notes[5:].split(",") if t.strip()]
        if not tags:
            tags = [default_tag]
        mode = RF_TO_GQ_MODE.get(str(station.get("mode", "OFF")).upper(), "Demod Off")
        lines.append("{:>12}; {:<25}; {:<20}; {:>8}; {}".format(
            _to_int(station.get("frequency")),
            station.get("name") or "Unnamed",
            mode,
            _to_int(station.get("bandwidth")),
            ", ".join(tags),
        ))

    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("direction", choices=["to-rfanalyzer", "to-gqrx"],
                        help="conversion direction")
    parser.add_argument("input", help="input file")
    parser.add_argument("-o", "--output", help="output file (default: stdout)")
    parser.add_argument("--list-name", default="GQrx import",
                        help="RF Analyzer bookmark list name to create (default: GQrx import)")
    args = parser.parse_args()

    with open(args.input, "r", encoding="utf-8") as handle:
        content = handle.read()

    if args.direction == "to-rfanalyzer":
        tag_colors, rows = parse_gqrx(content)
        result = json.dumps(to_rfanalyzer(tag_colors, rows, args.list_name), indent=4)
    else:
        result = to_gqrx(json.loads(content))

    if args.output:
        with open(args.output, "w", encoding="utf-8") as handle:
            handle.write(result)
    else:
        sys.stdout.write(result)


if __name__ == "__main__":
    main()
