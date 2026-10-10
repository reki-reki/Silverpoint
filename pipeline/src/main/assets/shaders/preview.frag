#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
// Live preview: the camera's processed frame through the recipe's preview look table, vignette and
// grain (the same hash as develop.comp), plus optional zebras and focus peaking. An approximation
// of the saved photo, which is developed from RAW.
precision highp float;
precision highp int;
precision highp sampler3D;

in vec2 vUv;
in vec2 vTex;
out vec4 fragColor;

uniform samplerExternalOES uCamera;
uniform sampler3D uLook;        // RGBA16F, linear filtering
uniform float uLookSize;
uniform mat4 uTexMatrix;
uniform vec2 uViewSize;         // pixels
uniform float uVignette;
uniform float uVignetteStart;   // DevelopParams.vignetteStart
uniform float uVignetteEnd;     // DevelopParams.vignetteEnd
uniform float uGrainAmount;
uniform float uGrainCell;
uniform float uGrainRoughness;
uniform int uGrainSeed;
uniform int uZebras;
uniform int uPeaking;
uniform float uTime;

const float VIGNETTE_GAIN = 0.8;
const float GRAIN_STRENGTH = 0.09;   // Grain.STRENGTH
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

uint pcg(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float lattice(int x, int y, int seed) {
    uint h = pcg(uint(x) + pcg(uint(y) + pcg(uint(seed))));
    return float(h & 65535u) / 65535.0 * 2.0 - 1.0;
}

float valueNoise(vec2 p, float cell, int seed) {
    vec2 f = p / cell;
    vec2 i = floor(f);
    vec2 t = f - i;
    vec2 s = t * t * (3.0 - 2.0 * t);
    int x0 = int(i.x);
    int y0 = int(i.y);
    float a = lattice(x0, y0, seed);
    float b = lattice(x0 + 1, y0, seed);
    float c = lattice(x0, y0 + 1, seed);
    float d = lattice(x0 + 1, y0 + 1, seed);
    return mix(mix(a, b, s.x), mix(c, d, s.x), s.y);
}

vec3 look(vec3 c) {
    vec3 coord = (clamp(c, 0.0, 1.0) * (uLookSize - 1.0) + 0.5) / uLookSize;
    return texture(uLook, coord).rgb;
}

vec3 camera(vec2 screenUv) {
    vec2 t = (uTexMatrix * vec4(screenUv, 0.0, 1.0)).xy;
    return texture(uCamera, t).rgb;
}

void main() {
    vec3 c = look(texture(uCamera, vTex).rgb);

    if (uVignette != 0.0) {
        vec2 d = vUv * 2.0 - 1.0;
        float r = sqrt(dot(d, d) / 2.0);
        c = clamp(c * max(0.0, 1.0 + VIGNETTE_GAIN * uVignette * smoothstep(uVignetteStart, uVignetteEnd, r)), 0.0, 1.0);
    }
    if (uGrainAmount > 0.0) {
        vec2 p = gl_FragCoord.xy;
        vec2 r = vec2(0.8 * p.x - 0.6 * p.y + 8192.0, 0.6 * p.x + 0.8 * p.y);   // Grain.at's turned lattice
        float coarse = (valueNoise(p, uGrainCell, uGrainSeed) + valueNoise(r, uGrainCell, uGrainSeed + 2)) * 0.70710678;
        float fine = (valueNoise(p, uGrainCell * 0.5, uGrainSeed + 1) + valueNoise(r, uGrainCell * 0.5, uGrainSeed + 3)) * 0.70710678;
        float n = mix(coarse, fine, 0.5 * uGrainRoughness);
        float y = dot(c, LUMA);
        c = clamp(c + n * GRAIN_STRENGTH * uGrainAmount * (1.0 - 0.85 * smoothstep(0.8, 1.0, y)), 0.0, 1.0);
    }
    if (uPeaking == 1) {
        vec2 px = 1.0 / uViewSize;
        float l = dot(camera(vUv - vec2(px.x, 0.0)), LUMA);
        float r = dot(camera(vUv + vec2(px.x, 0.0)), LUMA);
        float d = dot(camera(vUv - vec2(0.0, px.y)), LUMA);
        float u = dot(camera(vUv + vec2(0.0, px.y)), LUMA);
        float edge = abs(r - l) + abs(u - d);
        if (edge > 0.18) c = mix(c, vec3(1.0, 0.15, 0.1), 0.85);
    }
    if (uZebras == 1 && max(c.r, max(c.g, c.b)) >= 0.97) {
        float stripe = mod(gl_FragCoord.x + gl_FragCoord.y + uTime * 40.0, 16.0);
        c = stripe < 8.0 ? vec3(0.0) : vec3(1.0);
    }
    fragColor = vec4(c, 1.0);
}
