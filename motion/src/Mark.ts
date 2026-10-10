// Silverpoint's mark: silver-halide grains on a golden-angle spiral converging on one point of
// light. The same geometry is in scripts/make_logo.py (launcher icon) and ui/SilverpointMark.kt
// (the app): grain i (from 1) at radius 3*sqrt(i), angle i * 137.508 degrees, until the radius
// passes R_MAX, in "mark units" (the launcher icon's canvas is 108 units wide).

export const R_MAX = 38;
const GOLDEN = (137.508 * Math.PI) / 180;

export type Grain = { i: number; r: number; angle: number; size: number; alpha: number };

export const grains: Grain[] = (() => {
  const out: Grain[] = [];
  for (let i = 1; ; i++) {
    const r = 3 * Math.sqrt(i);
    if (r > R_MAX) return out;
    out.push({ i, r, angle: i * GOLDEN, size: 0.35 + (r / R_MAX) * 1.25, alpha: 0.35 + 0.65 * (1 - r / 40) });
  }
})();

/** Final glow: a radial gradient of this radius, and the bright point's radius. */
export const GLOW_R = 11;
export const POINT_R = 3.2;
export const GRAIN_COLOR = '#d9dde3';
