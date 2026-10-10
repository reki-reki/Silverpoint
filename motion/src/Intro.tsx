import React from 'react';
import { AbsoluteFill, Easing, interpolate, random, useCurrentFrame, useVideoConfig } from 'remotion';
import { GLOW_R, GRAIN_COLOR, POINT_R, R_MAX, grains } from './Mark';

/**
 * The start-up animation. On black (the app's background, so the video's square edges never show):
 *
 *  1. Silver grains drift in from beyond the frame and swirl inward along the spiral, outer ones
 *     first, with short motion trails, while the whole spiral slowly unwinds into place.
 *  2. The centre ignites: a bright point springs up, a flash blooms and fades, a thin anamorphic
 *     streak crosses it, and a shockwave ring runs outward, brightening each grain as it passes:
 *     an exposure.
 *  3. Everything settles on the exact launcher mark, held still for the last frames so the app can
 *     take over with the same mark drawn natively (ui/SilverpointMark.kt), at MARK_FRACTION of the
 *     video's width.
 */

/** The settled mark's radius (R_MAX) as a fraction of the video's width. Must match the app. */
export const MARK_FRACTION = 0.2;

const IGNITE = 112; // frame the point lights up
const SETTLED = 160; // from here on, every frame is the final mark

const clamp = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' } as const;

export const Intro: React.FC = () => {
  const frame = useCurrentFrame();
  const { width } = useVideoConfig();
  const unit = (width * MARK_FRACTION) / R_MAX; // pixels per mark unit
  const c = width / 2;
  const f = Math.min(frame, SETTLED);

  // The whole spiral unwinds by this much as it forms.
  const unwind = interpolate(f, [0, 150], [0.9, 0], { ...clamp, easing: Easing.out(Easing.cubic) });

  // Shockwave ring after ignition, in mark units.
  const ringT = interpolate(f, [IGNITE, IGNITE + 34], [0, 1], clamp);
  const ringR = interpolate(ringT, [0, 1], [3, 62], { easing: Easing.out(Easing.quad) });
  const ringOpacity = f < IGNITE ? 0 : interpolate(ringT, [0, 0.15, 1], [0, 0.55, 0]);

  // Where grain g is at frame t, and how bright.
  const at = (g: (typeof grains)[number], t: number) => {
    const delay = 4 + (1 - g.r / R_MAX) * 46 + random(`d${g.i}`) * 14;
    const p = interpolate(t, [delay, delay + 76], [0, 1], { ...clamp, easing: Easing.out(Easing.cubic) });
    const startR = 120 + random(`r${g.i}`) * 80;
    const r = interpolate(p, [0, 1], [startR, g.r]);
    const angle = g.angle + unwind - (1 - p) * (2.2 + random(`a${g.i}`) * 0.8);
    // Each grain fades in over its first 22 frames of travel, so the frame never fills with streaks.
    const fade = interpolate(t, [delay, delay + 22], [0, 1], clamp);
    return { x: c + r * unit * Math.cos(angle), y: c + r * unit * Math.sin(angle), p, fade };
  };

  const dots = grains.map((g) => {
    const now = at(g, f);
    const before = at(g, f - 1.2);
    const fadeIn = now.fade;
    // The exposure ripple: brighter while the shockwave passes this grain.
    const ripple = f < IGNITE ? 0 : 0.7 * Math.exp(-(((g.r - ringR) / 4.5) ** 2)) * (1 - ringT);
    const alpha = Math.min(1, g.alpha * fadeIn + ripple);
    const size = g.size * unit * (1 + ripple * 0.6);
    // A short motion trail, capped so fast grains read as streaks of light rather than rain.
    const dx = now.x - before.x, dy = now.y - before.y, len = Math.hypot(dx, dy);
    const cap = Math.min(1, (9 * unit) / Math.max(len, 1e-3));
    const moving = len > 0.6;
    return (
      <g key={g.i}>
        {moving && (
          <line
            x1={now.x - dx * cap} y1={now.y - dy * cap} x2={now.x} y2={now.y}
            stroke={GRAIN_COLOR} strokeOpacity={alpha * 0.45} strokeWidth={size * 1.6} strokeLinecap="round"
          />
        )}
        <circle cx={now.x} cy={now.y} r={size} fill={ripple > 0.05 ? '#ffffff' : GRAIN_COLOR} fillOpacity={alpha} />
      </g>
    );
  });

  // Ignition: the point springs up with a little overshoot; a flash blooms then settles to the glow.
  const pointScale = f < IGNITE ? 0 : interpolate(f, [IGNITE, IGNITE + 9, IGNITE + 20], [0, 1.6, 1], { ...clamp, easing: Easing.inOut(Easing.quad) });
  const glowR = f < IGNITE ? 0 : interpolate(f, [IGNITE, IGNITE + 7, IGNITE + 40], [0, 34, GLOW_R], { ...clamp, easing: Easing.out(Easing.cubic) });
  const flash = f < IGNITE ? 0 : interpolate(f, [IGNITE, IGNITE + 4, IGNITE + 26], [0, 0.42, 0], clamp);
  const streak = f < IGNITE ? 0 : interpolate(f, [IGNITE, IGNITE + 6, IGNITE + 36], [0, 1, 0], clamp);
  // A faint pre-glow gathers at the centre just before ignition.
  const gather = interpolate(frame, [IGNITE - 30, IGNITE], [0, 0.5], clamp) * (f < IGNITE ? 1 : 0);

  return (
    <AbsoluteFill style={{ backgroundColor: '#000' }}>
      <svg width={width} height={width} viewBox={`0 0 ${width} ${width}`}>
        <defs>
          <radialGradient id="glow">
            <stop offset="0" stopColor="#ffffff" />
            <stop offset="0.25" stopColor="#e8eef6" stopOpacity="0.85" />
            <stop offset="1" stopColor="#9fb3c8" stopOpacity="0" />
          </radialGradient>
          <radialGradient id="flash">
            <stop offset="0" stopColor="#ffffff" stopOpacity="1" />
            <stop offset="0.35" stopColor="#dfe7f2" stopOpacity="0.35" />
            <stop offset="1" stopColor="#9fb3c8" stopOpacity="0" />
          </radialGradient>
          <linearGradient id="streak" x1="0" x2="1">
            <stop offset="0" stopColor="#bcd0ea" stopOpacity="0" />
            <stop offset="0.5" stopColor="#ffffff" stopOpacity="1" />
            <stop offset="1" stopColor="#bcd0ea" stopOpacity="0" />
          </linearGradient>
        </defs>
        {flash > 0 && <circle cx={c} cy={c} r={c * 1.1} fill="url(#flash)" opacity={flash} />}
        {dots}
        {ringOpacity > 0 && (
          <circle cx={c} cy={c} r={ringR * unit} fill="none" stroke="#e8eef6" strokeOpacity={ringOpacity} strokeWidth={interpolate(ringT, [0, 1], [3, 0.6]) * unit * 0.4} />
        )}
        {gather > 0 && <circle cx={c} cy={c} r={6 * unit} fill="url(#glow)" opacity={gather} />}
        {glowR > 0 && <circle cx={c} cy={c} r={glowR * unit} fill="url(#glow)" />}
        {streak > 0 && (
          <rect x={c - 70 * unit * streak} y={c - 0.5 * unit} width={140 * unit * streak} height={unit} rx={0.5 * unit} fill="url(#streak)" opacity={streak * 0.8} />
        )}
        {pointScale > 0 && <circle cx={c} cy={c} r={POINT_R * unit * pointScale} fill="#ffffff" />}
      </svg>
    </AbsoluteFill>
  );
};
