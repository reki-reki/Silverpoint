#!/usr/bin/env python3
"""Turn app/Lightroom photo pairs into fit samples for RecipeMatchFitTest.

Each pair is the same RAW shot developed twice with one recipe: PHONE_<stamp>_<Recipe_Name>.jpg from
Silverpoint (Ultra HDR off) and IMG_<stamp>_<Recipe_Name>.jpg exported from Lightroom with the matching
preset. Lightroom corrects lens distortion and crops, so each export is aligned onto its app photo
(SIFT homography, then smoothed dense optical flow). Both are then cut into 32-pixel blocks; flat,
unclipped blocks become samples: the app colour, the Lightroom colour and the distance from centre.

A black-and-white app photo has no colour left to turn back into scene colour, so its samples take
their source colour from a colour pair of the same scene (--colour-from), aligned onto it; the B&W
app colour is kept as a check column.

    python3 -I scripts/match_samples.py PHOTO_DIR OUT_DIR [--colour-from Reportage]
    SILVERPOINT_MATCH_SAMPLES=OUT_DIR ./gradlew :pipeline:testDebugUnitTest \\
        --tests '*RecipeMatchFitTest*' -Dsilverpoint.dev=true

Needs numpy and opencv-python-headless. Photos stay out of git.
"""
import argparse
import glob
import json
import os
import re

import cv2
import numpy as np

B = 32
RECIPES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'assets', 'recipes.json')


def file_key(name):
    return re.sub(r'[^A-Za-z0-9]+', '_', name).strip('_')


def gray(rgb):
    g = cv2.cvtColor(np.ascontiguousarray(rgb[:, :, ::-1]), cv2.COLOR_BGR2GRAY)
    return cv2.createCLAHE(3.0, (8, 8)).apply(g)  # equalised so features match across looks


def homography(src_gray, dst_gray):
    """Homography taking src pixel positions to dst positions."""
    s = 0.5
    sift = cv2.SIFT_create(8000)
    ks, ds = sift.detectAndCompute(cv2.resize(src_gray, None, fx=s, fy=s, interpolation=cv2.INTER_AREA), None)
    kd, dd = sift.detectAndCompute(cv2.resize(dst_gray, None, fx=s, fy=s, interpolation=cv2.INTER_AREA), None)
    good = [x for x, y in cv2.BFMatcher().knnMatch(ds, dd, k=2) if x.distance < 0.7 * y.distance]
    ps = np.float32([ks[g.queryIdx].pt for g in good]) / s
    pd = np.float32([kd[g.trainIdx].pt for g in good]) / s
    H, inliers = cv2.findHomography(ps, pd, cv2.RANSAC, 3.0)
    return H, len(good), int(inliers.sum())


def align(app, lr):
    """Lightroom export warped onto the app photo, and a mask of pixels it covers."""
    ga = gray(app)
    H, n, inl = homography(gray(lr), ga)
    size = (app.shape[1], app.shape[0])
    warped = cv2.warpPerspective(lr, H, size, flags=cv2.INTER_CUBIC)
    valid = cv2.warpPerspective(np.ones(lr.shape[:2], np.uint8), H, size, flags=cv2.INTER_NEAREST)
    # What a homography cannot model (distortion correction): dense flow at quarter size, smoothed.
    q = 0.25
    flow = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM).calc(
        cv2.resize(ga, None, fx=q, fy=q, interpolation=cv2.INTER_AREA),
        cv2.resize(gray(warped), None, fx=q, fy=q, interpolation=cv2.INTER_AREA), None)
    flow = cv2.resize(cv2.GaussianBlur(flow, (0, 0), 6), size, interpolation=cv2.INTER_LINEAR) / q
    gx, gy = np.meshgrid(np.arange(size[0], dtype=np.float32), np.arange(size[1], dtype=np.float32))
    mx, my = gx + flow[..., 0], gy + flow[..., 1]
    print(f'  aligned: {n} matches, {inl} inliers, flow up to {np.abs(flow).max():.1f} px')
    return cv2.remap(warped, mx, my, cv2.INTER_CUBIC), cv2.remap(valid, mx, my, cv2.INTER_NEAREST)


