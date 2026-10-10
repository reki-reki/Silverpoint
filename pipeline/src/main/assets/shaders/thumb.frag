#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
// Live look thumbnails: the camera frame through one look's table, drawn into one card of the look
// selector (or one circle of the quick row) every frame. The viewport covers the card with the
// frame's 3:4 shape; uRect and uRadius cut it to the card's rounded rectangle (a circle when the
// radius is half the width), with a one-pixel soft edge. Output is premultiplied alpha.
precision highp float;
precision highp sampler3D;

in vec2 vUv;
in vec2 vTex;
out vec4 fragColor;

uniform samplerExternalOES uCamera;
uniform sampler3D uLook;        // RGBA16F, linear filtering
uniform float uLookSize;
uniform vec4 uRect;             // card in window pixels: x, y (bottom-left), width, height
uniform float uRadius;          // corner radius in pixels

void main() {
    vec3 c = clamp(texture(uCamera, vTex).rgb, 0.0, 1.0);
    c = texture(uLook, (c * (uLookSize - 1.0) + 0.5) / uLookSize).rgb;
    // Signed distance to the rounded rectangle.
    vec2 half_ = uRect.zw * 0.5;
    vec2 p = gl_FragCoord.xy - (uRect.xy + half_);
    vec2 q = abs(p) - (half_ - vec2(uRadius));
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;
    float a = clamp(0.5 - d, 0.0, 1.0);
    fragColor = vec4(c * a, a);
}
