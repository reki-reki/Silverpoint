#!/usr/bin/env python3
"""Check that recipes.json is well formed and matches the reference XMP presets.

Usage: python3 -I scripts/validate_recipes.py
Exit code 0 on success, 1 if any problem is found.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RECIPES = ROOT / "app/src/main/assets/recipes.json"
PRESETS = ROOT / "docs/reference/presets"
REQUIRED_META = {"file", "set", "group", "look", "best_for", "params"}
SETS = {"core", "popular"}
CORE_PARAMS = {"Contrast2012", "Highlights2012", "Shadows2012", "Whites2012", "Blacks2012",
               "Texture", "Clarity2012", "Vibrance", "Saturation",
               "GrainAmount", "GrainSize", "GrainFrequency", "curve"}


def xmp_value(text: str, key: str):
    m = re.search(rf'crs:{key}="([^"]*)"', text)
    return m.group(1) if m else None


def xmp_curve(text: str, tag: str):
    """Points of one curve element (ToneCurvePV2012, ToneCurvePV2012Red, ...), or None if absent."""
    m = re.search(rf"<crs:{tag}>\s*<rdf:Seq>(.*?)</rdf:Seq>", text, re.S)
    if not m:
        return None
    return [tuple(map(int, pt)) for pt in re.findall(r"<rdf:li>\s*(\d+),\s*(\d+)\s*</rdf:li>", m.group(1))]


def bad_curve(curve) -> bool:
    xs = [pt[0] for pt in curve]
    return len(curve) < 2 or any(b <= a for a, b in zip(xs, xs[1:])) or any(not (0 <= v <= 255) for pt in curve for v in pt)


def main() -> int:
    errors = []
    data = json.loads(RECIPES.read_text(encoding="utf-8"))
    files_seen = set()
    for name, r in data.items():
        missing = REQUIRED_META - r.keys()
        if missing:
            errors.append(f"{name}: missing fields {sorted(missing)}")
            continue
        if r["set"] not in SETS:
            errors.append(f"{name}: unknown set {r['set']!r}")
        p = r["params"]
        gaps = CORE_PARAMS - p.keys()
        if gaps:
            errors.append(f"{name}: missing params {sorted(gaps)}")
        curve = p.get("curve", [])
        for key in ("curve", "curve_red", "curve_green", "curve_blue"):
            if key in p and bad_curve(p[key]):
                errors.append(f"{name}: {key} must be strictly ascending points within 0..255")
        xmp = PRESETS / r["file"]
        files_seen.add(r["file"])
        if not xmp.exists():
            errors.append(f"{name}: preset file {r['file']} not found")
            continue
        text = xmp.read_text(encoding="utf-8")
        for key, val in p.items():
            if key.startswith("curve") or isinstance(val, (list, dict)):
                continue
            got = xmp_value(text, key)
            if got is None:
                errors.append(f"{name}: {key} absent from {r['file']}")
            elif isinstance(val, bool):
                if (got == "True") != val:
                    errors.append(f"{name}: {key} json={val} xmp={got}")
            elif abs(float(got) - float(val)) > 1e-6:
                errors.append(f"{name}: {key} json={val} xmp={got}")
        if xmp_curve(text, "ToneCurvePV2012") != [tuple(pt) for pt in curve]:
            errors.append(f"{name}: tone curve differs between json and xmp")
        identity = [(0, 0), (255, 255)]
        for ch in ("Red", "Green", "Blue"):
            want = xmp_curve(text, "ToneCurvePV2012" + ch) or identity
            got = [tuple(pt) for pt in p.get("curve_" + ch.lower(), identity)]
            if want != got:
                errors.append(f"{name}: {ch.lower()} channel curve differs between json and xmp")
    extra = {p.name for p in PRESETS.glob("*.xmp")} - files_seen
    if extra:
        errors.append(f"presets not referenced by recipes.json: {sorted(extra)}")
    if errors:
        print("\n".join(errors))
        print(f"\n{len(errors)} problem(s) found")
        return 1
    print(f"OK: {len(data)} recipes validated against {len(files_seen)} presets")
    return 0


if __name__ == "__main__":
    sys.exit(main())
