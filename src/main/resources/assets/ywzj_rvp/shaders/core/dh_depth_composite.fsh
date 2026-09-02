#version 150

uniform sampler2D RvpColor;
uniform sampler2D RvpDepth;
uniform sampler2D DhDepthCopy;
uniform mat4 RvpInverseProjection;
uniform mat4 DhInverseProjection;
uniform mat4 DhProjection;
uniform float DhEmptyDepth;
uniform float DhNearDepth;
uniform float DhFarPlane;
uniform float OcclusionBiasBlocks;

in vec2 texCoord;

out vec4 fragColor;

vec3 reconstructViewPosition(vec2 uv, float depth, mat4 inverseProjection) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = inverseProjection * clip;
    return view.xyz / view.w;
}

void main() {
    vec4 rvpColor = texture(RvpColor, texCoord);
    if (rvpColor.a <= 0.0001) {
        discard;
    }

    float rvpRawDepth = texture(RvpDepth, texCoord).r;
    float dhRawDepth = texture(DhDepthCopy, texCoord).r;
    vec3 rvpViewPosition = reconstructViewPosition(texCoord, rvpRawDepth, RvpInverseProjection);
    float rvpViewDepth = -rvpViewPosition.z;
    bool dhPixelExists = abs(dhRawDepth - DhEmptyDepth) > 0.00001;
    if (dhPixelExists) {
        vec3 dhViewPosition = reconstructViewPosition(texCoord, dhRawDepth, DhInverseProjection);
        float dhViewDepth = -dhViewPosition.z;
        if (rvpViewDepth > dhViewDepth + OcclusionBiasBlocks) {
            discard;
        }
    }

    vec4 dhClip = DhProjection * vec4(rvpViewPosition, 1.0);
    float encodedDepth = dhClip.z / dhClip.w * 0.5 + 0.5;
    if (rvpViewDepth > DhFarPlane || isnan(encodedDepth) || isinf(encodedDepth)) {
        // 超过 DH far 时仅写一个靠近空值的 apply 标记；普通原生 OpenGL 路径允许颜色继续显示。
        encodedDepth = mix(DhEmptyDepth, DhNearDepth, 0.00001);
    }
    gl_FragDepth = clamp(encodedDepth, 0.0, 1.0);
    // RVP 离屏 RGB 已预乘覆盖率；还原为直通颜色，交给 core shader 的标准 alpha 混合。
    fragColor = vec4(rvpColor.rgb / max(rvpColor.a, 0.0001), rvpColor.a);
}
