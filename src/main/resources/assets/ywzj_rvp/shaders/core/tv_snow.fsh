#version 150

uniform float Time;
uniform float Opacity;
uniform vec2 Resolution;
in vec2 texCoord0;
out vec4 fragColor;

// Integer noise is cheap per fragment and does not need CPU random pixels or texture uploads.
uint hashNoise(uvec3 p) {
    uint h = p.x * 1597334677u ^ p.y * 3812015801u ^ p.z * 2798796415u;
    h = (h ^ (h >> 16u)) * 2246822519u;
    h = (h ^ (h >> 13u)) * 3266489917u;
    return h ^ (h >> 16u);
}
float noiseAt(uvec2 p, uint frame) {
    return float(hashNoise(uvec3(p, frame))) / 4294967295.0;
}
void main() {
    // 和 crt_ui_lite 的弧形屏幕边缘一致；退出 TV 镜头后仍保留失图画面的黑框。
    vec2 screen = texCoord0 * 2.0 - 1.0;
    vec2 curved = screen * (1.0 + 0.05 * dot(screen, screen));
    if (any(greaterThan(abs(curved), vec2(1.0)))) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    float scale = max(1.0, min(Resolution.x / 1920.0, Resolution.y / 1080.0));
    uvec2 cell = uvec2(floor(texCoord0 * Resolution / vec2(1.3 * scale, scale)));
    uint frame = uint(floor(mod(Time, 120.0) * 30.0));
    float fine = (noiseAt(cell, frame) + noiseAt(cell + uvec2(23u, 71u), frame)) * 0.5;
    float coarse = noiseAt(cell / 2u, frame + 37u);
    float row = noiseAt(uvec2(0u, cell.y / 2u), frame);
    float value = 0.48 + (fine - 0.5) * 1.25 + (coarse - 0.5) * 0.20 + (row - 0.5) * 0.09;
    // A soft rolling band, slight scanlines and restrained edge falloff match an untuned CRT.
    float band = abs(fract(texCoord0.y - Time * 0.13) - 0.5);
    value *= 1.0 - 0.22 * (1.0 - smoothstep(0.025, 0.09, band));
    value *= 0.94 + 0.06 * mod(floor(gl_FragCoord.y / scale), 2.0);
    vec2 edge = (texCoord0 - 0.5) * vec2(1.0, 0.8);
    value *= 1.0 - 0.15 * dot(edge, edge);
    fragColor = vec4(vec3(clamp(value, 0.015, 0.96)), Opacity);
}
