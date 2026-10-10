#!/usr/bin/env python3
"""Make small test fixtures from a DNG written by Android's DngCreator and a Lightroom export of it.

Development tool only (not shipped). Needs numpy and Pillow.

    python3 -I scripts/make_fixture.py FULL.dng LIGHTROOM.jpg OUT_DIR [--full]
    python3 -I scripts/make_fixture.py FULL.dng - OUT_DIR [--full]      (no Lightroom export: RAW crops only)

With --full it also writes full.raw.gz / full.json (the whole frame) for the dev-only fit test;
never commit those.

Writes to OUT_DIR:
  alignment.json         how the Lightroom export maps onto the DNG's upright image (scale, offset)
  <name>.raw.gz          CFA-aligned crop of the RAW samples, uint16 little-endian, row-major
  <name>.json            metadata for the crop (CFA, black/white level, neutral, matrices, lens shading)
  <name>.lightroom.png   the same region from the Lightroom export, resampled to half the crop size

The full DNG and JPEG are not written anywhere; only the small crops.
"""
import gzip
import json
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image

# Crops in the upright (displayed) image: name -> (x0, y0), each 384 wide x 512 tall. Chosen for the
# committed scene (a sofa by a window, IMG_20261010_085929): fine fabric pattern and a cushion edge
# against plain upholstery; green leaves against a bright, window-lit wall.
CROPS = {
    "cushion": (2220, 2300),
    "window_wall": (1160, 1000),
}
CROP_W, CROP_H = 384, 512  # upright size; the sensor crop is 512 x 384

TYPES = {1: ("B", 1), 2: ("s", 1), 3: ("H", 2), 4: ("I", 4), 5: ("II", 8), 7: ("B", 1), 9: ("i", 4),
         10: ("ii", 8), 11: ("f", 4), 12: ("d", 8)}


def read_ifd(data, bo, off):
    n = struct.unpack(bo + "H", data[off:off + 2])[0]
    out = {}
    for i in range(n):
        e = data[off + 2 + 12 * i: off + 14 + 12 * i]
        tag, typ, cnt = struct.unpack(bo + "HHI", e[:8])
        fmt, sz = TYPES.get(typ, ("B", 1))
        total = sz * cnt
        raw = (e[8:12] if total <= 4 else data[struct.unpack(bo + "I", e[8:12])[0]:])[:total]
        if typ in (5, 10):
            v = struct.unpack(bo + ("I" if typ == 5 else "i") * (2 * cnt), raw)
            vals = [v[2 * k] / v[2 * k + 1] if v[2 * k + 1] else 0.0 for k in range(cnt)]
        elif typ == 2:
            vals = raw.rstrip(b"\0").decode(errors="replace")
        elif typ in (1, 7):
            vals = raw
        else:
            vals = list(struct.unpack(bo + fmt * cnt, raw))
        out[tag] = vals
    return out


def gain_maps(opcodes):
    """Parse GainMap opcodes (DNG opcode 9) into a list of dicts."""
    n = struct.unpack(">I", opcodes[:4])[0]
    p = 4
    maps = []
    for _ in range(n):
        oid, _ver, _flags, size = struct.unpack(">IIII", opcodes[p:p + 16])
        body = opcodes[p + 16:p + 16 + size]
        p += 16 + size
        if oid != 9:
            continue
        top, left, bottom, right, plane, planes, row_pitch, col_pitch, pv, ph = struct.unpack(">10I", body[:40])
        sv, sh, ov, oh = struct.unpack(">4d", body[40:72])
        map_planes = struct.unpack(">I", body[72:76])[0]
        gains = np.frombuffer(body[76:76 + 4 * pv * ph * map_planes], dtype=">f4").astype(np.float64)
        maps.append(dict(top=top, left=left, row_pitch=row_pitch, col_pitch=col_pitch, rows=pv, cols=ph,
                         spacing=(sv, sh), origin=(ov, oh), gains=gains.reshape(pv, ph)))
    return maps


def read_dng(path):
    data = Path(path).read_bytes()
    bo = "<" if data[:2] == b"II" else ">"
    t = read_ifd(data, bo, struct.unpack(bo + "I", data[4:8])[0])
    w, h = t[256][0], t[257][0]
    assert t[259][0] == 1 and t[258][0] == 16, "expects uncompressed 16-bit"
    raw = np.zeros((h, w), dtype=np.uint16)
    offs, counts, rps = t[273], t[279], t[278][0]
    row = 0
    for o, c in zip(offs, counts):
        strip = np.frombuffer(data[o:o + c], dtype=bo + "u2").reshape(-1, w)
        raw[row:row + strip.shape[0]] = strip
        row += strip.shape[0]
    assert row == h
    cfa_codes = list(t[33422])
    names = {0: "R", 1: "G", 2: "B"}
    cfa = "".join(names[c] for c in cfa_codes)
    meta = dict(
        width=w, height=h, cfa=cfa,
        blackLevel=[float(x) for x in t[50714]], whiteLevel=float(t[50717][0]),
        asShotNeutral=[float(x) for x in t[50728]],
        illuminant1=int(t[50778][0]), illuminant2=int(t[50779][0]) if 50779 in t else None,
        colorMatrix1=t[50721], colorMatrix2=t.get(50722), forwardMatrix1=t.get(50964), forwardMatrix2=t.get(50965),
        calibration1=t.get(50723), calibration2=t.get(50724), orientation=int(t[274][0]),
    )
    maps = gain_maps(t[51009]) if 51009 in t else []
    return raw, meta, maps


