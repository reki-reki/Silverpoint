"""Silverpoint's look-preview sample (app/src/main/assets/look_sample.jpg): a procedural golden-hour
landscape, drawn here rather than photographed. Usage: python3 scripts/make_look_sample.py out.jpg"""
import sys, numpy as np, cv2
W, H = 864, 1152           # rendered at 3x, downscaled for smooth edges
rng = np.random.default_rng(7)
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
u, v = xx / W, yy / H

def fbm(scale, octaves=5, seed=0):
    r = np.random.default_rng(seed)
    out = np.zeros((H, W), np.float32); amp = 1.0; tot = 0
    for o in range(octaves):
        s = int(scale * 2 ** o)
        g = r.random((s + 2, int(s * W / H) + 2)).astype(np.float32)
        out += amp * cv2.resize(g, (W, H), interpolation=cv2.INTER_CUBIC); tot += amp; amp *= 0.5
    return out / tot

def mix(a, b, t): return a + (b - a) * t[..., None]
lin = lambda c: (np.array(c, np.float32) / 255.0) ** 2.2

# Sky: deep blue at the top to a warm hazy horizon, sun glow on the right.
horizon = 0.50
t = np.clip(v / horizon, 0, 1) ** 1.3
sky = mix(np.broadcast_to(lin((52, 96, 168)), (H, W, 3)), np.broadcast_to(lin((250, 196, 150)), (H, W, 3)), t)
sun = np.array([0.74, 0.43])
d = np.hypot((u - sun[0]) * W / H, v - sun[1])
sky += lin((255, 190, 120)) * (np.exp(-(d / 0.11) ** 2) * 1.6 + np.exp(-(d / 0.35) ** 2) * 0.35)[..., None]
sky += np.clip(1 - d / 0.035, 0, 1)[..., None] * 3.0
# Clouds: soft fbm bands, lit warm underneath.
c = fbm(3, 6, 1) * (1 - np.clip(v / 0.45, 0, 1)) * np.clip((v - 0.06) / 0.12, 0, 1)
c = np.clip((c - 0.42) * 3.2, 0, 1) * (0.6 + 0.4 * fbm(10, 3, 2))
cloud = mix(np.broadcast_to(lin((238, 232, 236)), (H, W, 3)), np.broadcast_to(lin((255, 170, 120)), (H, W, 3)), np.clip(v / 0.45, 0, 1))
img = mix(sky, cloud, c * 0.85)

def ridge(base, amp, scale, seed):
    n = fbm(scale, 5, seed)[H // 2]
    return base - amp * (n - 0.5) * 2

# Far mountains (hazy blue), mid hills (green), near meadow (gold-green).
far = ridge(0.47, 0.06, 2, 3); mid = ridge(0.56, 0.05, 3, 4); near = ridge(0.66, 0.03, 4, 5)
haze = lin((150, 160, 190))
farcol = mix(np.broadcast_to(lin((70, 88, 130)), (H, W, 3)), np.broadcast_to(haze, (H, W, 3)), np.clip((v - 0.38) / 0.12, 0, 1) * 0.5 + 0.3)
img = np.where((v > far[None, :])[..., None], farcol, img)
tex = fbm(24, 4, 6)
midcol = mix(np.broadcast_to(lin((38, 74, 40)), (H, W, 3)), np.broadcast_to(lin((96, 128, 58)), (H, W, 3)), np.clip(tex * 1.4 - 0.2, 0, 1))
midcol = mix(midcol, np.broadcast_to(haze, (H, W, 3)), np.full((H, W), 0.18, np.float32))
img = np.where((v > mid[None, :])[..., None], midcol, img)
# Lake between mid hills and meadow: sky reflection with ripples.
lake_top, lake_bot = 0.585, 0.70
ripple = 0.03 * np.sin(yy * 0.9 + fbm(30, 2, 8) * 20)
refl_v = np.clip(2 * lake_top - v + ripple * 0.02, 0, 1)
ry = (refl_v * (H - 1)).astype(int)
lake = sky[ry, xx.astype(int)] * 0.75 + lin((40, 70, 110)) * 0.25
in_lake = (v > np.maximum(mid[None, :], lake_top)) & (v < lake_bot) & (u > 0.18) & (u < 0.95)
img = np.where(in_lake[..., None], lake, img)
# Meadow: warm grass with strokes, darker towards the bottom.
g = fbm(60, 3, 9); s = fbm(8, 3, 10)
grass = mix(np.broadcast_to(lin((120, 128, 52)), (H, W, 3)), np.broadcast_to(lin((214, 168, 84)), (H, W, 3)), np.clip(s * 1.2 - 0.1 + (g - 0.5) * 0.5, 0, 1))
grass *= (1.0 - 0.45 * np.clip((v - 0.7) / 0.3, 0, 1))[..., None]
img = np.where((v > np.maximum(near[None, :], lake_bot - 0.004 * 0))[..., None] & (~in_lake[..., None]), grass, img)
# A small red-roofed cabin and its shadow on the near shore (reds and whites for the looks).
cx, cy = 0.30, 0.715
body = (np.abs(u - cx) < 0.075) & (v > cy) & (v < cy + 0.06)
roof = (v > cy - 0.05 + np.abs(u - cx) * 0.62) & (v <= cy) & (np.abs(u - cx) < 0.09)
img = np.where(body[..., None], lin((236, 228, 214)) * (0.75 + 0.25 * (u < cx)[..., None]), img)
img = np.where(roof[..., None], lin((168, 44, 34)) * (0.8 + 0.3 * (u > cx)[..., None]), img)
door = (np.abs(u - cx + 0.02) < 0.012) & (v > cy + 0.025) & (v < cy + 0.06)
img = np.where(door[..., None], lin((60, 40, 32)), img)
# Trees: dark green clumps on the left.
for i, (tx, ty, r) in enumerate([(0.06, 0.62, 0.09), (0.15, 0.66, 0.06), (0.92, 0.70, 0.07)]):
    blob = np.hypot((u - tx) * W / H, (v - ty) / 1.6) < r * (0.85 + 0.3 * fbm(14, 3, 20 + i))
    leaf = mix(np.broadcast_to(lin((22, 48, 26)), (H, W, 3)), np.broadcast_to(lin((70, 110, 44)), (H, W, 3)), np.clip(fbm(40, 3, 30 + i) * 1.6 - 0.4, 0, 1))
    img = np.where(blob[..., None], leaf, img)
# Golden light from the sun side, gentle vignette, fine grain-free noise.
img *= (1 + 0.18 * np.clip((u - 0.4), 0, 1))[..., None] * np.array([1.04, 1.0, 0.94], np.float32)
img *= (1 - 0.18 * (np.hypot(u - 0.5, v - 0.5) / 0.7) ** 2)[..., None]
img = np.clip(img, 0, 1) ** (1 / 2.2)
out = cv2.resize((img * 255).astype(np.uint8)[:, :, ::-1], (W // 3, H // 3), interpolation=cv2.INTER_AREA)
cv2.imwrite(sys.argv[1], out, [cv2.IMWRITE_JPEG_QUALITY, 92])
