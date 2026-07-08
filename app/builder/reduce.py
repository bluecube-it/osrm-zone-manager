"""reduce.py — adapted from osm-map-manager.

FIX (condition #1 from LIGHT review): the original reduce.py produced a PBF
containing ONLY the synthetic linestring ways, discarding the osmium-extracted
region. Feeding that to osrm-extract gave a routing graph with custom ways but
no real road network.

New flow (called by builder):
  1. osmium extract --polygon → region.pbf           (caller does this)
  2. reduce.py writes custom-ways.pbf (this module, unchanged logic: tag-match
     from source PBF, 7-decimal snapping, synthetic node/way IDs from 1)
  3. osmium merge region.pbf custom-ways.pbf -o combined.pbf   (caller does this)
  4. osrm-extract -p car.lua combined.pbf             (caller)

This module only does step 2. Output = PBF with ONLY synthetic ways, intended
as merge input — NOT directly fed to osrm-extract.
"""

import json
import os
import sys

import osmium
import osmium.osm as osm
from shapely.geometry import shape


class OSMDataExtractor(osmium.SimpleHandler):
    """Extracts tags and coords from existing Ways in the source OSM file.

    Used to re-apply real-world road attributes (speed limits, surface) to
    our synthetic GeoJSON segments.
    """

    def __init__(self):
        super().__init__()
        self.nodes = {}   # node_id -> (lon, lat)
        self.ways = {}    # way_id -> {'tags': {...}, 'coords': [...]}

    def node(self, n):
        self.nodes[n.id] = (n.location.lon, n.location.lat)

    def way(self, w):
        if "highway" not in w.tags:
            return
        coords = []
        for node_ref in w.nodes:
            if node_ref.ref in self.nodes:
                coords.append(self.nodes[node_ref.ref])
        if len(coords) >= 2:
            self.ways[w.id] = {"tags": dict(w.tags), "coords": coords}


class OSMCreator:
    """Generates a PBF containing the GeoJSON linestrings as synthetic OSM ways.

    IDs start at 1 (will not collide with real OSM IDs on merge — those are
    much larger, but the builder should still verify uniqueness).
    """

    def __init__(self, output_file, osm_data):
        self.writer = osmium.SimpleWriter(output_file)
        self.osm_data = osm_data
        self.node_id = 1
        self.way_id = 1
        self.node_cache = {}   # (lon, lat) rounded -> node_id (snapping fuse)

    def find_matching_tags(self, linestring):
        """Closest original OSM way by coordinate-intersection score."""
        ls_coords = [(round(c[0], 6), round(c[1], 6)) for c in linestring.coords]
        best_match_tags = None
        max_score = 0.0

        for way_id, data in self.osm_data.items():
            way_coords = [(round(c[0], 6), round(c[1], 6)) for c in data["coords"]]
            score = len(set(ls_coords) & set(way_coords))
            ratio = score / len(ls_coords) if ls_coords else 0.0
            if ratio > max_score:
                max_score = ratio
                best_match_tags = data["tags"]
        # require >50% overlap to copy tags
        return best_match_tags if max_score > 0.5 else None

    def add_linestring(self, linestring):
        """Add a GeoJSON LineString as an OSM Way with node snapping."""
        tags = self.find_matching_tags(linestring)
        if not tags:
            tags = {
                "highway": "unclassified",
                "oneway": "no",
                "source": "custom_geojson",
            }

        way_node_refs = []
        for lon, lat in linestring.coords:
            coord_key = (round(lon, 7), round(lat, 7))  # ~1cm precision
            if coord_key not in self.node_cache:
                n = osm.mutable.Node(
                    id=self.node_id,
                    location=osm.Location(*coord_key),
                )
                n.version = 1
                n.visible = True
                self.writer.add_node(n)
                self.node_cache[coord_key] = self.node_id
                self.node_id += 1
            way_node_refs.append(self.node_cache[coord_key])

        w = osm.mutable.Way(id=self.way_id, nodes=way_node_refs)
        w.tags = tags
        w.version = 1
        w.visible = True
        self.writer.add_way(w)
        self.way_id += 1

    def close(self):
        self.writer.close()


def run_conversion(input_pbf, geojson_path, output_pbf):
    """Write synthetic-ways PBF from GeoJSON linestrings.

    Args:
        input_pbf:    full source PBF (used for tag lookup only — read-only)
        geojson_path: GeoJSON with LineString/MultiLineString features
        output_pbf:   output PBF with ONLY the synthetic ways (merge input)
    """
    if os.path.exists(output_pbf):
        print(f"reduce.py: overwriting existing {output_pbf}")
        os.remove(output_pbf)

    with open(geojson_path, "r") as f:
        gj_data = json.load(f)

    features = gj_data["features"] if gj_data["type"] == "FeatureCollection" else [gj_data]
    linestrings = []
    for f in features:
        geom = shape(f["geometry"])
        if geom.geom_type == "LineString":
            linestrings.append(geom)
        elif geom.geom_type == "MultiLineString":
            linestrings.extend(geom.geoms)

    print(f"reduce.py: reading tag source {input_pbf} (locations=False)")
    extractor = OSMDataExtractor()
    extractor.apply_file(input_pbf, locations=False)

    print(f"reduce.py: writing {len(linestrings)} synthetic ways to {output_pbf}")
    creator = OSMCreator(output_pbf, extractor.ways)
    for ls in linestrings:
        creator.add_linestring(ls)
    creator.close()
    print("reduce.py: done")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("Usage: reduce.py <input_source.osm.pbf> <linestrings.geojson> <output_custom_ways.osm.pbf>")
        sys.exit(2)
    run_conversion(sys.argv[1], sys.argv[2], sys.argv[3])