def lens_shading_full(maps, cfa):
    """Gain grids in Silverpoint's channel order R, G-even (green on even rows), G-odd, B."""
    order = {}
    for m in maps:
        assert m["row_pitch"] == 2 and m["col_pitch"] == 2
        assert abs(m["origin"][0]) < 1e-9 and abs(m["origin"][1]) < 1e-9, "grid must start at the image corner"
        assert abs(m["spacing"][0] * (m["rows"] - 1) - 1) < 1e-6 and abs(m["spacing"][1] * (m["cols"] - 1) - 1) < 1e-6
        colour = cfa[(m["left"] & 1) + 2 * (m["top"] & 1)]
        ch = {"R": 0, "B": 3}.get(colour, 1 if m["top"] % 2 == 0 else 2)
        order[ch] = m["gains"]
    assert sorted(order) == [0, 1, 2, 3]
    return np.stack([order[c] for c in range(4)], axis=-1)  # rows x cols x 4


def sample_grid(grid, w, h, x0, y0, cw, ch, cols, rows):
    """Bilinear-sample the full-image gain grid at a cols x rows grid spanning the crop."""
    gr, gc = grid.shape[:2]
    out = np.zeros((rows, cols, 4))
    for r in range(rows):
        for c in range(cols):
            x = x0 + c * (cw - 1) / (cols - 1)
            y = y0 + r * (ch - 1) / (rows - 1)
            gx = x * (gc - 1) / (w - 1)
            gy = y * (gr - 1) / (h - 1)
            ix, iy = min(int(gx), gc - 2), min(int(gy), gr - 2)
            fx, fy = gx - ix, gy - iy
            top = grid[iy, ix] + (grid[iy, ix + 1] - grid[iy, ix]) * fx
            bottom = grid[iy + 1, ix] + (grid[iy + 1, ix + 1] - grid[iy + 1, ix]) * fx
            out[r, c] = top + (bottom - top) * fy
    return out


