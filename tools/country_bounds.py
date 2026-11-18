#!/usr/bin/env python3
"""
Writes app/src/main/assets/location/country_bounds.csv - the box the maps start on for a country
when the user gave no position (LocationRepository.mapHome).

Source: Natural Earth 1:50m Admin 0 countries (public domain),
https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_countries.geojson

    python3 tools/country_bounds.py ne_50m_admin_0_countries.geojson > app/src/main/assets/location/country_bounds.csv

The box is the country's largest landmass plus the islands next to it (Sicily, Hokkaido), not every
overseas part: the US box is the contiguous states, France's is metropolitan France. A box across the
antimeridian has east > 180.
"""
import json
import math
import sys


def ring_area(ring):
    # planar area in degrees², longitude scaled by cos(latitude) - only compared with each other
    lat0 = sum(p[1] for p in ring) / len(ring)
    k = math.cos(math.radians(lat0))
    a = 0.0
    for (x1, y1), (x2, y2) in zip(ring, ring[1:] + ring[:1]):
        a += x1 * k * y2 - x2 * k * y1
    return abs(a) / 2


def bbox(ring):
    xs = [p[0] for p in ring]
    ys = [p[1] for p in ring]
    return [min(ys), min(xs), max(ys), max(xs)]  # south, west, north, east


def gap(a, b):
    """Distance in degrees (longitude scaled) between two boxes, 0 if they overlap."""
    k = math.cos(math.radians((a[0] + a[2]) / 2))
    dy = max(0.0, b[0] - a[2], a[0] - b[2])
    dx = max(0.0, b[1] - a[3], a[1] - b[3]) * k
    return math.hypot(dx, dy)


def shifted(box, offset):
    return [box[0], box[1] + offset, box[2], box[3] + offset]


# Alaska is near enough to the contiguous states by the rule below, but would stretch the box over
# half the hemisphere.
MAIN_PART_ONLY = {"US"}


def country_box(geometry, main_part_only=False):
    if geometry["type"] == "Polygon":
        rings = [geometry["coordinates"][0]]
    else:
        rings = [polygon[0] for polygon in geometry["coordinates"]]
    parts = sorted(((ring_area(r), bbox(r)) for r in rings), key=lambda p: -p[0])
    main_area, main = parts[0]
    k = math.cos(math.radians((main[0] + main[2]) / 2))
    near = 0.25 * math.hypot(main[2] - main[0], (main[3] - main[1]) * k)
    result = list(main)
    for area, box in parts[1:]:
        if main_part_only or area < 0.01 * main_area:
            continue
        # the copy one world east or west may lie next to the main part (Chukotka, Fiji)
        candidate = min((shifted(box, o) for o in (0, 360, -360)), key=lambda b: gap(main, b))
        if gap(main, candidate) <= near:
            result = [min(result[0], candidate[0]), min(result[1], candidate[1]),
                      max(result[2], candidate[2]), max(result[3], candidate[3])]
    if result[1] < -180:
        result = shifted(result, 360)
    return result


# Territories with their own country code that Natural Earth draws as part of another country: the
# parent's islands within the given degrees (latitude, longitude) of the given point. (Natural Earth at 1:50m has none for
# Gibraltar, Bouvet Island and the US Minor Outlying Islands - the app falls back to the region.)
TERRITORIES = {
    "GF": ("FR", 4.0, -53.0, 3, 3), "GP": ("FR", 16.2, -61.5, 1, 1), "MQ": ("FR", 14.6, -61.0, 1, 1),
    "RE": ("FR", -21.1, 55.5, 2, 2), "YT": ("FR", -12.8, 45.15, 1, 1), "BQ": ("NL", 12.2, -68.3, 1, 1),
    "SJ": ("NO", 78.5, 22.0, 3, 12), "CC": ("AU", -12.1, 96.9, 1, 1), "CX": ("AU", -10.5, 105.6, 1, 1),
    "TK": ("NZ", -9.2, -171.8, 2, 2),
}


def territory_box(geometry, lat, lon, dlat, dlon):
    rings = [geometry["coordinates"][0]] if geometry["type"] == "Polygon" else [p[0] for p in geometry["coordinates"]]
    boxes = [b for b in map(bbox, rings) if abs((b[0] + b[2]) / 2 - lat) < dlat and abs((b[1] + b[3]) / 2 - lon) < dlon]
    if not boxes:
        return None
    return [min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes)]


def main():
    features = json.load(open(sys.argv[1], encoding="utf-8"))["features"]
    boxes = {}
    for feature in features:
        props = feature["properties"]
        code = props.get("ISO_A2_EH")
        if not code or code == "-99":
            code = props.get("ISO_A2")
        if not code or code == "-99" or feature["geometry"] is None:
            continue
        for territory, (parent, lat, lon, dlat, dlon) in TERRITORIES.items():
            if parent == code and territory not in boxes:
                box = territory_box(feature["geometry"], lat, lon, dlat, dlon)
                if box:
                    boxes[territory] = (0, box)
        box = country_box(feature["geometry"], code in MAIN_PART_ONLY)
        size = (box[2] - box[0]) * (box[3] - box[1])
        if code not in boxes or size > boxes[code][0]:
            boxes[code] = (size, box)
    print("# country,south,west,north,east - Natural Earth 1:50m Admin 0 (public domain), tools/country_bounds.py")
    for code in sorted(boxes):
        s, w, n, e = boxes[code][1]
        print(f"{code},{s:.2f},{w:.2f},{n:.2f},{e:.2f}")


if __name__ == "__main__":
    main()
