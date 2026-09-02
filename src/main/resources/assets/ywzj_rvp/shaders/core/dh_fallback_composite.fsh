#version 150

uniform sampler2D RvpColor;
uniform int FallbackMode;
uniform vec4 FallbackColor;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 center = texture(RvpColor, texCoord);
    if (FallbackMode == 1) {
        if (center.a <= 0.0001) {
            discard;
        }
        fragColor = vec4(center.rgb / max(center.a, 0.0001), center.a);
        return;
    }

    vec2 pixel = 1.0 / vec2(textureSize(RvpColor, 0));
    float maximumAlpha = center.a;
    float minimumAlpha = center.a;
    for (int x = -1; x <= 1; ++x) {
        for (int y = -1; y <= 1; ++y) {
            float alpha = texture(RvpColor, texCoord + vec2(x, y) * pixel * 2.0).a;
            maximumAlpha = max(maximumAlpha, alpha);
            minimumAlpha = min(minimumAlpha, alpha);
        }
    }
    float edge = maximumAlpha * (1.0 - minimumAlpha);
    if (edge <= 0.01) {
        discard;
    }
    float alpha = FallbackColor.a * edge;
    fragColor = vec4(FallbackColor.rgb, alpha);
}
