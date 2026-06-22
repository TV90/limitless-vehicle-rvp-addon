#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    // Match vanilla CRT framing exactly, but without scanlines/noise flicker.
    vec2 uv = (texCoord - 0.5) * 2.0;
    float r = length(uv);

    float k = 0.05;
    uv *= 1.0 + k * r * r;
    uv = uv * 0.5 + 0.5;

    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec3 col = texture(DiffuseSampler, uv).rgb;
    fragColor = vec4(col, 1.0);
}
