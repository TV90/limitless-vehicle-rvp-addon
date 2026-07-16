#version 150

uniform sampler2D ScreenTexture;
uniform float time;
uniform mat4 ProjMat;
uniform int useType;
uniform float intensity;

in vec4 vertexColor;
in vec3 vNormal;
in vec3 vPos;

out vec4 fragColor;

vec2 viewToScreenUV(vec3 viewPos) {
    vec4 clip = ProjMat * vec4(viewPos, 1.0);
    vec2 ndc = clip.xy / clip.w;
    return clamp(ndc * 0.5 + 0.5, vec2(0.001), vec2(0.999));
}

void main() {
    vec3 normal = normalize(vNormal);
    vec3 viewDir = normalize(vPos);
    vec2 baseUV = viewToScreenUV(vPos);
    vec3 passthrough = texture(ScreenTexture, baseUV).rgb;
    float alpha = vertexColor.a;
    if (alpha <= 0.002 || intensity <= 0.0) {
        discard;
    }

    float edge = 1.0 - abs(dot(normal, -viewDir));
    float distortionBand = smoothstep(0.40, 0.78, edge) * (1.0 - smoothstep(0.94, 1.0, edge));
    float visibleBand = smoothstep(0.58, 0.86, edge) * (1.0 - smoothstep(0.98, 1.0, edge));
    if (distortionBand <= 0.001 && visibleBand <= 0.001) {
        discard;
    }

    float effective = intensity * alpha * alpha;
    float pulse = 0.85 + 0.15 * sin(time * 28.0);
    vec4 projectedNormal = ProjMat * vec4(vPos + normal, 1.0);
    vec2 normalUV = projectedNormal.xy / projectedNormal.w * 0.5 + 0.5;
    vec2 screenNormal = normalize(normalUV - baseUV);
    vec2 distortedUV = clamp(baseUV + screenNormal * distortionBand * 0.13 * effective * pulse,
            vec2(0.001), vec2(0.999));
    vec3 distorted = texture(ScreenTexture, distortedUV).rgb;
    vec3 rim = vec3(0.75, 0.9, 1.0) * visibleBand * 0.08 * effective;
    vec3 color = mix(passthrough, distorted + rim, clamp(effective, 0.0, 1.0));
    float outAlpha = max(distortionBand * 0.18, visibleBand * 0.10) * clamp(effective, 0.0, 1.0);
    if (outAlpha <= 0.003) {
        discard;
    }
    fragColor = vec4(color, outAlpha);
}
