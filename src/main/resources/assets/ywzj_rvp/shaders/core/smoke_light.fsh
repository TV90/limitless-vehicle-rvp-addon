#version 150
// 仅烟雾受光，不从烟雾纹理生成遮挡；输入深度在粒子烟绘制前保存。
uniform sampler2D Sampler0;
uniform sampler2D SceneDepth;
uniform sampler2D DistantDepth;
uniform mat4 InverseProjection;
uniform mat4 Projection;
uniform mat4 DistantProjection;
uniform mat4 InverseDistantProjection;
uniform mat4 DistantToView;
uniform mat4 ViewToDistant;
uniform int HasDistantDepth;
uniform float NearEmptyDepth;
uniform float DistantEmptyDepth;
uniform float HandDepthLimit;
uniform vec2 ScreenSize;
uniform int LightCount;
uniform vec4 LightPositionRadius0;
uniform vec4 LightColorIntensity0;
uniform vec4 LightPositionRadius1;
uniform vec4 LightColorIntensity1;
uniform vec4 LightPositionRadius2;
uniform vec4 LightColorIntensity2;
uniform vec4 LightPositionRadius3;
uniform vec4 LightColorIntensity3;
uniform vec4 LightPositionRadius4;
uniform vec4 LightColorIntensity4;
uniform vec4 LightPositionRadius5;
uniform vec4 LightColorIntensity5;
uniform vec4 LightPositionRadius6;
uniform vec4 LightColorIntensity6;
uniform vec4 LightPositionRadius7;
uniform vec4 LightColorIntensity7;
in vec2 texCoord;
in vec4 smokeColor;
in vec3 smokePosition;
out vec4 fragColor;

vec3 viewPosition(vec2 uv, float depth) {
    vec4 point = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return point.xyz / (abs(point.w) < 0.000001 ? 0.000001 : point.w);
}

// 分别逆投影近景/DH，再在同一玩家观察空间选择最近表面；w=0 表示天空，w=-1 表示手持物。
vec4 scenePosition(vec2 uv) {
    float depth = texture(SceneDepth, uv).r;
    float nearOrderedDepth = NearEmptyDepth > 0.5 ? depth : 1.0 - depth;
    if (HandDepthLimit > 0.0 && nearOrderedDepth < HandDepthLimit) { return vec4(0.0, 0.0, 0.0, -1.0); }
    vec3 position = vec3(0.0);
    bool found = abs(depth - NearEmptyDepth) > 0.0000001;
    if (found) {
        position = viewPosition(uv, depth);
        found = !any(isnan(position)) && !any(isinf(position)) && position.z < 0.0;
    }
    if (HasDistantDepth != 0) {
        // 从主画面射线换算 DH 的 UV，允许分辨率、视野、矩阵抖动和深度方向不同。
        vec3 ray = viewPosition(uv, 0.5);
        vec4 distantClip = DistantProjection * ViewToDistant * vec4(ray, 0.0);
        if (distantClip.w > 0.000001) {
            vec2 distantUV = distantClip.xy / distantClip.w * 0.5 + 0.5;
            if (all(greaterThanEqual(distantUV, vec2(0.0))) && all(lessThanEqual(distantUV, vec2(1.0)))) {
                float distantDepth = texture(DistantDepth, distantUV).r;
                if (abs(distantDepth - DistantEmptyDepth) > 0.0000001) {
                    vec4 distantPoint = InverseDistantProjection * vec4(distantUV * 2.0 - 1.0, distantDepth * 2.0 - 1.0, 1.0);
                    if (abs(distantPoint.w) > 0.0000001) {
                        vec3 candidate = (DistantToView * vec4(distantPoint.xyz / distantPoint.w, 1.0)).xyz;
                        if (!any(isnan(candidate)) && !any(isinf(candidate)) && candidate.z < 0.0
                                && (!found || candidate.z > position.z)) {
                            position = candidate;
                            found = true;
                        }
                    }
                }
            }
        }
    }
    return vec4(position, found ? 1.0 : 0.0);
}


// 烟不使用固体表面法线；从真实烟面片向光源检查方块/实体，烟雾自身永远不在此深度输入中。
float visibility(vec3 position, vec3 light) {
    for (int step = 1; step <= 8; step++) {
        vec3 point = mix(position, light, float(step) / 9.0);
        vec4 clip = Projection * vec4(point, 1.0);
        if (clip.w <= 0.00001) { return 0.0; }
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) { return 0.0; }
        vec4 opaque = scenePosition(uv);
        float bias = clamp(abs(point.z) * 0.001, 0.08, 0.25);
        if (opaque.w > 0.5 && opaque.z > point.z + bias) { return 0.0; }
    }
    return 1.0;
}

void main() {
    vec4 textureColor = texture(Sampler0, texCoord);
    float opacity = clamp(textureColor.a * smokeColor.a, 0.0, 1.0);
    if (opacity < 0.0001 || LightCount <= 0) { fragColor = vec4(0.0); return; }
    vec4 opaque = scenePosition(gl_FragCoord.xy / ScreenSize);
    if (opaque.w < -0.5) { fragColor = vec4(0.0); return; }
    float bias = clamp(abs(smokePosition.z) * 0.00005, 0.015, 0.1);
    if (opaque.w > 0.5 && opaque.z > smokePosition.z + bias) { fragColor = vec4(0.0); return; }
    vec4 positions[8] = vec4[8](LightPositionRadius0, LightPositionRadius1, LightPositionRadius2, LightPositionRadius3, LightPositionRadius4, LightPositionRadius5, LightPositionRadius6, LightPositionRadius7);
    vec4 colors[8] = vec4[8](LightColorIntensity0, LightColorIntensity1, LightColorIntensity2, LightColorIntensity3, LightColorIntensity4, LightColorIntensity5, LightColorIntensity6, LightColorIntensity7);
    vec3 contribution = vec3(0.0);
    for (int index = 0; index < 8; index++) {
        if (index >= LightCount) { break; }
        float radius = positions[index].w;
        if (radius <= 0.0 || colors[index].a <= 0.0) { continue; }
        float radial = distance(smokePosition, positions[index].xyz) / radius;
        if (radial >= 1.0) { continue; }
        float falloff = pow(max(0.0, 1.0 - radial * radial), 2.0);
        contribution += colors[index].rgb * colors[index].a * falloff * visibility(smokePosition, positions[index].xyz);
    }
    // 原粒子灰色是烟的底色，不能把它当成完全不散射的固体黑漆；仍保留贴图明暗与 RGB 色相。
    vec3 albedo = max(textureColor.rgb, vec3(0.0)) * (vec3(0.35) + 0.65 * clamp(smokeColor.rgb, 0.0, 1.0));
    vec3 response = 1.0 - exp(-clamp(contribution, vec3(0.0), vec3(60000.0)) * albedo);
    // RGB 已乘真实烟雾 Alpha；显示叠加保留原场景 Alpha，透明边缘不会形成亮方框。
    fragColor = vec4(response * opacity, 0.0);
}
