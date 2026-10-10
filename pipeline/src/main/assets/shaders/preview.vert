#version 300 es
// Full-screen quad for the live preview. uTexMatrix is SurfaceTexture.getTransformMatrix(): it
// maps screen UVs onto the camera buffer, including the sensor rotation the camera sets.
precision highp float;

in vec2 aPosition;          // clip space, -1..1
uniform mat4 uTexMatrix;
out vec2 vUv;               // 0..1, origin bottom-left
out vec2 vTex;              // camera texture coordinates

void main() {
    vUv = aPosition * 0.5 + 0.5;
    vTex = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
