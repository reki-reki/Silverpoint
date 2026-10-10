#!/usr/bin/env python3
"""Run the developer's compute shaders on a desktop OpenGL and compare with the CPU reference.

The phone's Diagnostics self-test does this on the device. This script does the same on a
computer, so a shader change can be checked before it reaches a phone: Mesa's software renderer
(llvmpipe, OpenGL 4.5 with ES 3.1 compatibility) runs the shaders unchanged.

    SILVERPOINT_GPU_PARITY=/tmp/parity ./gradlew :pipeline:testDebugUnitTest --tests '*GpuParityDumpTest*'
    python3 -I scripts/gpu_parity.py /tmp/parity

It mirrors GpuDeveloper.run: prepare.comp, lowres.comp, blur.comp (when Clarity or Texture is on)
and develop.comp, with the same textures and uniforms. Passes when every channel of every pixel is
within GpuSelfTest.TOLERANCE (2 levels) and the gain-map luminance within 0.2 %.
Needs numpy and moderngl (pip install moderngl) and an EGL driver (e.g. libegl-mesa0).
"""
import json
import re
import sys
from pathlib import Path

import moderngl
import numpy as np

SHADERS = Path(__file__).resolve().parent.parent / 'pipeline' / 'src' / 'main' / 'assets' / 'shaders'
TOLERANCE = 2
LOW_RES_TOLERANCE = 0.002


def source(name):
    """ShaderSource.expand: inline #include lines, drop the include extension."""
    out = []
    for line in (SHADERS / name).read_text().splitlines():
        if re.match(r'^\s*#extension\s+GL_GOOGLE_include_directive\b', line):
            out.append('')
            continue
        m = re.match(r'^\s*#include\s+"([^"]+)"\s*$', line)
        out.append(source(m.group(1)) if m else line)
    # moderngl expands `#include` anywhere, even in comments; real ones are already inlined.
    return '\n'.join(out).replace('#include', 'include')


def set_uniforms(prog, values):
    for k, v in values.items():
        if k in prog:  # unused uniforms are compiled away
            prog[k] = v


def groups(n):
    return (n + 15) // 16