def blocks(x):
    h, w = x.shape[0] // B * B, x.shape[1] // B * B
    x = x if x.ndim == 3 else x[..., None]
    return x[:h, :w].reshape(h // B, B, w // B, B, -1).astype(np.float64).mean((1, 3))


def texture(x):
    g = cv2.GaussianBlur(cv2.cvtColor(x.astype(np.float32), cv2.COLOR_RGB2GRAY), (0, 0), 4)
    h, w = g.shape[0] // B * B, g.shape[1] // B * B
    return g[:h, :w].reshape(h // B, B, w // B, B).std((1, 3))


def radius(shape):
    hb, wb = shape
    yy, xx = np.mgrid[0:hb, 0:wb]
    cy, cx = (hb - 1) / 2, (wb - 1) / 2
    return np.sqrt(((xx - cx) / cx) ** 2 + ((yy - cy) / cy) ** 2) / np.sqrt(2)


def read(path):
    return cv2.imread(path, cv2.IMREAD_COLOR)[:, :, ::-1]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('photos')
    ap.add_argument('out')
    ap.add_argument('--colour-from', help='recipe file key of a colour pair of the same scene, for B&W pairs')
    args = ap.parse_args()
    with open(RECIPES, encoding='utf-8') as f:
        recipes = {file_key(k): k for k in json.load(f)}
    os.makedirs(args.out, exist_ok=True)

    pairs = {}
    for phone in sorted(glob.glob(os.path.join(args.photos, '**', 'PHONE_*.jpg'), recursive=True)):
        stamp_key = os.path.basename(phone)[len('PHONE_'):-4]
        key = max((k for k in recipes if stamp_key.endswith('_' + k)), key=len, default=None)
        lr = glob.glob(os.path.join(args.photos, '**', 'IMG_' + stamp_key + '.jpg'), recursive=True)
        if key is None or not lr:
            print('skipped', phone, '(no recipe name or no IMG_ export)')
            continue
        print(recipes[key])
        app = read(phone)
        lr_img, valid = align(app, read(lr[0]))
        pairs[key] = (app, lr_img, valid)

    for key, (app, lr, valid) in pairs.items():
        name = recipes[key]
        src_name, src, tgt, check = name, app, lr, app
        if np.abs(app.astype(int) - app.mean(-1, keepdims=True)).max() < 6:  # black and white
            if args.colour_from not in pairs:
                print(name, 'is black and white: pass --colour-from with a colour pair of the same scene')
                continue
            c_app, _, c_valid = pairs[args.colour_from]
            H, _, _ = homography(gray(app), gray(c_app))
            size = (c_app.shape[1], c_app.shape[0])
            tgt = cv2.warpPerspective(lr, H, size, flags=cv2.INTER_CUBIC)
            check = cv2.warpPerspective(app, H, size, flags=cv2.INTER_CUBIC)
            valid = c_valid & cv2.warpPerspective(valid, H, size, flags=cv2.INTER_NEAREST) \
                & cv2.warpPerspective(np.ones(valid.shape, np.uint8), H, size, flags=cv2.INTER_NEAREST)
            src_name, src = recipes[args.colour_from], c_app
        S, T, C = blocks(src), blocks(tgt), blocks(check)
        V = blocks(valid.astype(float))[..., 0]
        keep = (V > 0.999) & (texture(src) < 4) & (texture(tgt) < 4) & (texture(check) < 4) \
            & (S.max(-1) < 250) & (T.max(-1) < 250) & (S.min(-1) > 3)
        r = radius(S.shape[:2])
        with open(os.path.join(args.out, key + '.tsv'), 'w', encoding='utf-8') as f:
            for s, t, c, rr in zip(S[keep], T[keep], C[keep], r[keep]):
                f.write('\t'.join([src_name] + [f'{v / 255:.5f}' for v in (*s, *t)] + [f'{rr:.4f}'] + [f'{v / 255:.5f}' for v in c]) + '\n')
        print(f'{name}: {int(keep.sum())} samples')


if __name__ == '__main__':
    main()
