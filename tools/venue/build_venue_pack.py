#!/usr/bin/env python3
"""Build an offline MoshGrid venue map (GeoJSON) from OpenStreetMap via the Overpass API.

Usage:
  build_venue_pack.py query --lat LAT --lon LON [--radius-m 3000]      # prints the Overpass query
  curl -s --data-urlencode data@query.txt https://overpass-api.de/api/interpreter > osm.json
  build_venue_pack.py convert --osm osm.json --lat LAT --lon LON --out app/src/main/assets/venues/ID.geojson

Data (c) OpenStreetMap contributors, ODbL. The app shows the attribution on the map.
Only public map features are fetched: roads and paths, water, parks and green areas,
large buildings and named venue areas. No personal data.
"""
import argparse
import json
import math



def bbox(lat, lon, radius_m):
    dlat = radius_m / 111_320.0
    dlon = radius_m / (111_320.0 * math.cos(math.radians(lat)))
    return (lat - dlat, lon - dlon, lat + dlat, lon + dlon)  # south, west, north, east


def query(s, w, n, e):
    b = f"{s:.5f},{w:.5f},{n:.5f},{e:.5f}"
    return f"""[out:json][timeout:120];
(
  way["highway"~"^(motorway|trunk|primary|secondary|tertiary|residential|unclassified|service|pedestrian|footway|path|living_street)$"]({b});
  way["natural"="water"]({b}); way["waterway"]({b});
  way["leisure"~"^(park|garden|pitch|stadium|sports_centre)$"]({b});
  way["landuse"~"^(grass|recreation_ground|forest)$"]({b});
  way["building"]["building:levels"~"^([4-9]|[1-9][0-9])$"]({b});
  way["amenity"~"^(hospital|police|toilets)$"]({b});
);
out geom;"""


def classify(tags):
    if "highway" in tags:
        major = tags["highway"] in ("motorway", "trunk", "primary", "secondary", "tertiary")
        foot = tags["highway"] in ("footway", "path", "pedestrian")
        return "road_major" if major else ("path" if foot else "road_minor")
    if tags.get("natural") == "water" or "waterway" in tags:
        return "water"
    if "leisure" in tags or "landuse" in tags:
        return "green"
    if tags.get("amenity") in ("hospital", "police", "toilets"):
        return "amenity"
    if "building" in tags:
        return "building"
    return None


def to_feature(el):
    kind = classify(el.get("tags", {}))
    geom = el.get("geometry")
    if not kind or not geom:
        return None
    coords = [[round(p["lon"], 6), round(p["lat"], 6)] for p in geom]
    closed = len(coords) > 3 and coords[0] == coords[-1]
    area = kind in ("water", "green", "building", "amenity") and closed
    g = {"type": "Polygon", "coordinates": [coords]} if area else {"type": "LineString", "coordinates": coords}
    props = {"kind": kind}
    name = el.get("tags", {}).get("name")
    if name and kind in ("amenity", "green"):
        props["name"] = name[:60]
    return {"type": "Feature", "geometry": g, "properties": props}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["query", "convert"])
    ap.add_argument("--lat", type=float, required=True)
    ap.add_argument("--lon", type=float, required=True)
    ap.add_argument("--radius-m", type=float, default=3000)
    ap.add_argument("--osm")
    ap.add_argument("--out")
    a = ap.parse_args()
    s, w, n, e = bbox(a.lat, a.lon, a.radius_m)
    if a.mode == "query":
        print(query(s, w, n, e))
        return
    with open(a.osm) as f:
        osm = json.load(f)
    feats = [f for f in (to_feature(el) for el in osm.get("elements", [])) if f]
    out = {"type": "FeatureCollection", "bbox": [round(w, 5), round(s, 5), round(e, 5), round(n, 5)], "features": feats}
    with open(a.out, "w") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"{len(feats)} features, bbox w,s,e,n = {out['bbox']}")


if __name__ == "__main__":
    main()