def upright_proxy(raw, meta):
    """Quick luminance-ish preview: 2x2 binned, black-subtracted, rotated upright, gamma 1/2.2."""
    b = np.mean(meta["blackLevel"])
    x = (raw.astype(np.float64) - b) / (meta["whiteLevel"] - b)
    h, w = x.shape
    binned = x[: h // 2 * 2, : w // 2 * 2].reshape(h // 2, 2, w // 2, 2).mean(axis=(1, 3))
    assert meta["orientation"] == 6, "only orientation 6 (rotate 90° clockwise) is handled"
    up = np.rot90(binned, k=-1)  # clockwise
    return np.clip(up, 0, 1) ** (1 / 2.2)


def align(proxy_half, lightroom):
    """Find scale and offset so that lightroom[y, x] ≈ upright_full[(y - dy) / s, (x - dx) / s].

    The search assumes an export about half the DNG's size. A larger export (Lightroom for Android
    exports full size) is searched at a 1/k copy, then the result is scaled up and refined at full size.
    """
    full_w = proxy_half.shape[1] * 2
    k = max(1, round(lightroom.width / full_w / 0.5))
    if k == 1:
        return align_half(proxy_half, lightroom)
    small = lightroom.resize((round(lightroom.width / k), round(lightroom.height / k)), Image.BOX)
    al = align_half(proxy_half, small)
    s, dx, dy = al["scale"] * k, al["dx"] * k, al["dy"] * k
    return refine(proxy_half, lightroom, s, dx, dy, -1.0, radius=k + 1)


def gradient_score(a, b):
    ga, gb = np.diff(a, axis=1), np.diff(b, axis=1)
    ga, gb = ga - ga.mean(), gb - gb.mean()
    return (ga * gb).sum() / np.sqrt((ga * ga).sum() * (gb * gb).sum())


def refine(proxy_half, lightroom, s, dx, dy, score, radius=2):
    """Best whole-pixel offset within ±radius of (dx, dy) at scale s."""
    lr = np.asarray(lightroom.convert("L"), dtype=np.float64) / 255.0
    lh, lw = lr.shape
    full_h, full_w = proxy_half.shape[0] * 2, proxy_half.shape[1] * 2
    pw, ph = int(round(full_w * s)), int(round(full_h * s))
    prox = np.asarray(Image.fromarray(proxy_half.astype(np.float32), mode="F").resize((pw, ph), Image.BILINEAR))
    y0, y1, x0, x1 = int(lh * 0.15), int(lh * 0.85), int(lw * 0.15), int(lw * 0.85)
    a = lr[y0:y1, x0:x1]
    best = (dx, dy)
    for ddy in range(dy - radius, dy + radius + 1):
        for ddx in range(dx - radius, dx + radius + 1):
            b = prox[y0 - ddy:y0 - ddy + a.shape[0], x0 - ddx:x0 - ddx + a.shape[1]]
            if b.shape != a.shape:
                continue
            sc = gradient_score(a, b)
            if sc > score:
                score, best = sc, (ddx, ddy)
    dx, dy = best
    return dict(scale=float(s), dx=int(dx), dy=int(dy), score=float(score))


def align_half(proxy_half, lightroom):
    lr = np.asarray(lightroom.convert("L"), dtype=np.float64) / 255.0
    lh, lw = lr.shape
    best = None
    full_h, full_w = proxy_half.shape[0] * 2, proxy_half.shape[1] * 2
    for s in np.arange(0.480, 0.5201, 0.002):
        pw, ph = int(round(full_w * s)), int(round(full_h * s))
        prox = np.asarray(Image.fromarray(proxy_half.astype(np.float32), mode="F").resize((pw, ph), Image.BILINEAR))
        for dy in range(-16, 17, 2):
            for dx in range(-16, 17, 2):
                # Compare the central 70% to stay clear of distortion-corrected borders.
                y0, y1, x0, x1 = int(lh * 0.15), int(lh * 0.85), int(lw * 0.15), int(lw * 0.85)
                a = lr[y0:y1, x0:x1]
                py0, px0 = y0 - dy, x0 - dx
                if py0 < 0 or px0 < 0 or py0 + a.shape[0] > ph or px0 + a.shape[1] > pw:
                    continue
                b = prox[py0:py0 + a.shape[0], px0:px0 + a.shape[1]]
                score = gradient_score(a, b)
                if best is None or score > best[0]:
                    best = (score, s, dx, dy)
    score, s, dx, dy = best
    return refine(proxy_half, lightroom, s, dx, dy, score)


def main():
    dng, jpeg, out = sys.argv[1], sys.argv[2], Path(sys.argv[3])
    out.mkdir(parents=True, exist_ok=True)
    raw, meta, maps = read_dng(dng)
    shading = lens_shading_full(maps, meta["cfa"]) if maps else None
    lightroom = None if jpeg == "-" else Image.open(jpeg).convert("RGB")
    if lightroom is not None:
        al = align(upright_proxy(raw, meta), lightroom)
        print("alignment", al)
        (out / "alignment.json").write_text(json.dumps(al, indent=2) + "\n")

    if "--full" in sys.argv[4:]:
        with gzip.open(out / "full.raw.gz", "wb", compresslevel=1) as f:
            f.write(raw.astype("<u2").tobytes())
        fm = dict(meta)
        if shading is not None:
            fm.update(shadingColumns=shading.shape[1], shadingRows=shading.shape[0],
                      shadingGains=[round(float(v), 6) for v in shading.reshape(-1)])
        (out / "full.json").write_text(json.dumps(fm, indent=1) + "\n")

    h = meta["height"]
    for name, (ux, uy) in CROPS.items():
        # Upright (xd, yd) comes from sensor (xs, ys) = (yd, H - 1 - xd) for a 90° clockwise rotation.
        xs0, ys0 = uy, h - (ux + CROP_W)
        xs0 -= xs0 % 2
        ys0 -= ys0 % 2
        cw, ch = CROP_H, CROP_W  # sensor crop is 512 wide, 384 tall
        crop = raw[ys0:ys0 + ch, xs0:xs0 + cw]
        with gzip.open(out / f"{name}.raw.gz", "wb", compresslevel=9) as f:
            f.write(crop.astype("<u2").tobytes())
        cm = dict(meta)
        cm.update(width=cw, height=ch, cropX=xs0, cropY=ys0, fullWidth=meta["width"], fullHeight=meta["height"])
        if shading is not None:
            g = sample_grid(shading, meta["width"], meta["height"], xs0, ys0, cw, ch, shading.shape[1], shading.shape[0])
            cm.update(shadingColumns=g.shape[1], shadingRows=g.shape[0], shadingGains=[round(float(v), 6) for v in g.reshape(-1)])
        (out / f"{name}.json").write_text(json.dumps(cm, indent=1) + "\n")
        if lightroom is None:
            print(name, "sensor crop", (xs0, ys0, cw, ch))
            continue
        # Matching Lightroom region: upright crop origin after the exact (even-aligned) sensor crop.
        uxa, uya = h - (ys0 + ch), xs0
        s, dx, dy = al["scale"], al["dx"], al["dy"]
        box = (uxa * s + dx, uya * s + dy, (uxa + CROP_W) * s + dx, (uya + CROP_H) * s + dy)
        ref = lightroom.transform((CROP_W // 2, CROP_H // 2), Image.EXTENT, box, Image.BICUBIC)
        ref.save(out / f"{name}.lightroom.png", optimize=True)
        print(name, "sensor crop", (xs0, ys0, cw, ch), "lightroom box", tuple(round(v, 1) for v in box))


if __name__ == "__main__":
    main()