def run_case(ctx, programs, d):
    p = json.loads((d / 'params.json').read_text())
    w, h = p['width'], p['height']
    lw, lh = p['lowSize']
    ow, oh = p['outSize']
    nearest = (moderngl.NEAREST, moderngl.NEAREST)

    def tex(size, comps, dtype, data=None):
        t = ctx.texture(size, comps, data, dtype=dtype)
        t.filter = nearest
        return t

    raw = tex((w, h), 1, 'u2', (d / 'raw.u16').read_bytes())
    sw, sh = p['shadingSize']
    shading = tex((sw, sh), 4, 'f4', (d / 'shading.f32').read_bytes())
    n = p['lookSize']
    look3 = np.frombuffer((d / 'look.f32').read_bytes(), '<f4').reshape(-1, 3)
    look4 = np.concatenate([look3, np.ones((len(look3), 1), np.float32)], 1).astype('<f4')
    look = ctx.texture3d((n, n, n), 4, look4.tobytes(), dtype='f4')
    look.filter = nearest
    cfa = tex((w, h), 1, 'f4')
    low = tex((lw, lh), 4, 'f4')
    tmp = tex((lw, lh), 1, 'f4')
    blur_s = tex((lw, lh), 1, 'f4')
    blur_l = tex((lw, lh), 1, 'f4')
    out = tex((ow, oh), 4, 'f1')
    ws = np.array(p['weightsS'], '<f4')
    wl = np.array(p['weightsL'], '<f4')
    weights_s = tex((len(ws), 1), 1, 'f4', ws.tobytes())
    weights_l = tex((len(wl), 1), 1, 'f4', wl.tobytes())
    m = p['matrix']
    colour = {
        'uRow0': tuple(m[0:3]), 'uRow1': tuple(m[3:6]), 'uRow2': tuple(m[6:9]),
        'uExposure': p['exposure'], 'uSaturation': p['saturation'], 'uVibrance': p['vibrance'], 'uKnee': p['knee'],
    }
    pattern = tuple(p['pattern'])

    prog = programs['prepare.comp']
    raw.use(0)
    shading.use(1)
    cfa.bind_to_image(0, read=False, write=True)
    set_uniforms(prog, {'uSize': (w, h), 'uBlack': tuple(p['black']), 'uWhite': p['white'], 'uWb': tuple(p['wb']),
                        'uPattern': pattern, 'uHasShading': p['hasShading'], 'uShadingSize': (sw, sh)})
    prog.run(groups(w), groups(h), 1)
    ctx.memory_barrier()

    prog = programs['lowres.comp']
    cfa.bind_to_image(0, read=True, write=False)
    low.bind_to_image(1, read=False, write=True)
    set_uniforms(prog, {'uSize': (w, h), 'uLowSize': (lw, lh), 'uPattern': pattern, **colour})
    prog.run(groups(lw), groups(lh), 1)
    ctx.memory_barrier()

    if p['clarity'] != 0 or p['texture'] != 0:
        prog = programs['blur.comp']
        for src, dst, wt, radius, horizontal in [(low, tmp, weights_s, len(ws) - 1, 1), (tmp, blur_s, weights_s, len(ws) - 1, 0),
                                                  (low, tmp, weights_l, len(wl) - 1, 1), (tmp, blur_l, weights_l, len(wl) - 1, 0)]:
            src.use(0)
            wt.use(1)
            dst.bind_to_image(0, read=False, write=True)
            set_uniforms(prog, {'uSize': (lw, lh), 'uRadius': radius, 'uHorizontal': horizontal})
            prog.run(groups(lw), groups(lh), 1)
            ctx.memory_barrier()

    prog = programs['develop.comp']
    cfa.bind_to_image(0, read=True, write=False)
    out.bind_to_image(1, read=False, write=True)
    look.use(2)
    blur_s.use(3)
    blur_l.use(4)
    set_uniforms(prog, {
        'uSize': (w, h), 'uPattern': pattern, **colour, 'uLookSize': n, 'uRotation': p['rotation'], 'uLowSize': (lw, lh),
        'uClarity': p['clarity'], 'uTexture': p['texture'], 'uVignette': p['vignette'],
        'uGrainAmount': p['grainAmount'], 'uGrainCell': p['grainCell'], 'uGrainRoughness': p['grainRoughness'],
        'uGrainSeed': p['grainSeed'], 'uSharpen': p.get('sharpen', 0.0), 'uChromaSmoothing': p.get('chromaSmoothing', 0.0),
        'uSharpenMask': p.get('sharpenMask', 0.0), 'uVignetteStart': p.get('vignetteStart', 0.3), 'uVignetteEnd': p.get('vignetteEnd', 1.0),
    })
    prog.run(groups(w), groups(h), 1)
    ctx.memory_barrier()
    ctx.finish()

    gpu = np.frombuffer(out.read(), np.uint8).reshape(oh, ow, 4)[..., :3].astype(int)
    argb = np.frombuffer((d / 'cpu.argb').read_bytes(), '<u4').reshape(oh, ow)
    cpu = np.stack([(argb >> 16) & 255, (argb >> 8) & 255, argb & 255], -1).astype(int)
    diff = np.abs(gpu - cpu)
    low_diff = None
    if (d / 'cpu_low.f32').exists():
        gl = np.frombuffer(low.read(), '<f4').reshape(lh, lw, 4)[..., 1].ravel()
        cl = np.frombuffer((d / 'cpu_low.f32').read_bytes(), '<f4')
        low_diff = float((np.abs(gl - cl) / np.maximum(cl, 1e-3)).max())
    return int(diff.max()), float(diff.mean()), low_diff, gpu, cpu


def main():
    root = Path(sys.argv[1])
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
    print(ctx.info['GL_RENDERER'], '/', ctx.info['GL_VERSION'])
    programs = {name: ctx.compute_shader(source(name)) for name in ['prepare.comp', 'lowres.comp', 'blur.comp', 'develop.comp']}
    ok = True
    for d in sorted(x for x in root.iterdir() if (x / 'params.json').exists()):
        mx, mean, low_diff, gpu, cpu = run_case(ctx, programs, d)
        passed = mx <= TOLERANCE and (low_diff is None or low_diff <= LOW_RES_TOLERANCE)
        ok &= passed
        low_text = 'n/a' if low_diff is None else f'{low_diff:.5f}'
        print(f'{"PASS" if passed else "FAIL"} {d.name}: max diff {mx}, mean {mean:.3f}, gain-map luminance {low_text}')
        if not passed:
            y, x = np.unravel_index(np.abs(gpu - cpu).max(-1).argmax(), gpu.shape[:2])
            print(f'     worst at ({x}, {y}): gpu {gpu[y, x].tolist()} cpu {cpu[y, x].tolist()}')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
