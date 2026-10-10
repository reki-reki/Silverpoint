// Shared by the compute shaders through `#include "common.glsl"` (GpuDeveloper inlines it at load
// time; glslangValidator resolves it with GL_GOOGLE_include_directive). Every function mirrors a
// Kotlin function in the :pipeline module; keep them in step.

const float VIBRANCE_CHROMA = 0.25;   // DevelopParams.VIBRANCE_CHROMA
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);   // LocalContrast.LR/LG/LB

float cbrtPositive(float x) { return x <= 0.0 ? 0.0 : pow(x, 1.0 / 3.0); }

// Oklab.fromLinearSrgb
vec3 toOklab(vec3 c) {
    float l = cbrtPositive(0.4122214708 * c.r + 0.5363325363 * c.g + 0.0514459929 * c.b);
    float m = cbrtPositive(0.2119034982 * c.r + 0.6806995451 * c.g + 0.1073969566 * c.b);
    float s = cbrtPositive(0.0883024619 * c.r + 0.2817188376 * c.g + 0.6299787005 * c.b);
    return vec3(
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s);
}

// Oklab.toLinearSrgb
vec3 fromOklab(vec3 lab) {
    float l1 = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
    float m1 = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
    float s1 = lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z;
    float l = l1 * l1 * l1;
    float m = m1 * m1 * m1;
    float s = s1 * s1 * s1;
    return vec3(
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
}

// ToneCurve.shoulder
float shoulder(float x, float knee) {
    if (x <= knee) return x;
    float room = 1.0 - knee;
    return knee + room * (1.0 - exp(-(x - knee) / room));
}

// ToneCurve.srgbEncode
float srgbEncode(float x) { return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1.0 / 2.4) - 0.055; }

// RecipeLook.smoothstep (written out so it rounds like the Kotlin version)
float smoothstepF(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// CpuDeveloper.linearLook: exposure, then saturation and vibrance in OKLab.
vec3 linearLook(vec3 lin, float exposure, float saturation, float vibrance) {
    vec3 c = max(lin, 0.0) * exposure;
    if (saturation != 1.0 || vibrance != 0.0) {
        vec3 lab = toOklab(c);
        float chroma = sqrt(lab.y * lab.y + lab.z * lab.z);
        float muted = clamp(1.0 - chroma / VIBRANCE_CHROMA, 0.0, 1.0);
        float k = saturation * (1.0 + vibrance * muted);
        c = max(fromOklab(vec3(lab.x, lab.y * k, lab.z * k)), 0.0);
    }
    return c;
}

vec3 shoulderEncode(vec3 c, float knee) {
    return vec3(srgbEncode(shoulder(c.r, knee)), srgbEncode(shoulder(c.g, knee)), srgbEncode(shoulder(c.b, knee)));
}

// Weighted sum in the same order as the Kotlin code (LR * r + LG * g + LB * b).
float luma(vec3 c) { return LUMA.x * c.r + LUMA.y * c.g + LUMA.z * c.b; }
